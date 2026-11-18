"""Fast-sim aim drill: a tiny built-in simulator for pre-training aim, tracking and chasing.

There's no Minecraft here, just a player and a moving target on flat ground, simulated in
Python thousands of ticks per second. An expert (turn smoothly toward the target, walk in,
strafe at fighting distance, swing when the crosshair is on it and the attack is charged)
supplies the actions, and the network imitates them. It learns the basics of turning to and
tracking a target before it ever plays, then real recordings and practice fine-tune it.

Use: `python -m minigamebots_brain train --profile X --sim aim --steps 2000`, or mix with
recordings (the sim then supplies part of each batch).
"""

from __future__ import annotations

import math
import random

import numpy as np

from . import actions, features

EYE = 1.62
REACH = 3.0
MAX_TURN = 12.0  # Degrees per tick: fast but human.


def _wrap(a: float) -> float:
    return (a + 180.0) % 360.0 - 180.0


class AimSim:
    """Same interface as SequenceSampler (len, batch) so the trainer can use it directly."""

    def __init__(self, length: int = 32, seed: int | None = None):
        self.length = length
        self.rng = random.Random(seed)

    def __len__(self) -> int:
        return 10 ** 9  # Endless.

    def _episode(self) -> list[tuple[dict, dict]]:
        r = self.rng
        yaw, pitch = r.uniform(-180, 180), r.uniform(-30, 30)
        px = pz = 0.0
        ang = math.radians(r.uniform(0, 360))
        dist = r.uniform(1.5, 10.0)
        tx, tz = px + math.sin(ang) * dist, pz + math.cos(ang) * dist
        ty = r.choice([0.0, 0.0, 0.0, 1.0, -1.0])  # Mostly level, sometimes a block up or down.
        speed = r.choice([0.0, 0.05, 0.12, 0.2])
        heading = r.uniform(0, 2 * math.pi)
        charge = 1.0
        strafe = r.choice([-1, 1])
        out = []
        for t in range(self.length + 3):
            if r.random() < 0.05:
                heading = r.uniform(0, 2 * math.pi)
            tx += math.cos(heading) * speed
            tz += math.sin(heading) * speed
            if r.random() < 0.04:
                strafe = -strafe
            dx, dz = tx - px, tz - pz
            dy = ty - EYE
            flat = math.hypot(dx, dz)
            obs = {
                "self": {"yaw": yaw, "pitch": pitch, "health": 20.0, "maxHealth": 20.0, "food": 20,
                         "onGround": True, "attackReady": charge, "critReady": 0.0},
                "selectedSlot": 0,
                "inventory": [{"slot": 0, "id": "minecraft:diamond_sword", "count": 1}],
                "entities": [{"id": 1, "type": "minecraft:mannequin", "dx": dx, "dy": dy, "dz": dz,
                              "vx": math.cos(heading) * speed, "vy": 0.0, "vz": math.sin(heading) * speed,
                              "visible": True, "health": 20.0, "maxHealth": 20.0, "hostile": False,
                              "player": False, "hitMe": -1, "hitByMe": -1}],
            }
            # The expert.
            want_yaw = math.degrees(math.atan2(-dx, dz))
            want_pitch = -math.degrees(math.atan2(dy + 0.9, max(flat, 1e-3)))
            yaw_err, pitch_err = _wrap(want_yaw - yaw), want_pitch - pitch
            d_yaw = max(-MAX_TURN, min(MAX_TURN, 0.6 * yaw_err))
            d_pitch = max(-MAX_TURN, min(MAX_TURN, 0.6 * pitch_err))
            keys = []
            if flat > REACH - 0.5 and abs(yaw_err) < 60:
                keys += ["forward", "sprint"]
            elif flat < REACH + 1 and charge < 0.9:
                keys.append("left" if strafe > 0 else "right")
            aimed = abs(yaw_err) < max(4.0, math.degrees(math.atan2(0.3, max(flat, 0.5))))
            dist3 = math.sqrt(flat ** 2 + (dy + 0.9) ** 2)
            if aimed and dist3 < REACH and charge >= 0.95:
                keys.append("attack")
                charge = 0.0
            inp = {"keys": keys, "attacks": 1 if "attack" in keys else 0, "uses": 0, "yaw": d_yaw, "pitch": d_pitch}
            out.append((obs, inp))
            # Physics.
            yaw = _wrap(yaw + d_yaw)
            pitch = max(-90.0, min(90.0, pitch + d_pitch))
            move = math.radians(yaw)
            step = 0.28 if "sprint" in keys else 0.22 if "forward" in keys else 0.0
            px += -math.sin(move) * step
            pz += math.cos(move) * step
            if "left" in keys or "right" in keys:
                side = 0.2 * (1 if "left" in keys else -1)
                px += math.cos(move) * side
                pz += math.sin(move) * side
            charge = min(1.0, charge + 1 / 12.5)
        return out[3:]

    def window(self) -> dict:
        ep = self._episode()
        feats = [features.featurize(o) for o, _ in ep]
        targets = [actions.encode_input(i) for _, i in ep]
        out = features.batch(feats)
        out.update({f"target_{k}": v for k, v in features.batch(targets).items()})
        n = len(ep)
        out["frames"] = np.zeros((n, features.FRAME_SIZE, features.FRAME_SIZE, 3), dtype=np.uint8)
        out["has_frame"] = np.zeros(n, dtype=np.float32)
        out["target_mark"] = np.zeros(n, dtype=np.int64)
        out["weight"] = np.ones(n, dtype=np.float32)
        out["camera_ok"] = np.ones(n, dtype=np.float32)
        return out

    def batch(self, size: int) -> dict:
        windows = [self.window() for _ in range(size)]
        return {k: np.stack([w[k] for w in windows]) for k in windows[0]}
