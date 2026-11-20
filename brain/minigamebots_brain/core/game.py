"""The Core's game mode: learn a minigame by playing it.

Plays with the AI's network and practice learning (PPO), while the rule finder watches what
happens. Its rewards are the rule finder's objective, rebuilt after every round as the rules get
clearer. Rules are remembered per game (by the sidebar title), so a game it has seen before starts
with the right rewards straight away. Skills that fit the game are suggested from the rules.
"""

from __future__ import annotations

from ..policies.practice import PracticePolicy
from .rules import THROWABLES, RuleFinder


class CoreGamePolicy(PracticePolicy):
    name = "core"

    def __init__(self, profile: str, me: str = "", learn: bool = True, stage_dir: str | None = None, **kwargs):
        self.me = me
        self.base_profile = profile
        self.store = stage_dir or profile
        self.key = None
        self.finder = RuleFinder(me)
        kwargs.pop("objective", None)
        super().__init__(profile, objective=self.finder.objective(), learn=learn, **kwargs)
        self.prev = None
        self.started_from = "general"
        self.curious = True  # A new game: curiosity helps find out what does what.

    def _switch_game(self, key: str) -> None:
        if self.key is not None:
            self.finder.save(self.store, self.key)
        self.key = key
        self.finder = RuleFinder.load(self.store, key, self.me)
        self._refresh()
        self._game_network(key)

    def _game_network(self, key: str) -> None:
        """Each game learns into its own network (the general one is never overwritten): a game seen
        before continues from its own; a new one starts from the AI's skill that fits its rules best."""
        import os

        from ..learn import checkpoint
        from ..rl import skills

        own = os.path.join(self.base_profile, "skills", "game_" + key)  # Shared with watching's per-game training.
        start = None
        if checkpoint.read_info(own):
            start = own
        else:
            for sid in self.finder.borrow():
                area = (skills.preset(sid) or {}).get("manager", "")
                d = os.path.join(self.base_profile, "skills", area) if area and area != "general" else None
                if d and checkpoint.read_info(d):
                    start = d
                    break
        self.profile = own
        if start is not None:
            try:
                model, info = checkpoint.load(start, "latest", self.device)
                self.model.load_state_dict(model.state_dict())
                if self.learner is not None:
                    self.learner.actor.load_state_dict(model.state_dict())
                    self.learner.learner.load_state_dict(model.state_dict())
                self.started_from = os.path.basename(start) if start != own else "its own"
            except Exception:
                self.started_from = "general"
        else:
            self.started_from = "general"

    def _refresh(self) -> None:
        from ..rl import objective as obj

        self.objective = self.finder.objective(self.key or "This game")
        self.engine = obj.RewardEngine(self.objective)

    def act(self, obs, frame):
        key = RuleFinder.game_key(obs)
        if key != self.key:
            self._switch_game(key)
        events = list(obs.get("_events") or [])
        # "I threw X" happens through the action itself: the game has no event for it.
        if self.prev is not None and "use" in (self.prev.keys or ()):
            held = next((i.get("id", "") for i in obs.get("inventory") or [] if i.get("slot") == obs.get("selectedSlot")), "")
            if any(t in held for t in THROWABLES):
                events.append({"type": "threw", "data": {"item": held}})
        self.finder.observe(obs, events, self.prev)
        for ev in events:
            if ev.get("type") == "tg_episode_end":
                self.finder.round_over((ev.get("data") or {}).get("result") == "win")
        episodes = self.episodes
        o = dict(obs)
        o["_events"] = events
        action = super().act(o, frame)
        if self.episodes != episodes:  # A round ended: the rules may be clearer now.
            self._refresh()
            self.finder.save(self.store, self.key)
        self.prev = action
        return action

    def status(self):
        out = super().status()
        out.update({"game": self.key, "rules": self.finder.describe(), "borrow": self.finder.borrow(),
                    "started_from": self.started_from, **self.finder.summary()})
        return out
