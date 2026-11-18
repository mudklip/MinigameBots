"""Hindsight goals: label each moment of a recording with what the player achieved next.

Recordings don't say what the player was trying to do, but their events say what they *did*:
killed a pig a few seconds later, picked up diamonds, reached their base. Training with that
as the goal teaches the network "when the goal is hunt pig, act like this", so the Manager's
goals mean something to skills learned from plain recordings, and every recording teaches
several goals for free.
"""

from __future__ import annotations

HORIZON = 200  # Ticks to look ahead (10 s): far enough to cover walking up to the target.

PASSIVE = ("pig", "cow", "sheep", "chicken", "rabbit", "mooshroom", "goat", "horse", "llama", "turtle",
           "cod", "salmon", "squid", "camel", "armadillo")
ORES = ("diamond", "emerald", "raw_iron", "raw_gold", "raw_copper", "coal", "redstone", "lapis",
        "ancient_debris", "quartz", "amethyst")


def achievement(event: dict) -> dict | None:
    """The goal an event completes, or None if it's not an achievement."""
    kind = event.get("type")
    data = event.get("data") or {}
    if kind == "kill":
        target = str(data.get("target", ""))
        if target.endswith("player"):
            return {"type": "fight", "entity": target}
        name = target.split(":")[-1]
        return {"type": "hunt" if name in PASSIVE else "fight", "entity": target}
    if kind == "item_gained":
        item = str(data.get("item", ""))
        if any(o in item.split(":")[-1] for o in ORES):
            return {"type": "mine", "item": item}
    if kind == "waypoint_reached":
        return {"type": "go_to", "waypoint": data.get("name") or data.get("type")}
    return None


def label(chunk: dict, start: int, length: int, horizon: int = HORIZON) -> list[dict | None]:
    """Goal (as the Manager would give it) for ticks start..start+length-1.

    `chunk` maps tick -> (record, jpeg). Each tick gets the next achievement within `horizon`
    ticks, with where its target is from that tick's observation when it can be seen.
    """
    upcoming: list[tuple[int, dict]] = []
    for t in range(start, start + length + horizon):
        entry = chunk.get(t)
        if entry is None:
            continue
        for e in entry[0].get("events") or []:
            a = achievement(e)
            if a is not None:
                upcoming.append((t, a))
    out: list[dict | None] = []
    j = 0
    for t in range(start, start + length):
        while j < len(upcoming) and upcoming[j][0] < t:
            j += 1
        if j >= len(upcoming) or upcoming[j][0] - t > horizon:
            out.append(None)
            continue
        a = upcoming[j][1]
        goal = {"type": a["type"]}
        entry = chunk.get(t)
        obs = (entry[0].get("obs") if entry else None) or {}
        if "entity" in a:
            best = None
            for ent in obs.get("entities") or []:
                if ent.get("type") == a["entity"]:
                    d = ent.get("dx", 0.0) ** 2 + ent.get("dz", 0.0) ** 2
                    if best is None or d < best[0]:
                        best = (d, ent)
            if best is not None:
                goal["target_dx"], goal["target_dz"] = best[1].get("dx", 0.0), best[1].get("dz", 0.0)
        elif a["type"] == "mine":
            from . import knowledge

            ore = knowledge.ore(a["item"])
            if ore and "best" in ore and "y" in (obs.get("self") or {}):
                goal["target_dy"] = float(ore["best"]) - float(obs["self"]["y"])
        elif a["type"] == "go_to":
            for w in obs.get("waypoints") or []:
                if a["waypoint"] in (w.get("name"), w.get("type")):
                    goal["target_dx"], goal["target_dz"] = w.get("dx", 0.0), w.get("dz", 0.0)
                    break
        out.append(goal)
    return out
