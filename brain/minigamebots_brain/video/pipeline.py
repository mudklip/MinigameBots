"""Video Lab import pipeline (DESIGN 3.10): a gameplay video -> labelled demos + questions.

1. Decode with the bundled FFmpeg (imageio-ffmpeg) at 20 frames a second, like game ticks.
2. Cut into shots where the picture jumps.
3. Keep first-person gameplay: the hotbar strip stays still while the world moves.
4. Mask static overlays (facecam, watermark) that never move while the world does.
5. Score quality (sharpness, length) and drop bad shots.
6. Guess the inputs with the input guesser (idm.py).
7. Hotbar items that don't match the reference atlas become questions, unless a Knowledge Book
   for the video's tags already knows them.
8. Write each kept shot as a recording (source "video", weight 0.5), so it trains like any other.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import struct
import time

import numpy as np
from PIL import Image

from ..learn import features
from . import idm as idm_mod

FPS = 20
DECODE_W, DECODE_H = 480, 270
CUT = 40.0  # Mean pixel change (0-255) that counts as a cut.
MIN_SHOT = 2 * FPS


def decode(path: str):
    """Yields HxWx3 uint8 frames at 20 fps and DECODE_W x DECODE_H."""
    import imageio_ffmpeg

    reader = imageio_ffmpeg.read_frames(path, output_params=["-vf", f"fps={FPS},scale={DECODE_W}:{DECODE_H}"],
                                        pix_fmt="rgb24")
    meta = next(reader)
    for raw in reader:
        yield np.frombuffer(raw, dtype=np.uint8).reshape(DECODE_H, DECODE_W, 3), meta


def _gray(f):
    return f.mean(axis=2)


def shots(frames: list[np.ndarray]) -> list[tuple[int, int]]:
    out, start = [], 0
    small = [_gray(f[::8, ::8]) for f in frames]
    for i in range(1, len(frames)):
        if np.abs(small[i] - small[i - 1]).mean() > CUT:
            out.append((start, i))
            start = i
    out.append((start, len(frames)))
    return [s for s in out if s[1] - s[0] >= MIN_SHOT]


def hotbar_box(frames: list[np.ndarray]):
    """(x0, x1, y0, y1) of the still strip at the bottom centre, or None if there's none.

    "Still" = how often a pixel differs from its usual (median) value: the hotbar only changes
    when the selection or a count changes, the world changes all the time.
    """
    g = np.stack([_gray(f) for f in frames[:: max(1, len(frames) // 40)]])
    changing = (np.abs(g - np.median(g, axis=0)) > 12).mean(axis=0)
    h, w = changing.shape
    world = changing[int(h * 0.2):int(h * 0.7), int(w * 0.2):int(w * 0.8)].mean()
    if world < 0.15:
        return None  # The picture barely moves: a menu, a still or a paused game.
    still_cols = changing[int(h * 0.9):, :].mean(axis=0) < world * 0.35
    c = w // 2
    if not still_cols[c]:
        return None
    x0 = c
    while x0 > 0 and still_cols[x0 - 1]:
        x0 -= 1
    x1 = c
    while x1 < w - 1 and still_cols[x1 + 1]:
        x1 += 1
    if not (0.15 * w <= x1 - x0 <= 0.6 * w):
        return None
    hb_h = int(round((x1 - x0) * 22 / 182))  # Vanilla hotbar: 182 x 22.
    return x0, x1, h - hb_h - 1, h - 1


def overlay_mask(small: np.ndarray) -> np.ndarray:
    """True where a (squeezed) pixel never changes while the world moves: overlays to blank out."""
    std = small.astype(np.float32).std(axis=0).mean(axis=-1)
    if std.mean() < 8.0:
        return np.zeros(std.shape, dtype=bool)
    mask = std < 1.5
    s = std.shape[0]
    mask[int(s * 0.85):, int(s * 0.25):int(s * 0.75)] = False  # The hotbar is real HUD.
    mask[s // 2 - 4:s // 2 + 4, s // 2 - 4:s // 2 + 4] = False  # Crosshair.
    return mask


def sharpness(f: np.ndarray) -> float:
    g = _gray(f)
    lap = 4 * g[1:-1, 1:-1] - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:]
    return float(lap.var())


def ahash(img: np.ndarray) -> str:
    small = np.asarray(Image.fromarray(img).convert("L").resize((8, 8), Image.BILINEAR), dtype=np.float32)
    bits = (small > small.mean()).flatten()
    return "%016x" % int("".join("1" if b else "0" for b in bits), 2)


class Atlas:
    """Item icons exported from your game (Video Lab > Build atlas), as 16x16 RGBA."""

    def __init__(self, directory: str):
        self.ids, icons = [], []
        for name in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
            if not name.endswith(".png"):
                continue
            try:
                im = Image.open(os.path.join(directory, name)).convert("RGBA")
                if im.height > im.width:  # Animated texture: a strip of frames; use the first.
                    im = im.crop((0, 0, im.width, im.width))
                im = im.resize((16, 16), Image.NEAREST)
            except OSError:
                continue
            self.ids.append(name[:-4].replace("__", ":", 1))
            icons.append(np.asarray(im, dtype=np.float32))
        self.icons = np.stack(icons) if icons else np.zeros((0, 16, 16, 4), dtype=np.float32)

    def match(self, crop: np.ndarray) -> list[tuple[str, float]]:
        """Best atlas matches (id, distance 0..1) for a slot's icon area."""
        if not len(self.icons):
            return []
        c = np.asarray(Image.fromarray(crop).resize((16, 16), Image.BILINEAR), dtype=np.float32)
        alpha = self.icons[..., 3:] / 255.0
        d = (np.abs(self.icons[..., :3] - c[None]) * alpha).sum(axis=(1, 2, 3)) / (alpha.sum(axis=(1, 2, 3)) * 3 * 255 + 1e-6)
        order = np.argsort(d)[:3]
        return [(self.ids[i], float(d[i])) for i in order]


