"""Realism (M12): how human the AI's input looks, and what style you prefer.

Three small, fast models over 2-second windows of input (keys, clicks, camera), no pictures:

- **Realism critic**: tells your own recordings (human) from the AI's and teachers' play.
  Its score (0 = robotic, 1 = human) is shown in the Realism screen and, during practice, a
  small reward for human-looking input (DESIGN 4.10).
- **Style model**: learns your taste from "which was better?" picks between two clips
  (Bradley-Terry on the same features) and also adds a small practice reward.
- **Humanizer tuning**: measures your own turn speed, acceleration and aim wobble, so the
  AI's human limits can match yours.

`python -m minigamebots_brain realism --profile P --recordings DIR` trains what the data allows
and writes <profile>/realism.json; it prints a JSON summary.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import os
import sys

import numpy as np

WINDOW = 40  # Ticks (2 s).
FEATURES = ("cam_mean", "cam_std", "cam_jerk", "cam_max", "still_moving", "pitch_mean", "yaw_flips",
            "key_changes", "attack_rate", "attack_regular", "forward", "strafe_changes", "snaps")


def window_features(inputs: list[dict]) -> np.ndarray:
    """Features of a window of inputs ({"keys", "yaw", "pitch", "attacks"} per tick)."""
    n = max(1, len(inputs))
    yaw = np.array([float(i.get("yaw", 0.0)) for i in inputs] or [0.0])
    pitch = np.array([float(i.get("pitch", 0.0)) for i in inputs] or [0.0])
    keys = [frozenset(i.get("keys") or ()) for i in inputs] or [frozenset()]
    cam = np.hypot(yaw, pitch)
    moving = np.array([bool(k & {"forward", "back", "left", "right"}) for k in keys])
    attacks = [t for t, i in enumerate(inputs) if "attack" in (i.get("keys") or ()) or int(i.get("attacks", 0)) > 0]
    gaps = np.diff(attacks) if len(attacks) > 2 else np.array([0.0])
    strafe = [("left" in k) - ("right" in k) for k in keys]
    f = [
        cam.mean(), cam.std(), np.abs(np.diff(cam)).mean() if n > 1 else 0.0, cam.max(),
        float((moving & (cam < 1e-3)).mean()), np.abs(pitch).mean(),
        float((np.diff(np.sign(yaw[np.abs(yaw) > 0.05])) != 0).sum()) / n if (np.abs(yaw) > 0.05).sum() > 1 else 0.0,
        sum(a != b for a, b in zip(keys, keys[1:])) / n, len(attacks) / n,
        float(gaps.std() / (gaps.mean() + 1e-3)) if len(attacks) > 2 else 0.0,
        float(np.mean(["forward" in k for k in keys])), sum(a != b for a, b in zip(strafe, strafe[1:])) / n,
        float((cam > 30).mean()),
    ]
    return np.log1p(np.abs(np.array(f, dtype=np.float64)))


def recording_windows(directory: str) -> list[np.ndarray]:
    """Feature windows of one recording (skipping camera jumps such as teleports)."""
    from .learn.recording import read_chunk

    inputs = []
    for path in sorted(glob.glob(os.path.join(directory, "chunk-*.bin.gz"))):
        for _, rec, _ in read_chunk(path):
            if not rec.get("cameraJump"):
                inputs.append(rec.get("input") or {})
    return [window_features(inputs[i:i + WINDOW]) for i in range(0, len(inputs) - WINDOW + 1, WINDOW)]


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30, 30)))


def fit_logistic(x: np.ndarray, y: np.ndarray, steps: int = 400, l2: float = 1e-2) -> np.ndarray:
    """Weights (bias last) of a logistic regression, by gradient descent (data is small)."""
    xb = np.hstack([x, np.ones((len(x), 1))])
    w = np.zeros(xb.shape[1])
    for _ in range(steps):
        p = _sigmoid(xb @ w)
        w -= 0.5 * (xb.T @ (p - y) / len(y) + l2 * np.r_[w[:-1], 0.0])
    return w


class Realism:
    """Loaded from <profile>/realism.json; scores windows of input."""

    def __init__(self, data: dict):
        self.data = data
        self.mean = np.array(data.get("mean", [0.0] * len(FEATURES)))
        self.std = np.array(data.get("std", [1.0] * len(FEATURES)))
        self.critic = np.array(data["critic"]) if data.get("critic") else None
        self.style = np.array(data["style"]) if data.get("style") else None

    @classmethod
    def load(cls, profile: str) -> "Realism | None":
        try:
            with open(os.path.join(profile, "realism.json"), encoding="utf-8") as f:
                return cls(json.load(f))
        except (OSError, ValueError, KeyError):
            return None

    def _z(self, f):
        return (f - self.mean) / self.std

    def human_score(self, inputs: list[dict]) -> float | None:
        """0 (robotic) .. 1 (human) for a window of the AI's inputs."""
        if self.critic is None:
            return None
        z = self._z(window_features(inputs))
        return float(_sigmoid(z @ self.critic[:-1] + self.critic[-1]))

    def style_score(self, inputs: list[dict]) -> float | None:
        """-1 .. 1: how much you'd like this window, from your "which was better?" picks."""
        if self.style is None:
            return None
        return float(np.tanh(self._z(window_features(inputs)) @ self.style))


