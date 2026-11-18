"""OpenAI VPT foundation model network (MIT, see LICENSE in this folder).

Adapted from VPT's ``lib/policy.py``: the network is unchanged so the published
weights load as-is, but the gym3/MineRL action-space plumbing is replaced by two
plain categorical heads and the model sizes are written out here instead of
being read from the pickled ``.model`` files (we never unpickle downloads).
"""
from copy import deepcopy
from typing import Dict, Optional

import torch as th
from torch import nn
from torch.nn import functional as F

from .impala_cnn import ImpalaCNN
from .util import FanInInitReLULayer, ResidualRecurrentBlocks

N_BUTTON_COMBOS = 8641
N_CAMERA_COMBOS = 121

_COMMON = dict(
    attention_mask_style="clipped_causal",
    attention_memory_size=256,
    img_shape=[128, 128, 3],
    impala_chans=[16, 32, 32],
    impala_kwargs={"post_pool_groups": 1},
    init_norm_kwargs={"batch_norm": False, "group_norm_groups": 1},
    n_recurrence_layers=4,
    pointwise_ratio=4,
    pointwise_use_activation=False,
    recurrence_is_residual=True,
    recurrence_type="transformer",
    timesteps=128,
    use_pointwise_layer=True,
    use_pre_lstm_ln=False,
)

# Read from the published .model files with pickletools (no unpickling).
SIZES = {
    "1x": dict(_COMMON, attention_heads=8, hidsize=1024, impala_width=4),
    "2x": dict(_COMMON, attention_heads=16, hidsize=2048, impala_width=8),
    "3x": dict(_COMMON, attention_heads=24, hidsize=3072, impala_width=12),
}

# VPT samples its button and camera heads at temperature 2 by default.
DEFAULT_TEMPERATURE = 2.0


class ImgObsProcess(nn.Module):
    """ImpalaCNN followed by a linear layer."""

    def __init__(self, cnn_outsize, output_size, dense_init_norm_kwargs=None, init_norm_kwargs=None, **kwargs):
        super().__init__()
        dense_init_norm_kwargs = dense_init_norm_kwargs or {}
        self.cnn = ImpalaCNN(outsize=cnn_outsize, init_norm_kwargs=init_norm_kwargs or {},
                             dense_init_norm_kwargs=dense_init_norm_kwargs, **kwargs)
        self.linear = FanInInitReLULayer(cnn_outsize, output_size, layer_type="linear", **dense_init_norm_kwargs)

    def forward(self, img):
        return self.linear(self.cnn(img))


class MinecraftPolicy(nn.Module):
    """VPT's trunk: image -> IMPALA CNN -> transformer-XL style memory -> latent."""

    def __init__(self, impala_width=1, impala_chans=(16, 32, 32), hidsize=512, img_shape=None,
                 init_norm_kwargs=None, impala_kwargs=None, attention_mask_style="clipped_causal",
                 attention_heads=8, attention_memory_size=2048, use_pointwise_layer=True, pointwise_ratio=4,
                 pointwise_use_activation=False, n_recurrence_layers=1, recurrence_is_residual=True,
                 timesteps=None, use_pre_lstm_ln=True, recurrence_type="transformer", **unused):
        super().__init__()
        init_norm_kwargs = init_norm_kwargs or {}
        chans = tuple(int(impala_width * c) for c in impala_chans)
        self.hidsize = hidsize
        dense = deepcopy(init_norm_kwargs)
        if dense.get("group_norm_groups", None) is not None:
            dense.pop("group_norm_groups", None)
            dense["layer_norm"] = True
        if dense.get("batch_norm", False):
            dense.pop("batch_norm", False)
            dense["layer_norm"] = True

        self.img_process = ImgObsProcess(cnn_outsize=256, output_size=hidsize, inshape=img_shape, chans=chans,
                                         nblock=2, dense_init_norm_kwargs=dense, init_norm_kwargs=init_norm_kwargs,
                                         first_conv_norm=False, **(impala_kwargs or {}))
        self.pre_lstm_ln = nn.LayerNorm(hidsize) if use_pre_lstm_ln else None
        self.recurrent_layer = ResidualRecurrentBlocks(
            hidsize=hidsize, timesteps=timesteps, recurrence_type=recurrence_type,
            is_residual=recurrence_is_residual, use_pointwise_layer=use_pointwise_layer,
            pointwise_ratio=pointwise_ratio, pointwise_use_activation=pointwise_use_activation,
            attention_mask_style=attention_mask_style, attention_heads=attention_heads,
            attention_memory_size=attention_memory_size, n_block=n_recurrence_layers)
        self.lastlayer = FanInInitReLULayer(hidsize, hidsize, layer_type="linear", **dense)
        self.final_ln = nn.LayerNorm(hidsize)

    def forward(self, img, first, state_in):
        x = img.to(dtype=th.float32) / 255.0
        x = self.img_process(x)
        if self.pre_lstm_ln is not None:
            x = self.pre_lstm_ln(x)
        x, state_out = self.recurrent_layer(x, first, state_in)
        x = F.relu(x, inplace=False)
        x = self.lastlayer(x)
        x = self.final_ln(x)
        return x, state_out

    def initial_state(self, batch_size):
        return self.recurrent_layer.initial_state(batch_size)


