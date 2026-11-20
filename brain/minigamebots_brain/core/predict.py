"""Self-taught prediction (the Core's common sense): from what's happening now, guess what
happens in the next seconds. The answers come from the recordings themselves (what did happen
next), so it needs no labels. Learning to predict forces the shared network to understand cause
and effect; the predictions also give "surprise" (how wrong it was), used for curiosity.
"""

from __future__ import annotations

import numpy as np

# (name, event types, extra check, horizon in ticks)
SIGNALS = (
    ("hurt", ("damage_taken", "hurt_by"), None, 20),
    ("hit", ("damage_dealt", "i_hit"), None, 20),
    ("die", ("death",), None, 60),
    ("kill", ("kill", "tg_kill"), None, 60),
    ("score_up", ("scoreboard_changed",), lambda d: _num(d.get("delta")) > 0, 60),
    ("broke", ("block_broken",), None, 20),
    ("got_item", ("item_gained",), None, 20),
    ("won", ("tg_episode_end",), lambda d: d.get("result") == "win", 100),
)
NAMES = tuple(s[0] for s in SIGNALS)
SIZE = len(SIGNALS)


def _num(v) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def happened(events: list) -> np.ndarray:
    """Which signals one tick's events contain."""
    out = np.zeros(SIZE, dtype=np.float32)
    for ev in events or []:
        t, d = ev.get("type"), ev.get("data") or {}
        for k, (_, types, check, _h) in enumerate(SIGNALS):
            if t in types and (check is None or check(d)):
                out[k] = 1.0
    return out


def targets(chunk: dict, start: int, length: int) -> tuple[np.ndarray, np.ndarray]:
    """(targets [length, SIZE], mask [length, SIZE]) for a window of a recording chunk
    ({tick: (record, jpeg)}). A target is 1 if the signal happens within its horizon after that
    tick; it's masked out where the chunk ends before the horizon (unknown future)."""
    last = start + length - 1 + max(s[3] for s in SIGNALS)
    occ = {}
    for t in range(start + 1, last + 1):
        entry = chunk.get(t)
        if entry is not None:
            occ[t] = happened(entry[0].get("events") or [])
    end = max(chunk) if chunk else start
    tg = np.zeros((length, SIZE), dtype=np.float32)
    mask = np.ones((length, SIZE), dtype=np.float32)
    for i in range(length):
        t = start + i
        for k, (_, _, _, h) in enumerate(SIGNALS):
            if t + h > end:
                mask[i, k] = 0.0
                continue
            for u in range(t + 1, t + h + 1):
                if u in occ and occ[u][k]:
                    tg[i, k] = 1.0
                    break
    return tg, mask
