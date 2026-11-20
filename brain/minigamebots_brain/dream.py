"""Dreaming: practising in its head while it sleeps.

1. A small "dream model" learns from its own recordings: given the moment (its health, the nearest
   opponent: where, how healthy, whether it was just hit, its own attack readiness...) and what it
   did (keys, attack, use), how did the next second go (damage dealt minus damage taken)?
2. The hard moments of the day (it got hurt in a fight) are replayed in its head with different
   choices: block, back off, strafe left or right, jump and strike, attack. When the dream model
   clearly expects one of them to go better than what it really did, that moment with the better
   choice becomes a "dream demo".
3. Dream demos are written as a low-weight recording (source "dream") that the sleep replay trains
   on with the rest. A small model's imagination is rough: if it can't predict real outcomes on
   moments it didn't learn from, there is no dreaming at all that night, and dream demos always
   count less than real experience.

With --profile, each moment is also described by the main network's own sense of it (its memory
state after seeing that moment, compressed to 16 numbers): far richer than the hand-picked numbers,
so the dream model predicts better and dreaming happens more often.

CLI: python -m minigamebots_brain dream --recordings DIR [--since ISO] [--profile DIR] -> {"moments", "better", ...}
"""

import argparse
import glob
import json
import math
import os
import sys
import time

import numpy as np

HORIZON = 20  # ticks: "the next second"
ALTERNATIVES = {
    "block": {"keys": ["use"], "attacks": 0, "uses": 1},
    "back_off": {"keys": ["back"], "attacks": 0, "uses": 0},
    "strafe_left": {"keys": ["left", "attack"], "attacks": 1, "uses": 0},
    "strafe_right": {"keys": ["right", "attack"], "attacks": 1, "uses": 0},
    "jump_strike": {"keys": ["jump", "forward", "attack"], "attacks": 1, "uses": 0},
    "attack": {"keys": ["forward", "attack"], "attacks": 1, "uses": 0},
}
ACTION_KEYS = ("forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use")
WEIGHT = 0.3  # Dream demos count this much compared with real play.


def situation(obs: dict) -> np.ndarray | None:
    """The moment, in a few numbers; None when nobody is close enough to matter."""
    me = obs.get("self") or {}
    foes = [e for e in obs.get("entities") or [] if (e.get("player") or e.get("hostile") or "mannequin" in str(e.get("type", "")))
            and not e.get("sameTeam") and e.get("visible", True)]
    if not foes:
        return None
    e = min(foes, key=lambda e: e.get("dx", 0) ** 2 + e.get("dz", 0) ** 2)
    d = math.sqrt(e.get("dx", 0) ** 2 + e.get("dy", 0) ** 2 + e.get("dz", 0) ** 2)
    if d > 6:
        return None
    yaw = math.radians(float(me.get("yaw", 0)))
    ahead = -math.sin(yaw) * e.get("dx", 0) + math.cos(yaw) * e.get("dz", 0)
    right = -math.cos(yaw) * e.get("dx", 0) - math.sin(yaw) * e.get("dz", 0)
    return np.array([
        float(me.get("health", 20)) / 20, d / 6, ahead / 6, right / 6, float(e.get("health", 20) or 20) / 20,
        1.0 if 0 <= e.get("hitMe", -1) < 20 else 0.0, 1.0 if 0 <= e.get("hitByMe", -1) < 20 else 0.0,
        float(me.get("attackReady", 1.0)), 1.0 if e.get("blocking") else 0.0, 1.0 if me.get("onGround", True) else 0.0,
    ], dtype=np.float32)


def action_vec(inp: dict) -> np.ndarray:
    keys = set(inp.get("keys") or [])
    v = [1.0 if k in keys else 0.0 for k in ACTION_KEYS]
    v[ACTION_KEYS.index("attack")] = max(v[ACTION_KEYS.index("attack")], 1.0 if int(inp.get("attacks", 0) or 0) > 0 else 0.0)
    v[ACTION_KEYS.index("use")] = max(v[ACTION_KEYS.index("use")], 1.0 if int(inp.get("uses", 0) or 0) > 0 else 0.0)
    return np.array(v, dtype=np.float32)


