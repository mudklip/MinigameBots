"""PPO for the Lite network, learning while the AI plays.

The actor (the network that plays) samples actions and records what it saw, did and earned.
Every `rollout` ticks the learner takes a copy of that experience and improves a copy of the
network in a background thread, so the AI keeps playing meanwhile; the improved weights are
then swapped in. A frozen copy of the imitation-trained network (the "prior") keeps it close to
how people play: the further it drifts, the more it costs ("stay human").
"""

import copy
import math
import threading
import time

import numpy as np
import torch
import torch.nn.functional as F

from ..learn import actions, features
from ..learn.model import to_tensors

CHUNK = 32
CATEGORICAL = ("attacks", "uses", "yaw", "pitch", "mark")  # Ticks per training sequence (the GRU memory is replayed over each chunk).


def sample(logits: dict, greedy: bool = False):
    """Samples one action from [1, 1, ...] logits. Returns (indices dict, log-prob, entropy)."""
    keys_p = torch.sigmoid(logits["keys"][0, 0].float())
    keys = (keys_p > 0.5).float() if greedy else torch.bernoulli(keys_p)
    idx = {"keys": keys}
    for k in CATEGORICAL:
        if k not in logits:
            continue
        l = logits[k][0, 0].float()
        idx[k] = l.argmax() if greedy else torch.multinomial(F.softmax(l, -1), 1)[0]
    return idx


def log_prob(logits: dict, act: dict) -> torch.Tensor:
    """Log-probability of stored actions under [B, T, ...] logits -> [B, T]."""
    lp = -F.binary_cross_entropy_with_logits(logits["keys"].float(), act["keys"], reduction="none").sum(-1)
    for k in [c for c in CATEGORICAL if c in act and c in logits]:
        lp = lp + F.log_softmax(logits[k].float(), -1).gather(-1, act[k].unsqueeze(-1)).squeeze(-1)
    return lp


def entropy(logits: dict) -> torch.Tensor:
    p = torch.sigmoid(logits["keys"].float())
    ent = -(p * torch.log(p.clamp(min=1e-6)) + (1 - p) * torch.log((1 - p).clamp(min=1e-6))).sum(-1)
    for k in [c for c in CATEGORICAL if c in logits]:
        lsm = F.log_softmax(logits[k].float(), -1)
        ent = ent - (lsm.exp() * lsm).sum(-1)
    return ent


def kl(logits: dict, prior: dict) -> torch.Tensor:
    """KL(current || prior) per step."""
    p = torch.sigmoid(logits["keys"].float()).clamp(1e-6, 1 - 1e-6)
    q = torch.sigmoid(prior["keys"].float()).clamp(1e-6, 1 - 1e-6)
    out = (p * torch.log(p / q) + (1 - p) * torch.log((1 - p) / (1 - q))).sum(-1)
    for k in [c for c in CATEGORICAL if c in logits and c in prior]:
        lp = F.log_softmax(logits[k].float(), -1)
        lq = F.log_softmax(prior[k].float(), -1)
        out = out + (lp.exp() * (lp - lq)).sum(-1)
    return out


def gae(rewards, values, dones, last_value, gamma, lam):
    adv = np.zeros_like(rewards)
    running = 0.0
    for t in reversed(range(len(rewards))):
        next_v = last_value if t == len(rewards) - 1 else values[t + 1]
        nonterminal = 1.0 - dones[t]
        delta = rewards[t] + gamma * next_v * nonterminal - values[t]
        running = delta + gamma * lam * nonterminal * running
        adv[t] = running
    return adv, adv + values


class Rollout:
    """What the actor saw and did since the last update."""

    def __init__(self):
        self.steps = []  # dicts: feats (featurized arrays incl. frame), act, logp, value
        self.rewards = []
        self.dones = []
        self.hidden = []  # GRU state before each step (only kept at chunk starts)
        self.ticks = {}

    def __len__(self):
        return len(self.steps)

    def add(self, tick, feats, act, logp, value, hidden):
        self.ticks[tick] = len(self.steps)
        self.steps.append({"feats": feats, "act": act, "logp": logp, "value": value})
        self.rewards.append(0.0)
        self.dones.append(0.0)
        self.hidden.append(hidden if len(self.steps) % CHUNK == 1 else None)

    def add_reward(self, tick, amount) -> bool:
        i = self.ticks.get(tick)
        if i is None:
            return False
        self.rewards[i] += amount
        return True


