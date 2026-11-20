"""Multi-world training (optional): practice in many arena copies at once (see MultiTrainer).

Training Grounds sends one observation per learner body each tick (obs["multiworld"]). This
policy runs the AI's network for all of them in one batch, keeps a separate memory, reward
engine and rollout per copy, and trains the one network on all of their experience with PPO,
like Practice does for a single body. The player's own body stands still meanwhile.
Lite base only (the bodies aren't rendered, so there are no pictures).
"""

from __future__ import annotations

import torch

from ..actions import Action
from .practice import PracticePolicy


class _Copy:
    def __init__(self, engine, rollout):
        self.engine = engine
        self.buffer = rollout
        self.hidden = None
        self.tick = 0


class MultiWorldPolicy(PracticePolicy):
    name = "multiworld"

    def __init__(self, profile: str, objective="duel", **kw):
        super().__init__(profile, objective=objective, **kw)
        self.copies: dict[int | tuple, _Copy] = {}

    def _copy(self, i, team: bool = False, drill: str = "") -> _Copy:
        from ..rl import objective as obj
        from ..rl.ppo import Rollout

        if i not in self.copies:
            goal = self.objective
            if drill:
                # This world practises a technique (crits, W-taps, breaking shields) instead of duelling.
                from .practice import load_objective
                goal = load_objective(self.profile, drill)
            elif team:
                # Team worlds: the team fight rules (shared round result, focus, help, no friendly fire).
                from .practice import load_objective
                goal = load_objective(self.profile, "team_fight")
            self.copies[i] = _Copy(obj.RewardEngine(goal), Rollout())
        return self.copies[i]

    def _with_buffer(self, c: _Copy, fn):
        """Runs fn with the learner writing into this copy's rollout."""
        if self.learner is None:
            return None
        own, self.learner.buffer = self.learner.buffer, c.buffer
        try:
            return fn()
        finally:
            c.buffer, self.learner.buffer = self.learner.buffer, own

    def act(self, obs, frame):
        from ..rl import ppo

        entries = obs.get("multiworld") or []
        live = []
        for e in entries:
            # Each learner body has its own memory and rollout: teammates share nothing but the world.
            key = int(e.get("copy", 0)) if e.get("slot") is None else (int(e.get("copy", 0)), int(e.get("slot")))
            c = self._copy(key, bool(e.get("team")), str(e.get("objective") or ""))
            c.tick += 1
            events = e.get("events") or []
            if events:
                res = c.engine.step(c.tick, events, None)
                self._with_buffer(c, lambda: (self.learner.reward(res.reward),
                                              [self.learner.reward_at(t, a) for t, a in res.delayed]))
                if any(ev.get("type") == "tg_episode_end" for ev in events):
                    for t, a in c.engine.end_episode():
                        self._with_buffer(c, lambda t=t, a=a: self.learner.reward_at(t, a))
                    self.episodes += 1
                    self.returns.append(c.engine.last_episode_return)
                    self._with_buffer(c, self.learner.end_episode)
                    c.hidden = None
            if e.get("obs") and e.get("puppet_id") is not None:
                live.append((e, c))
        if not live:
            return Action()
        feats = [ppo.featurize(e["obs"], None) for e, _ in live]
        batch = {k: torch.from_numpy(__import__("numpy").stack([f[k] for f in feats]))[:, None].to(self.device)
                 for k in feats[0]}
        hidden = torch.cat([c.hidden if c.hidden is not None else self.model.initial_state(1, self.device)
                            for _, c in live], dim=1)
        puppets = []
        with torch.no_grad():
            logits, new_hidden = self.model(batch, hidden)
        for j, (e, c) in enumerate(live):
            lj = {k: v[j:j + 1] for k, v in logits.items()}
            idx = ppo.sample(lj, greedy=not self.learn)
            act = ppo.encode_sample(idx)
            logp = float(ppo.log_prob(lj, {k: v.to(self.device)[None, None] for k, v in act.items()})[0, 0])
            value = float(lj["value"][0, 0])
            fj = feats[j]
            h_before = hidden[:, j:j + 1].detach().cpu()
            c.hidden = new_hidden[:, j:j + 1]
            if self.learner is not None:
                self._with_buffer(c, lambda: self.learner.record(c.tick, fj, act, logp, value * self.learner.value_scale,
                                                                 h_before))
                weights = self._with_buffer(c, lambda: self.learner.maybe_update(value * self.learner.value_scale))
                if weights is not None:
                    self.model.load_state_dict(weights)
                    self._maybe_save()
            a = ppo.decode(idx)
            puppets.append({"id": int(e["puppet_id"]), "keys": sorted(k for k in a.keys if not k.startswith("hotbar")),
                            "yaw": float(a.yaw), "pitch": float(a.pitch)})
            self.decisions += 1
        return Action(puppets=puppets)

    def status(self):
        out = super().status()
        out["worlds"] = len(self.copies)
        return out

