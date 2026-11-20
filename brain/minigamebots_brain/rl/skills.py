"""Skills (Phase A): ready-made and custom skills, each with its own rewards, hints, locks and stages.

A skill definition (rl/skills.json "skills", or <profile>/skills/<id>/skill.json for custom and
edited ones):

    {"name", "icon", "category", "help", "setup", "manager", "target",
     "locks": ["move", "inventory", ...],          # actions it may not use while practicing
     "rules": [objective rules, "off": true to switch one off],
     "stages": [{"name", "speed", "shaping": [rules], "advance": {"episodes", "avg"}, "discover"}],
     "stage": null | index,                         # fixed stage (null = move on by itself)
     "advanced": {"stay_human", "entropy", "lr"}}   # optional overrides

Rules can reward game events (kill, block_broken, ...) or *behaviours* ("b:<id>"), which are
measured here every tick from what the AI sees and does (e.g. "b:hold_break": keeps holding
break on a block). Hints (shaping) are behaviour rules that only apply in early stages.
"""

from __future__ import annotations

import json
import math
import os

with open(os.path.join(os.path.dirname(__file__), "skills.json"), encoding="utf-8") as _f:
    LIBRARY = json.load(_f)

BASE_LR = 1e-4
BASE_ENTROPY = 0.003
CENTER_BIN = 15
FOODS = ("beef", "porkchop", "chicken", "mutton", "rabbit", "bread", "apple", "carrot", "potato", "cod", "salmon",
         "stew", "cookie", "melon_slice", "berries", "pie", "soup")
SWORD_DAMAGE = {"netherite": 8, "diamond": 7, "iron": 6, "stone": 5, "golden": 4, "wooden": 4}
TOOLS = ("pickaxe", "shovel", "_axe", "hoe")
JUNK = ("rotten_flesh", "poisonous_potato", "spider_eye", "wheat_seeds")


def preset(skill_id: str) -> dict | None:
    d = LIBRARY["skills"].get(skill_id)
    return json.loads(json.dumps(d)) if d else None


def stage_index(defn: dict, saved: int) -> int:
    stages = defn.get("stages") or []
    if not stages:
        return 0
    fixed = defn.get("stage")
    i = fixed if isinstance(fixed, int) else saved
    return max(0, min(len(stages) - 1, int(i)))


def build_objective(defn: dict, stage: int) -> dict:
    """The skill's rules plus its current stage's hints, without switched-off ones."""
    rules = [dict(r) for r in defn.get("rules") or [] if not r.get("off")]
    stages = defn.get("stages") or []
    if stages:
        rules += [dict(r, note="hint: " + r.get("note", r.get("event", ""))) for r in stages[stage].get("shaping") or []
                  if not r.get("off")]
    return {"name": defn.get("name", "skill"), "rules": rules}


def learning(defn: dict, stage: int) -> dict:
    """Learning rate, stay-human and exploration for a stage (advanced settings override)."""
    stages = defn.get("stages") or []
    st = stages[stage] if stages else {}
    sp = LIBRARY["speeds"].get(st.get("speed", "normal"), LIBRARY["speeds"]["normal"])
    out = {"lr": BASE_LR * sp["lr"], "stay_human": sp["stay_human"],
           "entropy": BASE_ENTROPY * (4.0 if st.get("discover") else 1.0)}
    adv = defn.get("advanced") or {}
    for k in ("lr", "stay_human", "entropy"):
        if adv.get(k) is not None:
            out[k] = float(adv[k]) * (BASE_LR if k == "lr" else BASE_ENTROPY if k == "entropy" else 1.0)
    return out


def locked_keys(defn: dict) -> tuple[list[str], bool]:
    """Keys the skill may not press, and whether turning the camera is locked."""
    keys, camera = [], False
    for group in defn.get("locks") or []:
        g = LIBRARY["locks"].get(group)
        if g is None:
            continue
        keys += g["keys"]
        camera = camera or group == "camera"
    return keys, camera


def mask_logits(logits: dict, key_idx: list[int], camera: bool) -> dict:
    """Makes locked actions impossible (also used inside learning, so the maths stays right)."""
    if not key_idx and not camera:
        return logits
    out = dict(logits)
    if key_idx:
        k = out["keys"].clone()
        k[..., key_idx] = -30.0
        out["keys"] = k
    if camera:
        for name in ("yaw", "pitch"):
            if name in out:
                c = out[name].clone()
                c[...] = -30.0
                c[..., CENTER_BIN] = 30.0
                out[name] = c
    return out