def tune_humanizer(windows_inputs: list[list[dict]]) -> dict | None:
    """Turn speed, acceleration and aim wobble measured from human input (per-second units)."""
    yaw = np.array([float(i.get("yaw", 0.0)) for w in windows_inputs for i in w])
    if len(yaw) < 200:
        return None
    speed = np.abs(yaw) * 20  # Degrees per second.
    accel = np.abs(np.diff(yaw)) * 400
    small = np.abs(yaw[(np.abs(yaw) > 0) & (np.abs(yaw) < 0.5)])
    return {"maxTurnSpeed": round(float(np.clip(np.percentile(speed, 99), 150, 3000)), 0),
            "maxTurnAccel": round(float(np.clip(np.percentile(accel, 99), 1500, 40000)), 0),
            "aimNoise": round(float(np.clip(small.mean() if len(small) else 0.12, 0.02, 0.5)), 3)}


def train(profile: str, recordings_root: str) -> dict:
    human, machine, human_inputs = [], [], []
    by_id = {}
    for d in sorted(glob.glob(os.path.join(recordings_root, "*", ""))):
        try:
            with open(os.path.join(d, "meta.json"), encoding="utf-8") as f:
                meta = json.load(f)
        except (OSError, ValueError):
            continue
        wins = recording_windows(d)
        by_id[os.path.basename(os.path.normpath(d))] = d
        (human if meta.get("source", "human") == "human" else machine).extend(wins)
        if meta.get("source", "human") == "human":
            from .learn.recording import read_chunk

            for path in sorted(glob.glob(os.path.join(d, "chunk-*.bin.gz"))):
                human_inputs.append([r.get("input") or {} for _, r, _ in read_chunk(path) if not r.get("cameraJump")])
    allf = np.array(human + machine) if human or machine else np.zeros((0, len(FEATURES)))
    out = {"features": list(FEATURES), "window": WINDOW, "human_windows": len(human), "machine_windows": len(machine)}
    if len(allf):
        out["mean"] = allf.mean(0).tolist()
        out["std"] = (allf.std(0) + 1e-6).tolist()
    if len(human) >= 5 and len(machine) >= 5:
        z = (allf - np.array(out["mean"])) / np.array(out["std"])
        y = np.r_[np.ones(len(human)), np.zeros(len(machine))]
        w = fit_logistic(z, y)
        out["critic"] = w.tolist()
        out["critic_accuracy"] = round(float(((_sigmoid(np.hstack([z, np.ones((len(z), 1))]) @ w) > 0.5) == y).mean()), 3)
    style = train_style(profile, out)
    if style is not None:
        out["style"], out["style_pairs"] = style
    tuned = tune_humanizer(human_inputs)
    if tuned:
        out["humanizer"] = tuned
    old = Realism.load(profile)
    if old is not None and "style" not in out and old.data.get("style"):
        out["style"], out["style_pairs"] = old.data["style"], old.data.get("style_pairs", 0)
    with open(os.path.join(profile, "realism.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
    return {k: v for k, v in out.items() if k not in ("mean", "std", "critic", "style", "features")}


def train_style(profile: str, norm: dict):
    """Bradley-Terry weights from <profile>/preferences.jsonl: {"a": [inputs], "b": [inputs], "pick": "a"/"b"/"same"}."""
    path = os.path.join(profile, "preferences.jsonl")
    if not os.path.isfile(path) or "mean" not in norm:
        return None
    mean, std = np.array(norm["mean"]), np.array(norm["std"])
    diffs, ys = [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                p = json.loads(line)
            except ValueError:
                continue
            fa = (window_features(p["a"]) - mean) / std
            fb = (window_features(p["b"]) - mean) / std
            y = {"a": 1.0, "b": 0.0}.get(p.get("pick"), 0.5)
            diffs += [fa - fb, fb - fa]
            ys += [y, 1 - y]
    if len(diffs) < 4:
        return None
    w = fit_logistic(np.array(diffs), np.array(ys), l2=0.05)[:-1]  # Symmetric data: no bias.
    return w.tolist(), len(diffs) // 2


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="minigamebots_brain realism")
    p.add_argument("--profile", required=True)
    p.add_argument("--recordings", required=True, help="the recordings folder")
    args = p.parse_args(argv)
    try:
        print(json.dumps({"type": "done", **train(args.profile, args.recordings)}))
        return 0
    except Exception as e:  # Shown in the mod.
        print(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
