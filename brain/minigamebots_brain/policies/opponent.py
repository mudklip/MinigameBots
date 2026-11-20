"""Reading opponents: their fighting style, live, and remembered per player, with an eye for bluffs.

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
player who notices "they stopped rushing".

Counters are small nudges on its own choices (key logits): circle against their strafe, keep its
distance from rushers and close in on kiters, don't swing into a block, step back from a jump crit,
chase when they run low. It throws in short spells of the other strafe now and then, so it's
harder to read.

Not falling for bluffs:
  - Counters that don't work are dropped: each kind of counter is scored by how exchanges go
    (my hits minus theirs) while it's in use, per player. A "pattern" that keeps getting it hit when
    it counters it is bait, and it stops reacting to it.
  - Baiters: if their breaks from habit come right when they land hits (more than their usual
    randomness), they bait on purpose; their habits count for less when it matters (close range,
    anyone low).
  - Hedging: counters come and go in random spells, so even someone who knows it counters can't
    time it.
  - Players whose style keeps changing between fights get a lighter first impression.
  - Its own judgement: it keeps its own idea of their health (the health shown, or the damage it
    dealt if the server hides it) and checks whether how they act fits. Healthy but running like
    they're low: a fake retreat, so it doesn't chase into it. Low but rushing like they're fine: a
    bluff, so it doesn't back off; it presses. Each caught bluff counts against them.

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
COUNTERS = ("strafe", "spacing", "close_in", "hold_block", "crit_back", "chase")
CLOSE = 6.0  # Only fights at this distance or closer count.
MAX_PRIOR = 15  # What it remembers counts like this many fresh observations at most.
CREDIT = 20  # Ticks after a counter during which hits count for or against it.
RUN, PUSH = 0.15, 0.15  # Closing speeds (blocks/tick) that count as running away / rushing in.


class Trait:
    def __init__(self, name, prior=None, n=0):
        self.name = name
        self.slow = TRAITS[name] if prior is None else prior
        self.fast = self.slow
        self.n = int(n)
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
        self.name = name
        # Consistent players: what it remembers counts fully. Changeable ones: a lighter first impression.
        self.consistency = float(saved.get("consistency", 1.0))
        n = min(int(saved.get("n", 0)), round(MAX_PRIOR * max(0.2, self.consistency)))
        self.traits = {t: Trait(t, saved.get(t), n) for t in TRAITS}
        self.start = {t: saved.get(t) for t in TRAITS}  # What it believed at the start (consistency check).
        self.fights = int(saved.get("fights", 0)) + 1
        self.base = float(saved.get("base", 0.0))
        # The combined adaptation layer: how much to trust each kind of nudge against this player
        # (fast weights, counters), scored like the counters, relative to how exchanges usually go.
        self.trust = {k: float((saved.get("trust") or {}).get(k, 1.0)) for k in ("fast", "counter")}
        self.steering = {}  # kind -> last tick its nudge was strong
        self.works = {c: float((saved.get("works") or {}).get(c, self.base)) for c in COUNTERS}
        self.baiter = float(saved.get("baiter", 0.0))
        self.bluffs = int(saved.get("bluffs", 0))
        self.dev_hit = float(saved.get("dev_hit", 0.3))  # How often they break habit when they hit me...
        self.dev_base = float(saved.get("dev_base", 0.3))  # ...and in general.
        self.prev = None
        self.watch_block = None
        self.used = {}  # counter -> last tick it was in use
        self.dealt = 0.0  # Damage it dealt since they were last seen at full health (its own estimate).
        self.health_shown = False
        self.judged = None  # "fake_retreat" / "bluff_strength" / None, this moment.
        self.judged_at = -100
        self.acting = None
        self.acting_run = 0

    def believed_hp(self, e) -> float:
        """Its own idea of their health (0..1): what's shown, unless the server hides it (always
        full though it has hit them), then from the damage it dealt."""
        shown = float(e.get("health", 20) or 20) / max(1.0, float(e.get("maxHealth", 20) or 20))
        if not self.health_shown and shown >= 0.999 and self.dealt >= 6:
            return max(0.0, 1.0 - self.dealt / 20.0)
        return shown

    def counter_scale(self, c: str) -> float:
        """How much to trust a counter against this player: ~1 untested, less when exchanges go worse
        than usual with it in use (bait). Relative to how they go anyway: against someone simply
        better, every counter isn't bait."""
        return float(np.clip(1.0 + 1.5 * (self.works[c] - self.base), 0.0, 1.3))

    def vector(self) -> list:
        """The style as network inputs (features.OPP_DIM), roughly in -1..1."""
        v = {t: tr.value() for t, tr in self.traits.items()}
        conf = sum(tr.confidence() for tr in self.traits.values()) / len(self.traits)
        return [v["range"] / 6.0, max(-1.0, min(1.0, v["aggression"] * 5)), max(-1.0, min(1.0, v["strafe"] * 5)),
                v["block"], v["crit"], max(-1.0, min(1.0, v["low_retreat"] * 5)), conf, self.baiter,
                1.0 if self.judged == "fake_retreat" else 0.0, 1.0 if self.judged == "bluff_strength" else 0.0]

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
        if self.baiter > 0.25:
            words.append("baits")
        if self.bluffs:
            words.append(f"bluffed {self.bluffs}x")
        if self.consistency < 0.5:
            words.append("unpredictable")
        words.append(f"fights at {v['range']:.1f}")
        return ", ".join(words)


