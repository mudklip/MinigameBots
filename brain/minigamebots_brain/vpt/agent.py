"""Running a VPT network tick by tick, optionally with an AI profile's fine-tuned layers on top."""
import json
import os

import numpy as np

from . import actions as vpt_actions

ARCH = "vpt-tune"
# The layers an AI profile trains on top of the frozen foundation (the rest stays shared).
TRAINABLE_PREFIXES = ("net.recurrent_layer.blocks.3.", "net.lastlayer.", "net.final_ln.", "pi_head.")


def foundations_root_for(profile: str) -> str:
    """<minigamebots>/profiles/<id> -> <minigamebots>/foundations."""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(profile))), "foundations")


def read_info(profile: str) -> dict:
    with open(os.path.join(profile, "core", "model.json"), encoding="utf-8") as f:
        return json.load(f)


def trainable_names(model) -> list:
    return [n for n, _ in model.named_parameters() if n.startswith(TRAINABLE_PREFIXES)]


def load_profile_layers(model, profile: str, which: str = "model") -> dict:
    """Puts a profile's fine-tuned layers into `model`. Returns the profile's model.json."""
    from safetensors.torch import load_file

    core = os.path.join(profile, "core")
    info = read_info(profile)
    path = os.path.join(core, f"{which}.safetensors")
    if not os.path.isfile(path):
        path = os.path.join(core, "model.safetensors")
    layers = load_file(path, device="cpu")
    own = model.state_dict()
    unknown = [k for k in layers if k not in own or own[k].shape != layers[k].shape]
    if unknown:
        raise ValueError("This AI's layers don't fit its foundation model")
    model.load_state_dict({k: v.float() for k, v in layers.items()}, strict=False)
    return info


class VPTRunner:
    """Holds the network and its memory; one call per game tick."""

    def __init__(self, model, device: str):
        import torch

        self.torch = torch
        self.model = model
        self.device = device
        self.state = None
        self.first = True

    def reset(self):
        self.state = None
        self.first = True

    def step(self, frame, temperature: float):
        """frame: (128, 128, 3) uint8 RGB. Returns (button index, camera index)."""
        torch = self.torch
        if self.state is None:
            self.state = self.model.initial_state(1)
        img = torch.from_numpy(np.array(frame, dtype=np.uint8)).to(self.device)[None, None]  # Copy: frames may be read-only.
        first = torch.tensor([[self.first]], device=self.device)
        self.first = False
        with torch.no_grad(), torch.autocast(device_type="cuda", dtype=torch.float16, enabled=self.device == "cuda"):
            out, self.state = self.model(img, first, self.state, temperature=max(temperature, 1e-3))
        if temperature <= 0:
            return int(out["buttons"][0, 0].argmax()), int(out["camera"][0, 0].argmax())
        b = torch.multinomial(out["buttons"][0, 0].float().exp(), 1)
        c = torch.multinomial(out["camera"][0, 0].float().exp(), 1)
        return int(b), int(c)

    def act(self, frame, temperature: float):
        return vpt_actions.decode(*self.step(frame, temperature))
