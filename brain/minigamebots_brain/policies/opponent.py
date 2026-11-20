"""Reading opponents: their fighting style, live, and remembered per player.

During a fight it keeps a picture of the opponent, updated every tick:
  range       the distance they fight at (blocks)
  aggression  how fast they close in (+) or back off (-), blocks/tick
  strafe      which way they move across my view: + to my right, - to my left
  block       how often they block right after I hit them (0..1)
  crit        how often they're in the air when they hit me (jump crits, 0..1)
  low_retreat whether they back off when low on health (+) or keep pushing (-)

Each trait has a slow estimate (what it believes about them, starting from what it remembers about
this player, or a neutral guess for strangers) and a fast one (the last few seconds). When the two
clearly disagree they've changed how they fight: it trusts the fast one at once, like a good
player who notices "they stopped rushing". That's how it reads someone within a few exchanges
instead of over many fights.

Counters are small nudges on its own choices (key logits): circle against their strafe, keep its
distance from rushers and close in on kiters, don't swing into a block, step back from a jump crit,
chase when they run low. It also throws in short spells of the other strafe now and then, so
it's harder to read.

Styles are saved per player in <profile>/styles.json (the next fight starts prepared) and shown on
the Memory screen. A rough imitation of how top players adapt, with statistics and rules, not
their judgement.
"""

import json
import math
import os
import random
import time

import numpy as np

from .fastweights import who_key

TRAITS = {"range": 3.0, "aggression": 0.0, "strafe": 0.0, "block": 0.2, "crit": 0.2, "low_retreat": 0.0}
SPREAD = {"range": 0.8, "aggression": 0.08, "strafe": 0.08, "block": 0.25, "crit": 0.25, "low_retreat": 0.08}
FAST = {"range": 0.08, "aggression": 0.08, "strafe": 0.08, "block": 0.45, "crit": 0.45, "low_retreat": 0.08}
CLOSE = 6.0  # Only fights at this distance or closer count.
MAX_PRIOR = 15  # What it remembers counts like this many fresh observations at most.


class Trait:
    def __init__(self, name, prior=None, n=0):
        self.name = name
        self.slow = TRAITS[name] if prior is None else prior
        self.fast = self.slow
        self.n = min(n, MAX_PRIOR)
        self.fast_n = 0
        self.switches = 0

    def add(self, x):
        self.n += 1
        self.fast_n += 1
        self.slow += (x - self.slow) / min(self.n + 1, 60)
        self.fast += FAST[self.name] * (x - self.fast)
        # A clear change of style: believe the last few seconds.
        if self.fast_n >= 8 and abs(self.fast - self.slow) > SPREAD[self.name]:
            self.slow = self.fast
            self.n = min(self.n, 8)
            self.switches += 1

    def value(self):
        return self.slow

    def confidence(self):
        return min(1.0, self.n / 20.0)


class Reading:
    """One opponent, this fight."""

    def __init__(self, name, saved=None):
        saved = saved or {}
        n = int(saved.get("n", 0))
        self.name = name
        self.traits = {t: Trait(t, saved.get(t), n) for t in TRAITS}
        self.fights = int(saved.get("fights", 0)) + 1
        self.prev = None
        self.watch_block = None

    def summary(self):
        v = {t: tr.value() for t, tr in self.traits.items()}
        words = []
        if v["aggression"] > 0.05:
            words.append("rushes")
        elif v["aggression"] < -0.05:
            words.append("keeps away")
        if abs(v["strafe"]) > 0.05:
            words.append("strafes " + ("right" if v["strafe"] > 0 else "left"))
        if v["block"] > 0.45:
            words.append("blocks after hits")
        if v["crit"] > 0.45:
            words.append("jump-crits")
        if v["low_retreat"] > 0.05:
            words.append("runs when low")
        words.append(f"fights at {v['range']:.1f}")
        return ", ".join(words)


