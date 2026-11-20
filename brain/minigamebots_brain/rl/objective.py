"""Objectives: rules that turn game events into rewards (see docs/formats/objective.md).

A rule:
    {"event": "kill", "where": {"target": "minecraft:pig"}, "value": 5, "per": "event",
     "cap": 0, "cooldown": 0, "tier": 2,
     "forgive": {"by": ["kill", {"event": "tg_episode_end", "where": {"result": "win"}}], "within": 20},
     "budget": {"limit": 10}}

- event:    a game event type (from the mod, e.g. kill, damage_taken, tg_episode_end), or
            "second", which happens once a second with the player's state (health, food, ...).
- where:    conditions on the event's fields: a value to match, or {"min", "max", "contains"}.
- value:    reward per event, or per unit of the field named in "per" (e.g. per "amount").
- cap:      most this rule can add up to in one episode, as a size (0 = no cap).
- cooldown: seconds before the rule can count again.
- tier:     1 (most important) to 4. While a more important rule is in play (fired in the last
            20 s), penalties from less important tiers are scaled down (x0.25 per tier).
- forgive:  for penalties: held back, and cancelled if one of "by" happens within "within"
            seconds; otherwise applied at the moment it happened (the trainer rewrites that step).
- safe:     {"seconds": N}: a reward is held back and paid only if it isn't hurt or killed in the
            next N seconds (paid at the moment it was earned; cancelled otherwise).
- beyond:   true: only progress past its best this round counts (new depth for "going down", new
            height for "going up", further from the start for "moving"); rules without a measure
            count only the first time in a round.
- min_use:  for "item wasted": healing counts as wasted only if less than this share was used (0..1).
- budget:   for costs: free while the episode total stays under "limit"; above it, the cost
            counts with a weight that grows each episode the budget is broken, and shrinks
            again while it's kept. "value" is then the cost per unit.
"""

from dataclasses import dataclass, field

TICKS_PER_SECOND = 20
TIER_MEMORY_TICKS = 20 * TICKS_PER_SECOND
TIER_SCALE = 0.25
SECOND = "second"


def matches(where: dict | None, data: dict) -> bool:
    for key, cond in (where or {}).items():
        v = data.get(key)
        if isinstance(cond, dict):
            if v is None:
                return False
            if "contains" in cond and str(cond["contains"]).lower() not in str(v).lower():
                return False
            try:
                if "min" in cond and float(v) < float(cond["min"]):
                    return False
                if "max" in cond and float(v) > float(cond["max"]):
                    return False
            except (TypeError, ValueError):
                return False
        elif isinstance(cond, bool) or isinstance(v, bool):
            if bool(v) != bool(cond):
                return False
        elif isinstance(cond, (int, float)) and isinstance(v, (int, float)):
            if float(v) != float(cond):
                return False
        elif str(v) != str(cond):
            return False
    return True


def _trigger_matches(trigger, event: dict) -> bool:
    if isinstance(trigger, str):
        return event.get("type") == trigger
    return event.get("type") == trigger.get("event") and matches(trigger.get("where"), event.get("data") or {})


@dataclass
class _Escrow:
    tick: int
    amount: float
    rule: int
    expires: int
    by: list


@dataclass
@dataclass
class _Held:
    tick: int
    amount: float
    rule: int
    pays: int


# What "beyond its best" measures, per rule event (bigger = further).
BEYOND = {
    "b:going_down": lambda o: -float((o.get("self") or {}).get("y", 0)),
    "b:going_up": lambda o: float((o.get("self") or {}).get("y", 0)),
    "b:moving": None,  # Distance from where the round started (set in step).
    "b:new_area": None,
}
HURT = {"damage_taken", "death", "tg_damage_taken"}


@dataclass
class StepResult:
    reward: float = 0.0
    # (original tick, amount): penalties released from escrow (and rewards held until safe), to add
    # at the step they belong to.
    delayed: list = field(default_factory=list)
    parts: dict = field(default_factory=dict)


