"""Fast weights: quick adapting to one opponent, during the fight itself.

The trained network stays as it is (slow weights); on top of it, each opponent gets a small set of
"fast" adjustments to its decisions (which keys to press, how many attack and use clicks). They are
learned in the moment from what just happened: damage dealt makes the choices of the last second
or two more likely against this opponent, damage taken makes them less likely, a knockout or a
death counts more (a policy-gradient step on the logits, with a fading trace). They are kept per
opponent (so the next fight with Steve starts from what worked on Steve), clipped so they can only
nudge, and fade slowly between fights. Saved in <skill>/fast_weights.json. CPU only, a few numbers.
"""

import json
import math
import os
import re

import numpy as np

LR = 0.08
TRACE = 0.85  # Per tick: how quickly older choices stop getting credit.
WINDOW = 30  # Ticks of choices remembered for credit.
CLIP = 2.0
FADE_PER_DAY = 0.5  # Between fights: halves in a day.


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


def _softmax(x):
    e = np.exp(x - x.max())
    return e / e.sum()


# Health after a name: always set apart by a space or a heart (usernames have neither), so
# "Player123" stays itself while "Steve 20 ❤" and "Dummy ❤ 18.5" lose the number.
_HEALTH = re.compile(r"(\s+[❤♥]?\s*\d+(\.\d+)?\s*([❤♥]|hp|HP)?|\s*[❤♥]\s*\d+(\.\d+)?\s*([❤♥]|hp|HP)?|\s*[❤♥])\s*$")


def who_key(e: dict) -> str:
    """A stable name for one opponent: no colour codes, no health shown after the name (dummies
    and many servers add "❤ 18.5" to nametags, which would make every hit a new opponent)."""
    name = re.sub(r"§.", "", str(e.get("name") or e.get("type") or ""))
    prev = None
    while prev != name:
        prev = name
        name = _HEALTH.sub("", name).strip()
    return name or str(e.get("type") or "")


class FastWeights:
    def __init__(self, path: str | None):
        self.path = path
        self.table: dict = {}  # opponent -> {"keys": [...], "attacks": [...], "uses": [...], "at": time}
        self.trace: list = []  # recent (opponent, keys_on, key_probs, attack, attack_probs, use, use_probs)
        self.dirty = 0
        if path and os.path.isfile(path):
            try:
                with open(path) as fh:
                    self.table = json.load(fh)
            except (OSError, ValueError):
                self.table = {}

    @staticmethod
    def opponent(obs: dict):
        """Who it's fighting now: the player (or player-shaped bot) in the most recent exchange of hits."""
        best, best_t = None, 10 ** 9
        for e in obs.get("entities") or []:
            if not (e.get("player") or "mannequin" in str(e.get("type", ""))) or e.get("sameTeam") or e.get("relation", 0) > 0:
                continue
            t = min(x for x in (e.get("hitMe", -1), e.get("hitByMe", -1), 10 ** 9) if x >= 0) if (
                e.get("hitMe", -1) >= 0 or e.get("hitByMe", -1) >= 0) else 10 ** 9
            if t < 100 and t < best_t:
                best, best_t = who_key(e), t
        return best

    def bias(self, who, sizes: dict) -> dict:
        if who is None:
            return {k: np.zeros(n) for k, n in sizes.items()}
        w = self.table.get(who)
        if w is None:
            w = self.table[who] = {k: [0.0] * n for k, n in sizes.items()}
            w["at"] = 0
        # Faded since last time (only between fights: within a fight "at" is recent).
        import time

        now = time.time()
        if w.get("at") and now - w["at"] > 600:
            f = FADE_PER_DAY ** ((now - w["at"]) / 86400)
            for k in sizes:
                w[k] = [v * f for v in w[k]]
        w["at"] = now
        out = {}
        for k, n in sizes.items():
            v = np.asarray(w.get(k) or [0.0] * n, dtype=np.float64)
            out[k] = v if len(v) == n else np.zeros(n)
        return out

    def remember(self, who, keys_on, key_probs, attack, attack_probs, use, use_probs):
        if who is None:
            return
        self.trace.append((who, np.asarray(keys_on, dtype=np.float64), np.asarray(key_probs, dtype=np.float64), int(attack),
                           np.asarray(attack_probs, dtype=np.float64), int(use), np.asarray(use_probs, dtype=np.float64)))
        self.trace = self.trace[-WINDOW:]

    @staticmethod
    def reward(events) -> float:
        r = 0.0
        for ev in events or []:
            kind, data = ev.get("type"), ev.get("data") or {}
            if kind == "damage_dealt":
                r += float(data.get("amount", 0) or 0) / 20
            elif kind == "damage_taken":
                r -= float(data.get("amount", 0) or 0) / 20
            elif kind == "kill":
                r += 1.0
            elif kind in ("death", "tg_death"):
                r -= 1.0
        return r

    def learn(self, events) -> None:
        r = self.reward(events)
        if r == 0 or not self.trace:
            return
        n = len(self.trace)
        for i, (who, keys_on, kp, a, ap, u, up) in enumerate(self.trace):
            w = self.table.get(who)
            if w is None:
                continue
            credit = LR * r * TRACE ** (n - 1 - i)
            # Gradient of the log-probability of what was chosen, with respect to the logits.
            w["keys"] = list(np.clip(np.asarray(w["keys"]) + credit * (keys_on - kp), -CLIP, CLIP))
            onehot = np.zeros(len(ap))
            onehot[a] = 1
            w["attacks"] = list(np.clip(np.asarray(w["attacks"]) + credit * (onehot - ap), -CLIP, CLIP))
            onehot = np.zeros(len(up))
            onehot[u] = 1
            w["uses"] = list(np.clip(np.asarray(w["uses"]) + credit * (onehot - up), -CLIP, CLIP))
        self.dirty += 1

    def save(self) -> None:
        if not self.path or not self.dirty:
            return
        self.dirty = 0
        # Keep the 200 most recent opponents.
        if len(self.table) > 200:
            keep = sorted(self.table.items(), key=lambda kv: -kv[1].get("at", 0))[:200]
            self.table = dict(keep)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as fh:
            json.dump({k: {kk: (list(map(float, vv)) if isinstance(vv, (list, tuple)) else vv) for kk, vv in v.items()}
                       for k, v in self.table.items()}, fh)
        os.replace(tmp, self.path)

    def strength(self, who) -> float:
        w = self.table.get(who) if who else None
        if not w:
            return 0.0
        return float(math.sqrt(sum(v * v for k in ("keys", "attacks", "uses") for v in w.get(k, []))))
