"""The strategist: the slow layer above the Manager that decides the plan for the next 30-60 s.

Plans: gear_up, hunt, heal, ambush, pick_fights, roam, guard. Each plan is a small set of knobs
for the Manager (a goal to work on when it has nothing more specific, and how brave to be in a
fight). Long goals ("manhunt <player>", "kill N players") make hunting plans more attractive.

It decides from a few numbers about the situation (health, gear, who is around and how a fight
with them would go, risk, recent deaths and kills, night, base, the long goal). Rules give every
plan a starting score; a tiny learned model per plan (ridge regression on the same numbers,
updated from how each plan actually went: kills, deaths, damage, goal progress) takes over as it
gathers experience. CPU only, a few hundred numbers in all, saved in <profile>/strategist.json.

It rethinks every 30-60 s, or straight away when something surprising happens (it died, its
health dropped sharply, the manhunt target came into sight, it got a kill).
"""

import json
import math
import os
import random

import numpy as np

PLANS = ("gear_up", "hunt", "heal", "ambush", "pick_fights", "roam", "guard", "set_trap", "build_shelter")
FEATURES = ("bias", "health", "food", "armor", "weapon", "enemies", "best_odds", "prey", "risk", "deaths",
            "kills", "night", "base", "long_goal", "target_seen", "trap_ready", "shelter_ready")
NF = len(FEATURES)
SAY = {"gear_up": "gearing up", "hunt": "hunting", "heal": "retreating to heal", "ambush": "waiting in ambush",
       "pick_fights": "only taking winnable fights", "roam": "roaming", "guard": "guarding home",
       "set_trap": "setting a trap", "build_shelter": "building a shelter"}
WEAPONS = ("sword", "axe", "mace", "trident", "bow", "crossbow")


def person(e: dict) -> bool:
    """A player, or a player-shaped training bot (they stand in for players in practice)."""
    return bool(e.get("player")) or "mannequin" in str(e.get("type", ""))


def features(obs: dict, long_goal: dict | None, recent: dict, ready: dict | None = None) -> np.ndarray:
    me = obs.get("self") or {}
    inv = obs.get("inventory") or []
    foes = [e for e in obs.get("entities") or [] if person(e) and e.get("visible", True)
            and not e.get("sameTeam") and e.get("relation", 0) <= 0 and not e.get("allyInsignia")]
    odds = [float(e.get("fightOdds", 0.0)) for e in foes]
    target = (long_goal or {}).get("player")
    x = np.zeros(NF, dtype=np.float64)
    x[0] = 1.0
    x[1] = float(me.get("health", 20)) / 20
    x[2] = float(me.get("food", 20)) / 20
    x[3] = min(1.0, float(me.get("armor", 0)) / 20)
    x[4] = float(any(any(w in str(i.get("id", "")) for w in WEAPONS) for i in inv))
    x[5] = min(1.0, len(foes) / 4)
    x[6] = max(odds) if odds else 0.0
    x[7] = float(any(o > 0.15 for o in odds))
    x[8] = min(1.0, float((obs.get("risk") or {}).get("level", 0)))
    x[9] = min(1.0, recent.get("deaths", 0) / 3)
    x[10] = min(1.0, recent.get("kills", 0) / 3)
    t = int(me.get("timeOfDay", 6000)) % 24000
    x[11] = float(bool(me.get("dark")) if "dark" in me else 12500 <= t <= 23500)
    x[12] = float(any(w.get("type") == "base" for w in obs.get("waypoints") or []))
    x[13] = float(long_goal is not None)
    x[14] = float(bool(target) and any(e.get("name") == target for e in foes))
    ready = ready or {}
    x[15] = float(bool(ready.get("trap")))  # It has the items for one of its kept traps (and may use them).
    x[16] = float(bool(ready.get("shelter")))
    return x


def rule_scores(x: np.ndarray) -> dict:
    """Common sense, as a starting point (roughly -1..2)."""
    f = dict(zip(FEATURES, x))
    hurt = 1 - f["health"]
    geared = f["weapon"] * (0.5 + f["armor"])
    return {
        "heal": 2.2 * hurt ** 1.5 + 0.6 * (f["food"] < 0.35) - 0.3,
        "gear_up": 0.9 * (1 - min(1.0, geared)) - 0.5 * f["enemies"] - 0.6 * f["target_seen"] - 0.5 * f["night"] * f["base"],
        "hunt": 0.5 * geared + 0.8 * f["long_goal"] + 1.0 * f["target_seen"] + 0.6 * f["best_odds"] - 0.4 * f["deaths"]
        - 0.8 * hurt,
        "pick_fights": 0.35 + 0.7 * f["prey"] + 0.4 * f["best_odds"] + 0.2 * f["enemies"] - 0.7 * hurt,
        "ambush": 0.15 + 0.4 * f["risk"] + 0.3 * f["enemies"] * (f["best_odds"] < 0) + 0.2 * f["deaths"],
        "roam": 0.4 - 0.2 * f["night"],
        "guard": 0.8 * f["base"] * f["night"] + 0.2 * f["base"] * f["risk"] - 0.2,
        # Its own inventions: a trap where enemies are expected (not mid-fight), a shelter for the night.
        "set_trap": f["trap_ready"] * (0.3 + 0.4 * f["risk"] + 0.4 * f["long_goal"] + 0.2 * f["deaths"]
                                       - 0.9 * f["enemies"] - 0.8 * hurt) - (1 - f["trap_ready"]) * 5,
        "build_shelter": f["shelter_ready"] * (1.3 * f["night"] * (1 - f["base"]) - 0.6 * f["enemies"] - 0.2)
        - (1 - f["shelter_ready"]) * 5,
    }


