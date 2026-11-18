"""Turns an Observation (as sent by the mod and stored in recordings) into network inputs.

Everything is expressed relative to where the player is facing (in front / to the right / above),
so "a pig ahead and to the left" looks the same whichever way the player happens to face. Item,
entity and screen names are hashed into fixed-size vocabularies, so items the network has never
seen (modded, custom) still get a stable input of their own.

Bump FEATURES_VERSION whenever the layout changes: checkpoints record it and refuse to load
inputs they weren't trained on.
"""

import math
import zlib

import numpy as np

FEATURES_VERSION = 1

ITEM_BUCKETS = 4096
ENTITY_BUCKETS = 1024
SCREEN_BUCKETS = 256
MAX_ENTITIES = 16
BLOCK_SIZE = 11
BLOCK_CATEGORIES = 10
# Hotbar 0-8, armor 36-39, offhand 40.
ITEM_SLOTS = list(range(9)) + [36, 37, 38, 39, 40]
SELF_DIM = 31
ITEM_DIM = 2
ENTITY_DIM = 14
FRAME_SIZE = 128


def bucket(name: str, buckets: int) -> int:
    """Stable hash into 1..buckets-1 (0 means empty)."""
    if not name:
        return 0
    return zlib.crc32(name.encode("utf-8")) % (buckets - 1) + 1


def facing_axes(yaw_degrees: float):
    """Unit vectors (x, z) for straight ahead and to the right, in Minecraft's coordinates.

    Minecraft: yaw 0 faces +Z (south); yaw 90 faces -X (west).
    """
    yaw = math.radians(yaw_degrees)
    forward = (-math.sin(yaw), math.cos(yaw))
    right = (-math.cos(yaw), -math.sin(yaw))
    return forward, right


def to_local(dx: float, dz: float, yaw_degrees: float):
    """World offset -> (ahead, right) relative to the facing direction."""
    (fx, fz), (rx, rz) = facing_axes(yaw_degrees)
    return dx * fx + dz * fz, dx * rx + dz * rz


