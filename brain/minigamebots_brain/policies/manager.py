"""The Manager: runs a queue of goals, picks the right skill for each, and lets instincts
interrupt (eat, fight back, flee when losing). Free play picks goals by itself.

A *skill* is one trained network in the AI profile: "general" is <profile>/core, others are
<profile>/skills/<name>/core (trained the same way, from recordings or practice). Missing skills
fall back to "general"; if nothing is trained yet, simple built-in reflexes (walk to a target,
eat, flee) still make it act.

goals: [{"type": "mine", "item": "diamond", "count": 3}, {"type": "go_to", "waypoint": "base"},
        {"type": "hunt", "target": "minecraft:cow", "count": 2}, {"type": "protect", "player": "Alex"},
        {"type": "follow", "player": "Alex"}, {"type": "explore", "minutes": 5},
        {"type": "survive", "minutes": 10}, {"type": "free_play"},
        {"type": "manhunt", "player": "Steve"}, {"type": "kill_players", "count": 10}]

Long goals (manhunt, kill_players) and free play are steered by the strategist (strategy.py),
which picks a plan every 30-60 s and sets what the Manager works on in between.
"""

import math
import os
import random

from ..actions import Action
from .follow import FollowPolicy, wrap_degrees
from .base import Policy

DECIDE_EVERY = 10  # ticks (0.5 s)
SKILLS_FOR = {
    "mine": ("mining", "general"), "hunt": ("hunting", "combat", "general"), "protect": ("combat", "general"),
    "follow": ("travel", "general"), "go_to": ("travel", "general"), "explore": ("explore", "travel", "general"),
    "survive": ("survival", "general"), "fight": ("combat", "general"), "flee": ("flee", "travel", "general"),
    "free_play": ("general",), "hunt_players": ("combat", "general"), "farm": ("farming", "general"),
    "build": ("building", "general"), "stash": ("general",), "ambush": ("general",),
    "manhunt": ("combat", "general"), "kill_players": ("combat", "general"), "invent_build": ("building", "general"),
}
TIMED = ("explore", "survive", "farm", "build", "stash", "hunt_players", "ambush", "invent_build")
LONG = ("manhunt", "kill_players")
FOODS = ("cooked_beef", "cooked_porkchop", "golden_carrot", "cooked_mutton", "cooked_chicken", "cooked_salmon",
         "cooked_cod", "bread", "baked_potato", "carrot", "apple", "cooked_rabbit", "pumpkin_pie", "beef", "porkchop",
         "mutton", "chicken", "potato", "melon_slice", "sweet_berries", "cookie", "golden_apple")
FOOD_ANIMALS = ("minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken")
DEFAULT_LIKES = {"explore": 1.0, "mine": 1.0, "hunt": 0.5}


def skill_dir(profile: str, name: str) -> str:
    return profile if name == "general" else os.path.join(profile, "skills", name)


def trained(profile: str, name: str) -> bool:
    return os.path.isfile(os.path.join(skill_dir(profile, name), "core", "model.json"))


class Goal:
    def __init__(self, spec: dict, tick: int):
        self.spec = dict(spec)
        self.type = spec.get("type", "free_play")
        self.started = tick
        self.progress = 0
        self.done = False

    @property
    def count(self) -> int:
        return int(self.spec.get("count", 1) or 1)

    def label(self) -> str:
        extra = self.spec.get("item") or self.spec.get("target") or self.spec.get("player") or self.spec.get("waypoint") or ""
        extra = str(extra).replace("minecraft:", "")
        need = f" {self.progress}/{self.count}" if self.type in ("mine", "hunt", "kill_players") else ""
        return f"{self.type} {extra}{need}".strip()

    def on_event(self, ev: dict) -> None:
        kind, data = ev.get("type"), ev.get("data") or {}
        if self.type == "mine" and kind == "item_gained" and self.spec.get("item", "") in str(data.get("item", "")):
            self.progress += int(data.get("count", 1))
        elif self.type == "hunt" and kind in ("kill", "tg_kill"):
            target = self.spec.get("target", "")
            if not target or target in str(data.get("target", data.get("victim_type", ""))):
                self.progress += 1
        elif self.type == "hunt_players" and kind == "kill" and "player" in str(data.get("target", "")):
            self.done = True
        elif self.type == "manhunt" and kind == "kill" and data.get("name") == self.spec.get("player"):
            self.done = True
        elif self.type == "kill_players" and kind == "kill" and any(w in str(data.get("target", "")) for w in ("player", "mannequin")):
            self.progress += 1
        elif self.type == "go_to" and kind == "waypoint_reached":
            want = self.spec.get("waypoint", "")
            if want in (data.get("name"), data.get("type")):
                self.done = True
        if self.type in ("mine", "hunt", "kill_players") and self.progress >= self.count:
            self.done = True

    def check_time(self, tick: int) -> None:
        minutes = self.spec.get("minutes")
        minutes = minutes or (2 if self.type in TIMED else None)
        if self.type in TIMED and minutes and tick - self.started >= float(minutes) * 1200:
            self.done = True


