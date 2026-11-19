"""Commands from you -> the Manager's goal list (optional addition O2).

`/ai mine 10 iron then go home` is turned into goals the Goals screen could also make:
[{"type": "mine", "item": "iron", "count": 10}, {"type": "go_to", "waypoint": "base"}].

Two tiers: a rule parser for common phrasings (instant, no download), then, for anything it
doesn't follow, the optional local language model (llm.py). Whatever produced them, goals are
checked against the allowed types and values before use, so a command can never do more than
the Goals screen can. Commands only come from the player running this client (a client-side
command); other players' chat can't give orders.
"""

from __future__ import annotations

import re

GOAL_TYPES = ("mine", "hunt", "go_to", "follow", "protect", "explore", "survive", "fight", "free_play")
ORES = {"diamond": "diamond", "diamonds": "diamond", "iron": "iron", "coal": "coal", "gold": "gold", "copper": "copper",
        "redstone": "redstone", "lapis": "lapis", "emerald": "emerald", "emeralds": "emerald", "debris": "ancient_debris",
        "netherite": "ancient_debris", "quartz": "quartz"}
MOBS = ("pig", "cow", "sheep", "chicken", "rabbit", "zombie", "skeleton", "spider", "creeper", "enderman", "slime",
        "witch", "goat", "horse", "salmon", "cod", "mannequin", "player")
NUMBERS = {w: i for i, w in enumerate("zero one two three four five six seven eight nine ten eleven twelve thirteen "
                                       "fourteen fifteen sixteen seventeen eighteen nineteen twenty".split())}
NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")


def _num(text: str, default: int) -> int:
    m = re.search(r"\b(\d+)\b", text)
    if m:
        return int(m.group(1))
    for w, n in NUMBERS.items():
        if re.search(rf"\b{w}\b", text):
            return n
    if re.search(r"\b(a|an)\b", text):
        return 1
    return default


def _player(text: str, me: str) -> str | None:
    """A player name from the clause as typed (names keep their capitals); "me" = you."""
    if re.search(r"\b(me|us)\b", text, re.I):
        return me or None
    m = re.search(r"(?:follow|protect|guard|defend|escort|help)\s+(?:player\s+)?([A-Za-z0-9_]{1,16})", text, re.I)
    return m.group(1) if m else None


def parse_clause(c: str, me: str) -> dict | str | None:
    """One clause -> a goal, "stop", or None if not understood."""
    original = c.strip().rstrip(".!")
    c = original.lower()
    if not c:
        return None
    if re.fullmatch(r"(stop|halt|cancel|stand still|wait|nevermind|never mind)( it| now| please)*", c):
        return "stop"
    if re.search(r"\b(free ?play|do (whatever|what) you want|have fun|play by yourself|your own thing)\b", c):
        return {"type": "free_play"}
    if re.search(r"\b(go|come|head|return|get)( back)? (home|to (the )?base|back)\b|\bgo home\b|\breturn\b", c):
        return {"type": "go_to", "waypoint": "base"}
    m = re.search(r"\bgo to (?:the )?([a-z0-9_ -]{1,24})$", c)
    if m:
        return {"type": "go_to", "waypoint": m.group(1).strip()}
    if re.search(r"\b(mine|get|collect|gather|find|dig)\b", c):
        for w, ore in ORES.items():
            if re.search(rf"\b{w}\b", c):
                return {"type": "mine", "item": ore, "count": _num(c, 5)}
    if re.search(r"\b(hunt|kill|slay|attack|get|find)\b", c):
        for mob in MOBS:
            if re.search(rf"\b{mob}s?\b", c):
                target = "minecraft:" + mob
                return {"type": "hunt", "target": target, "count": _num(c, 1)}
        if re.search(r"\b(food|meat|something to eat)\b", c):
            return {"type": "hunt", "target": "minecraft:cow", "count": 2}
    if re.search(r"\b(follow|come with|come along)\b", c):
        p = _player(original, me)
        return {"type": "follow", "player": p} if p else None
    if re.search(r"\b(protect|guard|defend|escort|keep .* safe)\b", c):
        p = _player(original, me)
        return {"type": "protect", "player": p} if p else None
    if re.search(r"\b(explore|wander|look around|scout)\b", c):
        return {"type": "explore", "minutes": _num(c, 5) if re.search(r"\bmin", c) else 5}
    if re.search(r"\b(survive|stay alive)\b", c):
        return {"type": "survive", "minutes": _num(c, 10)}
    if re.search(r"\b(fight|attack)\b", c):
        return {"type": "fight"}
    return None