class RewardEngine:
    def __init__(self, objective: dict):
        self.objective = objective
        self.rules = [dict(r) for r in objective.get("rules", [])]
        for r in self.rules:
            r.setdefault("value", 0.0)
            r.setdefault("tier", 2)
            r.setdefault("per", "event")
        self.budget_weight = [1.0 for _ in self.rules]
        self.reset_episode()

    def reset_episode(self) -> None:
        self.totals = [0.0 for _ in self.rules]
        self.costs = [0.0 for _ in self.rules]
        self.last_fired = [-10**9 for _ in self.rules]
        self.escrow: list[_Escrow] = []
        self.held: list[_Held] = []
        self.best: dict = {}
        self.start = None
        self.cancelled: list = []  # (rule, amount) held rewards lost this step (for the popups).
        self.paid: list = []
        self.new_held: list = []
        self.episode_return = 0.0

    def _active_tier(self, tick: int) -> int:
        tiers = [r["tier"] for i, r in enumerate(self.rules) if tick - self.last_fired[i] <= TIER_MEMORY_TICKS]
        return min(tiers) if tiers else 4

    def step(self, tick: int, events: list, obs: dict | None = None) -> StepResult:
        out = StepResult()
        events = list(events)
        if tick % TICKS_PER_SECOND == 0:
            events.append({"type": SECOND, "data": dict((obs or {}).get("self") or {})})

        # Successes first, so a penalty and its forgiveness in the same tick cancel out.
        for ev in events:
            for e in list(self.escrow):
                if any(_trigger_matches(b, ev) for b in e.by):
                    self.escrow.remove(e)
        for e in list(self.escrow):
            if tick >= e.expires:
                self.escrow.remove(e)
                out.delayed.append((e.tick, e.amount))
                self._count(e.rule, e.amount, out)
        # Held rewards: lost if it got hurt, paid once it stayed safe long enough.
        self.cancelled = []
        self.paid = []
        self.new_held = []
        if any(ev.get("type") in HURT for ev in events):
            self.cancelled = [(h.rule, h.amount) for h in self.held]
            self.held = []
        for h in list(self.held):
            if tick >= h.pays:
                self.held.remove(h)
                self.paid.append((h.rule, h.amount))
                out.delayed.append((h.tick, h.amount))
                self._count(h.rule, h.amount, out)
        me = (obs or {}).get("self") or {}
        if self.start is None and "x" in me:
            self.start = (float(me.get("x", 0)), float(me.get("z", 0)))

        for ev in events:
            data = ev.get("data") or {}
            for i, r in enumerate(self.rules):
                if r.get("event") != ev.get("type") or not matches(r.get("where"), data):
                    continue
                if "min_use" in r and data.get("used") is not None and float(data["used"]) >= float(r["min_use"]):
                    continue  # Healing used well enough (the rule's own threshold).
                cooldown = int(float(r.get("cooldown", 0)) * TICKS_PER_SECOND)
                if cooldown and tick - self.last_fired[i] < cooldown:
                    continue
                units = 1.0
                if r["per"] != "event":
                    try:
                        units = abs(float(data.get(r["per"], 0.0)))
                    except (TypeError, ValueError):
                        units = 0.0
                if r.get("beyond"):
                    units = self._beyond(i, r, obs, units)
                    if units <= 0:
                        continue
                amount = float(r["value"]) * units
                self.last_fired[i] = tick
                if "budget" in r:
                    amount = self._budget(i, r, units)
                if amount == 0:
                    continue
                cap = float(r.get("cap", 0) or 0)
                if cap > 0:
                    room = cap - abs(self.totals[i])
                    if room <= 0:
                        continue
                    amount = max(-room, min(room, amount))
                if amount < 0:
                    active = self._active_tier(tick)
                    if r["tier"] > active:
                        amount *= TIER_SCALE ** (r["tier"] - active)
                forgive = r.get("forgive")
                if amount < 0 and forgive and forgive.get("by"):
                    self.totals[i] += amount  # Counts toward the cap even while held.
                    self.escrow.append(_Escrow(tick, amount, i, tick + int(float(forgive.get("within", 20)) * TICKS_PER_SECOND),
                                               list(forgive["by"])))
                    continue
                safe = r.get("safe")
                if amount > 0 and safe:
                    self.totals[i] += amount
                    secs = float(safe.get("seconds", 5) if isinstance(safe, dict) else 5)
                    self.held.append(_Held(tick, amount, i, tick + int(secs * TICKS_PER_SECOND)))
                    self.new_held.append((i, amount))
                    continue
                self.totals[i] += amount
                self._count(i, amount, out, now=True)
        return out

    def _beyond(self, i: int, r: dict, obs: dict | None, units: float) -> float:
        """Only the part past its best this round (or the first time, for rules without a measure)."""
        ev = r.get("event")
        me = (obs or {}).get("self") or {}
        if ev in BEYOND and obs is not None and me:
            if BEYOND[ev] is not None:
                m = BEYOND[ev](obs)
            else:
                sx, sz = self.start or (float(me.get("x", 0)), float(me.get("z", 0)))
                m = ((float(me.get("x", 0)) - sx) ** 2 + (float(me.get("z", 0)) - sz) ** 2) ** 0.5
            best = self.best.get(i)
            if best is None:
                # The first measure is where it starts: progress is counted from there.
                self.best[i] = m - (units if r.get("per", "event") != "event" else 0.0)
                best = self.best[i]
            if m <= best + 1e-6:
                return 0.0
            gain = m - best
            self.best[i] = m
            return gain if r.get("per", "event") != "event" else 1.0
        if self.best.get(i):
            return 0.0
        self.best[i] = 1
        return units

    def _budget(self, i: int, r: dict, units: float) -> float:
        limit = float(r["budget"].get("limit", 0))
        before = self.costs[i]
        self.costs[i] += units
        over = max(0.0, self.costs[i] - max(limit, before))
        return -abs(float(r["value"])) * over * self.budget_weight[i]

    def _count(self, rule: int, amount: float, out: StepResult, now: bool = False) -> None:
        if now:
            out.reward += amount
        name = self.rules[rule].get("note") or self.rules[rule].get("event", "?")
        out.parts[name] = out.parts.get(name, 0.0) + amount
        self.episode_return += amount

    def end_episode(self) -> list:
        """Applies every penalty still held (nothing can forgive it now), adjusts budget weights,
        and starts a new episode. Returns the (tick, amount) penalties released."""
        released = [(e.tick, e.amount) for e in self.escrow] + [(h.tick, h.amount) for h in self.held]
        self.episode_return += sum(a for _, a in released)
        for i, r in enumerate(self.rules):
            if "budget" in r:
                limit = float(r["budget"].get("limit", 0))
                if self.costs[i] > limit:
                    self.budget_weight[i] = min(self.budget_weight[i] * 1.5, 20.0)
                else:
                    self.budget_weight[i] = max(self.budget_weight[i] * 0.9, 0.1)
        finished = self.episode_return
        self.reset_episode()
        self.last_episode_return = finished
        return released


