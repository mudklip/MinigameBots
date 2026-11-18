"""The "Lite" policy network: sees the frame and the game state, remembers recent ticks, and
predicts what to press next.

    frame (128x128) -> IMPALA-style CNN ----------\\
    self, hotbar/armor items -> MLP ----------------\\
    nearby entities -> shared MLP, pooled -----------+-> fuse -> GRU memory -> heads
    crowd radar (everyone else) -> MLP -------------/
    block cube (11^3, facing-relative) -> 3D CNN ---/                           keys (22 on/off)
    open screen -> embedding ----------------------/                            extra attack/use clicks
                                                                                camera yaw / pitch bins
Small enough (a few million parameters) to train on a 6 GB GPU or, slowly, on a CPU.
"""

import torch
from torch import nn
import torch.nn.functional as F

from . import actions, features

ARCH = "lite-v2"


class ResBlock(nn.Module):
    def __init__(self, channels: int):
        super().__init__()
        self.c1 = nn.Conv2d(channels, channels, 3, padding=1)
        self.c2 = nn.Conv2d(channels, channels, 3, padding=1)

    def forward(self, x):
        return x + self.c2(F.relu(self.c1(F.relu(x))))


class ImpalaStack(nn.Module):
    def __init__(self, cin: int, cout: int):
        super().__init__()
        self.conv = nn.Conv2d(cin, cout, 3, padding=1)
        self.res1 = ResBlock(cout)
        self.res2 = ResBlock(cout)

    def forward(self, x):
        x = F.max_pool2d(self.conv(x), 3, stride=2, padding=1)
        return self.res2(self.res1(x))


