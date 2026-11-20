"""Learning from other players (M14): every player seen in a recording becomes a demo.

Your recordings already hold, every tick, each nearby player's position, look direction,
held item, swings, item use, sprinting, sneaking and getting hurt (from the AI's own client,
so it works on any server, plugin servers included). From that this rebuilds what each one
did, tick by tick, from *their* point of view (the self-play view, selfplay.py):

- movement keys from how they moved relative to where they looked, jumps from leaving the
  ground upward, attacks from the start of a swing, camera from how their head turned;
- with Training Grounds' exact mode (/tg record on) their real keys are used instead.

Each moment is weighted by what came of it: leading to a hit or kill counts more, leading to
getting hurt counts less, and the seconds before they died count nothing. So it copies the
good play of enemies and friends, not their mistakes. Player demos weigh half of yours.
Recordings made while watching a Flashback replay work the same way.

Spectator learning (Phase "watch"):
- every player gets a **rating** from all the fights seen so far (hits landed vs taken, deaths),
  kept in <recordings>/player_ratings.json; their demos are weighted by it, so strong players
  count more while weak players' good moments still count a little;
- only the **moments with action** are kept (a few seconds around their swings and hits), so
  hours of watching make compact demos;
- a recording is converted **once** (a marker file), and demos are tagged with the server and
  the game (sidebar title) they came from.
"""

from __future__ import annotations

import argparse
import glob
import gzip
import json
import math
import os
import struct
import sys

from .learn.recording import read_chunk
from .selfplay import opponent_view

EYE = 1.62
MIN_TICKS = 100
SKIP_SOURCES = ("video", "player")


def _wrap(a: float) -> float:
    return (a + 180.0) % 360.0 - 180.0


def _abs_pos(me: dict, e: dict):
    return (me.get("x", 0.0) + e.get("dx", 0.0), me.get("y", 0.0) + EYE + e.get("dy", 0.0), me.get("z", 0.0) + e.get("dz", 0.0))


def guess_input(prev: dict, cur: dict, prev_pos, pos) -> dict:
    """What a player most likely pressed between two ticks, from how they moved and looked."""
    yaw = math.radians(float(cur.get("yaw", 0.0)))
    vx, vz = pos[0] - prev_pos[0], pos[2] - prev_pos[2]
    fwd = -math.sin(yaw) * vx + math.cos(yaw) * vz
    left = math.cos(yaw) * vx + math.sin(yaw) * vz
    keys = []
    if fwd > 0.03:
        keys.append("forward")
    elif fwd < -0.03:
        keys.append("back")
    if left > 0.03:
        keys.append("left")
    elif left < -0.03:
        keys.append("right")
    if prev.get("onGround", True) and not cur.get("onGround", True) and pos[1] - prev_pos[1] > 0.15:
        keys.append("jump")
    if cur.get("sprinting"):
        keys.append("sprint")
    if cur.get("sneaking"):
        keys.append("sneak")
    if cur.get("swinging") and not prev.get("swinging"):
        keys.append("attack")
    if cur.get("usingItem"):
        keys.append("use")
    return {"keys": keys, "attacks": 1 if "attack" in keys else 0, "uses": 0,
            "yaw": _wrap(float(cur.get("yaw", 0.0)) - float(prev.get("yaw", 0.0))),
            "pitch": float(cur.get("pitch", 0.0)) - float(prev.get("pitch", 0.0))}


def outcome_weights(track: list[dict], hits: set[int], died: bool) -> list[float]:
    """Per-tick weight from what followed: hits landed (+), getting hurt (-), death (0)."""
    n = len(track)
    w = [1.0] * n
    for i in range(1, n):
        if track[i]["e"].get("hurtTime", 0) > track[i - 1]["e"].get("hurtTime", 0):
            for j in range(max(0, i - 30), i + 1):
                w[j] -= 0.6
        if track[i]["t"] in hits:
            for j in range(max(0, i - 40), i + 1):
                w[j] += 0.5
    if died:
        for j in range(max(0, n - 100), n):
            w[j] = 0.0
    return [max(0.0, min(1.5, x)) for x in w]