AVOIDANCE_EVENTS = {"damage_taken", "tg_damage_taken", "hit_by"}
WIN_EVENTS = {"kill", "tg_kill", "tg_episode_end", "damage_dealt", "tg_damage_dealt", "tg_dummy_hit"}


def analyze(objective: dict) -> list:
    """Plain-language warnings about Objectives that tend to teach the wrong thing."""
    rules = objective.get("rules", [])
    warnings = []
    positive = [r for r in rules if float(r.get("value", 0)) > 0 and "budget" not in r]
    if not rules:
        return ["This Objective has no rules, so nothing will be learned."]
    if not positive:
        warnings.append("Nothing is rewarded, only punished: it will learn that doing nothing is safest.")
    harsh = [r for r in rules if r.get("event") in AVOIDANCE_EVENTS and float(r.get("value", 0)) < 0
             and not r.get("forgive") and "budget" not in r]
    rewards_winning = any(r.get("event") in WIN_EVENTS and float(r.get("value", 0)) > 0 for r in rules)
    if harsh and not rewards_winning:
        warnings.append("Taking damage is punished but winning or hitting back isn't rewarded: "
                        "this will likely teach it to avoid fights.")
    elif harsh:
        warnings.append("Damage penalties can't be forgiven: consider 'forgive by kill/win' so it still "
                        "takes hits when that's how it wins.")
    for r in rules:
        if float(r.get("value", 0)) > 0 and r.get("event") == SECOND and not r.get("cap"):
            warnings.append("A reward every second without a cap rewards stalling; add a cap or a time limit.")
            break
    return warnings
