"""The input guesser (Inverse Dynamics Model): which keys and mouse moves happened between frames.

Videos have pictures but no inputs. Looking at a few frames around a moment (before and
after), this network guesses the input at that moment, which turns any first-person video
into a demo. It's trained on recordings that do have exact inputs (yours, the AI's and teacher
demos), so it keeps improving as you record. Seeing the future frames is what makes this much
easier than playing: the camera turn is visible in the next frame.

(OpenAI released a much larger IDM; porting it is a possible later upgrade. This one is small
and fast enough to train and run on a 6 GB GPU or even the CPU.)
"""

from __future__ import annotations

import glob
import json
import os
import random

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from ..learn import actions, features

CONTEXT = 2  # Frames before and after the labelled one: 5 frames in all.
SIZE = 64  # Frames are shrunk to this for speed.
# Keys a video can show: movement, jumping, sneaking, sprinting, attacking and using.
VKEYS = ("forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use")


def shrink(frame: np.ndarray) -> np.ndarray:
    """HxWx3 uint8 -> SIZExSIZEx3 uint8 (area average)."""
    t = torch.from_numpy(np.ascontiguousarray(frame)).permute(2, 0, 1)[None].float()
    return F.interpolate(t, size=(SIZE, SIZE), mode="area")[0].permute(1, 2, 0).round().byte().numpy()