class StageTracker:
    """Which stage a skill is in (<skill dir>/stage.json); moves on after enough good tries."""

    def __init__(self, directory: str, defn: dict):
        self.path = os.path.join(directory, "stage.json")
        self.defn = defn
        self.data = {"stage": 0, "episodes": 0, "returns": []}
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass

    @property
    def stage(self) -> int:
        return stage_index(self.defn, self.data["stage"])

    def episode(self, ret: float) -> bool:
        """Counts a finished try; True if the skill moved to its next stage."""
        self.data["episodes"] += 1
        self.data["returns"] = (self.data["returns"] + [round(float(ret), 3)])[-10:]
        moved = False
        stages = self.defn.get("stages") or []
        if not isinstance(self.defn.get("stage"), int) and self.stage < len(stages) - 1:
            adv = stages[self.stage].get("advance") or {}
            need = int(adv.get("episodes", 0) or 0)
            avg_ok = "avg" not in adv or (len(self.data["returns"]) >= 3
                                          and sum(self.data["returns"]) / len(self.data["returns"]) >= float(adv["avg"]))
            need = max(need, int(self.data.get("hold", 0) or 0))
            if need > 0 and self.data["episodes"] >= need and avg_ok:
                rets = self.data["returns"]
                self.data.update(stage=self.stage + 1, episodes=0, returns=[], hold=0,
                                 entry_avg=sum(rets) / len(rets) if rets else None)
                moved = True
        # Automatic difficulty, the other way: clearly struggling at this stage -> one back, and it
        # has to earn the next one again over twice as many tries (no bouncing).
        if not moved and not isinstance(self.defn.get("stage"), int) and self.stage > 0 and len(self.data["returns"]) >= 10:
            prev = (stages[self.stage - 1].get("advance") or {}) if self.stage - 1 < len(stages) else {}
            ref = float(prev["avg"]) if "avg" in prev else self.data.get("entry_avg")
            avg = sum(self.data["returns"]) / len(self.data["returns"])
            if ref is not None and ref > 0 and avg < 0.25 * ref:
                hold = 2 * int(prev.get("episodes", 10) or 10)
                self.data.update(stage=self.stage - 1, episodes=0, returns=[], hold=hold, stepped_back=True)
        self.save()
        return moved

    def save(self) -> None:
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(self.data, f)


# --- behaviours ---------------------------------------------------------------------------

def _held(obs: dict) -> str:
    sel = obs.get("selectedSlot", 0)
    for i in obs.get("inventory") or []:
        if i.get("slot") == sel:
            return i.get("id", "")
    return ""


def _offhand(obs: dict) -> str:
    for i in obs.get("inventory") or []:
        if i.get("slot") == 40:
            return i.get("id", "")
    return ""


def _weapon_score(item: str, blocking: bool) -> float:
    for mat, dmg in SWORD_DAMAGE.items():
        if item.endswith(mat + "_sword"):
            return dmg
        if item.endswith(mat + "_axe"):
            return 20 + dmg if blocking else dmg - 1.5  # Axes disable shields; slower otherwise.
    return 0.0


