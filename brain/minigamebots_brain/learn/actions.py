"""Recorded input <-> network outputs.

Keys are on/off. Clicks per tick are 0-3. Camera turns per tick are put into bins that are
fine near zero (small aiming adjustments) and coarse for big flicks, like OpenAI's VPT.
"""

import math

import numpy as np

from ..actions import KEYS, Action

CAMERA_BINS = 31
CAMERA_MAX = 50.0  # Degrees per tick; anything bigger is clipped.
CAMERA_MU = 10.0
CLICK_CLASSES = 4  # 0, 1, 2, 3+ clicks in one tick.
NUM_KEYS = len(KEYS)
KEY_INDEX = {k: i for i, k in enumerate(KEYS)}


def camera_to_bin(degrees: float) -> int:
    x = max(-CAMERA_MAX, min(CAMERA_MAX, float(degrees))) / CAMERA_MAX
    y = math.copysign(math.log1p(CAMERA_MU * abs(x)) / math.log1p(CAMERA_MU), x)
    return int(round((y + 1) / 2 * (CAMERA_BINS - 1)))


def bin_to_camera(index: int) -> float:
    y = index / (CAMERA_BINS - 1) * 2 - 1
    x = math.copysign((math.pow(1 + CAMERA_MU, abs(y)) - 1) / CAMERA_MU, y)
    return x * CAMERA_MAX


def encode_input(inp: dict) -> dict:
    """A recorded input ({"keys", "attacks", "uses", "yaw", "pitch"}) -> training targets."""
    keys = np.zeros(NUM_KEYS, dtype=np.float32)
    for k in inp.get("keys", []):
        if k in KEY_INDEX:
            keys[KEY_INDEX[k]] = 1.0
    # A fresh press of attack/use already counts as one click; the network predicts held keys
    # and extra clicks separately, matching how the mod's AgentAction works.
    attack_held = "attack" in inp.get("keys", [])
    use_held = "use" in inp.get("keys", [])
    attacks = max(0, int(inp.get("attacks", 0)) - (1 if attack_held else 0))
    uses = max(0, int(inp.get("uses", 0)) - (1 if use_held else 0))
    return {
        "keys": keys,
        "attacks": np.int64(min(attacks, CLICK_CLASSES - 1)),
        "uses": np.int64(min(uses, CLICK_CLASSES - 1)),
        "yaw": np.int64(camera_to_bin(inp.get("yaw", 0.0))),
        "pitch": np.int64(camera_to_bin(inp.get("pitch", 0.0))),
    }


def decode_action(keys_on, attacks: int, uses: int, yaw_bin: int, pitch_bin: int) -> Action:
    return Action(
        keys={KEYS[i] for i, on in enumerate(keys_on) if on},
        attacks=int(attacks), uses=int(uses),
        yaw=bin_to_camera(yaw_bin), pitch=bin_to_camera(pitch_bin),
    )


def mirror_targets(t: dict) -> dict:
    """Training targets (encode_input output, possibly stacked) flipped left-right."""
    out = dict(t)
    keys = t["keys"].copy()
    l, r = KEY_INDEX["left"], KEY_INDEX["right"]
    keys[..., [l, r]] = keys[..., [r, l]]
    out["keys"] = keys
    out["yaw"] = (CAMERA_BINS - 1) - t["yaw"]
    return out
