"""Self-play: a past version of the AI steers a Training Grounds dummy against the AI.

Training Grounds can field a "self" opponent: a dummy that obeys movement, look and attack
commands from the AI's own client. Both sides are decided here, in the same brain, so self-play
needs only one Minecraft client (the low-RAM plan in DESIGN 7.1):

1. The AI's observation includes the dummy (as an entity). `opponent_view` turns that around
   into what the dummy would observe: itself as "self", the AI as an enemy player.
2. An opponent picked from the AI's saved versions (core/history) decides the dummy's input.
   Older versions are the league of past selves: beating them all keeps it from forgetting
   how to beat earlier styles. The scripted teacher takes about 30% of rounds, and all of them
   when there are no usable versions yet (or the AI isn't a Lite network, which can play
   without pictures).
3. The input goes back with the AI's own action as "puppet"; the client forwards it.
"""

from __future__ import annotations

import glob
import json
import os
import random

EYE = 1.62


def opponent_view(obs: dict, puppet_id: int) -> dict | None:
    """The observation from the dummy's side, or None if it can't be seen."""
    me = obs.get("self") or {}
    ents = obs.get("entities") or []
    pup = next((e for e in ents if e.get("id") == puppet_id), None)
    if pup is None:
        return None
    px, py, pz = pup.get("dx", 0.0), pup.get("dy", 0.0), pup.get("dz", 0.0)  # Its feet, from my eyes.
    held = next((i.get("id") for i in obs.get("inventory") or [] if i.get("slot") == obs.get("selectedSlot")), "")
    view_self = {
        "health": pup.get("health", 20.0), "maxHealth": pup.get("maxHealth", 20.0), "food": 20, "saturation": 5.0,
        "yaw": pup.get("yaw", 0.0), "pitch": pup.get("pitch", 0.0), "onGround": pup.get("onGround", True),
        "vx": pup.get("vx", 0.0), "vy": pup.get("vy", 0.0), "vz": pup.get("vz", 0.0),
        "attackReady": pup.get("attackReady", 1.0), "armor": pup.get("armor", 0),
    }
    # Me from its eyes: my feet are EYE below my eyes, its eyes EYE above its feet.
    others = [{
        "id": -1, "type": "minecraft:player", "player": True, "name": "opponent", "visible": True,
        "dx": -px, "dy": -py - 2 * EYE, "dz": -pz, "vx": me.get("vx", 0.0), "vy": me.get("vy", 0.0),
        "vz": me.get("vz", 0.0), "health": me.get("health", 20.0), "maxHealth": me.get("maxHealth", 20.0),
        "heldItem": held or "", "armor": me.get("armor", 0), "hostile": False, "hitMe": -1, "hitByMe": -1,
        "blocking": bool(me.get("usingItem")) and "shield" in str(held),
    }]
    for e in ents:
        if e is pup:
            continue
        o = dict(e)
        o["dx"], o["dy"], o["dz"] = e.get("dx", 0.0) - px, e.get("dy", 0.0) - py - EYE, e.get("dz", 0.0) - pz
        o["hitMe"] = o["hitByMe"] = -1
        others.append(o)
    others.sort(key=lambda e: e["dx"] ** 2 + e["dy"] ** 2 + e["dz"] ** 2)
    return {"self": view_self, "entities": others, "selectedSlot": 0,
            "inventory": [{"slot": 0, "id": pup.get("heldItem") or "minecraft:iron_sword", "count": 1}],
            "_events": []}


class SelfPlay:
    """Plays the puppet dummy for one AI profile."""

    def __init__(self, profile: str, device: str | None = None, seed: int | None = None):
        self.profile = profile
        self.device = device
        self.rng = random.Random(seed)
        self.versions = self._versions()
        self.opponent = None
        self.opponent_name = ""
        self.round = None
        self.since_attack = 100  # Ticks since the puppet swung (the game charges attacks over 12.5 ticks).

    def _versions(self) -> list[str]:
        """Saved Lite versions (newest first) plus the current one: folders holding a core."""
        out = []
        cores = [os.path.join(self.profile, "core")] + sorted(
            glob.glob(os.path.join(self.profile, "core", "history", "*")), reverse=True)
        for c in cores:
            try:
                with open(os.path.join(c, "model.json"), encoding="utf-8") as f:
                    arch = json.load(f).get("arch", "lite")
            except (OSError, ValueError):
                continue
            if not str(arch).startswith("vpt") and os.path.isfile(os.path.join(c, "model.safetensors")):
                out.append(c)
        return out

    def _pick(self) -> None:
        from .policies.teacher import TeacherPolicy

        # The scripted teacher is in the pool too: a past self can be too weak to be worth beating.
        if self.versions and self.rng.random() >= 0.3:
            # Mostly recent versions, sometimes old ones (so it doesn't forget how to beat them).
            n = len(self.versions)
            i = min(int(self.rng.expovariate(1.0 / max(1.0, n / 3))), n - 1)
            core = self.versions[i]
            try:
                from .policies.learned import LearnedPolicy

                self.opponent = LearnedPolicy(core, temperature=1.0, device=self.device)
                self.opponent_name = "current" if i == 0 else os.path.basename(core)
                return
            except Exception:
                self.versions.pop(i)
        self.opponent = TeacherPolicy(style=self.rng.choice(["mixed", "crits", "wtap"]))
        self.opponent_name = "teacher"

    def act(self, obs: dict, puppet_id: int, round_id=None) -> dict | None:
        if self.opponent is None or round_id != self.round:
            self.round = round_id
            self._pick()
        self.since_attack += 1
        view = opponent_view(obs, puppet_id)
        if view is None:
            return {"id": puppet_id, "keys": [], "yaw": 0.0, "pitch": 0.0}
        view["self"]["attackReady"] = min(1.0, self.since_attack / 12.5)
        a = self.opponent.act(view, None)
        if "attack" in a.keys:
            self.since_attack = 0
        return {"id": puppet_id, "keys": sorted(a.keys), "yaw": float(a.yaw), "pitch": float(a.pitch),
                "opponent": self.opponent_name}
