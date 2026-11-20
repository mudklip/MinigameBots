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

FEATURES_VERSION = 2

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
ENTITY_DIM = 19
# Crowd radar: entities that didn't fit in the slots, counted by kind x distance band x direction.
CROWD_KINDS = 3  # players, hostile mobs, others
CROWD_BANDS = 3  # 0-8, 8-24, 24-64 blocks
CROWD_SECTORS = 8  # 45 degrees each; stored by compass direction, turned here to be facing-relative
CROWD_DIM = CROWD_KINDS * CROWD_BANDS * CROWD_SECTORS
# Ticks a remembered hit stays relevant (the mod forgets after 30 s).
HIT_MEMORY = 600
# Memory inputs (added after features v2; older recordings simply give zeros here).
WAYPOINT_TYPES = ("base", "chest", "mine", "portal", "danger", "farm", "custom")  # Waypoint.TYPES in the mod
MAX_WAYPOINTS = 4
WAYPOINT_DIM = len(WAYPOINT_TYPES) + 6
ENTITY_REL_DIM = 4
# Knowledge-base calculators (the mod computes them from real item stats).
ENTITY_CALC_DIM = 3  # 1/hits I need, 1/hits they need, fight odds (-1 lose .. 1 win)
SELF_CALC_DIM = 3  # attack charge, crit ready, fall damage if landing now
# The current goal (from the Manager): its type, and where its target is if it has one.
GOAL_TYPES = ("free_play", "mine", "hunt", "protect", "follow", "go_to", "explore", "survive", "fight", "flee")
GOAL_DIM = len(GOAL_TYPES) + 5
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


LOOT_TYPES = ("minecraft:item", "minecraft:experience_orb")


def importance(e: dict) -> float:
    """Same ordering as the mod's RelationTracker.importance (lower comes first), so recordings
    made before the mod ordered entities this way still put the right ones in the slots."""
    distance = math.sqrt(e.get("dx", 0.0) ** 2 + e.get("dy", 0.0) ** 2 + e.get("dz", 0.0) ** 2)
    score = distance
    if e.get("protectedTarget"):
        score -= 1000
    if 0 <= e.get("hitMe", -1) <= 200:
        score -= 500
    if 0 <= e.get("hitProtected", -1) <= 200:
        score -= 400
    if 0 <= e.get("hitByMe", -1) <= 200:
        score -= 150
    if e.get("player"):
        score -= 60
    elif e.get("hostile"):
        score -= 40
    elif e.get("type") in LOOT_TYPES:
        score += 30
    return score


# The opponent's style, as read by policies/opponent.py (obs["opponent"]; zeros when nobody):
# range, aggression, strafe, block, crit, low_retreat, confidence, baiter, fake retreat, bluff.
OPP_DIM = 10
OPP_STRAFE = 2