def with_truth(obs: dict) -> dict:
    """The observation with players at their TRUE states when Watch & learn recorded them
    (privileged data: follows players behind walls). Only for building demos; the demo's own
    observation is rebuilt from the tracked player's side and never includes this field."""
    priv = obs.get("privileged")
    if not priv:
        return obs
    ents = [e for e in obs.get("entities") or [] if not e.get("player")]
    for p in priv:
        ents.append({"id": p.get("id"), "type": "minecraft:player", "player": True, "name": p.get("name"),
                     "visible": True, "dx": p.get("dx", 0.0), "dy": p.get("dy", 0.0), "dz": p.get("dz", 0.0),
                     "yaw": p.get("yaw", 0.0), "pitch": p.get("pitch", 0.0), "onGround": p.get("onGround", True),
                     "health": p.get("health", 20.0), "maxHealth": 20.0, "hurtTime": p.get("hurtTime", 0),
                     "swinging": p.get("swinging", False), "usingItem": p.get("usingItem", False),
                     "sprinting": p.get("sprinting", False), "sneaking": p.get("sneaking", False),
                     "heldItem": p.get("heldItem", "")})
    out = dict(obs)
    out["entities"] = ents
    out.pop("privileged", None)
    return out


def tracks(recording: str) -> dict[str, list[list[dict]]]:
    """Each player's continuous stretches in a recording: [{"t", "e", "obs", "exact"}]."""
    records = []
    for path in sorted(glob.glob(os.path.join(recording, "chunk-*.bin.gz"))):
        records.extend((t, r) for t, r, _ in read_chunk(path))
    by_name: dict[str, list[list[dict]]] = {}
    last_seen: dict[str, int] = {}
    for t, rec in records:
        obs = with_truth(rec.get("obs") or {})
        exact = {}
        for ev in rec.get("events") or []:
            if ev.get("type") == "tg_inputs":
                for p in (ev.get("data") or {}).get("players") or []:
                    exact[p.get("name")] = p.get("keys") or []
        for e in obs.get("entities") or []:
            name = e.get("name")
            if not e.get("player") or not name:
                continue
            segs = by_name.setdefault(name, [])
            if not segs or t - last_seen.get(name, -99) > 2:
                segs.append([])
            segs[-1].append({"t": t, "e": e, "obs": obs, "exact": exact.get(name)})
            last_seen[name] = t
    return by_name


def _hits_by(track: list[dict]) -> set[int]:
    """Ticks where this player started a swing and something near them got hurt within 3 ticks."""
    out = set()
    for i in range(1, len(track)):
        cur, prev = track[i]["e"], track[i - 1]["e"]
        if not (cur.get("swinging") and not prev.get("swinging")):
            continue
        me = track[i]["obs"].get("self") or {}
        pos = _abs_pos(me, cur)
        for k in range(i, min(len(track), i + 4)):
            obs = track[k]["obs"]
            for e in obs.get("entities") or []:
                if e.get("name") == cur.get("name") or e.get("hurtTime", 0) < 9:
                    continue
                if math.dist(_abs_pos(obs.get("self") or {}, e), pos) < 4.5:
                    out.add(track[i]["t"])
    return out


ACTION_MARGIN = 60  # Ticks kept before/after each swing or hit.


def action_ticks(track: list[dict]) -> list[bool]:
    """Which ticks of a track are near action (their swings, their hurt flashes, hits by them)."""
    n = len(track)
    near = [False] * n
    marks = [i for i in range(n) if track[i]["e"].get("swinging") or track[i]["e"].get("hurtTime", 0) > 0]
    for i in marks:
        for j in range(max(0, i - ACTION_MARGIN), min(n, i + ACTION_MARGIN + 1)):
            near[j] = True
    return near