class CategoricalHead(nn.Module):
    def __init__(self, input_dim, n):
        super().__init__()
        self.linear_layer = nn.Linear(input_dim, n)

    def forward(self, x, temperature=1.0):
        return F.log_softmax(self.linear_layer(x).float() / temperature, dim=-1)


class PiHead(nn.Module):
    def __init__(self, input_dim):
        super().__init__()
        self.buttons = CategoricalHead(input_dim, N_BUTTON_COMBOS)
        self.camera = CategoricalHead(input_dim, N_CAMERA_COMBOS)


class ValueNormalizer(nn.Module):
    """Holds VPT's value-normalisation statistics so the published weights load."""

    def __init__(self, size=1):
        super().__init__()
        self.running_mean = nn.Parameter(th.zeros(size), requires_grad=False)
        self.running_mean_sq = nn.Parameter(th.zeros(size), requires_grad=False)
        self.debiasing_term = nn.Parameter(th.tensor(0.0), requires_grad=False)

    def denormalize(self, v):
        mean = self.running_mean / self.debiasing_term.clamp(min=1e-5)
        mean_sq = self.running_mean_sq / self.debiasing_term.clamp(min=1e-5)
        std = (mean_sq - mean ** 2).clamp(min=1e-2).sqrt()
        return v * std + mean


class ValueHead(nn.Module):
    def __init__(self, input_dim):
        super().__init__()
        self.linear = nn.Linear(input_dim, 1)
        self.normalizer = ValueNormalizer(1)

    def forward(self, x):
        return self.linear(x)


class VPTModel(nn.Module):
    """The full VPT agent network; parameter names match the published .weights files."""

    def __init__(self, size="1x"):
        super().__init__()
        if size not in SIZES:
            raise ValueError(f"unknown VPT size '{size}', choose from {sorted(SIZES)}")
        self.size = size
        self.net = MinecraftPolicy(**SIZES[size])
        self.value_head = ValueHead(self.net.hidsize)
        self.pi_head = PiHead(self.net.hidsize)

    def initial_state(self, batch_size):
        return self.net.initial_state(batch_size)

    def forward(self, img, first, state_in, temperature=DEFAULT_TEMPERATURE):
        """img: uint8 (B, T, 128, 128, 3); first: bool (B, T). Returns log-probs and new state."""
        latent, state_out = self.net(img, first, state_in)
        return {
            "buttons": self.pi_head.buttons(latent, temperature),
            "camera": self.pi_head.camera(latent, temperature),
            "value": self.value_head(latent)[..., 0],
        }, state_out


def load_weights(model: VPTModel, path, device="cpu", strict=True):
    """Loads a published VPT .weights file safely (tensors only, no code execution)."""
    state = th.load(path, map_location=device, weights_only=True)
    if not isinstance(state, dict):
        raise ValueError("not a VPT weights file")
    result = model.load_state_dict(state, strict=strict)
    return result