class PPOLearner:
    def __init__(self, model, prior=None, device="cpu", rollout=1024, epochs=3, minibatch_chunks=8, lr=1e-4,
                 gamma=0.995, lam=0.95, clip=0.2, stay_human=0.05, entropy_coef=0.003, threads=2):
        self.device = device
        self.actor = model
        self.learner = copy.deepcopy(model)
        self.prior = prior
        self.rollout_len = rollout
        self.epochs = epochs
        self.minibatch_chunks = minibatch_chunks
        self.gamma, self.lam, self.clip = gamma, lam, clip
        self.stay_human = stay_human
        self.entropy_coef = entropy_coef
        self.threads = threads
        self.optimizer = torch.optim.AdamW(self.learner.parameters(), lr=lr, weight_decay=0.0)
        self.buffer = Rollout()
        self.lock = threading.Lock()
        self.thread = None
        self.pending_weights = None
        self.updates = 0
        self.last_stats = {}
        self.ret_var = 1.0  # Running variance of returns, to keep the value scale sane.
        self.mask = None  # Skills: makes locked actions impossible (logits -> logits).

    def set_learning(self, lr=None, stay_human=None, entropy=None):
        """Changes how fast it learns (a skill moving to its next stage)."""
        if lr is not None:
            for g in self.optimizer.param_groups:
                g["lr"] = float(lr)
        if stay_human is not None:
            self.stay_human = float(stay_human)
        if entropy is not None:
            self.entropy_coef = float(entropy)

    # --- acting side ---

    def record(self, tick, feats, act, logp, value, hidden):
        self.buffer.add(tick, feats, act, logp, value, hidden)

    def reward(self, amount):
        if len(self.buffer):
            self.buffer.rewards[-1] += amount

    def reward_at(self, tick, amount):
        """A reward that belongs to an earlier tick (escrow); lands on the current step if that tick
        was already trained on."""
        if not self.buffer.add_reward(tick, amount):
            self.reward(amount)

    def end_episode(self):
        if len(self.buffer):
            self.buffer.dones[-1] = 1.0

    def maybe_update(self, last_value: float):
        """Starts a background update when enough experience is collected. Returns new weights to
        load into the actor when an update has finished (else None)."""
        weights = None
        with self.lock:
            if self.pending_weights is not None:
                weights, self.pending_weights = self.pending_weights, None
        if len(self.buffer) >= self.rollout_len and (self.thread is None or not self.thread.is_alive()):
            batch, self.buffer = self.buffer, Rollout()
            self.thread = threading.Thread(target=self._update, args=(batch, last_value), daemon=True,
                                           name="practice-learner")
            self.thread.start()
        return weights

    def busy(self):
        return self.thread is not None and self.thread.is_alive()

    # --- learning side ---

    def _update(self, ro: Rollout, last_value: float):
        try:
            torch.set_num_threads(self.threads)
            started = time.time()
            stats = self._train(ro, last_value)
            stats["seconds"] = time.time() - started
            state = {k: v.detach().clone() for k, v in self.learner.state_dict().items()}
            with self.lock:
                self.pending_weights = state
                self.updates += 1
                self.last_stats = stats
        except Exception as e:  # Keep playing even if an update fails; report it.
            import traceback

            traceback.print_exc()
            with self.lock:
                self.last_stats = {"error": f"{type(e).__name__}: {e}"}

    def _train(self, ro: Rollout, last_value: float) -> dict:
        n = len(ro) - len(ro) % CHUNK
        if n < CHUNK:
            return {}
        rewards = np.asarray(ro.rewards[:n], dtype=np.float32)
        values = np.asarray([s["value"] for s in ro.steps[:n]], dtype=np.float32)
        dones = np.asarray(ro.dones[:n], dtype=np.float32)
        boot = float(ro.steps[n]["value"]) if n < len(ro) else last_value
        adv, ret = gae(rewards, values, dones, boot, self.gamma, self.lam)
        self.ret_var = 0.9 * self.ret_var + 0.1 * float(np.var(ret) + 1e-8)
        scale = 1.0 / math.sqrt(self.ret_var)
        adv = (adv - adv.mean()) / (adv.std() + 1e-8)

        chunks = n // CHUNK
        feats = [s["feats"] for s in ro.steps[:n]]
        stacked = {k: np.stack([f[k] for f in feats]).reshape(chunks, CHUNK, *feats[0][k].shape) for k in feats[0]}
        acts = {k: torch.stack([s["act"][k] for s in ro.steps[:n]]).reshape(chunks, CHUNK, *ro.steps[0]["act"][k].shape)
                for k in ro.steps[0]["act"]}
        old_logp = torch.tensor([s["logp"] for s in ro.steps[:n]], dtype=torch.float32).reshape(chunks, CHUNK)
        h0 = torch.cat([ro.hidden[c * CHUNK] for c in range(chunks)], dim=1)  # [1, chunks, H]
        adv_t = torch.from_numpy(adv).reshape(chunks, CHUNK)
        ret_t = torch.from_numpy(ret * scale).reshape(chunks, CHUNK)

        dev = self.device
        self.learner.train()
        totals = {"policy": 0.0, "value": 0.0, "kl_prior": 0.0, "entropy": 0.0, "clipped": 0.0}
        count = 0
        for _ in range(self.epochs):
            order = np.random.permutation(chunks)
            for start in range(0, chunks, self.minibatch_chunks):
                idx = order[start:start + self.minibatch_chunks]
                batch = to_tensors({k: v[idx] for k, v in stacked.items()}, dev)
                act = {k: v[idx].to(dev) for k, v in acts.items()}
                logits, _ = self.learner(batch, h0[:, idx].to(dev))
                if self.mask is not None:
                    logits = self.mask(logits)
                lp = log_prob(logits, act)
                ratio = torch.exp(lp - old_logp[idx].to(dev))
                a = adv_t[idx].to(dev)
                pg = -torch.min(ratio * a, ratio.clamp(1 - self.clip, 1 + self.clip) * a).mean()
                v_loss = F.mse_loss(logits["value"].float(), ret_t[idx].to(dev))
                ent = entropy(logits).mean()
                loss = pg + 0.5 * v_loss - self.entropy_coef * ent
                kl_p = torch.zeros((), device=dev)
                if self.prior is not None and self.stay_human > 0:
                    with torch.no_grad():
                        prior_logits, _ = self.prior(batch, None)
                        if self.mask is not None:
                            prior_logits = self.mask(prior_logits)
                    kl_p = kl(logits, prior_logits).mean()
                    loss = loss + self.stay_human * kl_p
                self.optimizer.zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(self.learner.parameters(), 0.5)
                self.optimizer.step()
                totals["policy"] += float(pg.detach())
                totals["value"] += float(v_loss.detach())
                totals["kl_prior"] += float(kl_p.detach())
                totals["entropy"] += float(ent.detach())
                totals["clipped"] += float(((ratio.detach() - 1).abs() > self.clip).float().mean())
                count += 1
        out = {k: v / max(1, count) for k, v in totals.items()}
        out["steps"] = n
        out["mean_reward"] = float(rewards.mean())
        return out

    @property
    def value_scale(self):
        """Values are learned on returns divided by this; multiply to compare with rewards."""
        return math.sqrt(self.ret_var)


def encode_sample(idx: dict) -> dict:
    """Stored action tensors (CPU)."""
    return {"keys": idx["keys"].detach().cpu().float(),
            **{k: idx[k].detach().cpu().long().reshape(()) for k in CATEGORICAL if k in idx}}


def decode(idx: dict):
    action = actions.decode_action(idx["keys"].cpu().numpy() > 0.5, int(idx["attacks"]), int(idx["uses"]),
                                   int(idx["yaw"]), int(idx["pitch"]))
    if "mark" in idx and int(idx["mark"]) > 0:
        action.mark = features.WAYPOINT_TYPES[int(idx["mark"]) - 1]
    return action


def featurize(obs: dict, frame) -> dict:
    f = features.featurize(obs)
    if frame is None or isinstance(frame, (bytes, bytearray)):
        f["frames"] = np.zeros((features.FRAME_SIZE, features.FRAME_SIZE, 3), dtype=np.uint8)
    else:
        f["frames"] = np.array(frame, dtype=np.uint8)  # A copy: frames from shared memory are read-only.
    return {k: np.asarray(v) for k, v in f.items()}