def load_books(books_dir: str, tags: list[str]) -> dict:
    known = {}
    for t in tags:
        try:
            with open(os.path.join(books_dir, f"{t}.json"), encoding="utf-8") as f:
                known.update(json.load(f).get("items", {}))
        except (OSError, ValueError):
            pass
    return known


def write_recording(directory: str, frames128: list[np.ndarray], inputs: list[dict], meta: dict) -> None:
    os.makedirs(directory, exist_ok=True)
    per = 1200
    for ci in range(0, len(frames128), per):
        buf = io.BytesIO()
        for t in range(ci, min(ci + per, len(frames128))):
            rec = json.dumps({"input": inputs[t], "obs": {}, "events": [], "frameAge": 0}).encode("utf-8")
            jb = io.BytesIO()
            Image.fromarray(frames128[t]).save(jb, format="JPEG", quality=85)
            jpeg = jb.getvalue()
            buf.write(struct.pack(">ii", t, len(rec)) + rec + struct.pack(">i", len(jpeg)) + jpeg)
        with gzip.open(os.path.join(directory, f"chunk-{ci // per:05d}.bin.gz"), "wb") as f:
            f.write(buf.getvalue())
    m = dict(meta, ticks=len(frames128), chunks=(len(frames128) + per - 1) // per, ticksPerChunk=per,
             frameSize=features.FRAME_SIZE, source="video", weight=0.5)
    with open(os.path.join(directory, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(m, f, indent=1)


def run(video: str, recordings: str, lab: str, tags: list[str], log=print) -> dict:
    """Imports one video. `lab` is the Video Lab folder (idm/, atlas/, books/, questions/)."""
    tags = tags or ["untagged"]  # Answers for untagged videos go to the "untagged" book.
    labeler = idm_mod.Labeler(os.path.join(lab, "idm"))
    atlas = Atlas(os.path.join(lab, "atlas"))
    known = load_books(os.path.join(lab, "books"), tags)
    frames = []
    for f, _ in decode(video):
        frames.append(f)
        if len(frames) % 400 == 0:
            log(f"decoded {len(frames) // FPS} s")
    name = os.path.splitext(os.path.basename(video))[0]
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in name)[:40]
    report = {"video": os.path.basename(video), "seconds": len(frames) / FPS, "shots": 0, "kept": 0,
              "kept_seconds": 0.0, "questions": 0, "recognized_items": 0, "dropped": {}}
    questions = []
    qdir = os.path.join(lab, "questions")
    os.makedirs(qdir, exist_ok=True)
    for si, (a, b) in enumerate(shots(frames)):
        report["shots"] += 1
        shot = frames[a:b]
        box = hotbar_box(shot)
        if box is None:
            report["dropped"]["not first-person gameplay"] = report["dropped"].get("not first-person gameplay", 0) + 1
            continue
        sharp = np.median([sharpness(f) for f in shot[::10]])
        if sharp < 20:
            report["dropped"]["blurry"] = report["dropped"].get("blurry", 0) + 1
            continue
        small = np.stack([np.asarray(Image.fromarray(f).resize((features.FRAME_SIZE,) * 2, Image.BILINEAR)) for f in shot])
        mask = overlay_mask(small)
        small[:, mask] = 0
        inputs = labeler.label([idm_mod.shrink(f) for f in small])
        rid = f"video-{safe}-{si:03d}"
        write_recording(os.path.join(recordings, rid), list(small), inputs,
                        {"id": rid, "name": f"{name} #{si + 1}", "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
                         "tags": tags, "videoQuality": round(float(sharp), 1), "maskedPixels": int(mask.sum())})
        report["kept"] += 1
        report["kept_seconds"] += len(shot) / FPS
        # Hotbar items: known (atlas or a Knowledge Book), or a question.
        x0, x1, y0, y1 = box
        mid = shot[len(shot) // 2]
        slot = (x1 - x0) / 9
        crops = []
        for k in range(9):
            sx = int(x0 + k * slot + slot * 0.18)
            crops.append((sx, mid[y0 + int((y1 - y0) * 0.18):y1 - int((y1 - y0) * 0.18), sx:sx + int(slot * 0.64)]))
        means = [c.reshape(-1, 3).mean(axis=0) if c.size else np.zeros(3) for _, c in crops]
        for k, (sx, crop) in enumerate(crops):
            alike = sum(np.abs(means[k] - m).max() < 10 for j, m in enumerate(means) if j != k)
            if crop.size == 0 or (alike >= 3 and crop.std() < 25):
                continue  # Empty slot: plain, and looks like the other empty ones.
            h = ahash(crop)
            if h in known:
                report["recognized_items"] += 1
                continue
            best = atlas.match(crop)
            if best and best[0][1] < 0.12:
                report["recognized_items"] += 1
                continue
            qid = hashlib.sha1(f"{name}-{si}-{k}-{h}".encode()).hexdigest()[:12]
            Image.fromarray(crop).resize((64, 64), Image.NEAREST).save(os.path.join(qdir, f"{qid}.png"))
            Image.fromarray(mid).save(os.path.join(qdir, f"{qid}-frame.jpg"), quality=80)
            questions.append({"id": qid, "kind": "item", "hash": h, "video": os.path.basename(video), "tags": tags,
                              "box": [sx, y0, int(slot * 0.64), y1 - y0],
                              "suggestions": [s for s, _ in best], "value": 1.0 - (best[0][1] if best else 0.0)})
            known[h] = {"pending": True}
    # Merge into the queue, most useful first, without duplicates of the same look.
    qfile = os.path.join(qdir, "queue.json")
    try:
        with open(qfile, encoding="utf-8") as f:
            queue = json.load(f)
    except (OSError, ValueError):
        queue = []
    seen = {q["hash"] for q in queue}
    queue += [q for q in questions if q["hash"] not in seen]
    queue.sort(key=lambda q: -q.get("value", 0))
    with open(qfile, "w", encoding="utf-8") as f:
        json.dump(queue, f, indent=1)
    report["questions"] = len(questions)
    report["kept_seconds"] = round(report["kept_seconds"], 1)
    return report