class OpponentModel:
    def __init__(self, path: str | None):
        self.path = path
        self.saved: dict = {}
        self.live: dict = {}
        self.mix = 0.0
        self.mix_until = 0
        self.hedge = True
        self.hedge_until = 0
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
        prev = r.prev or {}
        # Exchanges: did I just hit them, did they just hit me (their counters restarted)?
        hb, phb = e.get("hitByMe", -1), prev.get("hitByMe", -1)
        hm, phm = e.get("hitMe", -1), prev.get("hitMe", -1)
        i_hit = 0 <= hb <= 1 and (phb < 0 or phb > hb)
        they_hit = 0 <= hm <= 1 and (phm < 0 or phm > hm)
        shown = float(e.get("health", 20) or 20) / max(1.0, float(e.get("maxHealth", 20) or 20))
        if shown < 0.999:
            r.health_shown = True  # The server shows real health: no need to guess it.
        if i_hit:
            r.dealt += 4.0  # A typical melee hit; only used if the server hides their health.
        if hb < 0 or hb > 200:
            r.dealt = 0.0  # A fresh life (no hit on record), or long enough to heal up.
        # Counters get credit (or blame) for exchanges shortly after they were in use.
        if i_hit or they_hit:
            x = (1.0 if i_hit else 0.0) - (1.0 if they_hit else 0.0)
            r.base += 0.05 * (x - r.base)  # How exchanges go with them in general.
            for kind, at in r.steering.items():
                if self.tick - at <= CREDIT:
                    r.trust[kind] = float(np.clip(r.trust[kind] + 0.1 * ((x - r.base) - 0.0), 0.2, 1.5))
            for c, at in r.used.items():
                if self.tick - at <= CREDIT:
                    before = r.counter_scale(c)
                    r.works[c] += 0.15 * (x - r.works[c])
                    if before >= 0.5 > r.counter_scale(c):
                        from .. import feed

                        feed.push("know", f"Counter dropped vs {r.name}: {c.replace('_', ' ')} (looks like bait)")
        if self.tick % 200 == 0 and r.traits["range"].n >= 20:
            s = r.summary()
            if s != getattr(r, "told", None):
                r.told = s
                from .. import feed

                feed.push("know", f"Read {r.name}: {s}")
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
        # Breaking habit right now? (Strafing against their usual side, or doing the opposite of
        # their usual rushing / keeping away.)
        s_hab, a_hab = t["strafe"].value(), t["aggression"].value()
        deviating = (abs(s_hab) > 0.05 and lateral * s_hab < -0.003) or (abs(a_hab) > 0.05 and closing * a_hab < -0.003)
        r.dev_base += 0.01 * ((1.0 if deviating else 0.0) - r.dev_base)
        if they_hit:
            r.dev_hit += 0.2 * ((1.0 if deviating else 0.0) - r.dev_hit)
        r.baiter = float(np.clip((r.dev_hit - r.dev_base) / 0.4, 0.0, 1.0))
        # Its own judgement: does how they act fit how hurt it believes they are?
        # Only against their habit: a kiter backing off or a player who always pushes when low
        # isn't bluffing, that's how they fight.
        hp = r.believed_hp(e)
        acting = None
        # (A habit counts once it's established: 10+ observations.)
        known_runner = t["aggression"].n >= 10 and a_hab <= -0.05
        known_pusher = t["low_retreat"].n >= 10 and t["low_retreat"].value() <= -0.05
        # Flying back from its hit (knockback), or stepping back after landing one (spacing, S-tap):
        # techniques, not running.
        knocked = 0 <= hb < 12 or 0 <= hm < 12
        if hp >= 0.6 and closing < -RUN and t["aggression"].n >= 10 and not known_runner and not knocked:
            acting = "fake_retreat"  # Running like they're low, but they aren't.
        elif hp < 0.35 and closing > PUSH and not known_pusher:
            acting = "bluff_strength"  # Rushing like they're fine, but they're low.
        r.acting_run = r.acting_run + 1 if acting is not None and acting == r.acting else (1 if acting else 0)
        r.acting = acting
        # A quarter second of it, not one stray step; kept up for 3 seconds it's no bluff but their
        # new way of fighting, and it's learned like any other.
        judged = acting if 5 <= r.acting_run <= 60 else None
        if judged is not None:
            if self.tick - r.judged_at > 60:
                r.bluffs += 1  # A new bluff (not the same one going on).
                from .. import feed

                feed.push("know", f"Bluff caught ({r.name}): " + ("fake retreat" if judged == "fake_retreat" else "acting strong while low"))
            r.judged_at = self.tick
            r.judged = judged
        elif self.tick - r.judged_at > 20:
            r.judged = None
        # The style itself (a judged bluff isn't their real habit: left out).
        t["range"].add(d)
        if (acting is None or r.acting_run > 60) and not knocked:
            t["aggression"].add(closing)
            if hp < 0.35:
                t["low_retreat"].add(-closing)
        t["strafe"].add(lateral)
        # Just hit by me: do they block within the next half second?
        if i_hit:
            r.watch_block = self.tick
        if r.watch_block is not None:
            if e.get("blocking"):
                t["block"].add(1.0)
                r.watch_block = None
            elif self.tick - r.watch_block > 10:
                t["block"].add(0.0)
                r.watch_block = None
        # Just hit me: from the air (a crit) or not?
        if they_hit:
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
        me = obs.get("self") or {}
        my_hp = float(me.get("health", 20) or 20) / 20.0
        hp = r.believed_hp(e)
        # When it matters (close, or anyone low), a baiter's habits count for less.
        stakes = d < 3.0 or my_hp < 0.35 or hp < 0.35
        trust = 1.0 - (0.7 * r.baiter if stakes else 0.0)
        # Hedging: counters on in random spells, so they can't be timed.
        if self.tick >= self.hedge_until:
            self.hedge = random.random() < 0.75
            self.hedge_until = self.tick + random.randint(20, 60)
        on = trust * (1.0 if self.hedge else 0.25)

        def nudge(k, x):
            if k in key_index:
                keys[key_index[k]] += float(np.clip(x, -1.0, 1.0))

        def use(counter, amount):
            r.used[counter] = self.tick
            return amount * r.counter_scale(counter) * on

        # Its own judgement comes first: a caught bluff decides, the habits don't.
        if r.judged == "fake_retreat":
            nudge("forward", -0.6)  # Don't chase into it: hold the ground, let them come back.
            nudge("sprint", -0.5)
        elif r.judged == "bluff_strength":
            nudge("forward", 0.6)  # They're weaker than they act: press.
            nudge("back", -0.6)
        # Circle against their strafe (the line between us turns fast: hard for them to track).
        s = np.tanh(v["strafe"] / 0.08) * c["strafe"]
        if abs(s) > 0.05:
            k = use("strafe", 0.8 * s)
            nudge("left", k)
            nudge("right", -k)
        # Rushers: keep the spacing, let them run onto the hit. Kiters: close in, sprinting.
        a = np.tanh(v["aggression"] / 0.1) * c["aggression"]
        if r.judged is None:
            if a > 0.05 and d < v["range"] + 0.5:
                k = use("spacing", a)
                nudge("back", 0.6 * k)
                nudge("forward", -0.4 * k)
            elif a < -0.05:
                k = use("close_in", -a)
                nudge("forward", 0.6 * k)
                nudge("sprint", 0.5 * k)
        # They like to fight from further out than it: get inside their range.
        if v["range"] > 3.2 and d > 2.8 and r.judged != "fake_retreat":
            nudge("forward", 0.3 * c["range"] * on)
        # Blockers: don't swing into the block right after a hit; wait for it to drop.
        if 0 <= e.get("hitByMe", -1) <= 8 and e.get("blocking"):
            attacks[1:] -= 1.2 * use("hold_block", v["block"] * c["block"])
        # Jump-critters in the air coming at it: step back out of the crit.
        if not e.get("onGround", True) and d < 3.5:
            nudge("back", 0.8 * use("crit_back", v["crit"] * c["crit"]))
        # They really run when low (not a fake: it believes they're low): chase.
        if hp < 0.35 and v["low_retreat"] > 0 and r.judged is None:
            k = use("chase", c["low_retreat"])
            nudge("forward", 0.7 * k)
            nudge("sprint", 0.6 * k)
        # Hard to read: now and then a short spell of the other strafe.
        if self.tick >= self.mix_until:
            self.mix = random.choice((-1.0, 0.0, 0.0, 1.0)) * 0.5
            self.mix_until = self.tick + random.randint(30, 80)
        nudge("left", self.mix)
        nudge("right", -self.mix)
        return {"keys": keys, "attacks": attacks}

    def combine(self, r: Reading | None, fast: dict, counter: dict) -> dict:
        """One adaptation layer: fast weights and counters, each weighed by how much it has helped
        against this player, then capped so together they only ever nudge the network."""
        if r is None:
            out = {k: fast[k] + counter.get(k, 0) for k in fast}
        else:
            for kind, b in (("fast", fast), ("counter", counter)):
                if any(float(np.abs(v).max()) > 0.3 for v in b.values() if np.size(v)):
                    r.steering[kind] = self.tick
            out = {k: r.trust["fast"] * fast[k] + r.trust["counter"] * counter.get(k, 0) for k in fast}
        return {k: np.clip(v, -2.0, 2.0) for k, v in out.items()}

    def end_fight(self, who):
        r = self.live.pop(who, None)
        if r is not None:
            # How much did they fight like it expected (from before this fight)?
            known = [t for t in TRAITS if r.start[t] is not None and r.traits[t].n >= 10]
            if known:
                gap = max(min(1.0, abs(r.traits[t].value() - r.start[t]) / (2 * SPREAD[t])) for t in known)
                r.consistency += 0.3 * ((1.0 - gap) - r.consistency)
            self._store(r)
            self.save()

    def _store(self, r: Reading):
        entry = {t: round(tr.value(), 4) for t, tr in r.traits.items()}
        entry.update(n=max(tr.n for tr in r.traits.values()), fights=r.fights, at=int(time.time()),
                     switches=sum(tr.switches for tr in r.traits.values()), summary=r.summary(),
                     works={k: round(x, 3) for k, x in r.works.items()}, base=round(r.base, 3), baiter=round(r.baiter, 3),
                     trust={k: round(x, 3) for k, x in r.trust.items()},
                     bluffs=r.bluffs, dev_hit=round(r.dev_hit, 3), dev_base=round(r.dev_base, 3),
                     consistency=round(r.consistency, 3))
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