class LitePolicy(nn.Module):
    def __init__(self, width: int = 1, hidden: int = 512):
        super().__init__()
        c = [16 * width, 32 * width, 32 * width, 32 * width]
        self.config = {"width": width, "hidden": hidden}
        self.cnn = nn.Sequential(ImpalaStack(3, c[0]), ImpalaStack(c[0], c[1]), ImpalaStack(c[1], c[2]),
                                 ImpalaStack(c[2], c[3]))
        self.cnn_out = nn.Linear(c[3] * 8 * 8, 256)

        self.item_emb = nn.Embedding(features.ITEM_BUCKETS, 16)
        n_items = len(features.ITEM_SLOTS)
        self.state = nn.Sequential(
            nn.Linear(features.SELF_DIM + n_items * (16 + features.ITEM_DIM), 256), nn.ReLU(), nn.Linear(256, 128))

        self.ent_emb = nn.Embedding(features.ENTITY_BUCKETS, 16)
        self.ent = nn.Sequential(nn.Linear(16 + 16 + features.ENTITY_DIM, 96), nn.ReLU(), nn.Linear(96, 96))

        self.block_emb = nn.Embedding(features.BLOCK_CATEGORIES + 6, 4)  # spare ids for future categories
        self.blocks = nn.Sequential(nn.Conv3d(4, 16, 3, stride=2, padding=1), nn.ReLU(),
                                    nn.Conv3d(16, 16, 3, stride=2, padding=1), nn.ReLU())
        self.blocks_out = nn.Linear(16 * 3 * 3 * 3, 96)

        self.screen_emb = nn.Embedding(features.SCREEN_BUCKETS, 16)
        self.crowd = nn.Sequential(nn.Linear(features.CROWD_DIM, 64), nn.ReLU(), nn.Linear(64, 32))

        fused = 256 + 128 + 96 * 2 + 96 + 16 + 32
        self.fuse = nn.Sequential(nn.Linear(fused, hidden), nn.ReLU())
        self.memory = nn.GRU(hidden, hidden, batch_first=True)
        self.head_keys = nn.Linear(hidden, actions.NUM_KEYS)
        self.head_attacks = nn.Linear(hidden, actions.CLICK_CLASSES)
        self.head_uses = nn.Linear(hidden, actions.CLICK_CLASSES)
        self.head_yaw = nn.Linear(hidden, actions.CAMERA_BINS)
        self.head_pitch = nn.Linear(hidden, actions.CAMERA_BINS)
        # "How well is this going?" (expected future reward). Trained in practice (RL);
        # imitation leaves it untouched, and older checkpoints without it still load.
        self.head_value = nn.Linear(hidden, 1)
        # Memory (added later; zero-initialised so older checkpoints behave exactly as before):
        # friend/foe signals per entity, the nearest waypoints, and a "mark this place" output.
        self.ent_rel = nn.Linear(features.ENTITY_REL_DIM, 96)
        self.memory_in = nn.Sequential(nn.Linear(features.MAX_WAYPOINTS * features.WAYPOINT_DIM, 64), nn.ReLU(),
                                       nn.Linear(64, hidden))
        self.head_mark = nn.Linear(hidden, 1 + len(features.WAYPOINT_TYPES))
        self.reset_memory_modules()

    def reset_memory_modules(self):
        """Neutral start for the memory parts: no effect on behaviour until trained, and marking
        places is very unlikely (P(no mark) ~ 99.98%) until it learns when to."""
        nn.init.zeros_(self.ent_rel.weight)
        nn.init.zeros_(self.ent_rel.bias)
        nn.init.zeros_(self.memory_in[2].weight)
        nn.init.zeros_(self.memory_in[2].bias)
        nn.init.zeros_(self.head_mark.weight)
        with torch.no_grad():
            self.head_mark.bias.fill_(0.0)
            self.head_mark.bias[0] = 10.0

    def initial_state(self, batch: int, device) -> torch.Tensor:
        return torch.zeros(1, batch, self.config["hidden"], device=device)

    def forward(self, x: dict, state: torch.Tensor | None = None):
        """Inputs are [batch, time, ...] tensors as produced by `to_tensors`."""
        b, t = x["self"].shape[:2]
        frames = x["frames"].reshape(b * t, *x["frames"].shape[2:]).permute(0, 3, 1, 2).float() / 255.0
        vis = F.relu(self.cnn_out(F.relu(self.cnn(frames)).flatten(1))).reshape(b, t, -1)

        items = torch.cat([self.item_emb(x["items"]), x["item_vals"]], dim=-1).flatten(2)
        state_vec = F.relu(self.state(torch.cat([x["self"], items], dim=-1)))

        ent = self.ent(torch.cat([self.ent_emb(x["ent_type"]), self.item_emb(x["ent_held"])[..., :16],
                                  x["ent_vals"]], dim=-1))
        if "ent_rel" in x:
            ent = ent + self.ent_rel(x["ent_rel"])
        mask = x["ent_mask"].unsqueeze(-1)
        ent_max = torch.where(mask, ent, torch.full_like(ent, -1e4)).max(dim=2).values
        ent_max = torch.where(mask.any(dim=2), ent_max, torch.zeros_like(ent_max))
        ent_mean = (ent * mask).sum(dim=2) / mask.sum(dim=2).clamp(min=1)

        blk = self.block_emb(x["blocks"].reshape(b * t, *x["blocks"].shape[2:])).permute(0, 4, 1, 2, 3)
        blk = F.relu(self.blocks_out(self.blocks(blk).flatten(1))).reshape(b, t, -1)

        screen = self.screen_emb(x["screen"])
        crowd = F.relu(self.crowd(x["crowd"]))
        h = self.fuse(torch.cat([vis, state_vec, ent_max, ent_mean, blk, screen, crowd], dim=-1))
        if "wp_vals" in x:
            wp = x["wp_vals"] * x["wp_mask"].unsqueeze(-1)
            h = h + self.memory_in(wp.flatten(2))
        if state is None:
            state = self.initial_state(b, h.device)
        out, state = self.memory(h, state)
        logits = {
            "keys": self.head_keys(out),
            "attacks": self.head_attacks(out),
            "uses": self.head_uses(out),
            "yaw": self.head_yaw(out),
            "pitch": self.head_pitch(out),
            "value": self.head_value(out).squeeze(-1),
            "mark": self.head_mark(out),
        }
        return logits, state


INPUT_KEYS = ("self", "items", "item_vals", "ent_type", "ent_held", "ent_vals", "ent_mask", "blocks", "screen", "crowd",
              "frames", "ent_rel", "wp_vals", "wp_mask")


def to_tensors(batch: dict, device) -> dict:
    """Numpy batch -> tensors on `device`."""
    return {k: torch.from_numpy(batch[k]).to(device, non_blocking=True) for k in batch}


# Parameters that may be missing from older checkpoints (they start fresh when loaded).
OPTIONAL_PREFIXES = ("head_value.", "ent_rel.", "memory_in.", "head_mark.")


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())