def load_ratings(recordings: str) -> dict:
    try:
        with open(os.path.join(recordings, "player_ratings.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def rating(stats: dict) -> float:
    """0..1 from hits landed vs taken and deaths, with a prior so few fights stay near 0.5."""
    landed = stats.get("landed", 0) + 2
    taken = stats.get("taken", 0) + stats.get("deaths", 0) * 3 + 2
    return landed / (landed + taken)


def game_of(track: list[dict]) -> str:
    for t in track[:200]:
        title = ((t["obs"].get("text") or {}).get("sidebarTitle")) or ""
        title = "".join(ch for ch in str(title) if ch.isprintable())
        import re

        title = re.sub(r"§.", "", title).strip()
        if title:
            return title
    return ""


def write(directory: str, rows: list[dict], meta: dict) -> None:
    os.makedirs(directory, exist_ok=True)
    per = 1200
    for ci in range(0, len(rows), per):
        buf = bytearray()
        for t in range(ci, min(ci + per, len(rows))):
            rec = json.dumps(rows[t]).encode("utf-8")
            buf += struct.pack(">ii", t, len(rec)) + rec + struct.pack(">i", -1)
        with gzip.open(os.path.join(directory, f"chunk-{ci // per:05d}.bin.gz"), "wb") as f:
            f.write(bytes(buf))
    m = dict(meta, ticks=len(rows), chunks=(len(rows) + per - 1) // per, ticksPerChunk=per, frameSize=0,
             source="player", weight=0.5)
    with open(os.path.join(directory, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(m, f, indent=1)


def convert(recordings: str, own_name: str = "") -> dict:
    made, players, exact_ticks, ticks = 0, set(), 0, 0
    ratings = load_ratings(recordings)
    todo = []
    for d in sorted(glob.glob(os.path.join(recordings, "*", ""))):
        rid = os.path.basename(os.path.normpath(d))
        try:
            with open(os.path.join(d, "meta.json"), encoding="utf-8") as f:
                meta = json.load(f)
        except (OSError, ValueError):
            continue
        if meta.get("source") in SKIP_SOURCES or rid.startswith(("players-", "video-")):
            continue
        if os.path.exists(os.path.join(d, "players_done")):
            continue  # Converted before.
        todo.append((d, rid, meta, tracks(d)))
    # Ratings first, from every fight in these recordings, so each demo gets its player's rating.
    for _, _, _, by_name in todo:
        for name, segs in by_name.items():
            if name == own_name:
                continue
            st = ratings.setdefault(name, {"landed": 0, "taken": 0, "deaths": 0, "seen_ticks": 0})
            for track in segs:
                st["landed"] += len(_hits_by(track))
                st["taken"] += sum(1 for i in range(1, len(track))
                                   if track[i]["e"].get("hurtTime", 0) > track[i - 1]["e"].get("hurtTime", 0))
                st["deaths"] += 1 if float(track[-1]["e"].get("health", 20.0)) <= 0.0 else 0
                st["seen_ticks"] += len(track)
    for d, rid, meta, by_name in todo:
        for name, segs in by_name.items():
            if name == own_name:
                continue
            skill = rating(ratings.get(name, {}))
            scale = 0.4 + 1.2 * skill  # Weak players' moments still count a little, strong players' more.
            for si, track in enumerate(segs):
                if len(track) < MIN_TICKS:
                    continue
                last = track[-1]["e"]
                died = float(last.get("health", 20.0)) <= 0.0
                weights = outcome_weights(track, _hits_by(track), died)
                near = action_ticks(track)
                rows = []
                for i in range(1, len(track)):
                    if not near[i]:
                        continue  # Only the moments with action.
                    prev, cur = track[i - 1], track[i]
                    view = opponent_view(cur["obs"], cur["e"].get("id"))
                    if view is None:
                        continue
                    inp = guess_input(prev["e"], cur["e"], _abs_pos(prev["obs"].get("self") or {}, prev["e"]),
                                      _abs_pos(cur["obs"].get("self") or {}, cur["e"]))
                    if cur["exact"] is not None:
                        inp["keys"] = list(cur["exact"])
                        inp["attacks"] = 1 if "attack" in inp["keys"] else 0
                        exact_ticks += 1
                    view.pop("_events", None)
                    view.pop("privileged", None)
                    rows.append({"input": inp, "obs": view, "events": [], "frameAge": -1, "w": weights[i] * scale})
                if len(rows) < MIN_TICKS // 2:
                    continue
                game = game_of(track)
                where = " · ".join(x for x in (meta.get("world", ""), game) if x)
                safe = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in name)[:24]
                out = os.path.join(recordings, f"players-{rid}-{safe}-{si}")
                write(out, rows, {"id": os.path.basename(out),
                                  "name": f"{name} ({where or meta.get('name', rid)}, rating {int(skill * 100)})",
                                  "player": name, "from": rid, "game": game, "server": meta.get("world", ""),
                                  "rating": round(skill, 3)})
                made += 1
                ticks += len(rows)
                players.add(name)
        try:
            with open(os.path.join(d, "players_done"), "w", encoding="utf-8") as f:
                f.write("converted\n")
        except OSError:
            pass
    with open(os.path.join(recordings, "player_ratings.json"), "w", encoding="utf-8") as f:
        json.dump(ratings, f, indent=1)
    top = sorted(((rating(v), k) for k, v in ratings.items() if v.get("seen_ticks", 0) > 200), reverse=True)[:5]
    return {"recordings": made, "players": sorted(players), "seconds": round(ticks / 20, 1), "exact_ticks": exact_ticks,
            "top": [{"name": k, "rating": round(r, 2)} for r, k in top]}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="minigamebots_brain players")
    p.add_argument("--recordings", required=True)
    p.add_argument("--me", default="", help="your own player name (not turned into a demo)")
    args = p.parse_args(argv)
    try:
        print(json.dumps({"type": "done", **convert(args.recordings, args.me)}))
        return 0
    except Exception as e:
        print(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
