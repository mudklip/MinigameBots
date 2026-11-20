"""Item wasted: used up, and none of what that item is for happened soon after.

Each use (an item's count going down without dropping it) opens a short watch for the benefits
that count for that kind of item:
  projectiles (arrows, snowballs, eggs)  hit something
  food                                   only what overflowed full hunger and saturation is wasted
  golden apples                          a fight came before their effects ran out
  healing (instant health, regeneration) the share of the healing actually used (self + allies)
  harmful splash/lingering potions       reached an enemy (its health dropped, or one was close)
  buff potions                           reached it; strength: dealt damage while it lasted,
                                         fire resistance: was in fire/lava while it lasted
  ender pearls                           closed in and hit, got away, or got somewhere (up, across)
  wind charges                           hit or knocked back an enemy, got away, or real height or
                                         distance toward something
Waste is scaled by scarcity: with plenty of that item left it costs little, with the last few a lot.
It only ever costs (a budget rule): using an item well earns nothing by itself. Blocks are left out.

Events: {"type": "b:item_wasted", "data": {"units", "item", "why", "used"}} ("used": the share of
healing used, for the rule's "min_use").
"""

import math

PROJECTILES = ("minecraft:arrow", "minecraft:spectral_arrow", "minecraft:tipped_arrow", "minecraft:snowball", "minecraft:egg")
GOLDEN = ("minecraft:golden_apple", "minecraft:enchanted_golden_apple")
POTIONS = ("minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion")
FOOD = {  # hunger, saturation modifier
    "apple": (4, 0.3), "bread": (5, 0.6), "cooked_beef": (8, 0.8), "cooked_porkchop": (8, 0.8),
    "cooked_chicken": (6, 0.6), "cooked_mutton": (6, 0.8), "cooked_salmon": (6, 0.8), "cooked_cod": (5, 0.6),
    "cooked_rabbit": (5, 0.6), "baked_potato": (5, 0.6), "carrot": (3, 0.6), "golden_carrot": (6, 1.2),
    "melon_slice": (2, 0.3), "cookie": (2, 0.1), "pumpkin_pie": (8, 0.3), "sweet_berries": (2, 0.1),
    "glow_berries": (2, 0.1), "mushroom_stew": (6, 0.6), "rabbit_stew": (10, 0.6), "beetroot_soup": (6, 0.6),
    "beetroot": (1, 0.6), "dried_kelp": (1, 0.3), "potato": (1, 0.3), "beef": (3, 0.3), "porkchop": (3, 0.3),
    "chicken": (2, 0.3), "mutton": (2, 0.3), "cod": (2, 0.1), "salmon": (2, 0.1), "honey_bottle": (6, 0.1),
}
HEALING = ("healing",)
REGEN = ("regeneration",)
HARMFUL = ("harming", "poison", "slowness", "weakness")
SCARCE_K = {"projectile": 16, "food": 16, "golden": 4, "potion": 3, "pearl": 4, "wind": 8}
DAMAGE_DEALT = {"damage_dealt", "tg_damage_dealt", "kill", "tg_dummy_hit", "tg_aim_hit"}


def _counts(obs):
    c = {}
    for it in obs.get("inventory") or []:
        k = (it.get("id", ""), it.get("detail") or "")
        c[k] = c.get(k, 0) + int(it.get("count", 1) or 1)
    return c


def _left(obs, item_id):
    return sum(int(it.get("count", 1) or 1) for it in obs.get("inventory") or [] if it.get("id") == item_id)


def _effects(me):
    return {":".join(str(x).split(":")[:2]) for x in me.get("effects") or []}


def _enemies(obs):
    return [e for e in obs.get("entities") or [] if (e.get("player") or e.get("hostile")) and not e.get("sameTeam")
            and not e.get("allyInsignia")]


def _allies(obs):
    return [e for e in obs.get("entities") or [] if e.get("sameTeam") or e.get("allyInsignia")]


def _dist(e):
    return math.sqrt(float(e.get("dx", 0)) ** 2 + float(e.get("dy", 0)) ** 2 + float(e.get("dz", 0)) ** 2)


