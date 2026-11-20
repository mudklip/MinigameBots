"""The rule finder: works out a game's rules from a few rounds, by counting what follows what.

Causes are things that happen by someone's action ("my snowball hit someone", "a snowball hit
me", "I threw an egg", "I broke a block"); effects are what games are about ("I died", "I got a
kill", "my score went up", "You won!"). For each cause -> effect pair it counts how often the
effect follows within a few seconds, and compares that with how often the effect happens anyway,
so coincidences don't become rules. A handful of examples is enough for clear rules.

Rules then become an automatic objective (rewards for causes that lead to good effects,
penalties for those that lead to bad ones) and suggest which of the AI's skills fit the game.
Learned rules are kept per game (recognised by the sidebar title) so it starts out knowing it
next time. Cheap: counting and a few dictionaries, no network.
"""

from __future__ import annotations

import json
import os
import re

WINDOW = 60  # Ticks (3 s) an effect may follow its cause.
THROWABLES = ("snowball", "egg", "ender_pearl", "splash_potion", "lingering_potion", "trident", "fire_charge", "wind_charge")
WIN_WORDS = re.compile(r"\b(you win|you won|victory|winner|won the game|#1)\b", re.I)
LOSE_WORDS = re.compile(r"\b(you lose|you lost|game over|eliminated|you died|defeat)\b", re.I)
EFFECT_VALUE = {"i_died": -1.0, "i_killed": 1.0, "won": 2.0, "lost": -2.0, "round_won": 2.0, "round_lost": -2.0}
BORROW = {"threw": "bow", "i_hit:projectile": "bow", "i_hit:melee": "sword_pvp", "hurt_by:projectile": "fleeing",
          "hurt_by:melee": "shield_block", "broke": "mining_blocks", "placed": "bridging"}


def short(item_or_type: str) -> str:
    return str(item_or_type or "").split(":")[-1]


def clean(text: str) -> str:
    return re.sub(r"§.", "", str(text or "")).strip()


