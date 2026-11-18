"""The built-in Minecraft knowledge base: facts loaded from minigamebots_brain/knowledge/*.json.

Facts a person learns by being told once (where diamonds are, what a gold farm needs, how crits
work) are looked up here instead of being trained. The Manager turns goals into parameters with
it, Objectives can use it for shaping, and the optional mind can look things up. The files can
be edited; entries marked "check" depend on version or server rules and should be verified.
"""

import json
import os
import re

DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "knowledge")
_cache = None


def topics() -> dict:
    """{topic: {entry: dict}} for every knowledge file (loaded once)."""
    global _cache
    if _cache is None:
        _cache = {}
        for name in sorted(os.listdir(DIR)):
            if name.endswith(".json"):
                with open(os.path.join(DIR, name), encoding="utf-8") as f:
                    data = json.load(f)
                _cache[name[:-5]] = {k: v for k, v in data.items() if not k.startswith("_")}
    return _cache


def get(topic: str, entry: str) -> dict | None:
    return topics().get(topic, {}).get(entry)


def search(text: str, limit: int = 5) -> list:
    """(topic, entry, data) whose name or text best matches the words in `text`."""
    words = [w for w in re.findall(r"[a-z0-9]+", text.lower()) if len(w) > 2]
    scored = []
    for topic, entries in topics().items():
        for name, data in entries.items():
            body = (name.replace("_", " ") + " " + json.dumps(data)).lower()
            score = sum(3 if w in name else 1 for w in words if w in body)
            if score:
                scored.append((score, topic, name, data))
    scored.sort(key=lambda s: -s[0])
    return [(t, n, d) for _, t, n, d in scored[:limit]]


def ore(name: str) -> dict | None:
    """Where an ore is found: {"range": [lo, hi], "best": y, "method": ...}."""
    key = name.split(":")[-1].replace("raw_", "").replace("_ore", "").replace("deepslate_", "")
    return get("ores", {"diamonds": "diamond", "emeralds": "emerald", "redstone_dust": "redstone",
                        "lapis_lazuli": "lapis", "netherite_scrap": "ancient_debris", "quartz": "nether_quartz",
                        "netherite": "ancient_debris"}.get(key, key))