class ManagerPolicy(Policy):
    name = "manager"
    CACHE = 3

    def __init__(self, profile: str, goals: list | None = None, free_play: bool = False, temperature: float = 1.0,
                 foundations: str | None = None, likes: dict | None = None, device: str | None = None,
                 mode: str = "neutral", after: str = "auto", strategy: bool | None = None, learn_strategy: bool = True,
                 inventions: str = "shelters", **_):
        from . import drives
        from .strategy import Strategist

        if mode == "personality":
            mode = drives.load(profile).get("from_personality", "neutral")
        self.drives = drives.Drives(mode)
        self.talk = drives.TeamTalk()
        self.profile = profile
        self.foundations = foundations
        self._aim = FollowPolicy()
        self._tdy = None
        self.temperature = temperature
        self.device = device
        self.queue = [Goal(g, 0) for g in (goals or [])]
        self.free_play = bool(free_play) or not self.queue
        # The strategist plans for free play and long goals (or when asked); a plain task list runs as given.
        self.strategy_on = strategy if strategy is not None else (self.free_play or any(g.type in LONG for g in self.queue))
        self.strategist = Strategist(profile, learn=learn_strategy)
        # Its own inventions in play: "off", "shelters" (default) or "all" (traps too: only where allowed).
        self.use_inventions = inventions if inventions in ("off", "shelters", "all") else "shelters"
        self._kept = None
        self._kept_at = -10 ** 9
        self.builder = None
        self.building = None  # (kind, design name)
        self.likes = dict(likes or DEFAULT_LIKES)
        if self.drives.mode != "neutral" or not likes:
            self.likes = dict(self.drives.cfg["likes"])
        self.skills = {}  # name -> policy (most recently used last)
        self.tick = 0
        self.mode = "goal"  # goal | fight | flee | eat
        self.skill = None
        self.why = ""
        self.eating = 0
        self.unstick_until = -1  # Tick until which it makes a "try something else" move (watchdog).
        self.stuck_count = 0
        self.completed = []
        self.sub = None  # Goal picked by free play
        self.rng = random.Random()
        self.after = after if after in drives.AFTER else "auto"
        self.fight_kind = "real"
        self.fight_target = None
        self.target_dist = []  # Recent distances to the one it's fighting (to see them back off).
        self.aftermath = None  # {"name", "x", "z", "until", "mode", "picked": [item ids]}
        self.warned = {}  # id -> tick of last warning
        self._annoyer, self._annoy_stage = None, "calm"
        self.calm_ticks = 0
        self.held = Action()
        self.thinking = 20
        self.available = [n for n in ("general", "combat", "mining", "hunting", "travel", "explore", "survival", "flee")
                          if trained(profile, n)]
        if os.path.isdir(os.path.join(profile, "skills")):
            for n in sorted(os.listdir(os.path.join(profile, "skills"))):
                if n not in self.available and trained(profile, n):
                    self.available.append(n)

    # --- skills ---

    def _skill(self, names) -> tuple:
        for n in names:
            if n in self.available:
                if n not in self.skills:
                    from .learned import create_learned

                    if len(self.skills) >= self.CACHE:
                        old = self.skills.pop(next(iter(self.skills)))
                        try:
                            old.close()  # Keeps what it learned about opponents.
                        except Exception:
                            pass
                    self.skills[n] = create_learned(skill_dir(self.profile, n), self.temperature, self.device,
                                                    self.foundations)
                policy = self.skills.pop(n)
                self.skills[n] = policy  # Most recently used last.
                return n, policy
        return None, None

    # --- deciding ---

    def _on_stuck(self) -> None:
        """Watchdog said no progress for a while: try another way; after 3 tries, give the goal up."""
        self.stuck_count += 1
        self.unstick_until = self.tick + 40
        goal = self.queue[0] if self.queue else self.sub
        if self.stuck_count >= 3 and goal is not None:
            goal.done = True
            goal.spec["why"] = "gave up: stuck"
            self.stuck_count = 0

    def _idle(self) -> Action:
        """No goal: act like a player waiting around: look about, sometimes take a few steps."""
        phase = self.tick % 200
        if phase < 30:
            return Action(yaw=2.0 if (self.tick // 200) % 2 else -2.0, pitch=0.3 if phase < 15 else -0.3)
        if 100 <= phase < 112 and (self.tick // 200) % 3 == 0:
            return Action(keys={"forward"})
        if phase == 150 and (self.tick // 200) % 4 == 1:
            return Action(keys={"sneak"})
        return Action()

    def long_goal(self) -> Goal | None:
        return self.queue[0] if self.queue and self.queue[0].type in LONG and not self.queue[0].done else None

    def current(self) -> Goal | None:
        while self.queue and self.queue[0].done:
            self.completed.append(self.queue.pop(0).label())
        if self.queue and self.queue[0].type not in LONG:
            return self.queue[0]
        if self.free_play or self.long_goal() is not None:
            if self.sub is None or self.sub.done or self.tick - self.sub.started > 2400:
                self.sub = self._plan_goal() if self.strategy_on else self._pick_free_play()
            return self.sub
        return None

    # --- its own inventions ---

    def _inventions(self) -> dict:
        """Its hall of fame (re-read every 5 minutes: inventing may run meanwhile)."""
        if self._kept is None or self.tick - self._kept_at > 6000:
            self._kept_at = self.tick
            self._kept = {}
            try:
                from ..invent import Inventions

                inv = Inventions(self.profile)
                for kind, qs in inv.data.get("hall", {}).items():
                    order = sorted(qs.items(), key=lambda q: q[0] != "overall")  # Best overall first.
                    ids = [i for _, lst in order for i, _ in lst]
                    self._kept[kind] = [inv.data["designs"][i] for i in dict.fromkeys(ids) if i in inv.data["designs"]]
            except (OSError, ValueError, KeyError):
                self._kept = {}
        return self._kept

    def _design_for(self, kind: str, obs: dict):
        """A kept design of this kind it has all the items for, or None."""
        if self.use_inventions == "off" or (kind == "trap" and self.use_inventions != "all"):
            return None
        have = {}
        for it in obs.get("inventory") or []:
            have[it.get("id")] = have.get(it.get("id"), 0) + int(it.get("count", 1))
        for d in self._inventions().get(kind, []):
            need = {}
            for b in d.get("blocks", []):
                block = d["palette"][b[3]].split("[")[0]
                if block == "minecraft:air":
                    continue
                item = {"minecraft:lava": "minecraft:lava_bucket", "minecraft:water": "minecraft:water_bucket"}.get(block, block)
                need[item] = need.get(item, 0) + 1
            if "minecraft:oak_door" in need:
                need["minecraft:oak_door"] = max(1, need["minecraft:oak_door"] // 2)
            if all(have.get(k, 0) >= v for k, v in need.items()):
                return d
        return None

    def _build_invention(self, obs: dict, goal) -> Action:
        from .build import BuildPolicy

        me = obs.get("self") or {}
        if self.builder is None:
            design = self._design_for(goal.spec.get("kind", "shelter"), obs)
            if design is None:
                goal.done = True
                return Action()
            # A few blocks ahead, at its feet's level.
            yaw = math.radians(float(me.get("yaw", 0)))
            dx, dz = -math.sin(yaw), math.cos(yaw)
            ax = math.floor(float(me.get("x", 0)) + dx * 5)
            az = math.floor(float(me.get("z", 0)) + dz * 5)
            self.builder = BuildPolicy(design=design, anchor=[ax, math.floor(float(me.get("y", 0))), az])
            self.building = (goal.spec.get("kind"), design.get("name", ""))
        action = self.builder.act(obs, None)
        self.skill = "building"
        st = self.builder.status()
        if st["finished"]:
            kind = self.building[0] if self.building else ""
            self.builder, self.building = None, None
            goal.done = True
            self.strategist.reward += 0.5 * st["placed"] / max(1, st["total"])
            self.strategist.next_at = self.tick  # Rethink straight away.
            if kind == "shelter":
                action.mark = "base"  # Its new home: guarding home at night now means here.
            elif kind == "trap":
                self.strategist.plan = "ambush"  # Wait by it.
                self.sub = Goal({"type": "ambush", "minutes": 1, "why": "waiting by its trap"}, self.tick)
        return action

    def _plan_goal(self) -> Goal:
        """What the strategist's plan means right now ("roam" is ordinary free play)."""
        if self.strategist.plan == "roam" or (self.strategist.plan == "guard" and self._night()):
            return self._pick_free_play()  # Ordinary free play (it also heads home when it gets dark).
        lg = self.long_goal()
        spec = self.strategist.goal(lg.spec if lg else None)
        return Goal(spec, self.tick)

    def _pick_free_play(self) -> Goal:
        """What it feels like doing: needs first (night -> home, hungry -> food), then what it likes."""
        obs = self._obs
        me = obs.get("self") or {}
        wps = obs.get("waypoints") or []
        has_base = any(w.get("type") == "base" for w in wps)
        if has_base and self._night():
            return Goal({"type": "go_to", "waypoint": "base", "why": "it's getting dark"}, self.tick)
        if float(me.get("food", 20)) <= 12 and not self._food_slot():
            return Goal({"type": "hunt", "target": self.rng.choice(FOOD_ANIMALS), "count": 1, "why": "hungry"}, self.tick)
        own = self.drives.pick_goal(obs, self.rng)
        if own is not None:
            return Goal(own, self.tick)
        kinds = list(self.likes)
        kind = self.rng.choices(kinds, weights=[max(0.0, self.likes[k]) for k in kinds])[0]
        spec = {"type": kind, "why": "felt like it"}
        if kind == "mine":
            spec.update(item=self.rng.choice(["coal", "iron", "copper", "diamond"]), count=4)
        elif kind == "hunt":
            spec.update(target=self.rng.choice(FOOD_ANIMALS), count=1)
        elif kind in TIMED:
            spec.update(minutes=3)
        return Goal(spec, self.tick)

    def _night(self) -> bool:
        me = self._obs.get("self") or {}
        if "dark" in me:
            return bool(me["dark"])
        t = int(me.get("timeOfDay", 6000)) % 24000
        return 12500 <= t <= 23500

    def _food_slot(self) -> int | None:
        for it in (self._obs.get("inventory") or []):
            slot = it.get("slot", -1)
            if 0 <= slot < 9 and it.get("id", "").split(":")[-1] in FOODS:
                return slot
        return None

    def _threat(self):
        """The most pressing opponent: who hit me recently, or a hostile mob close by."""
        best, best_score = None, 0.0
        for e in self._obs.get("entities") or []:
            if not e.get("visible", True) or e.get("relation", 0) > 0 or e.get("sameTeam"):
                continue  # Never a teammate (a stray hit from one is an accident).
            d = math.sqrt(e.get("dx", 0) ** 2 + e.get("dy", 0) ** 2 + e.get("dz", 0) ** 2)
            hit = e.get("hitMe", -1)
            score = 0.0
            joke = e.get("player") and self.drives.judge.joke(e.get("id")) and e.get("relation", 0) >= 0
            a = self.aftermath
            if a and a["mode"] == "ruthless" and e.get("name") == a["name"] and d < 24:
                score = 3.0  # Back for their things: fight them again.
            elif 0 <= hit < self._chase_ticks() and not joke:
                score = 3.0
            elif e.get("hostile") and d < 5:
                score = 2.0
            elif e.get("relation", 0) < 0 and d < 8:
                score = 1.5
            mate = self.talk.helping(self.tick, self._obs)
            if mate is not None and not e.get("sameTeam") and (e.get("hostile") or e.get("relation", 0) < 0 or e.get("player")):
                # A teammate called for help: whoever is near them comes first.
                near = math.hypot(e.get("dx", 0) - mate.get("dx", 0), e.get("dz", 0) - mate.get("dz", 0))
                if near < 5 and d < 24:
                    score = max(score, 2.5)
            if score and score / (1 + d * 0.05) > best_score:
                best, best_score = e, score / (1 + d * 0.05)
        return best

    def _aftermath_events(self, obs: dict) -> None:
        from . import drives

        me = obs.get("self") or {}
        for ev in obs.get("_events") or []:
            t, d = ev.get("type"), ev.get("data") or {}
            if t == "kill" and d.get("name"):
                e = next((x for x in obs.get("entities") or [] if x.get("name") == d["name"]), None)
                x = float(me.get("x", 0)) + (e.get("dx", 0) if e else 0)
                z = float(me.get("z", 0)) + (e.get("dz", 0) if e else 0)
                mode = drives.after_mode(self.after, self.drives.mode, self.fight_kind)
                self.aftermath = {"name": d["name"], "x": x, "z": z, "until": self.tick + 3600, "mode": mode, "picked": []}
            elif t == "item_gained" and self.aftermath and self.tick < self.aftermath["until"] - 3000:
                if self._dist_to_spot(me) < 8:
                    self.aftermath["picked"].append(str(d.get("item", "")))
        if self.aftermath and self.tick > self.aftermath["until"]:
            self.aftermath = None

    def _dist_to_spot(self, me: dict) -> float:
        a = self.aftermath
        return math.hypot(float(me.get("x", 0)) - a["x"], float(me.get("z", 0)) - a["z"]) if a else 1e9

    def _annoyed_by(self):
        """Someone joke-hitting it enough to react to (but not fed up): (entity, stage)."""
        for e in self._obs.get("entities") or []:
            st = self.drives.judge.stage(e.get("id"))
            if st in ("notice", "warn") and e.get("visible", True):
                return e, st
        return None, "calm"

    def _chase_ticks(self) -> int:
        """How long after their last hit it keeps after them: ruthless fighters chase longer."""
        from . import drives

        return 300 if drives.after_mode(self.after, self.drives.mode, self.fight_kind) == "ruthless" else 100

    def _backing_off(self, t: dict) -> bool:
        """The one it's fighting is leaving and hasn't hit it for 5 s."""
        d = math.sqrt(t.get("dx", 0) ** 2 + t.get("dz", 0) ** 2)
        self.target_dist = (self.target_dist + [d])[-30:]
        hit = t.get("hitMe", -1)
        recent = self.target_dist[-4:]
        return (len(recent) >= 4 and recent[-1] > recent[0] + 1.5 and d > 4 and (hit < 0 or hit > 40))

    def _decide(self, goal: Goal | None):
        me = self._obs.get("self") or {}
        threat = self._threat()
        from . import drives

        if threat is not None and threat.get("id") != self.fight_target:
            self.fight_target = threat.get("id")
            self.target_dist = []
            verdict = self.drives.judge.verdict.get(threat.get("id")) or ("", "")
            self.fight_kind = ("sport" if (self._obs.get("risk") or {}).get("sport")
                               else "annoyed" if "fed up" in verdict[1] else "real")
        if threat is not None and threat.get("player"):
            mode = drives.after_mode(self.after, self.drives.mode, self.fight_kind)
            if mode in ("mercy", "fair") and self._backing_off(threat):
                return "goal", f"{threat.get('name', 'they')} backed off: letting it go"
        if threat is None:
            annoyer, stage = self._annoyed_by()
            if annoyer is not None:
                self._annoyer, self._annoy_stage = annoyer, stage
                return "back_off", f"{annoyer.get('name', 'someone')} keeps joke-hitting ({stage})"
            a = self.aftermath
            if a and a["mode"] in ("fair", "mercy") and self._dist_to_spot(me) < 7:
                return "leave_spot", f"letting {a['name']} get their things"
            if a and a["mode"] == "mercy" and a["picked"] and self._drop_slot() is not None:
                return "give_back", "giving " + a["name"] + " their things back"

        health = float(me.get("health", 20))
        back_for_loot = (threat is not None and self.aftermath is not None and self.aftermath["mode"] == "ruthless"
                         and threat.get("name") == self.aftermath["name"])
        real = threat is not None and (back_for_loot or not threat.get("player") or self.drives.judge.real(threat.get("id"))
                                       or threat.get("relation", 0) < 0 or threat.get("enemyInsignia", False))
        need = self.drives.needs(self._obs, threat, real)
        if threat is not None:
            odds = float(threat.get("fightOdds", 0.0))
            name = threat.get("name") or threat.get("type", "?").split(":")[-1]
            scared = (0.8 if self.drives.mode == "peaceful" else 1.0) + (self.strategist.courage() if self.strategy_on else 0.0)
            if (health <= 6 and odds < 0.2) or (need["fear"] >= scared and need["fear"] >= need["anger"]):
                return "flee", f"afraid of {name} (health {health:.0f}, odds {odds:+.2f})"
            if need["anger"] >= 0.8 and (goal is None or goal.type != "flee"):
                verdict = self.drives.judge.verdict.get(threat.get("id"))
                return "fight", f"{name} (odds {odds:+.2f}" + (f", {verdict[0]}: {verdict[1]}" if verdict else "") + ")"
        if self.eating > 0 or (float(me.get("food", 20)) <= 14 and self._food_slot() is not None):
            return "eat", "hungry"
        return "goal", ""

    # --- acting ---

    def act(self, obs, frame):
        action = self._act(obs, frame)
        if self.mode in ("fight", "flee") and not getattr(action, "say", None):
            line = self.talk.callout(self.tick, self.mode, obs, self._chase_ticks())
            if line:
                action.say = line
        return action

    def _act(self, obs, frame):
        self._obs = obs
        self.tick = int(obs.get("tick", self.tick + 1))
        self.drives.judge.update(self.tick, obs, obs.get("_events") or [])
        self._aftermath_events(obs)
        for ev in obs.get("_events") or []:
            for g in self.queue[:1] + ([self.sub] if self.sub else []):
                g.on_event(ev)
            if ev.get("type") == "stuck":
                self._on_stuck()
            if ev.get("type") == "chat":
                self.talk.heard(self.tick, (ev.get("data") or {}).get("text", ""), obs)
        if self.strategy_on:
            lg = self.long_goal()
            wake = self.strategist.on_events(self.tick, obs.get("_events") or [], obs.get("self") or {},
                                             lg.spec if lg else None)
            if wake or self.strategist.due(self.tick):
                before = self.strategist.plan
                ready = {"trap": self._design_for("trap", obs) is not None,
                         "shelter": self._design_for("shelter", obs) is not None}
                self.strategist.decide(self.tick, obs, lg.spec if lg else None, ready=ready)
                if self.strategist.plan != before or wake:
                    self.sub = None  # Work on the new plan now.
                self.strategist.save()
        goal = self.current()
        if goal is not None:
            goal.check_time(self.tick)
        guard = self.drives.cfg.get("guard_radius")
        if guard and self.tick % DECIDE_EVERY == 0 and (goal is None or goal.type != "go_to"):
            for w in obs.get("waypoints") or []:
                if w.get("type") == "base" and math.hypot(w.get("dx", 0.0), w.get("dz", 0.0)) > guard:
                    self.sub = Goal({"type": "go_to", "waypoint": "base", "why": "too far from what it guards"}, self.tick)
                    goal = self.current()
                    break
        if obs.get("self", {}).get("dead"):
            return Action()
        if self.tick % DECIDE_EVERY == 0 or self.mode == "goal" and goal is None:
            self.mode, self.why = self._decide(goal)
        if self.mode == "eat":
            return self._eat()
        if self.mode == "back_off":
            return self._back_off()
        if self.mode == "leave_spot":
            return self._leave_spot()
        if self.mode == "give_back":
            return self._give_back()
        if self.tick < self.unstick_until:
            # Watchdog: it hasn't got anywhere for a while: turn away, jump and walk, then retry.
            self.skill = "unstick"
            return Action(keys={"forward", "jump"}, yaw=9.0 if self.unstick_until - self.tick > 30 else 0.0)
        gtype = self.mode if self.mode in ("fight", "flee") else (goal.type if goal else None)
        if gtype is None:
            self.skill = "idle"
            return self._idle()
        if gtype != "invent_build" and self.builder is not None:
            self.builder, self.building = None, None  # Interrupted (a fight...): starts again next time.
        if gtype == "invent_build":
            return self._build_invention(obs, goal)
        if gtype == "ambush":
            # Keep still and low, and look about slowly: let them come (anyone close wakes the fight check).
            self.skill = "ambush"
            return Action(keys={"sneak"}, yaw=1.5 if (self.tick // 60) % 2 else -1.5)
        # Awareness: when nothing is going on, think 5 times a second instead of 20 and keep
        # walking in between (like walking on autopilot); anything close or surprising wakes it.
        if self._calm(obs, gtype):
            self.calm_ticks += 1
            if self.calm_ticks % 4 != 1:
                self.thinking = 5
                return Action(keys=set(k for k in self.held.keys if k not in ("attack", "use")))
        else:
            self.calm_ticks = 0
            self.thinking = 20
        obs = dict(obs)
        obs["goal"] = self._goal_input(gtype, goal)
        name, policy = self._skill(SKILLS_FOR.get(gtype, ("general",)))
        self.skill = name
        if policy is None:
            return self._reflex(gtype, goal, obs)
        self._aim = FollowPolicy()  # A skill is steering; the reflex aim starts fresh next time.
        action = policy.act(obs, frame)
        self.held = action
        if goal is not None and goal.type == "protect" and self.tick % 20 == 0:
            action.protect = goal.spec.get("player", "")
        return action

    def _calm(self, obs: dict, gtype: str) -> bool:
        """Nothing needs attention: no fight, nobody close, not hurt, nothing surprising happened."""
        if gtype in ("fight", "flee", "hunt", "hunt_players", "farm") or self.mode != "goal":
            return False
        if any(e.get("type") in ("damage_taken", "hit_by", "hurt_by", "death") for e in obs.get("_events") or []):
            return False
        for e in obs.get("entities") or []:
            if e.get("visible", True) and (e.get("player") or e.get("hostile")) \
                    and e.get("dx", 0) ** 2 + e.get("dy", 0) ** 2 + e.get("dz", 0) ** 2 < 16 * 16:
                return False
        if float((obs.get("risk") or {}).get("level", 0)) > 0.4:
            return False  # A busy area on a PvP-heavy server: stay alert.
        return float((obs.get("self") or {}).get("health", 20)) >= 14

    def _goal_input(self, gtype: str, goal: Goal | None) -> dict:
        from ..learn import knowledge

        me = self._obs.get("self") or {}
        g = {"type": gtype}
        target = self._target(gtype, goal)
        if target is not None:
            g["target_dx"], g["target_dz"] = target
        if gtype == "mine" and goal is not None:
            ore = knowledge.ore(goal.spec.get("item", ""))
            if ore and "best" in ore:
                g["target_dy"] = float(ore["best"]) - float(me.get("y", 64))
        return g

    def _target(self, gtype: str, goal: Goal | None):
        """(dx, dz) to what the goal is about, if visible or remembered."""
        self._tdy = None
        if gtype == "hunt_players":
            want = goal.spec.get("player") if goal else None
            t = next((e for e in self._obs.get("entities") or [] if want and e.get("name") == want
                      and e.get("visible", True)), None) if want else None
            if t is None:
                t = self.drives.prey(self._obs)
                # Only fights worth taking under the current plan (the manhunt target is always worth it).
                if t is not None and self.strategy_on and float(t.get("fightOdds", 0.0)) < self.strategist.min_odds():
                    t = None
            if t is None:
                return None
            self._tdy = t.get("dy")
            return t.get("dx", 0.0), t.get("dz", 0.0)
        if gtype in ("fight", "flee"):
            t = self._threat()
            if t is None:
                return None
            self._tdy = t.get("dy")
            return t.get("dx", 0.0), t.get("dz", 0.0)
        if goal is None:
            return None
        if gtype in ("follow", "protect"):
            name = goal.spec.get("player")
            for e in self._obs.get("entities") or []:
                if e.get("name") == name:
                    return e.get("dx", 0.0), e.get("dz", 0.0)
        if gtype == "stash":
            for w in self._obs.get("waypoints") or []:
                if w.get("type") == "base":
                    return w.get("dx", 0.0), w.get("dz", 0.0)
        if gtype == "go_to":
            want = goal.spec.get("waypoint")
            for w in self._obs.get("waypoints") or []:
                if want in (w.get("name"), w.get("type")):
                    return w.get("dx", 0.0), w.get("dz", 0.0)
        if gtype == "hunt":
            target = goal.spec.get("target", "")
            for e in self._obs.get("entities") or []:
                if target in e.get("type", "") and e.get("visible", True):
                    self._tdy = e.get("dy")
                    return e.get("dx", 0.0), e.get("dz", 0.0)
        return None

    # --- built-in reflexes (when no trained skill exists yet) ---

    def _back_off(self) -> Action:
        """Annoyed: look at them and step back; at the warning stage, say so (once in a while)."""
        e = self._annoyer
        if e is None:
            return Action()
        me = self._obs.get("self") or {}
        want_yaw = math.degrees(math.atan2(-e.get("dx", 0), e.get("dz", 0)))
        yaw_err = wrap_degrees(want_yaw - float(me.get("yaw", 0.0)))
        act = Action(keys={"back"}, yaw=max(-12.0, min(12.0, yaw_err * 0.4)))
        if self._annoy_stage == "warn" and self.tick - self.warned.get(e.get("id"), -10**9) > 600:
            self.warned[e.get("id")] = self.tick
            act.say = "stop it"
        return act

    def _leave_spot(self) -> Action:
        """Walk away from where they were knocked out, so they can get their things."""
        a = self.aftermath
        me = self._obs.get("self") or {}
        dx, dz = float(me.get("x", 0)) - a["x"], float(me.get("z", 0)) - a["z"]
        want_yaw = math.degrees(math.atan2(-dx, dz))
        yaw_err = wrap_degrees(want_yaw - float(me.get("yaw", 0.0)))
        return Action(keys={"forward"} if abs(yaw_err) < 60 else set(), yaw=max(-15.0, min(15.0, yaw_err * 0.5)))

    def _drop_slot(self):
        a = self.aftermath
        for it in self._obs.get("inventory") or []:
            if it.get("slot", 99) < 9 and any(p and p in it.get("id", "") for p in a["picked"]):
                return it["slot"]
        return None

    def _give_back(self) -> Action:
        """Drop what it picked up from their body (one item every other tick, from the hotbar)."""
        slot = self._drop_slot()
        if slot is None:
            self.aftermath["picked"] = []
            return Action()
        if self._obs.get("selectedSlot") != slot:
            return Action(keys={f"hotbar_{slot + 1}"})
        return Action(keys={"drop"}) if self.tick % 2 == 0 else Action()

    def _eat(self) -> Action:
        slot = self._food_slot()
        if slot is None:
            self.eating = 0
            return Action()
        if self.eating <= 0:
            self.eating = 40
        self.eating -= 1
        return Action(keys={f"hotbar_{slot + 1}", "use"})

    def _farm(self, obs: dict) -> Action:
        """Built-in farming: break ripe-looking crops in the crosshair, replant, wander the field."""
        look = (obs.get("look") or {}).get("block", "")
        crops = ("wheat", "carrots", "potatoes", "beetroots")
        seeds = None
        for it in obs.get("inventory") or []:
            if it.get("slot", 99) < 9 and any(s in it.get("id", "") for s in ("seeds", "carrot", "potato")):
                seeds = it["slot"]
        if any(c in look for c in crops):
            return Action(keys={"attack"}, pitch=0.0)
        if "farmland" in look and seeds is not None:
            if obs.get("selectedSlot") != seeds:
                return Action(keys={f"hotbar_{seeds + 1}"})
            return Action(keys={"use"})
        phase = self.tick % 80
        return Action(keys={"forward"} if phase < 30 else set(), yaw=6.0 if phase in (30, 31, 32) else 0.0,
                      pitch=(40.0 - float((obs.get("self") or {}).get("pitch", 0.0))) * 0.2)

    def _reflex(self, gtype: str, goal: Goal | None, obs: dict) -> Action:
        me = obs.get("self") or {}
        if gtype == "farm":
            return self._farm(obs)
        target = self._target(gtype, goal)
        if target is None:
            if gtype == "explore":
                return Action(keys={"forward", "sprint"} | ({"jump"} if self.tick % 40 < 2 else set()),
                              yaw=8.0 if self.tick % 200 < 20 else 0.0)
            return Action()
        dx, dz = target
        if gtype == "flee":
            dx, dz = -dx, -dz
        aim = self._aim
        pending = aim._pending(me)
        dist = math.hypot(dx, dz)
        want_yaw = math.degrees(math.atan2(-dx, dz))
        yaw_err = wrap_degrees(want_yaw - float(me.get("yaw", 0.0)))
        pitch_err = 0.0
        if gtype in ("fight", "hunt", "hunt_players") and self._tdy is not None:
            # Aim at the body (dy is measured from the eyes to the feet), not over its head.
            want_pitch = -math.degrees(math.atan2(float(self._tdy) + 0.5, max(dist, 1e-3)))
            pitch_err = want_pitch - float(me.get("pitch", 0.0))
        elif gtype in ("flee", "go_to", "follow", "protect", "explore", "stash"):
            pitch_err = -float(me.get("pitch", 0.0))  # Look ahead while travelling.
        keys = set()
        if gtype in ("fight", "hunt", "hunt_players"):
            slot = aim.weapon_slot(obs)
            if slot is not None and obs.get("selectedSlot") != slot:
                return aim._turn({f"hotbar_{slot + 1}"}, 0.0, 0.0)
            if dist > aim.reach - 0.4 and abs(yaw_err) < 45:
                keys |= {"forward", "sprint"}
            reach = math.sqrt(dist ** 2 + (float(self._tdy or -1.0) + 0.5) ** 2)
            if reach < aim.reach + 0.4 and abs(yaw_err) < 12 and float(me.get("attackReady", 1.0)) > 0.9 \
                    and aim.cooldown == 0:
                keys.add("attack")
                aim.cooldown = 4
        elif gtype == "flee" or (dist > 3 and abs(yaw_err) < 60):
            keys |= {"forward", "sprint"}  # Fleeing: run now, finish turning on the way.
        aim.cooldown = max(0, aim.cooldown - 1)
        if "forward" in keys and self.tick % 30 < 2:
            keys.add("jump")
        return aim._turn(keys, aim.gain * wrap_degrees(yaw_err - pending[0]), aim.gain * (pitch_err - pending[1]))

    def close(self):
        """Swapped out: the strategist's plan memory and every skill it used keep what they learned."""
        try:
            self.strategist.save()
        except OSError:
            pass
        for policy in self.skills.values():
            try:
                policy.close()
            except Exception:
                pass
        self.skills.clear()

    def status(self):
        lg = self.long_goal()
        goal = self.sub if lg is not None and self.sub is not None else self.queue[0] if self.queue else self.sub
        return {"goal": goal.label() if goal else None, "why": (goal.spec.get("why") if goal else None) or self.why,
                "mode": self.mode, "skill": self.skill or "reflexes", "queue": len(self.queue),
                "completed": self.completed[-5:], "skills": self.available, "free_play": self.free_play,
                "attitude": self.drives.mode, "thinking": self.thinking,
                "after_fight": self.aftermath["mode"] if self.aftermath else None, "feelings": self.drives.feelings,
                "judged": {str(k): f"{v[0]} ({v[1]})" for k, v in list(self.drives.judge.verdict.items())[-3:]},
                "strategy": self.strategist.status() if self.strategy_on else None,
                "long_goal": lg.label() if lg is not None else None,
                "building": f"{self.building[0]}: {self.building[1]}" if self.building else None}
