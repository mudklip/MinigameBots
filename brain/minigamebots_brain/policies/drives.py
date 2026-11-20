"""Drives (Phase D): what the AI is about, and which need wins at each moment.

A *mode* is its overall attitude:
    neutral  - lives like a normal player (farm, mine, build, stock up, stash valuables); fights
               hostile mobs and anyone who attacks it for real, never starts fights with players
    hunter   - "kill players": gears up first (grinding ores), then looks for players and fights
               the ones it can beat (judged on its *estimated* health of them), retreats to heal
    guard    - stays near its base or the player it protects; fights anything hostile nearby
    peaceful - never fights players; runs from real attacks; still handles mobs

Needs compete every half second: fear (low health, bad odds), anger (a real attack), hunger,
duty (the mode's goal) and boredom (free play). Each mode weights them differently; the
strongest one above its threshold decides.

The hit judge tells a joke hit (a friend's punch) from a real attack: weapon in hand, damage,
repeated hits, crits, who it is, whether they keep coming.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re

MODES = {
    "neutral": {"label": "Neutral", "help": "Lives like a normal player; fights only real attacks and monsters",
                "anger": 1.0, "fear": 1.0, "duty": 0.6, "starts_fights": False,
                "likes": {"farm": 1.0, "mine": 1.0, "build": 0.6, "hunt": 0.5, "stash": 0.4, "explore": 0.5}},
    "hunter": {"label": "Hunt players", "help": "Gears up, then finds players and fights the ones it can beat",
               "anger": 1.3, "fear": 0.8, "duty": 1.2, "starts_fights": True,
               "likes": {"mine": 1.0, "explore": 1.0, "hunt": 0.3}},
    "guard": {"label": "Guard", "help": "Stays near its base or friend and fights anything hostile nearby",
              "anger": 1.5, "fear": 0.7, "duty": 1.0, "starts_fights": False, "guard_radius": 16,
              "likes": {"build": 1.0, "farm": 0.6, "stash": 0.4}},
    "peaceful": {"label": "Peaceful", "help": "Never fights players: runs from real attacks; still deals with monsters",
                 "anger": 0.0, "fear": 1.4, "duty": 0.6, "starts_fights": False,
                 "likes": {"farm": 1.0, "build": 1.0, "explore": 0.6, "stash": 0.4}},
}
WEAPONS = ("_sword", "_axe", "trident", "mace", "bow", "crossbow")
GEAR_ARMOR = 15  # Armour points before a hunter goes looking for players.
NEW_HIT = 2  # "hitMe" (ticks since it hit me) at most this means a fresh hit.


def is_weapon(item: str) -> bool:
    return any(w in (item or "") for w in WEAPONS)


class HitJudge:
    """Real attack or joke? Per attacker, from the last 15 seconds."""

    WINDOW = 300

    def __init__(self):
        self.hits = {}  # id -> [(tick, damage, weapon, crit)]
        self.prev = {}
        self.verdict = {}  # id -> ("real"|"joke", why)

    def update(self, tick: int, obs: dict, events: list) -> None:
        dmg = sum(float((e.get("data") or {}).get("amount", 0)) for e in events if e.get("type") == "damage_taken")
        for e in obs.get("entities") or []:
            i = e.get("id")
            hit = e.get("hitMe", -1)
            before = self.prev.get(i, -1)
            self.prev[i] = hit
            fresh = 0 <= hit <= NEW_HIT and (before < 0 or before > hit)
            if not fresh:
                continue
            crit = not e.get("onGround", True) and float(e.get("vy", 0)) < 0
            lst = [h for h in self.hits.get(i, []) if tick - h[0] < self.WINDOW]
            lst.append((tick, dmg, is_weapon(e.get("heldItem", "")), crit))
            self.hits[i] = lst
            self.verdict[i] = self.judge(e, lst, obs)

    @staticmethod
    def judge(e: dict, hits: list, obs: dict) -> tuple:
        """Scores how serious the attacker is; 3 or more = real."""
        why = []
        score = 0.0
        if any(h[2] for h in hits):
            score += 2
            why.append("armed")
        total = sum(h[1] for h in hits)
        if total >= 3:
            score += 2
            why.append(f"{total:.0f} damage")
        if len(hits) >= 3:
            score += 2
            why.append(f"{len(hits)} hits")
        elif len(hits) == 2:
            score += 1
        if any(h[3] for h in hits):
            score += 1
            why.append("crit")
        mem = {p.get("id"): p for p in obs.get("people") or []}.get(e.get("id"))
        if mem is not None:
            # Memory: no benefit of the doubt for someone with a grudge; a bit more for someone liked.
            if mem.get("betrayals", 0) > 0 or float(mem.get("like", 0)) < -0.3:
                score += 3
                why.append("remembered")
            elif float(mem.get("like", 0)) > 0.3:
                score -= 1
        rel = e.get("relation", 0)
        if rel < 0 or e.get("enemyInsignia"):
            score += 3
            why.append("enemy")
        elif rel > 0 or e.get("sameTeam") or e.get("allyInsignia"):
            score -= 2
            why.append("friend")
        if e.get("hostile"):
            score += 3
        me = obs.get("self") or {}
        if float(me.get("health", 20)) <= 8 and total > 0:
            score += 1
        real = score >= 3
        return ("real" if real else "joke", ", ".join(why) or ("one light hit" if not real else "attacking"))

    def real(self, entity_id) -> bool:
        v = self.verdict.get(entity_id)
        return v is not None and v[0] == "real"

    def joke(self, entity_id) -> bool:
        v = self.verdict.get(entity_id)
        return v is not None and v[0] == "joke"


class Drives:
    def __init__(self, mode: str = "neutral"):
        self.mode = mode if mode in MODES else "neutral"
        self.cfg = MODES[self.mode]
        self.judge = HitJudge()
        self.feelings = {}
        self.focus = None  # The player a hunter has picked.

    @staticmethod
    def health_of(e: dict, memory: dict) -> tuple:
        """(estimate, how sure 0..1) of an entity's health: the counted estimate for players."""
        m = memory.get(e.get("id"))
        if m is not None:
            return float(m.get("est", e.get("health", 20))), float(m.get("sure", 0.5))
        return float(e.get("health", 20)), 1.0

    @staticmethod
    def recall(obs: dict, e: dict | None) -> dict:
        """What it remembers about this entity (empty if nothing)."""
        if not e:
            return {}
        return next((p for p in obs.get("people") or [] if p.get("id") == e.get("id")), {})

    def prey(self, obs: dict):
        """A hunter's best target: a visible non-friend player it can probably beat."""
        memory = {m.get("id"): m for m in obs.get("healthMemory") or []}
        best, best_score = None, -1e9
        for e in obs.get("entities") or []:
            if not e.get("player") or not e.get("visible", True) or e.get("sameTeam") or e.get("relation", 0) > 0 \
                    or e.get("allyInsignia"):
                continue
            d = math.sqrt(e.get("dx", 0) ** 2 + e.get("dy", 0) ** 2 + e.get("dz", 0) ** 2)
            hp, sure = self.health_of(e, memory)
            odds = float(e.get("fightOdds", 0.0))
            # Weak (or probably weak) targets close by first; unsure counts as a bit healthier.
            score = odds * 2 - d * 0.05 - (hp + (1 - sure) * 4) * 0.1
            mem = self.recall(obs, e)
            score += 0.3 * min(3, mem.get("iKnockedOut", 0)) - 1.0 * float(mem.get("danger", 0))
            if score > best_score:
                best, best_score = e, score
        return best

    def geared(self, obs: dict) -> bool:
        me = obs.get("self") or {}
        has_weapon = any(is_weapon(i.get("id", "")) for i in obs.get("inventory") or [])
        return has_weapon and float(me.get("armor", 0)) >= GEAR_ARMOR

    def needs(self, obs: dict, threat, real_threat: bool) -> dict:
        """How strong each need is right now (0..~2)."""
        me = obs.get("self") or {}
        health = float(me.get("health", 20))
        food = float(me.get("food", 20))
        c = self.cfg
        fear = 0.0
        anger = 0.0
        if threat is not None:
            odds = float(threat.get("fightOdds", 0.0))
            fear = max(0.0, (10 - health) / 10) + max(0.0, -odds)
            if real_threat or threat.get("hostile"):
                anger = 1.0 + max(0.0, odds)
            if threat.get("player") and not c["starts_fights"] and not real_threat:
                anger = 0.0
            if self.mode == "peaceful" and threat.get("player"):
                anger = 0.0
                fear += 0.8 if real_threat else 0.0
        mem = self.recall(obs, threat)
        if mem:
            fear += 0.5 * float(mem.get("danger", 0)) * (1.0 if health < 14 else 0.5)
            if float(mem.get("like", 0)) < -0.3 and real_threat:
                anger += 0.3
        here = next((p for p in obs.get("places") or [] if p.get("here")), None)
        if here is not None and float(here.get("feeling", 0)) < -0.4:
            fear += 0.3  # It remembers bad things happening here.
        hunger = max(0.0, (16 - food) / 8)
        duty = c["duty"]
        if self.mode == "hunter" and self.geared(obs) and self.prey(obs) is not None:
            duty = 1.6
        self.feelings = {"fear": round(fear * c["fear"], 2), "anger": round(anger * c["anger"], 2),
                         "hunger": round(hunger, 2), "duty": round(duty, 2), "boredom": 0.3}
        return self.feelings

    def pick_goal(self, obs: dict, rng) -> dict | None:
        """The mode's own goal when there's nothing urgent (None = free play by likes)."""
        if self.mode == "hunter":
            if not self.geared(obs):
                me = obs.get("self") or {}
                return {"type": "mine", "item": "diamond" if float(me.get("armor", 0)) >= 10 else "iron", "count": 6,
                        "why": "gearing up before hunting players"}
            if self.prey(obs) is not None:
                return {"type": "hunt_players", "why": "a player it can beat is nearby"}
            return {"type": "explore", "minutes": 3, "why": "looking for players"}
        if self.mode == "guard":
            return {"type": "go_to", "waypoint": "base", "why": "back to guard the base"} if rng.random() < 0.3 else None
        return None


