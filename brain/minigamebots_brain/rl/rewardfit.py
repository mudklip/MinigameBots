"""Reward weights learned from your own clips (preference learning).

Around every moment you marked in a recording ("good" or "mistake"), it counts what each of a
skill's rules would have counted (the same events and behaviours the trainer sees). Then it fits
weights so that the good moments score higher than the mistakes (logistic regression with a pull
toward the current weights), and reports how often each set of weights ranks a good moment above a
mistake (1.0 = always, 0.5 = a coin flip):

  {"windows": {"good", "mistake"}, "now": 0.62, "fitted": 0.81,
   "rules": [{"index", "name", "current", "suggested"}], "why": "..."}

Suggestions keep the overall size of the current weights; they're only offered when they clearly
rank better and there are enough marks (3+ of each). A check of the rewards before training too:
"now" alone says whether the current rules agree with you.

CLI: python -m minigamebots_brain fitrewards --skill FILE --recordings DIR
"""

import argparse
import glob
import json
import os
import sys
from types import SimpleNamespace

import numpy as np

from .objective import matches

RADIUS = 40  # ticks around a mark (as recording.MARK_RADIUS)


def _action(inp: dict):
    return SimpleNamespace(keys=set(inp.get("keys") or []), attacks=int(inp.get("attacks", 0) or 0),
                           uses=int(inp.get("uses", 0) or 0), yaw=float(inp.get("yaw", 0) or 0),
                           pitch=float(inp.get("pitch", 0) or 0))


def _units(rule: dict, events: list) -> float:
    total = 0.0
    for ev in events:
        if ev.get("type") != rule.get("event"):
            continue
        data = ev.get("data") or {}
        if not matches(rule.get("where"), data):
            continue
        per = rule.get("per", "event")
        if per == "event":
            total += 1.0
        else:
            try:
                total += abs(float(data.get(per, 0.0)))
            except (TypeError, ValueError):
                pass
    return total


def windows(skill: dict, root: str):
    """(X [n, rules], y [n]) from every marked moment in the recordings under root."""
    from ..learn.recording import read_chunk
    from .skills import Behaviours

    rules = [r for r in skill.get("rules") or [] if not r.get("off")]
    X, y = [], []
    for meta_path in sorted(glob.glob(os.path.join(root, "*", "meta.json"))):
        try:
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        except (OSError, ValueError):
            continue
        marks = [m for m in meta.get("marks") or [] if m.get("type") in ("good", "mistake")]
        if not marks:
            continue
        rows = {}
        for ch in sorted(glob.glob(os.path.join(os.path.dirname(meta_path), "chunk-*.bin.gz"))):
            for t, row, _ in read_chunk(ch):
                rows[t] = row
        beh = Behaviours(skill)
        per_tick = {}
        for t in sorted(rows):
            row = rows[t]
            game = row.get("events") or []
            evs = list(game) + beh.events(row.get("obs") or {}, _action(row.get("input") or {}), game)
            per_tick[t] = [_units(r, evs) for r in rules]
        for m in marks:
            t0 = int(m.get("tick", 0))
            vec = np.zeros(len(rules))
            for t in range(t0 - RADIUS, t0 + RADIUS + 1):
                if t in per_tick:
                    vec += per_tick[t]
            X.append(vec)
            y.append(1.0 if m["type"] == "good" else 0.0)
    if not X:
        return None, None, rules
    return np.array(X), np.array(y), rules


def ranking(w, X, y) -> float:
    """How often a good moment scores above a mistake (ties count half)."""
    s = X @ w
    good, bad = s[y == 1], s[y == 0]
    if len(good) == 0 or len(bad) == 0:
        return 0.5
    diff = good[:, None] - bad[None, :]
    return float(((diff > 0).sum() + 0.5 * (diff == 0).sum()) / diff.size)


def fit(skill: dict, root: str, l2: float = 0.5) -> dict:
    X, y, rules = windows(skill, root)
    if X is None:
        return {"windows": {"good": 0, "mistake": 0}, "why": "No marked moments yet: mark good moments and mistakes in your recordings."}
    counts = {"good": int(y.sum()), "mistake": int(len(y) - y.sum())}
    w0 = np.array([float(r.get("value", 0)) for r in rules])
    now = ranking(w0, X, y)
    out = {"windows": counts, "now": round(now, 3)}
    if counts["good"] < 3 or counts["mistake"] < 3:
        out["why"] = "Needs at least 3 good moments and 3 mistakes marked."
        return out
    # Standardise the counts, fit a logistic model pulled toward the current weights.
    scale = X.std(0) + 1e-6
    Z = (X - X.mean(0)) / scale
    prior = w0 * scale
    norm = np.abs(prior).sum() or 1.0
    prior = prior / norm
    w = prior.copy()
    for _ in range(2000):
        p = 1.0 / (1.0 + np.exp(-(Z @ w)))
        grad = Z.T @ (p - y) / len(y) + l2 * (w - prior)
        w -= 0.2 * grad
    fitted_w = w / scale
    # Keep the overall size of the current weights.
    size = np.abs(w0).sum() or 1.0
    fitted_w = fitted_w * size / (np.abs(fitted_w).sum() or 1.0)
    fitted = ranking(fitted_w, X, y)
    out["fitted"] = round(fitted, 3)
    if fitted < now + 0.05:
        out["why"] = "Your current rewards already agree with your marks about as well as anything this can find."
        return out
    out["rules"] = [{"index": i, "name": r.get("note") or r.get("event"), "current": round(float(w0[i]), 3),
                     "suggested": round(float(fitted_w[i]), 3)} for i, r in enumerate(rules)]
    out["why"] = f"These weights rank your good moments above your mistakes {round(fitted * 100)}% of the time (now {round(now * 100)}%)."
    return out


def main(argv) -> int:
    ap = argparse.ArgumentParser(prog="fitrewards")
    ap.add_argument("--skill", required=True, help="skill definition (JSON file)")
    ap.add_argument("--recordings", required=True)
    a = ap.parse_args(argv)
    with open(a.skill, encoding="utf-8") as f:
        skill = json.load(f)
    print(json.dumps(fit(skill, a.recordings)))
    sys.stdout.flush()
    return 0
