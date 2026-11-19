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
        self.store = stage_dir or profile
        self.key = None
        self.finder = RuleFinder(me)
        kwargs.pop("objective", None)
        super().__init__(profile, objective=self.finder.objective(), learn=learn, **kwargs)
        self.prev = None

    def _switch_game(self, key: str) -> None:
        if self.key is not None:
            self.finder.save(self.store, self.key)
        self.key = key
        self.finder = RuleFinder.load(self.store, key, self.me)
        self._refresh()

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
        out.update({"game": self.key, "rules": self.finder.describe(), "borrow": self.finder.borrow()})
        return out