def featurize(obs: dict) -> dict:
    # Privileged training data (Watch & learn) is never an input of the playing network.
    if "privileged" in obs:
        obs = {k: v for k, v in obs.items() if k != "privileged"}
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
    ent_rel = np.zeros((MAX_ENTITIES, ENTITY_REL_DIM), dtype=np.float32)
    ent_calc = np.zeros((MAX_ENTITIES, ENTITY_CALC_DIM), dtype=np.float32)
    entities = sorted(obs.get("entities") or [], key=importance)
    for i, e in enumerate(entities[:MAX_ENTITIES]):
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
            recency(e.get("hitMe", -1)), recency(e.get("hitByMe", -1)), recency(e.get("hitProtected", -1)),
            float(e.get("sameTeam", False)), float(e.get("protectedTarget", False)),
        ]
        ent_mask[i] = True
        # Friend or foe: remembered names, scoreboard teams and insignia (shields, banners, dyes).
        ent_rel[i] = [float(e.get("relation", 0)), float(e.get("allyInsignia", False)),
                      float(e.get("enemyInsignia", False)), float(bool(e.get("insignia")))]
        mine, theirs = e.get("myHitsToKill", 0), e.get("theirHitsToKill", 0)
        ent_calc[i] = [1.0 / mine if mine else 0.0, 1.0 / theirs if theirs else 0.0, float(e.get("fightOdds", 0.0))]

    wp_vals = np.zeros((MAX_WAYPOINTS, WAYPOINT_DIM), dtype=np.float32)
    wp_mask = np.zeros(MAX_WAYPOINTS, dtype=np.bool_)
    for i, w in enumerate((obs.get("waypoints") or [])[:MAX_WAYPOINTS]):
        t = w.get("type", "custom")
        if t in WAYPOINT_TYPES:
            wp_vals[i, WAYPOINT_TYPES.index(t)] = 1.0
        dx, dy, dz = w.get("dx", 0.0), w.get("dy", 0.0), w.get("dz", 0.0)
        ahead, right = to_local(dx, dz, yaw)
        dist = max(1e-3, float(w.get("distance", math.sqrt(dx * dx + dy * dy + dz * dz))))
        n = len(WAYPOINT_TYPES)
        # Direction (unit, facing-relative), height difference and log distance, so far places still register.
        wp_vals[i, n:n + 6] = [ahead / dist, right / dist, max(-1.0, min(1.0, dy / 32)), math.log1p(dist) / 8,
                               float(dist < 4), float(w.get("pinned", False))]
        wp_mask[i] = True

    crowd = crowd_counts(obs.get("crowd"))
    # Detailed entities beyond our slots still count, on the crowd radar.
    for e in entities[MAX_ENTITIES:]:
        dx, dz = e.get("dx", 0.0), e.get("dz", 0.0)
        distance = math.sqrt(dx * dx + e.get("dy", 0.0) ** 2 + dz * dz)
        kind = 0 if e.get("player") else 1 if e.get("hostile") else 2
        band = 0 if distance < 8 else 1 if distance < 24 else 2
        crowd[kind, band, compass_sector(dx, dz)] += 1

    blocks = decode_blocks(obs.get("blocks"))
    blocks = rotate_blocks(blocks, yaw)

    goal = obs.get("goal") or {}
    goal_vec = np.zeros(GOAL_DIM, dtype=np.float32)
    if goal.get("type") in GOAL_TYPES:
        goal_vec[GOAL_TYPES.index(goal["type"])] = 1.0
        n = len(GOAL_TYPES)
        if "target_dy" in goal:
            goal_vec[n] = max(-1.0, min(1.0, float(goal["target_dy"]) / 64.0))
        if "target_dx" in goal and "target_dz" in goal:
            ahead, right = to_local(float(goal["target_dx"]), float(goal["target_dz"]), yaw)
            dist = max(1e-3, math.hypot(ahead, right))
            goal_vec[n + 1:n + 5] = [ahead / dist, right / dist, math.log1p(dist) / 8, 1.0]

    gui = obs.get("gui")
    screen = bucket(gui.get("screen", ""), SCREEN_BUCKETS) if gui else 0
    return {
        "crowd": crowd_features(crowd, yaw),
        "self": self_vec, "items": items, "item_vals": item_vals, "ent_type": ent_type, "ent_held": ent_held,
        "ent_vals": ent_vals, "ent_mask": ent_mask, "blocks": blocks, "screen": np.int64(screen),
        "ent_rel": ent_rel, "wp_vals": wp_vals, "wp_mask": wp_mask, "ent_calc": ent_calc, "goal": goal_vec,
        "opp": np.asarray((obs.get("opponent") or [0.0] * OPP_DIM)[:OPP_DIM], dtype=np.float32),
        "self_calc": np.array([float(me.get("attackReady", 1.0)), float(me.get("critReady", False)),
                               min(float(me.get("fallDamage", 0.0)), 40.0) / 20.0], dtype=np.float32),
    }


