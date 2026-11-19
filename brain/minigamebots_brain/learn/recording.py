"""Reads .mgbrec recordings (see docs/formats/recording.md) and serves training sequences.

Chunks are loaded on demand and kept in a small cache, so memory stays bounded no matter how
many hours of recordings you train on. Frames stay JPEG-compressed until a batch needs them.
"""

import gzip
import io
import json
import os
import random
import struct
from collections import OrderedDict

import numpy as np

from . import actions, features

# Marked moments: imitate around "good" ones more, and don't imitate "mistake" ones at all.
MARK_RADIUS = 40
GOOD_WEIGHT = 2.0


def read_meta(directory: str) -> dict:
    with open(os.path.join(directory, "meta.json"), encoding="utf-8") as f:
        return json.load(f)


def read_chunk(path: str) -> list:
    """All complete records in a chunk file: (tick, record_json, jpeg_bytes_or_None)."""
    records = []
    last_frame = None
    try:
        with gzip.open(path, "rb") as f:
            data = f.read()
    except (OSError, EOFError):
        # A chunk cut short by a crash: read what decompresses.
        data = _read_partial_gzip(path)
    pos = 0
    while pos + 8 <= len(data):
        tick, json_len = struct.unpack_from(">ii", data, pos)
        pos += 8
        if pos + json_len + 4 > len(data):
            break
        record = json.loads(data[pos:pos + json_len].decode("utf-8"))
        pos += json_len
        (jpeg_len,) = struct.unpack_from(">i", data, pos)
        pos += 4
        if jpeg_len >= 0:
            if pos + jpeg_len > len(data):
                break
            last_frame = data[pos:pos + jpeg_len]
            pos += jpeg_len
        records.append((tick, record, last_frame))
    return records


def _read_partial_gzip(path: str) -> bytes:
    import zlib

    out = bytearray()
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    with open(path, "rb") as f:
        try:
            while True:
                block = f.read(65536)
                if not block:
                    break
                out += d.decompress(block)
        except zlib.error:
            pass
    return bytes(out)


def decode_jpeg(data) -> np.ndarray:
    from PIL import Image

    image = Image.open(io.BytesIO(data)).convert("RGB")
    if image.size != (features.FRAME_SIZE, features.FRAME_SIZE):
        image = image.resize((features.FRAME_SIZE, features.FRAME_SIZE))
    return np.asarray(image, dtype=np.uint8)


class Recording:
    def __init__(self, directory: str):
        self.directory = directory
        self.meta = read_meta(directory)
        self.id = self.meta.get("id", os.path.basename(directory))
        per_chunk = int(self.meta.get("ticksPerChunk", 1200)) or 1200
        self.ticks_per_chunk = per_chunk
        ticks = int(self.meta.get("ticks", 0))
        start = self.meta.get("trimStart", -1)
        end = self.meta.get("trimEnd", -1)
        self.first = start if start is not None and start >= 0 else 0
        self.last = min(end, ticks - 1) if end is not None and end >= 0 else ticks - 1
        self.chunks = int(self.meta.get("chunks", 0)) or (ticks + per_chunk - 1) // per_chunk
        self.weights = self._tick_weights(ticks)
        # Places the player marked (e.g. "mark base"): the AI learns to mark them too.
        self.mark_ticks = {}
        for mark in self.meta.get("marks") or []:
            t = mark.get("type")
            if t in features.WAYPOINT_TYPES:
                self.mark_ticks[int(mark.get("tick", 0))] = features.WAYPOINT_TYPES.index(t) + 1

    def chunk_path(self, index: int) -> str:
        return os.path.join(self.directory, f"chunk-{index:05d}.bin.gz")

    def _tick_weights(self, ticks: int) -> np.ndarray:
        # Imported videos count less than your own recordings (their inputs are guesses).
        # A Flashback replay's camera isn't a player: only its other players teach (players.py).
        base = 0.0 if self.meta.get("source") == "replay" else float(self.meta.get("weight", 1.0))
        w = np.full(max(ticks, 1), base, dtype=np.float32)
        for mark in self.meta.get("marks") or []:
            t = int(mark.get("tick", 0))
            lo, hi = max(0, t - MARK_RADIUS), min(len(w), t + MARK_RADIUS + 1)
            if mark.get("type") == "mistake":
                w[lo:hi] = 0.0
            elif mark.get("type") == "good":
                w[lo:hi] = np.maximum(w[lo:hi], GOOD_WEIGHT) * (w[lo:hi] > 0)
        return w


