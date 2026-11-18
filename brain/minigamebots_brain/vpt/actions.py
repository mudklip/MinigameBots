"""Translating between VPT's action numbering and the mod's actions.

VPT picks one of 8641 button combinations and one of 121 camera combinations per tick.
The numbering below rebuilds VPT's ``CameraHierarchicalMapping`` exactly (same group
order, same itertools.product order), so the published heads are read correctly.
"""
import itertools
import math

import numpy as np

from ..actions import Action

BUTTON_GROUPS = [
    ("hotbar", ["none"] + [f"hotbar.{i}" for i in range(1, 10)]),
    ("fore_back", ["none", "forward", "back"]),
    ("left_right", ["none", "left", "right"]),
    ("sprint_sneak", ["none", "sprint", "sneak"]),
    ("use", ["none", "use"]),
    ("drop", ["none", "drop"]),
    ("attack", ["none", "attack"]),
    ("jump", ["none", "jump"]),
    ("camera", ["none", "camera"]),
]
COMBINATIONS = list(itertools.product(*[options for _, options in BUTTON_GROUPS])) + ["inventory"]
COMBINATION_INDEX = {c: i for i, c in enumerate(COMBINATIONS)}
assert len(COMBINATIONS) == 8641

CAMERA_BINS = 11
CAMERA_NULL_BIN = 5
CAMERA_MAXVAL = 10.0  # Degrees per tick.
CAMERA_BINSIZE = 2.0
CAMERA_MU = 10.0
CAMERA_NULL = CAMERA_NULL_BIN * CAMERA_BINS + CAMERA_NULL_BIN  # 60

# VPT button name -> the mod's key name.
TO_MOD_KEY = {"forward": "forward", "back": "back", "left": "left", "right": "right", "sprint": "sprint",
              "sneak": "sneak", "use": "use", "drop": "drop", "attack": "attack", "jump": "jump",
              **{f"hotbar.{i}": f"hotbar_{i}" for i in range(1, 10)}}


def camera_bin_to_degrees(b: int) -> float:
    """VPT's mu-law bin (0..10) -> degrees per tick."""
    v = (b * CAMERA_BINSIZE - CAMERA_MAXVAL) / CAMERA_MAXVAL
    return math.copysign((1.0 / CAMERA_MU) * ((1.0 + CAMERA_MU) ** abs(v) - 1.0), v) * CAMERA_MAXVAL if v else 0.0


def degrees_to_camera_bin(d: float) -> int:
    d = max(-CAMERA_MAXVAL, min(CAMERA_MAXVAL, float(d))) / CAMERA_MAXVAL
    enc = math.copysign(math.log(1.0 + CAMERA_MU * abs(d)) / math.log(1.0 + CAMERA_MU), d) * CAMERA_MAXVAL
    return int(np.round((enc + CAMERA_MAXVAL) / CAMERA_BINSIZE))


def decode(button_index: int, camera_index: int) -> Action:
    """VPT's choice -> the mod's action. Camera index = pitch_bin * 11 + yaw_bin."""
    combo = COMBINATIONS[int(button_index)]
    if combo == "inventory":
        return Action(keys={"inventory"})
    keys = {TO_MOD_KEY[c] for c in combo[:-1] if c != "none"}
    action = Action(keys=keys)
    if combo[-1] == "camera":
        pitch_bin, yaw_bin = divmod(int(camera_index), CAMERA_BINS)
        action.pitch = camera_bin_to_degrees(pitch_bin)
        action.yaw = camera_bin_to_degrees(yaw_bin)
    return action


def encode(inp: dict) -> tuple:
    """A recorded input ({"keys", "attacks", "uses", "yaw", "pitch"}) -> (button index, camera index).

    Mod keys VPT has no button for (pick block, swap offhand) are dropped. Clicks count as the
    button being pressed this tick. Camera moves beyond 10 degrees/tick are clipped like VPT.
    """
    keys = set(inp.get("keys", []))
    if int(inp.get("attacks", 0)) > 0:
        keys.add("attack")
    if int(inp.get("uses", 0)) > 0:
        keys.add("use")
    if "inventory" in keys:
        return COMBINATION_INDEX["inventory"], CAMERA_NULL
    hotbar = next((f"hotbar.{i}" for i in range(1, 10) if f"hotbar_{i}" in keys), "none")
    fore_back = "none" if ("forward" in keys) == ("back" in keys) else ("forward" if "forward" in keys else "back")
    left_right = "none" if ("left" in keys) == ("right" in keys) else ("left" if "left" in keys else "right")
    sprint_sneak = "sneak" if "sneak" in keys else ("sprint" if "sprint" in keys else "none")
    pitch_bin = degrees_to_camera_bin(inp.get("pitch", 0.0))
    yaw_bin = degrees_to_camera_bin(inp.get("yaw", 0.0))
    moving = pitch_bin != CAMERA_NULL_BIN or yaw_bin != CAMERA_NULL_BIN
    combo = (hotbar, fore_back, left_right, sprint_sneak,
             "use" if "use" in keys else "none", "drop" if "drop" in keys else "none",
             "attack" if "attack" in keys else "none", "jump" if "jump" in keys else "none",
             "camera" if moving else "none")
    return COMBINATION_INDEX[combo], pitch_bin * CAMERA_BINS + yaw_bin if moving else CAMERA_NULL


_MIRROR_BUTTON = {}


def mirror_buttons(index: int) -> int:
    """The same button combination with left and right swapped."""
    if not _MIRROR_BUTTON:
        swap = {"left": "right", "right": "left"}
        for i, c in enumerate(COMBINATIONS):
            if c == "inventory":
                _MIRROR_BUTTON[i] = i
            else:
                _MIRROR_BUTTON[i] = COMBINATION_INDEX[tuple(swap.get(x, x) for x in c)]
    return _MIRROR_BUTTON[int(index)]


def mirror_camera(index: int) -> int:
    pitch_bin, yaw_bin = divmod(int(index), CAMERA_BINS)
    return pitch_bin * CAMERA_BINS + (CAMERA_BINS - 1 - yaw_bin)