class IDM(nn.Module):
    def __init__(self):
        super().__init__()
        c = 3 * (2 * CONTEXT + 1)
        self.net = nn.Sequential(
            nn.Conv2d(c, 48, 5, 2, 2), nn.ReLU(), nn.Conv2d(48, 96, 3, 2, 1), nn.ReLU(),
            nn.Conv2d(96, 128, 3, 2, 1), nn.ReLU(), nn.Conv2d(128, 128, 3, 2, 1), nn.ReLU(), nn.Flatten(),
            nn.Linear(128 * (SIZE // 16) ** 2, 256), nn.ReLU())
        self.keys = nn.Linear(256, len(VKEYS))
        self.yaw = nn.Linear(256, actions.CAMERA_BINS)
        self.pitch = nn.Linear(256, actions.CAMERA_BINS)

    def forward(self, stack: torch.Tensor):
        """stack: [B, 2*CONTEXT+1, SIZE, SIZE, 3] uint8."""
        b = stack.shape[0]
        x = stack.permute(0, 1, 4, 2, 3).reshape(b, -1, SIZE, SIZE).float() / 255.0
        # Differences between neighbouring frames carry the motion; keep both.
        h = self.net(x)
        return self.keys(h), self.yaw(h), self.pitch(h)


def _targets(inp: dict):
    keys = np.array([k in (inp.get("keys") or ()) or (k == "attack" and int(inp.get("attacks", 0)) > 0) for k in VKEYS],
                    dtype=np.float32)
    return keys, actions.camera_to_bin(float(inp.get("yaw", 0.0))), actions.camera_to_bin(float(inp.get("pitch", 0.0)))


def load_examples(recording_dirs: list[str], limit_per_recording: int = 4000) -> list[tuple]:
    """(frame stack, keys, yaw bin, pitch bin) from recordings with frames."""
    from ..learn.recording import decode_jpeg, read_chunk

    out = []
    for d in recording_dirs:
        recs = []
        for path in sorted(glob.glob(os.path.join(d, "chunk-*.bin.gz"))):
            recs.extend(read_chunk(path))
        cache = {}

        def frame(i):
            j = recs[i][2]
            if j is None:
                return None
            if id(j) not in cache:
                cache[id(j)] = shrink(decode_jpeg(j))
            return cache[id(j)]

        idx = list(range(CONTEXT, len(recs) - CONTEXT))
        random.Random(0).shuffle(idx)
        for i in idx[:limit_per_recording]:
            rec = recs[i][1]
            if rec.get("cameraJump") or (rec.get("frameAge") or 0) < 0:
                continue
            frames = [frame(k) for k in range(i - CONTEXT, i + CONTEXT + 1)]
            if any(f is None for f in frames):
                continue
            k, y, p = _targets(rec.get("input") or {})
            out.append((np.stack(frames), k, y, p))
    return out


def train(recording_dirs: list[str], out_dir: str, steps: int = 1500, device: str | None = None, log=print) -> dict:
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    data = load_examples(recording_dirs)
    if len(data) < 200:
        raise ValueError("Need more recordings with pictures to learn from (a few minutes at least)")
    random.Random(1).shuffle(data)
    split = max(50, len(data) // 10)
    val, tr = data[:split], data[split:]
    model = IDM().to(device)
    path = os.path.join(out_dir, "idm.pt")
    if os.path.isfile(path):
        try:
            model.load_state_dict(torch.load(path, map_location=device, weights_only=True))
            log("Continuing from the saved input guesser")
        except (RuntimeError, ValueError):
            pass
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)

    def batch(items):
        s = torch.from_numpy(np.stack([i[0] for i in items])).to(device)
        k = torch.from_numpy(np.stack([i[1] for i in items])).to(device)
        y = torch.tensor([i[2] for i in items], device=device)
        p = torch.tensor([i[3] for i in items], device=device)
        return s, k, y, p

    rng = random.Random(2)
    for step in range(1, steps + 1):
        s, k, y, p = batch(rng.sample(tr, min(64, len(tr))))
        lk, ly, lp = model(s)
        loss = F.binary_cross_entropy_with_logits(lk, k) + F.cross_entropy(ly, y) + F.cross_entropy(lp, p)
        opt.zero_grad()
        loss.backward()
        opt.step()
        if step % 100 == 0:
            log(f"step {step}/{steps} loss {float(loss):.3f}")
    model.eval()
    with torch.no_grad():
        s, k, y, p = batch(val[:256])
        lk, ly, lp = model(s)
        key_acc = float(((lk > 0).float() == k).float().mean())
        cam_err = float((torch.tensor([actions.bin_to_camera(int(i)) for i in ly.argmax(-1)])
                         - torch.tensor([actions.bin_to_camera(int(i)) for i in y])).abs().mean())
    os.makedirs(out_dir, exist_ok=True)
    torch.save(model.state_dict(), path)
    info = {"examples": len(data), "key_accuracy": round(key_acc, 3), "yaw_error_deg": round(cam_err, 2)}
    with open(os.path.join(out_dir, "idm.json"), "w", encoding="utf-8") as f:
        json.dump(info, f)
    return info


class Labeler:
    """Guesses inputs for a sequence of frames with a trained input guesser."""

    def __init__(self, model_dir: str, device: str | None = None):
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.model = IDM().to(self.device)
        self.model.load_state_dict(torch.load(os.path.join(model_dir, "idm.pt"), map_location=self.device,
                                              weights_only=True))
        self.model.eval()

    @torch.no_grad()
    def label(self, small_frames: list[np.ndarray]) -> list[dict]:
        """Inputs for each frame (SIZExSIZE) that has CONTEXT frames on both sides; others get none."""
        n = len(small_frames)
        out = [{"keys": [], "attacks": 0, "uses": 0, "yaw": 0.0, "pitch": 0.0} for _ in range(n)]
        idx = list(range(CONTEXT, n - CONTEXT))
        for start in range(0, len(idx), 128):
            chunk = idx[start:start + 128]
            s = torch.from_numpy(np.stack([np.stack(small_frames[i - CONTEXT:i + CONTEXT + 1]) for i in chunk])).to(self.device)
            lk, ly, lp = self.model(s)
            for j, i in enumerate(chunk):
                keys = [VKEYS[k] for k in range(len(VKEYS)) if lk[j, k] > 0]
                out[i] = {"keys": keys, "attacks": 1 if "attack" in keys else 0, "uses": 0,
                          "yaw": actions.bin_to_camera(int(ly[j].argmax())), "pitch": actions.bin_to_camera(int(lp[j].argmax()))}
        return out