def featurize(obs: dict) -> dict:
    """Numpy arrays for one tick. Missing fields fall back to neutral values."""
    me = obs.get("self") or {}
    yaw = float(me.get("yaw", 0.0))
    pitch = float(me.get("pitch", 0.0))

    v_ahead, v_right = to_local(float(me.get("vx", 0.0)), float(me.get("vz", 0.0)), yaw)
    selected = int(obs.get("selectedSlot", 0))
    self_vec = np.zeros(SELF_DIM, dtype=np.float32)
    values = [
        me.get("health", 20) / 20, me.get("maxHealth", 20) / 20, me.get("food", 20) / 20,
        me.get("saturation", 5) / 20, me.get("armor", 0) / 20, min(me.get("xpLevel", 0), 30) / 30,
        me.get("xpProgress", 0), me.get("air", 300) / max(1, me.get("maxAir", 300)),
        float(me.get("onGround", True)), float(me.get("inWater", False)), float(me.get("inLava", False)),
        float(me.get("sneaking", False)), float(me.get("sprinting", False)), float(me.get("usingItem", False)),
        math.sin(math.radians(yaw)), math.cos(math.radians(yaw)), pitch / 90,
        v_ahead * 2, float(me.get("vy", 0.0)) * 2, v_right * 2,
        float(me.get("dead", False)), float(obs.get("gui") is not None),
    ]
    self_vec[:len(values)] = values
    if 0 <= selected < 9:
        self_vec[22 + selected] = 1.0

    items = np.zeros(len(ITEM_SLOTS), dtype=np.int64)
    item_vals = np.zeros((len(ITEM_SLOTS), ITEM_DIM), dtype=np.float32)
    by_slot = {it.get("slot"): it for it in obs.get("inventory") or []}
    for i, slot in enumerate(ITEM_SLOTS):
        it = by_slot.get(slot)
        if it:
            items[i] = bucket(it.get("id", ""), ITEM_BUCKETS)
            item_vals[i, 0] = min(it.get("count", 1), 64) / 64
            max_damage = it.get("maxDamage", 0)
            item_vals[i, 1] = 1 - it.get("damage", 0) / max_damage if max_damage else 1.0

    ent_type = np.zeros(MAX_ENTITIES, dtype=np.int64)
    ent_held = np.zeros(MAX_ENTITIES, dtype=np.int64)
    ent_vals = np.zeros((MAX_ENTITIES, ENTITY_DIM), dtype=np.float32)
    ent_mask = np.zeros(MAX_ENTITIES, dtype=np.bool_)
    for i, e in enumerate((obs.get("entities") or [])[:MAX_ENTITIES]):
        dx, dy, dz = e.get("dx", 0.0), e.get("dy", 0.0), e.get("dz", 0.0)
        ahead, right = to_local(dx, dz, yaw)
        horizontal = math.hypot(dx, dz)
        distance = math.sqrt(dx * dx + dy * dy + dz * dz)
        bearing = math.atan2(right, ahead) / math.pi  # -1..1, 0 = straight ahead
        # How far to look up/down to aim at its middle; Minecraft pitch is positive downwards.
        aim_pitch = -math.degrees(math.atan2(dy + 0.5, max(horizontal, 1e-3)))
        vahead, vright = to_local(e.get("vx", 0.0), e.get("vz", 0.0), yaw)
        max_health = e.get("maxHealth", 0)
        ent_type[i] = bucket(e.get("type", ""), ENTITY_BUCKETS)
        ent_held[i] = bucket(e.get("heldItem", ""), ITEM_BUCKETS)
        ent_vals[i] = [
            ahead / 48, right / 48, dy / 48, distance / 48, bearing,
            (aim_pitch - pitch) / 90, vahead * 2, e.get("vy", 0.0) * 2, vright * 2,
            e.get("health", 0) / max_health if max_health else 0.0, min(max_health, 100) / 100,
            float(e.get("player", False)), float(e.get("visible", True)), min(e.get("ticksSinceSeen", 0), 60) / 60,
        ]
        ent_mask[i] = True

    blocks = decode_blocks(obs.get("blocks"))
    blocks = rotate_blocks(blocks, yaw)

    gui = obs.get("gui")
    screen = bucket(gui.get("screen", ""), SCREEN_BUCKETS) if gui else 0
    return {
        "self": self_vec, "items": items, "item_vals": item_vals, "ent_type": ent_type, "ent_held": ent_held,
        "ent_vals": ent_vals, "ent_mask": ent_mask, "blocks": blocks, "screen": np.int64(screen),
    }


def decode_blocks(blocks) -> np.ndarray:
    """The block cube as [dy][dz][dx] categories, or all "unknown" if missing."""
    size = BLOCK_SIZE
    if not blocks or "categories" not in blocks:
        return np.zeros((size, size, size), dtype=np.int64)
    import base64

    raw = blocks["categories"]
    data = base64.b64decode(raw) if isinstance(raw, str) else bytes(raw)
    if len(data) != size ** 3:
        return np.zeros((size, size, size), dtype=np.int64)
    return np.frombuffer(data, dtype=np.uint8).reshape(size, size, size).astype(np.int64)


def rotate_blocks(blocks: np.ndarray, yaw_degrees: float) -> np.ndarray:
    """Turns the cube so that the direction the player faces (rounded to the nearest of the four
    compass directions) is always +dz in the result."""
    quarter = int(round(yaw_degrees / 90.0)) % 4
    # Facing +Z needs no turn; each further quarter turn (clockwise seen from above) is undone
    # by rotating the dz/dx plane back.
    return np.ascontiguousarray(np.rot90(blocks, k=quarter, axes=(1, 2)))


def batch(features: list) -> dict:
    """Stacks a list of featurize() results along a new first axis."""
    return {k: np.stack([f[k] for f in features]) for k in features[0]}