def recency(ticks_since) -> float:
    """1 = just now, fading to 0 after HIT_MEMORY ticks; 0 = never (or -1 from the mod)."""
    if ticks_since is None or ticks_since < 0:
        return 0.0
    return max(0.0, 1.0 - ticks_since / HIT_MEMORY)


def compass_sector(dx: float, dz: float) -> int:
    """Same as RelationTracker.sector in the mod: 0 = south (+Z), 2 = west, 4 = north, 6 = east."""
    yaw = math.degrees(math.atan2(-dx, dz))
    return int(math.floor((yaw + 360 + 22.5) / 45.0)) % CROWD_SECTORS


def crowd_counts(crowd) -> np.ndarray:
    """The mod's crowd radar as a [kind, band, sector] array (zeros if missing)."""
    counts = crowd.get("counts") if isinstance(crowd, dict) else crowd
    if counts is None or len(counts) != CROWD_DIM:
        return np.zeros((CROWD_KINDS, CROWD_BANDS, CROWD_SECTORS), dtype=np.float32)
    return np.asarray(counts, dtype=np.float32).reshape(CROWD_KINDS, CROWD_BANDS, CROWD_SECTORS).copy()


def crowd_features(crowd, yaw_degrees: float) -> np.ndarray:
    """The crowd radar, turned so sector 0 is straight ahead, as log-scaled counts."""
    arr = crowd if isinstance(crowd, np.ndarray) and crowd.ndim == 3 else crowd_counts(crowd)
    # The mod's sector s covers Minecraft yaw s*45 (0 = south, 90 = west). Facing yaw, the
    # sector straight ahead is round(yaw / 45).
    shift = int(round(yaw_degrees / 45.0)) % CROWD_SECTORS
    arr = np.roll(arr, -shift, axis=2)
    return (np.log1p(arr) / 3.0).reshape(-1).astype(np.float32)


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


# --- Mirroring (training data augmentation) ---
# The same moment seen in a mirror (left <-> right) is an equally valid example, so every
# recording can also be trained on flipped. Everything here is facing-relative, which makes
# the flip simple: negate "right" components, flip the block cube and crowd radar sideways.
SELF_SIN_YAW = 14
SELF_V_RIGHT = 19
ENT_RIGHT_COLUMNS = (1, 4, 8)  # right, bearing, sideways velocity


def mirror(f: dict) -> dict:
    """A featurize() result (one tick, or stacked along leading axes) flipped left-right."""
    out = dict(f)
    s = f["self"].copy()
    s[..., SELF_SIN_YAW] *= -1
    s[..., SELF_V_RIGHT] *= -1
    out["self"] = s
    e = f["ent_vals"].copy()
    for c in ENT_RIGHT_COLUMNS:
        e[..., c] *= -1
    out["ent_vals"] = e
    if "opp" in f:
        o = f["opp"].copy()
        o[..., OPP_STRAFE] *= -1
        out["opp"] = o
    if "wp_vals" in f:
        w = f["wp_vals"].copy()
        w[..., len(WAYPOINT_TYPES) + 1] *= -1
        out["wp_vals"] = w
    if "goal" in f:
        g = f["goal"].copy()
        g[..., len(GOAL_TYPES) + 1] *= -1
        out["goal"] = g
    out["blocks"] = np.ascontiguousarray(np.flip(f["blocks"], axis=-1))
    crowd = f["crowd"].reshape(*f["crowd"].shape[:-1], CROWD_KINDS, CROWD_BANDS, CROWD_SECTORS)
    order = [(-k) % CROWD_SECTORS for k in range(CROWD_SECTORS)]
    out["crowd"] = np.ascontiguousarray(crowd[..., order]).reshape(f["crowd"].shape)
    if "frames" in f:
        out["frames"] = np.ascontiguousarray(np.flip(f["frames"], axis=-2))
    return out