class RuleFinder:
    def __init__(self, me: str = ""):
        self.me = me
        self.tick = 0
        self.recent = []  # [(tick, cause)]
        self.n_c = {}
        self.n_e = {}
        self.n_ce = {}
        self.score = {}
        self.titles_seen = set()
        self.results = []  # 1 = won the round, 0 = lost.
        self.told = {}  # (cause, effect) -> sentence it came from (rules told in words).
        self.last_reason = ""  # Why it lost last time (cause and effect).
        self.learned_after = None  # Rounds it took to play well (3 of the last 5 won).

    # --- watching ---

    def causes_and_effects(self, obs: dict, events: list, action=None) -> tuple[list, list]:
        causes, effects = [], []
        me = obs.get("self") or {}
        for ev in events:
            t, d = ev.get("type"), ev.get("data") or {}
            if t == "i_hit":
                w = str(d.get("with", ""))
                causes.append("i_hit:" + (short(w.split("melee:")[-1]) if w.startswith("melee") else short(w)))
            elif t == "hurt_by":
                w = str(d.get("with", ""))
                causes.append("hurt_by:" + (short(w.split("melee:")[-1]) if w.startswith("melee") else short(w)))
            elif t == "block_broken":
                causes.append("broke:" + short(d.get("block", "")))
            elif t == "block_placed":
                causes.append("placed:" + short(d.get("block", "")))
            elif t == "death":
                effects.append("i_died")
            elif t in ("kill", "tg_kill"):
                effects.append("i_killed")
            elif t == "tg_episode_end":
                effects.append("round_won" if d.get("result") == "win" else "round_lost")
            elif t in ("title", "subtitle", "chat", "actionbar"):
                text = clean(d.get("text", ""))
                if WIN_WORDS.search(text):
                    effects.append("won")
                elif LOSE_WORDS.search(text):
                    effects.append("lost")
            elif t == "scoreboard_changed":
                name = clean(d.get("name", ""))
                try:
                    delta = float(d.get("delta", 0))
                except (TypeError, ValueError):
                    delta = 0.0
                who = "me" if name and name == self.me else name.lower()
                if delta and who:
                    effects.append(f"score:{who}:{'+' if delta > 0 else '-'}")
        if action is not None and ("use" in (getattr(action, "keys", ()) or ())):
            held = ""
            for it in obs.get("inventory") or []:
                if it.get("slot") == obs.get("selectedSlot"):
                    held = it.get("id", "")
            if any(t in held for t in THROWABLES):
                causes.append("threw:" + short(held))
        return causes, effects

    def observe(self, obs: dict, events: list, action=None) -> None:
        self.tick = int(obs.get("tick", self.tick + 1))
        causes, effects = self.causes_and_effects(obs, events, action)
        self.recent = [(t, c) for t, c in self.recent if self.tick - t <= WINDOW]
        bad = [e for e in effects if self.value(e) < 0]
        if bad:
            self.last_reason = self.explain(bad[0], {c for _, c in self.recent} | set(causes))
        for e in effects:
            self.n_e[e] = self.n_e.get(e, 0) + 1
            for c in {c for _, c in self.recent} | set(causes):
                self.n_ce[(c, e)] = self.n_ce.get((c, e), 0) + 1
        for c in causes:
            self.n_c[c] = self.n_c.get(c, 0) + 1
            self.recent.append((self.tick, c))

    # --- conclusions ---

    def rules(self, min_count: int = 2) -> list[dict]:
        """[{"cause", "effect", "sure" 0..1, "times", "value"}], strongest first."""
        out = []
        ticks = max(1, self.tick)
        for (c, e), n in self.n_ce.items():
            if n < min_count:
                continue
            p_ec = n / max(1, self.n_c.get(c, 0))
            base = min(1.0, self.n_e.get(e, 0) * WINDOW / ticks)
            if p_ec < 0.5 or p_ec < 2 * base:
                continue
            sure = min(1.0, (n + 0.5) / (self.n_c.get(c, 0) + 1)) * min(1.0, n / 4)
            out.append({"cause": c, "effect": e, "sure": round(sure, 2), "times": n, "value": self.value(e)})
        known = {(r["cause"], r["effect"]) for r in out}
        for (c, e), why in self.told.items():
            n_c = self.n_c.get(c, 0)
            if n_c >= 5 and self.n_ce.get((c, e), 0) / n_c < 0.2:
                continue  # Told, but play showed it isn't so.
            if (c, e) not in known:
                out.append({"cause": c, "effect": e, "sure": 0.8, "times": 0, "value": self.value(e), "told": why})
        out.sort(key=lambda r: -r["sure"] * abs(r["value"]))
        return out

    def explain(self, effect: str, recent: set) -> str:
        """Why a bad thing happened: the best-known rule whose cause happened just before."""
        best = None
        for r in self.rules(min_count=1):
            if r["effect"] == effect and r["cause"] in recent and (best is None or r["sure"] > best["sure"]):
                best = r
        if best is None:
            return ""
        return f"{self._effect_name(effect)} because of {self._cause_name(best['cause'])}"

    @staticmethod
    def _cause_name(c: str) -> str:
        c = c.replace("i_hit:", "my hit with ").replace("hurt_by:", "getting hit by ").replace("threw:", "throwing ")
        return c.replace("broke:", "breaking ").replace("placed:", "placing ").replace("_", " ")

    @staticmethod
    def _effect_name(e: str) -> str:
        return {"i_died": "I was out", "lost": "I lost", "round_lost": "I lost the round", "i_killed": "I got a kill",
                "won": "I won", "round_won": "I won the round"}.get(e, e.replace("score:", "score ").replace(":", " "))

    # --- rules told in words ---

    WORDS = {"snowball": "snowball", "egg": "egg", "arrow": "arrow", "trident": "trident", "sword": "sword", "axe": "axe",
             "punch": "air", "fist": "air", "hand": "air", "fireball": "fireball", "pearl": "ender_pearl"}

    def tell(self, sentence: str) -> list:
        """Adds rules from a sentence like "snowballs knock you out" or "kills give points".
        Returns the (cause, effect) pairs understood. Play can still overturn them."""
        text = sentence.lower()
        found = []
        thing = next((v for k, v in self.WORDS.items() if k in text), None)
        out_words = re.search(r"knock\w* (you |them |players? )?out|kills?\b|eliminat|one.?hit|instantly", text)
        if thing and out_words:
            found += [("hurt_by:" + thing, "i_died"), ("i_hit:" + thing, "i_killed")]
        if re.search(r"(kills?|knock\w* out)\b.*(points?|score)", text):
            found.append(("kill:any", "score:me:+"))
        if thing and re.search(r"(points?|score)", text) and not found:
            found.append(("i_hit:" + thing, "score:me:+"))
        for pair in found:
            self.told[pair] = sentence.strip()
        return found

    def value(self, effect: str) -> float:
        if effect in EFFECT_VALUE:
            return EFFECT_VALUE[effect]
        if effect.startswith("score:me:"):
            return 0.5 if effect.endswith("+") else -0.5
        if effect.startswith("score:"):
            # A score line that isn't a player name: rising is usually good if it rises with my kills/wins.
            if any(self.n_ce.get((c, effect), 0) for c in self.n_c if c.startswith(("i_hit", "threw"))):
                return 0.5 if effect.endswith("+") else -0.5
            return 0.0
        return 0.0

    def describe(self) -> list[str]:
        names = {"i_died": "I'm out", "i_killed": "I get a kill", "won": "I win", "lost": "I lose", "round_won": "round won",
                 "round_lost": "round lost"}
        out = []
        for r in self.rules()[:8]:
            c = r["cause"].replace("i_hit:", "my hit with ").replace("hurt_by:", "getting hit by ").replace("threw:", "throwing ")
            c = c.replace("broke:", "breaking ").replace("placed:", "placing ").replace("_", " ")
            e = names.get(r["effect"], r["effect"].replace("score:", "score ").replace(":", " "))
            out.append(f"{c} -> {e} ({'told' if r.get('told') else str(int(r['sure'] * 100)) + '% sure'})")
        return out

    def objective(self, name: str = "This game") -> dict:
        """Rewards from the rules found, plus the plain outcomes every game shares."""
        rules = [{"event": "kill", "value": 1.0, "tier": 1, "note": "kill"},
                 {"event": "death", "value": -1.0, "tier": 1, "note": "out"},
                 {"event": "tg_episode_end", "where": {"result": "win"}, "value": 2.0, "tier": 1, "note": "round won"},
                 {"event": "tg_episode_end", "where": {"result": "loss"}, "value": -2.0, "tier": 1, "note": "round lost"}]
        for r in self.rules():
            v = r["value"] * r["sure"]
            if abs(v) < 0.05:
                continue
            kind, _, what = r["cause"].partition(":")
            if kind == "kill":
                continue  # Kills are rewarded anyway.
            event = {"i_hit": "i_hit", "hurt_by": "hurt_by", "threw": "threw", "broke": "block_broken",
                     "placed": "block_placed"}.get(kind)
            if event is None:
                continue
            field = {"i_hit": "with", "hurt_by": "with", "threw": "item", "block_broken": "block", "block_placed": "block"}[event]
            rules.append({"event": event, "where": {field: {"contains": what}}, "value": round(0.5 * v, 3), "tier": 2,
                          "cooldown": 0.5, "note": "rule: " + r["cause"].replace(":", " ") + " -> " + r["effect"]})
        return {"name": name, "rules": rules}

    def borrow(self) -> list[str]:
        """Skills that fit this game, from what its rules are about."""
        out = []
        for r in self.rules():
            kind, _, what = r["cause"].partition(":")
            key = kind
            if kind in ("i_hit", "hurt_by"):
                key = kind + (":melee" if any(w in what for w in ("sword", "axe", "air", "mace")) else ":projectile")
            skill = BORROW.get(key)
            if skill and skill not in out:
                out.append(skill)
        return out

    def seed_from(self, others: list, weight: float = 0.3) -> None:
        """A new game starts with a weaker copy of the rules seen in other games (carry-over)."""
        for o in others:
            for r in o.rules():
                if r.get("told"):
                    continue
                pair = (r["cause"], r["effect"])
                add = max(1, int(round(r["times"] * weight)))
                self.n_ce[pair] = self.n_ce.get(pair, 0) + add
                self.n_c[r["cause"]] = self.n_c.get(r["cause"], 0) + add
                self.n_e[r["effect"]] = self.n_e.get(r["effect"], 0) + add
        self.tick = max(self.tick, 2000)  # Treat the copy as old evidence (weak base rate).

    # --- memory per game ---

    @staticmethod
    def game_key(obs: dict) -> str:
        title = clean(((obs.get("text") or {}).get("sidebarTitle")) or "")
        return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")[:40] or "unknown_game"

    def round_over(self, won: bool) -> None:
        """The exam: how many rounds until it plays well (3 of its last 5 won)."""
        self.results.append(1 if won else 0)
        if self.learned_after is None and len(self.results) >= 5 and sum(self.results[-5:]) >= 3:
            self.learned_after = len(self.results)

    def summary(self) -> dict:
        last = self.results[-10:]
        return {"rounds": len(self.results), "win_rate": round(sum(last) / len(last), 2) if last else None,
                "learned_after": self.learned_after}

    def to_json(self) -> dict:
        return {"n_c": self.n_c, "n_e": self.n_e, "n_ce": [[c, e, n] for (c, e), n in self.n_ce.items()], "tick": self.tick,
                "results": self.results[-200:], "learned_after": self.learned_after, "summary": self.summary(),
                "rules": self.describe(), "told": [[c, e, w] for (c, e), w in self.told.items()],
                "last_reason": self.last_reason}

    @classmethod
    def from_json(cls, data: dict, me: str = "") -> "RuleFinder":
        f = cls(me)
        f.n_c = dict(data.get("n_c") or {})
        f.n_e = dict(data.get("n_e") or {})
        f.n_ce = {(c, e): n for c, e, n in data.get("n_ce") or []}
        f.tick = int(data.get("tick", 0))
        f.results = list(data.get("results") or [])
        f.told = {(c, e): w for c, e, w in data.get("told") or []}
        f.last_reason = data.get("last_reason", "")
        f.learned_after = data.get("learned_after")
        return f

    @staticmethod
    def path(profile: str, key: str) -> str:
        return os.path.join(profile, "core", "games", key + ".json")

    def save(self, profile: str, key: str) -> None:
        p = self.path(profile, key)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(self.to_json(), f)

    @classmethod
    def load(cls, profile: str, key: str, me: str = "") -> "RuleFinder":
        try:
            with open(cls.path(profile, key), encoding="utf-8") as f:
                return cls.from_json(json.load(f), me)
        except (OSError, ValueError):
            return cls(me)
