import json
import os
import time
from collections import deque

import torch

from ..actions import Action
from .base import Policy


def load_objective(profile: str, objective, params: dict | None = None) -> dict:
    """An Objective given as a dict, a template name, or a file name in <profile>/objectives/."""
    from ..rl import templates

    if isinstance(objective, dict):
        return objective
    path = os.path.join(profile, "objectives", f"{objective}.json")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return templates.build(objective, params)


class PracticePolicy(Policy):
    """Plays with the AI's network and improves it from an Objective's rewards (PPO).

    profile     -- the AI profile; its trained network is the starting point and stays the
                   "prior" it is kept close to (stay_human), and practice saves back into it
    objective   -- template name (duel, hunt, survive, aim), a saved objective's name, or a dict
    learn       -- False just plays and scores (an exam), without changing the network
    stay_human  -- how strongly to stay close to the imitation-trained behaviour (0 = not at all)
    resources   -- quiet | balanced | max: how hard learning may push the PC
    """

    name = "practice"

    RESOURCES = {"quiet": {"threads": 1, "rollout": 2048}, "balanced": {"threads": 2, "rollout": 1024},
                 "max": {"threads": max(1, (os.cpu_count() or 2) - 1), "rollout": 1024}}
    SAVE_EVERY_UPDATES = 2
    FREE_PLAY_TRY_TICKS = 60 * 20

    def __init__(self, profile: str, objective="duel", params: dict | None = None, learn: bool = True,
                 stay_human: float = 0.05, resources: str = "balanced", device: str | None = None,
                 skill_def: dict | None = None, stage_dir: str | None = None, **_):
        from ..learn import checkpoint
        from ..learn.model import LitePolicy
        from ..rl import objective as obj
        from ..rl.ppo import PPOLearner

        from ..learn.actions import KEY_INDEX
        from ..rl import skills

        self.profile = profile
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        # A skill (Phase A): its rules and current stage's hints, locks and learning speed.
        self.skill = skill_def
        self.tracker = None
        self.behaviours = skills.Behaviours(skill_def)
        self.prev_action = None
        self.key_mask, self.camera_lock = [], False
        if skill_def:
            self.tracker = skills.StageTracker(stage_dir or profile, skill_def)
            objective = skills.build_objective(skill_def, self.tracker.stage)
            keys, self.camera_lock = skills.locked_keys(skill_def)
            self.key_mask = [KEY_INDEX[k] for k in keys if k in KEY_INDEX]
            stay_human = skills.learning(skill_def, self.tracker.stage)["stay_human"]
        self.objective = load_objective(profile, objective, params)
        self.engine = obj.RewardEngine(self.objective)
        self.warnings = obj.analyze(self.objective)
        self.info = checkpoint.read_info(profile) or {}
        if self.info.get("arch") == "vpt-tune":
            raise ValueError("Practice works with AIs on the Lite base for now")
        prior = None
        if self.info:
            model, self.info = checkpoint.load(profile, "latest", self.device)
            prior, _ = checkpoint.load(profile, "model", self.device)
            prior.eval()
            for p in prior.parameters():
                p.requires_grad_(False)
        else:
            model = LitePolicy().to(self.device)  # Learning from nothing: slow, but it works.
        self.model = model
        self.model.eval()
        res = self.RESOURCES.get(resources, self.RESOURCES["balanced"])
        self.learn = bool(learn)
        self.learner = PPOLearner(model, prior, self.device, rollout=res["rollout"], threads=res["threads"],
                                  stay_human=float(stay_human)) if self.learn else None
        if self.learner is not None and skill_def:
            self.learner.mask = self._mask
            self.learner.set_learning(**skills.learning(skill_def, self.tracker.stage))
        self.state = None
        self.decisions = 0
        self.episodes = 0
        self.returns = deque(maxlen=20)
        best = self.info.get("practice_best")
        self.best = float(best) if best is not None else float("-inf")
        self.started = time.time()
        self.last_value = 0.0
        self.saved_updates = 0
        self.episode_start = None
        self.match_rounds = False  # Seen Training Grounds rounds: they define the tries.
        # Realism (M12): small rewards for human-looking input and for the style you picked.
        from ..realism import Realism

        self.realism = Realism.load(profile)
        self.recent_inputs = deque(maxlen=40)
        self.human = None
        # Core: predictions of the next seconds, surprise when they're wrong, curiosity from surprise.
        self.expect = None
        self.surprise = 0.0
        self.curious = bool(skill_def and (skill_def.get("stages") or [{}])[
            self.tracker.stage if self.tracker else 0].get("discover"))

    def act(self, obs, frame):
        from ..rl import ppo

        events = list(obs.get("_events") or [])
        events += self.behaviours.events(obs, self.prev_action, events)
        tick = int(obs.get("tick", self.decisions))
        res = self.engine.step(tick, events, obs)
        self._feed(res)
        if self.learner is not None:
            self.learner.reward(res.reward)
            for t, amount in res.delayed:
                self.learner.reward_at(t, amount)
        if any(e.get("type") in ("tg_episode_start", "tg_episode_end") for e in events):
            self.match_rounds = True
        if self.episode_start is None or any(e.get("type") == "tg_episode_start" for e in events):
            self.episode_start = tick
        ended = any(e.get("type") in ("tg_episode_end", "death") for e in events)
        # Outside matches, a try is also closed every minute so it can be scored (the learner
        # isn't told it ended: the game simply goes on).
        timed_out = not ended and not self.match_rounds and tick - self.episode_start >= self.FREE_PLAY_TRY_TICKS
        if ended or timed_out:
            self.episode_start = tick
            for t, amount in self.engine.end_episode():
                if self.learner is not None:
                    self.learner.reward_at(t, amount)
            self.episodes += 1
            self.returns.append(self.engine.last_episode_return)
            if self.tracker is not None and self.learn and self.tracker.episode(self.engine.last_episode_return):
                self._next_stage()
            if self.learner is not None and ended:
                self.learner.end_episode()
        if obs.get("self", {}).get("dead"):
            self.state = None
            return Action()

        self._surprise(events)
        feats = ppo.featurize(obs, frame)
        batch = {k: torch.from_numpy(v[None, None]).to(self.device) for k, v in feats.items()}
        hidden = self.state if self.state is not None else self.model.initial_state(1, self.device)
        with torch.no_grad():
            logits, self.state = self.model(batch, hidden)
            if "predict" in logits:
                self.expect = torch.sigmoid(logits["predict"][0, 0].float()).cpu().numpy()
            logits = self._mask(logits)
            idx = ppo.sample(logits, greedy=not self.learn)
            act = ppo.encode_sample(idx)
            logp = float(ppo.log_prob({k: v for k, v in logits.items()},
                                      {k: v.to(self.device)[None, None] for k, v in act.items()})[0, 0])
            value = float(logits["value"][0, 0])
        self.decisions += 1
        if self.learner is not None:
            self.last_value = value * self.learner.value_scale
            self.learner.record(tick, feats, act, logp, self.last_value, hidden.detach().cpu())
            weights = self.learner.maybe_update(self.last_value)
            if weights is not None:
                self.model.load_state_dict(weights)
                self._maybe_save()
        action = ppo.decode(idx)
        self._realism_reward(action)
        self.prev_action = action
        return action

    CURIOSITY = 0.02

    def _surprise(self, events) -> None:
        """How unexpected this moment was (things that happened that it thought unlikely). In
        self-discovery and while learning a new game, surprise in a safe moment is a small reward."""
        from ..core import predict

        if self.expect is None:
            return
        now = predict.happened(events)
        if not now.any():
            self.surprise *= 0.95
            return
        import numpy as np

        s = float(-(np.log(np.clip(self.expect, 1e-3, 1.0)) * now).sum())
        self.surprise = 0.7 * self.surprise + 0.3 * s
        bad = now[predict.NAMES.index("hurt")] or now[predict.NAMES.index("die")]
        if self.curious and self.learner is not None and not bad:
            self.learner.reward(self.CURIOSITY * min(5.0, s))

    def _mask(self, logits):
        from ..rl import skills

        return skills.mask_logits(logits, self.key_mask, self.camera_lock)

    def _next_stage(self):
        """The skill moved on: new hints and a new learning speed, same network."""
        from ..rl import objective as obj
        from ..rl import skills

        self.objective = skills.build_objective(self.skill, self.tracker.stage)
        budget = self.engine.budget_weight
        self.engine = obj.RewardEngine(self.objective)
        if len(budget) == len(self.engine.budget_weight):
            self.engine.budget_weight = budget
        if self.learner is not None:
            self.learner.set_learning(**skills.learning(self.skill, self.tracker.stage))
        self.curious = bool((self.skill.get("stages") or [{}])[self.tracker.stage].get("discover"))

    REALISM_WEIGHT = 0.05
    STYLE_WEIGHT = 0.05

    def _realism_reward(self, action) -> None:
        if self.realism is None:
            return
        self.recent_inputs.append({"keys": sorted(action.keys), "yaw": action.yaw, "pitch": action.pitch,
                                   "attacks": action.attacks})
        if len(self.recent_inputs) < self.recent_inputs.maxlen or self.decisions % 40:
            return
        window = list(self.recent_inputs)
        bonus = 0.0
        human = self.realism.human_score(window)
        if human is not None:
            self.human = human
            bonus += self.REALISM_WEIGHT * (human - 0.5)
        style = self.realism.style_score(window)
        if style is not None:
            bonus += self.STYLE_WEIGHT * style
        if self.learner is not None and bonus:
            self.learner.reward(bonus)

    def close(self):
        """Swapped out (the brain process stays): keep the updates since the last save."""
        if self.learner is not None and self.learner.updates > self.saved_updates:
            self._maybe_save(force=True)

    def _maybe_save(self, force: bool = False):
        from ..learn import checkpoint

        done = self.learner.updates - self.saved_updates
        if done <= 0 or (done < self.SAVE_EVERY_UPDATES and not force):
            return
        self.saved_updates = self.learner.updates
        avg = sum(self.returns) / len(self.returns) if self.returns else None
        better = avg is not None and len(self.returns) >= 3 and avg > self.best
        if better:
            self.best = avg
        info = dict(self.info)
        info.update({"phase": "practice", "objective": self.objective.get("name", "custom"),
                     "practice_updates": int(info.get("practice_updates", 0)) + done,
                     "practice_avg_return": avg, "practice_best": self.best if self.best != float("-inf") else None})
        self.info = info
        # "model" (what plays normally) only moves forward when practice scores better.
        checkpoint.save(self.profile, self.model, info, best=better, optimizer=None)

    def _feed(self, res):
        """Points and held rewards for the optional popups."""
        from .. import feed

        name = lambda i: self.engine.rules[i].get("note") or self.engine.rules[i].get("event", "?")  # noqa: E731
        for part, amount in res.parts.items():
            feed.points(part, amount)
        for i, amount in self.engine.new_held:
            feed.push("held", name(i), amount)
        for i, amount in self.engine.paid:
            feed.push("paid", name(i), amount)
        for i, amount in self.engine.cancelled:
            feed.push("cancelled", name(i), amount)

    def status(self):
        avg = sum(self.returns) / len(self.returns) if self.returns else None
        out = {"objective": self.objective.get("name"), "episodes": self.episodes, "avg_return": avg,
               "last_return": self.returns[-1] if self.returns else None, "device": self.device,
               "warnings": self.warnings, "human_score": self.human}
        if self.expect is not None:
            from ..core import predict

            out["expect"] = {n: round(float(v), 2) for n, v in zip(predict.NAMES, self.expect)}
            out["surprise"] = round(self.surprise, 2)
        if self.tracker is not None:
            st = (self.skill.get("stages") or [{}])[self.tracker.stage] if self.skill.get("stages") else {}
            out.update({"stage": self.tracker.stage, "stage_name": st.get("name", ""),
                        "stage_episodes": self.tracker.data["episodes"]})
        if self.learner is not None:
            out.update({"updates": self.learner.updates, "learning": self.learner.busy(),
                        "collected": len(self.learner.buffer), "rollout": self.learner.rollout_len,
                        **{f"last_{k}": v for k, v in self.learner.last_stats.items()}})
        return out