class Behaviours:
    """Measures behaviours each tick and returns them as "b:<id>" events with "units"."""

    def __init__(self, defn: dict | None = None):
        self.target_type = (defn or {}).get("target") or ""
        self.prev = None
        self.prev_target = None
        self.seen = set()
        self.best_y = None
        self.prev_hits = {}
        self.combo = 0
        self.in_void = False
        from .waste import WasteTracker

        self.waste = WasteTracker()
        self.beaten_with = set()

    def load_beaten(self, profile: str) -> None:
        """What the players who beat it used (defeats.py tally): items that did 20%+ of the damage."""
        import json
        import os

        root = os.path.abspath(profile)
        for _ in range(4):
            path = os.path.join(root, "hurt_by.json")
            if os.path.isfile(path):
                try:
                    with open(path, encoding="utf-8") as f:
                        tally = json.load(f)
                except (OSError, ValueError):
                    return
                for g in tally.values():
                    held = g.get("held") or {}
                    total = sum(held.values()) or 1.0
                    self.beaten_with |= {k for k, v in held.items() if v / total >= 0.2}
                return
            root = os.path.dirname(root)

    def _target(self, obs: dict):
        best = None
        for e in obs.get("entities") or []:
            if not e.get("visible", True) or e.get("sameTeam"):
                continue
            t = e.get("type", "")
            if self.target_type:
                if t != self.target_type:
                    continue
            elif not (e.get("player") or e.get("hostile") or "mannequin" in t or t == "minecraft:interaction"):
                continue
            d = math.sqrt(e.get("dx", 0) ** 2 + e.get("dy", 0) ** 2 + e.get("dz", 0) ** 2)
            if best is None or d < best[0]:
                best = (d, e)
        return best

    @staticmethod
    def _aim_error(me: dict, e: dict) -> tuple[float, float, float]:
        h = 0.5 if e.get("type") == "minecraft:interaction" else 1.1
        flat = math.hypot(e.get("dx", 0), e.get("dz", 0))
        want_yaw = math.degrees(math.atan2(-e.get("dx", 0), e.get("dz", 0)))
        want_pitch = -math.degrees(math.atan2(e.get("dy", 0) + h, max(flat, 1e-3)))
        dy = abs((want_yaw - float(me.get("yaw", 0)) + 180) % 360 - 180)
        dp = abs(want_pitch - float(me.get("pitch", 0)))
        return dy, dp, flat

    def events(self, obs: dict, action, game_events: list) -> list:
        me = obs.get("self") or {}
        prev = self.prev or obs
        pme = prev.get("self") or {}
        keys = set(getattr(action, "keys", ()) or ()) if action is not None else set()
        attacking = "attack" in keys or int(getattr(action, "attacks", 0) or 0) > 0
        look = obs.get("look") or {}
        types = {e.get("type") for e in game_events}
        out = {}

        def put(name, units):
            if units:
                out[name] = out.get(name, 0.0) + float(units)

        # Mining.
        if "attack" in keys and look.get("breaking"):
            put("hold_break", 1)
        if (prev.get("look") or {}).get("breaking") and not look.get("breaking") and "block_broken" not in types \
                and "attack" not in keys:
            put("early_release", 1)
        # Targets.
        found = self._target(obs)
        dist = found[0] if found else None
        if found:
            e = found[1]
            dy, dp, flat = self._aim_error(me, e)
            err = max(dy, dp)
            put("face_target", max(0.0, 1.0 - err / 45.0))
            tol = max(3.0, math.degrees(math.atan2(0.4, max(flat, 0.5))))
            if dy < tol and dp < tol * 1.3:
                put("aim_on_target", 1)
            if self.prev_target is not None and dist > 3:
                put("approach_target", max(-0.5, min(0.5, self.prev_target - dist)))
            reach = math.hypot(max(0.0, flat - 0.3), e.get("dy", 0) + 1.1)
            if 2.2 <= reach <= 3.2:
                put("keep_range", 1)
            if dist < 6:
                if attacking and float(pme.get("attackReady", 1.0)) < 0.9:
                    put("wasted_swing", 1)
                best = max((_weapon_score(i.get("id", ""), bool(e.get("blocking"))) for i in obs.get("inventory") or []
                            if i.get("slot", 99) < 9), default=0.0)
                if best > 0 and _weapon_score(_held(obs), bool(e.get("blocking"))) >= best:
                    put("best_weapon", 1)
                if e.get("swinging") and dist < 4.5 and me.get("usingItem") and "shield" in _offhand(obs):
                    put("shield_up", 1)
            if me.get("usingItem") and "bow" in _held(obs) and err < 15:
                put("bow_drawn", 1)
            if self.prev_target is not None and dist < 16:
                put("escape", max(-0.5, min(0.5, dist - self.prev_target)))
        self.prev_target = dist
        for ev in game_events:
            t, data = ev.get("type"), ev.get("data") or {}
            if t == "attack":
                if not pme.get("onGround", True) and float(pme.get("vy", 0.0)) < 0:
                    put("falling_hit", 1)
                if pme.get("sprinting"):
                    put("sprint_hit", 1)
                if "_axe" in str(data.get("weapon", "")) and found and found[1].get("blocking"):
                    put("axe_on_shield", 1)
            elif t == "sound_heard" and "shield.block" in str(data.get("sound", "")):
                put("blocked_hit", 1)
            elif t == "hunger_changed" and float(data.get("to", 0)) > float(data.get("from", 0)):
                put("ate", 1)
            elif t == "screen_opened" and "inventory" in str(data.get("screen", "")).lower():
                put("inventory_open", 1)
            elif t == "damage_dealt":
                self.combo += 1
                if self.combo >= 3:
                    put("combo", 1)
            elif t == "damage_taken":
                self.combo = 0
                if data.get("cause") == "void" and not self.in_void:
                    self.in_void = True
                    put("fell_into_void", 1)
        for e in obs.get("entities") or []:
            d2 = math.hypot(float(e.get("dx", 0)), float(e.get("dz", 0)))
            if (e.get("sameTeam") or e.get("allyInsignia")) and d2 < 4:
                put("near_teammate", 1)
            if e.get("protectedTarget") and d2 < 5:
                put("near_protectee", 1)
            if e.get("sameTeam") or e.get("allyInsignia"):
                h = int(e.get("hitByMe", 0))
                if h > self.prev_hits.get(e.get("id"), h):
                    put("hit_ally", 1)
                self.prev_hits[e.get("id")] = h
        # Movement.
        moved = math.hypot(float(me.get("x", 0)) - float(pme.get("x", 0)), float(me.get("z", 0)) - float(pme.get("z", 0)))
        if moved < 2:  # Not a teleport.
            put("moving", min(0.5, moved))
        if me.get("sprinting"):
            put("sprinting", 1)
        cell = (me.get("dimension"), int(float(me.get("x", 0)) // 8), int(float(me.get("z", 0)) // 8))
        if cell not in self.seen:
            if self.seen:
                put("new_area", 1)
            self.seen.add(cell)
        cam = abs(float(getattr(action, "yaw", 0.0) or 0.0)) + abs(float(getattr(action, "pitch", 0.0) or 0.0)) if action else 0.0
        if not keys and cam < 0.01 and not obs.get("gui"):
            put("idle", 1)
        pitch = float(me.get("pitch", 0))
        if abs(pitch) < 20:
            put("look_level", 1)
        if pitch > 60:
            put("look_down", 1)
        y = float(me.get("y", 0))
        if self.best_y is None:
            self.best_y = y
        if y > self.best_y + 0.5 and moved < 2:
            put("going_up", y - self.best_y)
            self.best_y = y
        if me.get("sneaking") and me.get("onGround"):
            put("sneak_edge", 1)
        drop = float(pme.get("y", y)) - y
        if drop > 0.01 and moved < 2:
            put("going_down", drop)
        if me.get("onGround"):
            self.in_void = False
            edge = _edge_distance(obs)
            if edge is not None and edge <= 1:
                put("near_edge", 1)
            elif edge is None or edge > 2:
                put("away_from_edge", 1)
        if me.get("inWater") and not pme.get("inWater") and float(pme.get("vy", 0)) < -0.4:
            put("landed_in_water", 1)
        max_air = float(me.get("maxAir", 300) or 300)
        air = float(me.get("air", max_air))
        if air < max_air * 0.3:
            put("drowning", 1)
        if me.get("inWater") and air < max_air * 0.5 and float(me.get("vy", 0)) > 0:
            put("swim_up", 1)
        if me.get("inLava"):
            put("in_lava", 1)
        held = _held(obs)
        food = int(me.get("food", 20))
        if me.get("usingItem") and any(f in held for f in FOODS) and food < 20:
            put("eat_when_hungry", 1)
        if food <= 6:
            put("starving", 1)
        if obs.get("gui"):
            put("menu_time", 1)
        put("hotbar_tidy", hotbar_score(obs))
        self.prev = obs
        res = [{"type": "b:" + k, "data": {"units": v}} for k, v in out.items()]
        # Behaviours with a choice (matched against the rule's "where"): always on, with the detail.
        if me.get("onGround") and me.get("standingOn"):
            res.append({"type": "b:standing_on", "data": {"units": 1.0, "block": me.get("standingOn")}})
        if me.get("zone"):
            res.append({"type": "b:in_zone", "data": {"units": 1.0, "zone": me.get("zone")}})
        if held:
            res.append({"type": "b:holding", "data": {"units": 1.0, "item": held}})
        if held and held in self.beaten_with and ("attack" in keys or "use" in keys or attacking):
            res.append({"type": "b:uses_what_beat_it", "data": {"units": 1.0, "item": held}})
        res += self.waste.step(obs, action, game_events)
        return res


def _edge_distance(obs: dict):
    """Blocks to the nearest drop of 2+ (open air below the floor level next to it), within 3; None if none."""
    from ..learn.features import decode_blocks

    g = obs.get("blocks")
    if not g:
        return None
    try:
        grid = decode_blocks(g)
    except Exception:
        return None
    r = grid.shape[0] // 2
    best = None
    for dx in range(-3, 4):
        for dz in range(-3, 4):
            if dx == 0 and dz == 0:
                continue
            if int(grid[r - 1][dz + r][dx + r]) == 1 and int(grid[r - 2][dz + r][dx + r]) == 1:
                dist = max(abs(dx), abs(dz))
                best = dist if best is None else min(best, dist)
    return best


def hotbar_score(obs: dict) -> float:
    """0..1: weapon, food, blocks and a tool in the hotbar, and no junk in it."""
    hot = [i.get("id", "") for i in obs.get("inventory") or [] if i.get("slot", 99) < 9]
    if not hot:
        return 0.0
    weapon = any("_sword" in h or "_axe" in h for h in hot)
    food = any(any(f in h for f in FOODS) for h in hot)
    tool = any(any(t in h for t in TOOLS if t != "_axe") for h in hot)
    blocks = any(h.endswith(("_planks", "cobblestone", "_wool", "stone", "dirt", "_log", "_concrete")) for h in hot)
    junk = sum(any(j in h for j in JUNK) for h in hot)
    return max(0.0, (weapon + food + tool + blocks) / 4.0 - 0.1 * junk)