class Strategist:
    """Picks a plan; learns which plans pay off in which situations."""

    PERIOD = (600, 1200)  # 30-60 s at 20 ticks/s
    LAMBDA = 1.0

    def __init__(self, profile: str | None, learn: bool = True, seed: int | None = None):
        self.path = os.path.join(profile, "strategist.json") if profile else None
        self.learn = learn
        self.rng = random.Random(seed)
        self.A = np.stack([np.eye(NF) * self.LAMBDA for _ in PLANS])
        self.b = np.zeros((len(PLANS), NF))
        self.n = np.zeros(len(PLANS))
        self.plan = "roam"
        self.why = "starting out"
        self.scores: dict = {}
        self.started = -10 ** 9
        self.next_at = -1
        self.x = None
        self.reward = 0.0
        self.history: list = []
        self.recent = {"deaths": 0, "kills": 0}
        self._events_t: list = []
        self._last_health = 20.0
        self._load()

    # --- persistence ---

    def _load(self) -> None:
        if not self.path or not os.path.isfile(self.path):
            return
        try:
            with open(self.path) as fh:
                d = json.load(fh)
            if d.get("features") == list(FEATURES) and d.get("plans") == list(PLANS):
                self.A = np.array(d["A"], dtype=np.float64)
                self.b = np.array(d["b"], dtype=np.float64)
                self.n = np.array(d["n"], dtype=np.float64)
                self.history = d.get("history", [])[-50:]
        except (OSError, ValueError, KeyError):
            pass  # A damaged file: start fresh.

    def save(self) -> None:
        if not self.path:
            return
        tmp = self.path + ".tmp"
        with open(tmp, "w") as fh:
            json.dump({"features": list(FEATURES), "plans": list(PLANS), "A": self.A.tolist(), "b": self.b.tolist(),
                       "n": self.n.tolist(), "history": self.history[-50:]}, fh)
        os.replace(tmp, self.path)

    # --- learning ---

    def learned(self, x: np.ndarray) -> np.ndarray:
        return np.array([np.linalg.solve(self.A[i], self.b[i]) @ x for i in range(len(PLANS))])

    def _finish(self, tick: int) -> None:
        """The plan that just ended: score how it went and learn from it."""
        if self.x is None:
            return
        seconds = max(1.0, (tick - self.started) / 20)
        value = self.reward / (seconds / 45)  # Per typical plan length, so short and long plans compare.
        i = PLANS.index(self.plan)
        if self.learn:
            self.A[i] += np.outer(self.x, self.x)
            self.b[i] += value * self.x
            self.n[i] += 1
        self.history.append([self.plan, round(value, 3)])
        self.history = self.history[-50:]
        self.x = None

    def on_events(self, tick: int, events: list, me: dict, long_goal: dict | None) -> bool:
        """Adds up how the current plan is going. True = something surprising: rethink now."""
        wake = False
        target = (long_goal or {}).get("player")
        for ev in events:
            kind, data = ev.get("type"), ev.get("data") or {}
            if kind == "kill":
                player = any(w in str(data.get("target", "")) for w in ("player", "mannequin"))
                self.reward += 1.0 if player else 0.1
                if target and data.get("name") == target:
                    self.reward += 3.0
                if player:
                    self._events_t.append((tick, "kills"))
                    wake = True
            elif kind in ("death", "tg_death"):
                self.reward -= 1.5
                self._events_t.append((tick, "deaths"))
                wake = True
            elif kind in ("damage_dealt", "tg_damage_dealt"):
                self.reward += 0.03 * float(data.get("amount", 0) or 0) * (kind == "damage_dealt")
            elif kind == "damage_taken":
                self.reward -= 0.03 * float(data.get("amount", 0) or 0)
            elif kind == "item_gained" and any(w in str(data.get("item", "")) for w in WEAPONS + ("chestplate", "helmet",
                                                                                                  "leggings", "boots")):
                self.reward += 0.3
        self._events_t = [(t, k) for t, k in self._events_t if tick - t < 6000]  # The last 5 minutes.
        self.recent = {"deaths": sum(1 for _, k in self._events_t if k == "deaths"),
                       "kills": sum(1 for _, k in self._events_t if k == "kills")}
        health = float(me.get("health", 20))
        if health < 8 <= self._last_health and self.plan != "heal":
            wake = True  # Badly hurt: rethink.
        self._last_health = health
        return wake

    # --- deciding ---

    def decide(self, tick: int, obs: dict, long_goal: dict | None, why_now: str = "", ready: dict | None = None) -> str:
        self._finish(tick)
        x = features(obs, long_goal, self.recent, ready)
        prior = rule_scores(x)
        learned = self.learned(x)
        scores = {}
        for i, p in enumerate(PLANS):
            trust = self.n[i] / (self.n[i] + 25)  # The learned part takes over with experience.
            scores[p] = (1 - trust) * prior[p] + trust * learned[i]
        if not (x[12] > 0):
            scores["guard"] -= 5  # Nothing to guard.
        for p, i in (("set_trap", 15), ("build_shelter", 16)):
            if not x[i]:
                scores[p] = -9  # Can't (no items, or not allowed): never chosen, not even to try it out.
        if self.learn and self.history and self.rng.random() < 0.1:
            # Now and then, try something else, to find out (only while learning).
            options = [p for p in PLANS if scores[p] > -2]
            plan = self.rng.choice(options)
            reason = "trying it out"
        else:
            plan = max(scores, key=scores.get)
            reason = why_now or self._reason(plan, x)
        self.plan, self.why, self.scores = plan, reason, {p: round(float(v), 2) for p, v in scores.items()}
        self.x, self.reward, self.started = x, 0.0, tick
        self.next_at = tick + self.rng.randint(*self.PERIOD)
        return plan

    @staticmethod
    def _reason(plan: str, x: np.ndarray) -> str:
        f = dict(zip(FEATURES, x))
        if plan == "heal":
            return f"health {f['health'] * 20:.0f}"
        if plan == "hunt" and f["target_seen"]:
            return "target in sight"
        if plan == "hunt" and f["long_goal"]:
            return "the long goal"
        if plan == "pick_fights" and f["prey"]:
            return "someone it can beat is near"
        if plan == "gear_up":
            return "not geared yet"
        if plan == "ambush":
            return "dangerous here: let them come"
        if plan == "guard":
            return "night, close to home"
        if plan == "set_trap":
            return "enemies expected: one of its traps first"
        if plan == "build_shelter":
            return "night and no home: one of its shelters"
        return "nothing pressing"

    def due(self, tick: int) -> bool:
        return tick >= self.next_at

    # --- what the plan means for the Manager ---

    def courage(self) -> float:
        """Added to the fear it takes to run: + braver, - more careful."""
        return {"hunt": 0.35, "pick_fights": -0.1, "heal": -0.6, "ambush": 0.1, "gear_up": -0.25, "guard": 0.2}.get(
            self.plan, 0.0)

    def min_odds(self) -> float:
        """Only start fights at least this good (who hits it first still gets fought or fled)."""
        return {"pick_fights": 0.1, "heal": 0.5, "gear_up": 0.25, "hunt": -0.2, "ambush": 0.0}.get(self.plan, 0.0)

    def goal(self, long_goal: dict | None) -> dict | None:
        target = (long_goal or {}).get("player")
        why = "plan: " + SAY[self.plan]
        if self.plan in ("set_trap", "build_shelter"):
            return {"type": "invent_build", "kind": "trap" if self.plan == "set_trap" else "shelter", "minutes": 4, "why": why}
        if self.plan == "heal":
            return {"type": "go_to", "waypoint": "base", "why": why} if self._has_base else {"type": "ambush", "minutes": 1,
                                                                                           "why": why + " (keeping still)"}
        if self.plan == "gear_up":
            return {"type": "mine", "item": "iron", "count": 6, "why": why}
        if self.plan in ("hunt", "pick_fights"):
            spec = {"type": "hunt_players", "minutes": 1, "why": why}
            if target:
                spec["player"] = target
            return spec
        if self.plan == "ambush":
            return {"type": "ambush", "minutes": 1, "why": why}
        if self.plan == "guard":
            return {"type": "go_to", "waypoint": "base", "why": why}
        return {"type": "explore", "minutes": 1, "why": why}

    @property
    def _has_base(self) -> bool:
        return bool(self.x is not None and self.x[12] > 0)

    def status(self) -> dict:
        return {"plan": self.plan, "why": self.why, "scores": self.scores,
                "experience": int(self.n.sum()), "recent": self.history[-5:]}