# --- personality -> mode (one time, optionally with the mind) ------------------------------

KEYWORDS = {
    "hunter": ("ruthless", "aggressive", "bloodthirsty", "hunter", "killer", "pvp", "violent", "competitive", "sweat"),
    "guard": ("protective", "loyal", "guardian", "bodyguard", "defender", "knight"),
    "peaceful": ("gentle", "kind", "peaceful", "pacifist", "shy", "timid", "calm", "friendly"),
}


def from_keywords(personality: str) -> str:
    text = (personality or "").lower()
    best, hits = "neutral", 0
    for mode, words in KEYWORDS.items():
        n = sum(1 for w in words if w in text)
        if n > hits:
            best, hits = mode, n
    return best


def personality_hash(personality: str) -> str:
    return hashlib.sha1((personality or "").strip().encode("utf-8")).hexdigest()[:16]


def from_mind(llm, personality: str) -> str | None:
    """Asks the local language model once which mode fits; None if it can't answer clearly."""
    modes = ", ".join(f"{k} ({v['help']})" for k, v in MODES.items())
    prompt = (f"A Minecraft player's personality: \"{personality}\".\nWhich play style fits best? Options: {modes}.\n"
              "Answer with only one word: neutral, hunter, guard or peaceful.")
    try:
        answer = llm(prompt)
    except Exception:
        return None
    m = re.search(r"\b(neutral|hunter|guard|peaceful)\b", str(answer).lower())
    return m.group(1) if m else None


def load(profile: str) -> dict:
    try:
        with open(os.path.join(profile, "drives.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save(profile: str, data: dict) -> None:
    with open(os.path.join(profile, "drives.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)


def resolve(profile: str, personality: str, llm=None) -> dict:
    """The mode for a personality, worked out once: kept until the personality changes."""
    data = load(profile)
    h = personality_hash(personality)
    if data.get("personality_hash") == h and data.get("from_personality"):
        return data
    mode = from_mind(llm, personality) if llm is not None else None
    by = "mind" if mode else "keywords"
    mode = mode or from_keywords(personality)
    data.update(from_personality=mode, personality_hash=h, by=by)
    save(profile, data)
    return data