class OpponentModel:
    def __init__(self, path: str | None):
        self.path = path
        self.saved: dict = {}
        self.live: dict = {}
        self.mix = 0.0
        self.mix_until = 0
        self.tick = 0
        self.dirty = 0
        if path and os.path.isfile(path):
            try:
                with open(path, encoding="utf-8") as f:
                    self.saved = json.load(f)
            except (OSError, ValueError):
                self.saved = {}

    def reading(self, who):
        if who not in self.live:
            self.live[who] = Reading(who, self.saved.get(who))
        return self.live[who]

    def observe(self, obs: dict, who):
        """Updates the reading of `who` (the opponent of the moment) from this tick."""
        self.tick += 1
        if who is None:
            return None
        e = next((x for x in obs.get("entities") or [] if who_key(x) == who), None)
        if e is None:
            return None
        r = self.reading(who)
        dx, dz = float(e.get("dx", 0)), float(e.get("dz", 0))
        d = math.hypot(dx, dz)
        if d < 1e-3 or d > CLOSE:
            r.prev = e
            return r
        ux, uz = dx / d, dz / d
        vx, vz = float(e.get("vx", 0)), float(e.get("vz", 0))
        closing = -(vx * ux + vz * uz)
        lateral = vx * -uz + vz * ux  # + = moving to my right.
        t = r.traits
        t["range"].add(d)
        t["aggression"].add(closing)
        t["strafe"].add(lateral)
        hp = float(e.get("health", 20) or 20) / max(1.0, float(e.get("maxHealth", 20) or 20))
        if hp < 0.35:
            t["low_retreat"].add(-closing)
        prev = r.prev or {}
        # Just hit by me (its counter restarted): do they block within the next half second?
        hb, phb = e.get("hitByMe", -1), prev.get("hitByMe", -1)
        if 0 <= hb <= 1 and (phb < 0 or phb > hb):
            r.watch_block = self.tick
        if r.watch_block is not None:
            if e.get("blocking"):
                t["block"].add(1.0)
                r.watch_block = None
            elif self.tick - r.watch_block > 10:
                t["block"].add(0.0)
                r.watch_block = None
        # Just hit me: from the air (a crit) or not?
        hm, phm = e.get("hitMe", -1), prev.get("hitMe", -1)
        if 0 <= hm <= 1 and (phm < 0 or phm > hm):
            t["crit"].add(0.0 if e.get("onGround", True) else 1.0)
        r.prev = e
        self.dirty += 1
        if self.dirty >= 400:
            self.save()
        return r

    def bias(self, r: Reading | None, obs: dict, key_index: dict, n_keys: int, n_attacks: int) -> dict:
        """Nudges on its own choices (logits) that counter this opponent's style."""
        keys = np.zeros(n_keys)
        attacks = np.zeros(n_attacks)
        if r is None:
            return {"keys": keys, "attacks": attacks}
        e = next((x for x in obs.get("entities") or [] if who_key(x) == r.name), None) or r.prev or {}
        d = math.hypot(float(e.get("dx", 0)), float(e.get("dz", 0)))
        if d > CLOSE:
            return {"keys": keys, "attacks": attacks}
        v = {t: tr.value() for t, tr in r.traits.items()}
        c = {t: tr.confidence() for t, tr in r.traits.items()}

        def nudge(k, x):
            if k in key_index:
                keys[key_index[k]] += float(np.clip(x, -1.0, 1.0))

        # Circle against their strafe (the line between us turns fast: hard for them to track).
        s = np.tanh(v["strafe"] / 0.08) * c["strafe"]
        nudge("left", 0.8 * s)
        nudge("right", -0.8 * s)
        # Rushers: keep the spacing, let them run onto the hit. Kiters: close in, sprinting.
        a = np.tanh(v["aggression"] / 0.1) * c["aggression"]
        if a > 0 and d < v["range"] + 0.5:
            nudge("back", 0.6 * a)
            nudge("forward", -0.4 * a)
        elif a < 0:
            nudge("forward", 0.6 * -a)
            nudge("sprint", 0.5 * -a)
        # They like to fight from further out than it: get inside their range.
        if v["range"] > 3.2 and d > 2.8:
            nudge("forward", 0.3 * c["range"])
        # Blockers: don't swing into the block right after a hit; wait for it to drop.
        if 0 <= e.get("hitByMe", -1) <= 8 and e.get("blocking"):
            attacks[1:] -= 1.2 * v["block"] * c["block"]
        # Jump-critters in the air coming at it: step back out of the crit.
        if not e.get("onGround", True) and d < 3.5:
            nudge("back", 0.8 * v["crit"] * c["crit"])
        # They run when low: chase.
        hp = float(e.get("health", 20) or 20) / max(1.0, float(e.get("maxHealth", 20) or 20))
        if hp < 0.35 and v["low_retreat"] > 0:
            nudge("forward", 0.7 * c["low_retreat"])
            nudge("sprint", 0.6 * c["low_retreat"])
        # Hard to read: now and then a short spell of the other strafe.
        if self.tick >= self.mix_until:
            self.mix = random.choice((-1.0, 0.0, 0.0, 1.0)) * 0.5
            self.mix_until = self.tick + random.randint(30, 80)
        nudge("left", self.mix)
        nudge("right", -self.mix)
        return {"keys": keys, "attacks": attacks}

    def end_fight(self, who):
        r = self.live.pop(who, None)
        if r is not None:
            self._store(r)
            self.save()

    def _store(self, r: Reading):
        entry = {t: round(tr.value(), 4) for t, tr in r.traits.items()}
        entry.update(n=max(tr.n for tr in r.traits.values()), fights=r.fights, at=int(time.time()),
                     switches=sum(tr.switches for tr in r.traits.values()), summary=r.summary())
        self.saved[r.name] = entry

    def save(self):
        self.dirty = 0
        for r in self.live.values():
            self._store(r)
        if not self.path:
            return
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.saved, f, indent=1)
            os.replace(tmp, self.path)
        except OSError:
            pass