def split_clauses(text: str) -> list[str]:
    return [c for c in re.split(r",|;|\bthen\b|\band then\b|\bafter that\b|\band\b", text, flags=re.I) if c.strip()]


def validate(goals) -> list[dict]:
    """Keeps only goals the Goals screen could make, with sane values."""
    out = []
    for g in goals if isinstance(goals, list) else []:
        if not isinstance(g, dict) or g.get("type") not in GOAL_TYPES:
            continue
        t = g["type"]
        v = {"type": t}
        if t == "mine":
            item = str(g.get("item", "")).split(":")[-1].lower()
            item = ORES.get(item, item)
            if not re.fullmatch(r"[a-z_]{2,24}", item):
                continue
            v.update(item=item, count=max(1, min(64, int(g.get("count", 5) or 5))))
        elif t == "hunt":
            target = str(g.get("target", "")).lower()
            target = target if ":" in target else "minecraft:" + target
            if not re.fullmatch(r"[a-z_]+:[a-z_]{2,32}", target):
                continue
            v.update(target=target, count=max(1, min(32, int(g.get("count", 1) or 1))))
        elif t == "go_to":
            wp = str(g.get("waypoint", "base")).strip()[:24]
            if not wp:
                continue
            v["waypoint"] = wp
        elif t in ("follow", "protect"):
            p = str(g.get("player", ""))
            if not NAME.fullmatch(p):
                continue
            v["player"] = p
        elif t in ("explore", "survive"):
            v["minutes"] = max(1, min(60, int(g.get("minutes", 5) or 5)))
        out.append(v)
    return out[:8]


def describe(g: dict) -> str:
    t = g["type"]
    return {"mine": lambda: f"mine {g['item']} x{g['count']}",
            "hunt": lambda: f"hunt {g['target'].split(':')[-1]} x{g['count']}",
            "go_to": lambda: "go home" if g["waypoint"] == "base" else f"go to {g['waypoint']}",
            "follow": lambda: f"follow {g['player']}", "protect": lambda: f"protect {g['player']}",
            "explore": lambda: f"explore {g['minutes']} min", "survive": lambda: f"survive {g['minutes']} min",
            "fight": lambda: "fight", "free_play": lambda: "do what I like"}[t]()


def understand(text: str, me: str = "", llm=None) -> dict:
    """{"goals": [...], "stop": bool, "reply": str, "via": "rules" | "model" | "none"}."""
    parsed = [parse_clause(c, me) for c in split_clauses(text)]
    if parsed and all(p is not None for p in parsed):
        if "stop" in parsed:
            return {"goals": [], "stop": True, "reply": "Okay, stopping.", "via": "rules"}
        goals = validate(parsed)
        if goals:
            return {"goals": goals, "stop": False, "reply": "Okay: " + ", then ".join(map(describe, goals)) + ".",
                    "via": "rules"}
    if llm is not None:
        goals, stop = llm.to_goals(text, me)
        goals = validate(goals)
        if stop:
            return {"goals": [], "stop": True, "reply": "Okay, stopping.", "via": "model"}
        if goals:
            return {"goals": goals, "stop": False, "reply": "Okay: " + ", then ".join(map(describe, goals)) + ".",
                    "via": "model"}
    return {"goals": [], "stop": False, "via": "none",
            "reply": "I didn't get that. Try: 'mine 10 iron then go home', 'hunt 2 cows', 'follow me', 'explore', "
                     "'stop'." + ("" if llm else " (Install the mind model in Brain for free-form commands.)")}