class SequenceSampler:
    """Random training windows of `length` ticks from a set of recordings.

    `split` holds out the last `holdout` fraction of each recording for validation.
    """

    def __init__(self, directories, length: int = 32, split: str = "train", holdout: float = 0.1,
                 cache_chunks: int = 8, seed: int | None = None, vpt_targets: bool = False, mirror: float = 0.0,
                 hindsight: float = 0.0):
        self.vpt_targets = vpt_targets
        # Chance that a window is labelled with what the player achieved next as its goal
        # (see hindsight.py); the rest train with no goal, as in free play.
        self.hindsight = hindsight
        self.mirror = mirror  # Chance that a training window is used flipped left-right.
        self.recordings = [Recording(d) for d in directories]
        self.length = length
        self.rng = random.Random(seed)
        self.cache = OrderedDict()
        self.cache_chunks = cache_chunks
        self.windows = []  # (recording index, chunk index, start offset, end offset) per chunk
        for ri, rec in enumerate(self.recordings):
            usable = rec.last - rec.first + 1
            if usable <= 0:
                continue
            cut = rec.first + int(usable * (1 - holdout))
            lo, hi = (rec.first, cut) if split == "train" else (cut, rec.last + 1)
            for ci in range(rec.chunks):
                c_lo = max(lo, ci * rec.ticks_per_chunk)
                c_hi = min(hi, (ci + 1) * rec.ticks_per_chunk)
                if c_hi - c_lo >= length:
                    self.windows.append((ri, ci, c_lo, c_hi))
        self.total_ticks = sum(w[3] - w[2] for w in self.windows)

    def __len__(self):
        return self.total_ticks

    def _chunk(self, ri: int, ci: int) -> dict:
        key = (ri, ci)
        if key in self.cache:
            self.cache.move_to_end(key)
            return self.cache[key]
        rec = self.recordings[ri]
        by_tick = {tick: (record, jpeg) for tick, record, jpeg in read_chunk(rec.chunk_path(ci))}
        self.cache[key] = by_tick
        while len(self.cache) > self.cache_chunks:
            self.cache.popitem(last=False)
        return by_tick

    def sample(self) -> dict | None:
        """One window as stacked arrays, or None if this window has gaps."""
        if not self.windows:
            raise ValueError("No recording has enough usable ticks to train on")
        # Pick chunks in proportion to their length.
        total = self.total_ticks
        target = self.rng.randrange(total)
        for ri, ci, lo, hi in self.windows:
            if target < hi - lo:
                break
            target -= hi - lo
        start = self.rng.randrange(lo, hi - self.length + 1)
        return self.window(ri, ci, start)

    def window(self, ri: int, ci: int, start: int) -> dict | None:
        chunk = self._chunk(ri, ci)
        rec = self.recordings[ri]
        feats, targets, frames = [], [], []
        goals = None
        if self.hindsight > 0 and self.rng.random() < self.hindsight:
            from . import hindsight

            goals = hindsight.label(chunk, start, self.length)
        weights = np.zeros(self.length, dtype=np.float32)
        camera_ok = np.ones(self.length, dtype=np.float32)
        has_frame = np.ones(self.length, dtype=np.float32)
        for i in range(self.length):
            t = start + i
            entry = chunk.get(t)
            if entry is None:
                return None
            record, jpeg = entry
            obs = record.get("obs") or {}
            if goals is not None and goals[i] is not None:
                obs = dict(obs, goal=goals[i])
            feats.append(features.featurize(obs))
            targets.append(actions.encode_input(record.get("input") or {}))
            if jpeg is None or record.get("frameAge", 0) < 0:
                frames.append(np.zeros((features.FRAME_SIZE, features.FRAME_SIZE, 3), dtype=np.uint8))
                has_frame[i] = 0
            else:
                frames.append(decode_jpeg(jpeg))
            weights[i] = (rec.weights[t] if t < len(rec.weights) else 1.0) * float(record.get("w", 1.0))
            if record.get("cameraJump") or record.get("obs") is None:
                camera_ok[i] = 0
            if record.get("obs") is None:
                weights[i] = 0
        out = features.batch(feats)
        out.update({f"target_{k}": v for k, v in features.batch(targets).items()})
        if self.vpt_targets:
            from ..vpt import actions as vpt_actions

            pairs = [vpt_actions.encode(chunk[start + i][0].get("input") or {}) for i in range(self.length)]
            out["vpt_buttons"] = np.array([b for b, _ in pairs], dtype=np.int64)
            out["vpt_camera"] = np.array([c for _, c in pairs], dtype=np.int64)
        out["frames"] = np.stack(frames)
        out["target_mark"] = np.array([rec.mark_ticks.get(start + i, 0) for i in range(self.length)], dtype=np.int64)
        out["weight"] = weights
        out["camera_ok"] = camera_ok
        out["has_frame"] = has_frame
        return out

    def batch(self, size: int) -> dict:
        """`size` windows stacked as [batch, time, ...]."""
        windows = []
        attempts = 0
        while len(windows) < size:
            w = self.sample()
            attempts += 1
            if w is not None:
                if self.mirror and self.rng.random() < self.mirror:
                    w = mirror_window(w)
                windows.append(w)
            elif attempts > size * 20:
                raise ValueError("Recordings have too many missing ticks to sample from")
        return {k: np.stack([w[k] for w in windows]) for k in windows[0]}


TARGET_KEYS = ("keys", "attacks", "uses", "yaw", "pitch")


def mirror_window(w: dict) -> dict:
    """A window from window() flipped left-right (inputs and targets)."""
    out = features.mirror(w)
    t = actions.mirror_targets({k: w[f"target_{k}"] for k in TARGET_KEYS})
    for k in TARGET_KEYS:
        out[f"target_{k}"] = t[k]
    if "vpt_buttons" in w:
        from ..vpt import actions as vpt_actions

        out["vpt_buttons"] = np.array([vpt_actions.mirror_buttons(b) for b in w["vpt_buttons"]], dtype=np.int64)
        out["vpt_camera"] = np.array([vpt_actions.mirror_camera(c) for c in w["vpt_camera"]], dtype=np.int64)
    return out