def outcome(events_ahead: list) -> float:
    dealt = taken = 0.0
    for evs in events_ahead:
        for ev in evs or []:
            t, d = ev.get("type"), ev.get("data") or {}
            if t in ("damage_dealt", "tg_damage_dealt"):
                dealt += float(d.get("amount", 0) or 0) * (t == "damage_dealt")
            elif t == "damage_taken":
                taken += float(d.get("amount", 0) or 0)
    return (dealt - taken) / 10.0


def samples(root: str, since: float = 0.0, limit: int = 40000):
    """(X situations, A actions, Y outcomes, refs) from recordings made after `since` (epoch s)."""
    from .learn.recording import read_chunk

    X, A, Y, refs = [], [], [], []
    for d in sorted(glob.glob(os.path.join(root, "*", "meta.json"))):
        if os.path.getmtime(d) < since:
            continue
        try:
            meta = json.load(open(d, encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if meta.get("source") in ("dream", "player"):
            continue  # Only its own (and your) real play: dreams and other players' views have no outcomes.
        rows = []
        for ch in sorted(glob.glob(os.path.join(os.path.dirname(d), "chunk-*.bin.gz"))):
            rows += [r for _, r, _ in read_chunk(ch)]
        for i in range(len(rows) - HORIZON):
            s = situation(rows[i].get("obs") or {})
            if s is None:
                continue
            X.append(s)
            A.append(action_vec(rows[i].get("input") or {}))
            Y.append(outcome([rows[j].get("events") for j in range(i + 1, i + 1 + HORIZON)]))
            refs.append(rows[i])
            if len(X) >= limit:
                break
    if not X:
        return None
    return np.stack(X), np.stack(A), np.array(Y, dtype=np.float32), refs


class DreamModel:
    """A tiny MLP: (situation, action) -> expected outcome of the next second."""

    def __init__(self, n_in: int, seed: int = 0):
        rng = np.random.default_rng(seed)
        self.w1 = rng.normal(0, 0.3, (n_in, 32)).astype(np.float32)
        self.b1 = np.zeros(32, dtype=np.float32)
        self.w2 = rng.normal(0, 0.3, (32, 1)).astype(np.float32)
        self.b2 = np.zeros(1, dtype=np.float32)

    def predict(self, x):
        h = np.tanh(x @ self.w1 + self.b1)
        return (h @ self.w2 + self.b2)[:, 0]

    def fit(self, x, y, epochs: int = 300, lr: float = 0.05):
        n = len(x)
        for _ in range(epochs):
            h = np.tanh(x @ self.w1 + self.b1)
            p = (h @ self.w2 + self.b2)[:, 0]
            g = (p - y)[:, None] * (2.0 / n)
            gw2 = h.T @ g
            gb2 = g.sum(0)
            gh = g @ self.w2.T * (1 - h * h)
            self.w1 -= lr * (x.T @ gh)
            self.b1 -= lr * gh.sum(0)
            self.w2 -= lr * gw2
            self.b2 -= lr * gb2


def embed(observations: list, profile: str, dims: int = 16):
    """The main network's memory state after each moment (one step from a fresh state), compressed
    to `dims` numbers. None if there's no trained network."""
    try:
        import torch

        from .learn import checkpoint, features
        from .learn.model import to_tensors

        model, _ = checkpoint.load(profile, "model", "cpu")
        model.eval()
    except Exception:
        return None
    out = []
    with torch.no_grad():
        for i in range(0, len(observations), 256):
            feats = [features.featurize(o) for o in observations[i:i + 256]]
            batch = {k: np.stack([f[k] for f in feats])[:, None] for k in feats[0]}
            n = len(feats)
            batch["frames"] = np.zeros((n, 1, features.FRAME_SIZE, features.FRAME_SIZE, 3), dtype=np.uint8)
            batch["has_frame"] = np.zeros((n, 1), dtype=np.float32)
            _, state = model(to_tensors(batch, "cpu"), None)
            out.append(state.reshape(n, -1).float().numpy())
    e = np.concatenate(out)
    e = e - e.mean(0)
    _, _, vt = np.linalg.svd(e, full_matrices=False)
    z = e @ vt[:dims].T
    return (z / (z.std(0) + 1e-6)).astype(np.float32)


def dream(root: str, since: float = 0.0, out_dir: str | None = None, seed: int = 0, profile: str | None = None) -> dict:
    data = samples(root, since)
    if data is None:
        return {"moments": 0, "better": 0, "why": "no fights in today's recordings"}
    X, A, Y, refs = data
    n = len(X)
    if n < 200:
        return {"moments": 0, "better": 0, "why": f"too little fighting to dream about ({n} moments)"}
    rng = np.random.default_rng(seed)
    idx = rng.permutation(n)
    split = int(n * 0.8)
    tr, va = idx[:split], idx[split:]
    yv = Y[va]

    def fit(Xs):
        XA_ = np.concatenate([Xs, A], axis=1)
        m = DreamModel(XA_.shape[1], seed)
        m.fit(XA_[tr], Y[tr])
        # Can it predict moments it didn't learn from? If not, its imagination isn't worth trusting.
        pv = m.predict(XA_[va])
        c = float(np.corrcoef(pv, yv)[0, 1]) if np.std(pv) > 1e-6 and np.std(yv) > 1e-6 else 0.0
        return m, c

    model, corr = fit(X)
    used_network = False
    if profile:
        emb = embed([r.get("obs") or {} for r in refs], profile)
        if emb is not None:
            Xn = np.concatenate([X, emb], axis=1)
            model_n, corr_n = fit(Xn)
            if corr_n > corr:  # The network's sense of the moment only if it predicts better.
                X, model, corr, used_network = Xn, model_n, corr_n, True
    XA = np.concatenate([X, A], axis=1)
    if corr < 0.3:
        return {"moments": 0, "better": 0, "accuracy": round(corr, 2), "why": "its imagination isn't good enough yet"}
    hard = [i for i in range(n) if Y[i] < -0.05]
    rows = []
    for i in hard:
        real = float(model.predict(XA[i:i + 1])[0])
        best, best_v = None, real
        for name, alt in ALTERNATIVES.items():
            v = float(model.predict(np.concatenate([X[i], action_vec(alt)])[None, :])[0])
            if v > best_v + 0.1:
                best, best_v = name, v
        if best is not None:
            row = dict(refs[i])
            inp = dict(row.get("input") or {})
            inp.update(ALTERNATIVES[best])
            rows.append({"input": inp, "obs": row.get("obs"), "events": [], "frameAge": -1, "w": WEIGHT, "dream": best})
    result = {"moments": len(hard), "better": len(rows), "accuracy": round(corr, 2), "network": used_network}
    if rows and out_dir is not None:
        from . import players

        d = os.path.join(out_dir, "dream-" + time.strftime("%Y%m%d-%H%M%S"))
        players.write(d, rows, {"id": os.path.basename(d), "name": "Dreams " + time.strftime("%Y-%m-%d")})
        with open(os.path.join(d, "meta.json"), encoding="utf-8") as f:
            meta = json.load(f)
        meta.update(source="dream", weight=WEIGHT, complete=True, created=time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime()))
        with open(os.path.join(d, "meta.json"), "w", encoding="utf-8") as f:
            json.dump(meta, f, indent=1)
        result["recording"] = os.path.basename(d)
        tried = {}
        for r in rows:
            tried[r["dream"]] = tried.get(r["dream"], 0) + 1
        result["tried"] = tried
    return result


def main(argv) -> int:
    ap = argparse.ArgumentParser(prog="dream")
    ap.add_argument("--recordings", required=True)
    ap.add_argument("--since", type=float, default=0.0, help="epoch seconds")
    ap.add_argument("--profile", help="the AI's folder: use its network's sense of each moment")
    a = ap.parse_args(argv)
    print(json.dumps(dream(a.recordings, a.since, a.recordings, profile=a.profile)))
    sys.stdout.flush()
    return 0