class WasteTracker:
    def __init__(self):
        self.prev = None
        self.watch = []  # open checks
        self.tick = 0
        self.last_hurt = -10 ** 9

    def step(self, obs: dict, action, events: list) -> list:
        self.tick += 1
        me = obs.get("self") or {}
        types = [e.get("type") for e in events]
        if "damage_taken" in types:
            self.last_hurt = self.tick
        out = []
        prev = self.prev
        self.prev = obs
        if prev is not None and not me.get("dead"):
            dropped = action is not None and "drop" in set(getattr(action, "keys", ()) or ())
            before, after = _counts(prev), _counts(obs)
            for (item, detail), n in before.items():
                if after.get((item, detail), 0) < n and not dropped:
                    self._start(item, detail, prev, obs)
        for w in list(self.watch):
            res = self._check(w, obs, events)
            if res is None:
                continue
            self.watch.remove(w)
            waste, why, used = res
            if waste > 0.05:
                scale = 1.0 / (1.0 + _left(obs, w["item"]) / SCARCE_K[w["kind"]])
                out.append({"type": "b:item_wasted", "data": {"units": round(waste * scale, 3), "item": w["item"],
                                                              "why": why, "used": used}})
        return out

    # --- starting a watch ---

    def _start(self, item, detail, prev, obs):
        pme, me = prev.get("self") or {}, obs.get("self") or {}
        w = {"item": item, "detail": detail, "t0": self.tick, "x": float(me.get("x", 0)), "y": float(me.get("y", 0)),
             "z": float(me.get("z", 0)), "health0": float(pme.get("health", 20)), "healed": 0.0, "hit": False,
             "fight": False, "allies0": {e.get("id"): float(e.get("health", 20) or 20) for e in _allies(obs)},
             "enemies0": {e.get("id"): float(e.get("health", 20) or 20) for e in _enemies(obs) if _dist(e) < 10},
             "threat": self.tick - self.last_hurt < 60 or float(pme.get("health", 20)) < 10,
             "near": min((_dist(e) for e in _enemies(obs)), default=None)}
        name = item.split(":")[-1]
        if item in PROJECTILES:
            w.update(kind="projectile", until=60)
        elif item in GOLDEN:
            w.update(kind="golden", until=2400)
        elif name in FOOD:
            w.update(kind="food", until=6, food0=int(pme.get("food", 20)), sat0=float(pme.get("saturation", 0)))
        elif item in POTIONS and detail:
            pot = detail.split(":")[-1]
            base = pot.replace("strong_", "").replace("long_", "")
            w.update(kind="potion", pot=pot, base=base, splash=item != "minecraft:potion")
            if base in HEALING:
                w.update(until=12, potential=8.0 if pot.startswith("strong_") else 4.0)
            elif base in REGEN:
                w.update(until=1800, potential=36.0 if pot.startswith("long_") else 18.0, seen_effect=False)
            elif base in HARMFUL:
                w.update(until=40)
            else:
                w.update(until=4800, seen_effect=False)
        elif item == "minecraft:ender_pearl":
            w.update(kind="pearl", until=120, landed=None)
        elif item == "minecraft:wind_charge":
            w.update(kind="wind", until=60, top=w["y"], far=0.0)
        else:
            return
        self.watch.append(w)

    # --- checking it ---

    def _check(self, w, obs, events):
        """None while still watching; else (waste 0..1, why, share of healing used or None)."""
        me = obs.get("self") or {}
        age = self.tick - w["t0"]
        types = {e.get("type") for e in events}
        for e in events:
            if e.get("type") == "healed":
                w["healed"] += float((e.get("data") or {}).get("amount", 0) or 0)
            if e.get("type") in DAMAGE_DEALT:
                w["hit"] = True
            if e.get("type") in ("damage_taken", "damage_dealt", "tg_damage_dealt"):
                w["fight"] = True
        k = w["kind"]
        if k == "projectile":
            if w["hit"]:
                return 0.0, "", None
            return (1.0, "missed", None) if age >= w["until"] else None
        if k == "food":
            if age < w["until"]:
                return None
            h, mod = FOOD[w["item"].split(":")[-1]]
            expected = h + h * mod * 2
            got = (int(me.get("food", 20)) - w["food0"]) + (float(me.get("saturation", 0)) - w["sat0"])
            waste = max(0.0, min(1.0, 1.0 - got / expected))
            return (waste, "ate while full", None) if waste > 0.2 else (0.0, "", None)
        if k == "golden":
            if w["fight"] or w["healed"] >= 2:
                return 0.0, "", None
            return (1.0, "no fight before it wore off", None) if age >= w["until"] else None
        if k == "potion":
            return self._potion(w, obs, me, age)
        if k == "pearl":
            return self._pearl(w, obs, me, age, types)
        if k == "wind":
            return self._wind(w, obs, me, age)
        return 0.0, "", None

    def _ally_heal(self, w, obs):
        got = 0.0
        for e in _allies(obs):
            h0 = w["allies0"].get(e.get("id"))
            if h0 is not None:
                got += max(0.0, float(e.get("health", 20) or 20) - h0)
        return got

    def _potion(self, w, obs, me, age):
        base = w["base"]
        effects = _effects(me)
        if base in HEALING:
            if age < w["until"]:
                return None
            used = min(1.0, (w["healed"] + (self._ally_heal(w, obs) if w["splash"] else 0.0)) / w["potential"])
            return 1.0 - used, f"only {round(used * 100)}% of the healing used", round(used, 2)
        if base in REGEN:
            active = "minecraft:regeneration" in effects
            w["seen_effect"] = w["seen_effect"] or active
            if (w["seen_effect"] and not active) or age >= w["until"] or (not w["seen_effect"] and age > 40):
                used = min(1.0, (w["healed"] + (self._ally_heal(w, obs) if w["splash"] else 0.0)) / w["potential"])
                why = "missed itself" if not w["seen_effect"] else f"only {round(used * 100)}% of the healing used"
                return 1.0 - used, why, round(used, 2)
            return None
        if base in HARMFUL:
            for e in _enemies(obs):
                h0 = w["enemies0"].get(e.get("id"))
                if h0 is not None and float(e.get("health", 20) or 20) < h0:
                    return 0.0, "", None
            if age < w["until"]:
                return None
            close = w["near"] is not None and w["near"] < 6
            return (0.0, "", None) if close and base in ("slowness", "weakness") else (1.0, "reached no enemy", None)
        # Buffs.
        eff = "minecraft:" + {"swiftness": "speed", "leaping": "jump_boost", "turtle_master": "resistance"}.get(base, base)
        active = eff in effects
        w["seen_effect"] = w["seen_effect"] or active
        if not w["seen_effect"]:
            return (1.0, "missed itself", None) if age > 40 else None
        if base == "strength":
            if w["hit"]:
                return 0.0, "", None
            return (1.0, "no hit while strong", None) if not active or age >= w["until"] else None
        if base == "fire_resistance":
            if me.get("inLava") or me.get("onFire"):
                return 0.0, "", None
            return (1.0, "never near fire", None) if not active or age >= w["until"] else None
        return 0.0, "", None

    def _pearl(self, w, obs, me, age, types):
        x, y, z = float(me.get("x", 0)), float(me.get("y", 0)), float(me.get("z", 0))
        if w["landed"] is None:
            if math.dist((x, z), (w["x"], w["z"])) > 3 or abs(y - w["y"]) > 3:
                w["landed"] = self.tick
                w.update(lx=x, ly=y, lz=z, near_at_land=min((_dist(e) for e in _enemies(obs)), default=None))
            elif age >= w["until"]:
                return 1.0, "went nowhere", None
            return None
        if me.get("inLava") or y < -64:
            return 1.0, "landed in danger", None
        since = self.tick - w["landed"]
        n0, n1 = w["near"], w["near_at_land"]
        if n0 is not None and n1 is not None and n0 - n1 >= 3 and w["hit"]:
            return 0.0, "", None  # Closed in and hit.
        if w["threat"] and n0 is not None and (n1 is None or n1 - n0 >= 4):
            return 0.0, "", None  # Got away.
        if w["ly"] - w["y"] >= 3 or math.dist((w["lx"], w["lz"]), (w["x"], w["z"])) >= 8:
            if since > 20 and not me.get("inLava"):
                return 0.0, "", None  # Got somewhere: up, or across.
        return (1.0, "gained nothing", None) if since > 80 else None

    def _wind(self, w, obs, me, age):
        x, y, z = float(me.get("x", 0)), float(me.get("y", 0)), float(me.get("z", 0))
        w["top"] = max(w["top"], y)
        w["far"] = max(w["far"], math.dist((x, z), (w["x"], w["z"])))
        if w["hit"]:
            return 0.0, "", None
        for e in _enemies(obs):
            if _dist(e) < 5 and math.hypot(float(e.get("vx", 0)), float(e.get("vz", 0))) > 0.6:
                return 0.0, "", None  # Knocked an enemy back.
        if age < w["until"]:
            return None
        near_now = min((_dist(e) for e in _enemies(obs)), default=None)
        if w["threat"] and w["near"] is not None and (near_now is None or near_now - w["near"] >= 4):
            return 0.0, "", None  # Got away.
        toward = w["near"] is not None and near_now is not None and w["near"] - near_now >= 3
        if (w["top"] - w["y"] >= 3 or w["far"] >= 6) and (toward or w["top"] - w["y"] >= 3):
            return 0.0, "", None  # Real height or distance toward something.
        return 1.0, "gained nothing", None
