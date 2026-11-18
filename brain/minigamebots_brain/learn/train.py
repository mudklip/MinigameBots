"""Imitation training (behaviour cloning): teach an AI to do what the recordings show.

Run by the mod's Training screen as a separate process:

    python -m minigamebots_brain train --profile <dir> --recording <dir> [--recording <dir> ...]
        [--steps N] [--resources quiet|balanced|max] [--fresh] [--base lite|vpt --foundation <dir>]

Progress is printed as one JSON object per line ({"type": "progress", ...}); the mod draws
graphs from it. Writing "stop" to stdin finishes the current step, saves, and exits.
"""

import argparse
import json
import math
import os
import sys
import threading
import time

import torch
import torch.nn.functional as F

from . import actions, checkpoint
from .model import LitePolicy, count_parameters, to_tensors
from .recording import SequenceSampler

SEQ_LEN = 32
BURN_IN = 8  # First ticks of each window only warm up the memory; they aren't scored.

# How hard training may push the PC (see the design's resource profiles).
RESOURCES = {
    "quiet": {"threads": 2, "batch": 8, "pause": 0.3},
    "balanced": {"threads": 3, "batch": 16, "pause": 0.05},
    "max": {"threads": max(1, (os.cpu_count() or 2) - 1), "batch": 32, "pause": 0.0},
}


def emit(kind: str, **fields) -> None:
    print(json.dumps({"type": kind, **fields}), flush=True)


def loss_fn(logits: dict, batch: dict) -> tuple:
    """Weighted imitation loss and accuracy numbers."""
    w = batch["weight"][:, BURN_IN:]
    cam_ok = batch["camera_ok"][:, BURN_IN:] * w
    denom = w.sum().clamp(min=1.0)

    def cut(t):
        return t[:, BURN_IN:]

    keys = F.binary_cross_entropy_with_logits(cut(logits["keys"]), cut(batch["target_keys"]), reduction="none").sum(-1)
    attacks = F.cross_entropy(cut(logits["attacks"]).flatten(0, 1), cut(batch["target_attacks"]).flatten(),
                              reduction="none").view_as(w)
    uses = F.cross_entropy(cut(logits["uses"]).flatten(0, 1), cut(batch["target_uses"]).flatten(),
                           reduction="none").view_as(w)
    yaw = F.cross_entropy(cut(logits["yaw"]).flatten(0, 1), cut(batch["target_yaw"]).flatten(),
                          reduction="none").view_as(w)
    pitch = F.cross_entropy(cut(logits["pitch"]).flatten(0, 1), cut(batch["target_pitch"]).flatten(),
                            reduction="none").view_as(w)
    total = ((keys + attacks + uses) * w).sum() / denom + ((yaw + pitch) * cam_ok).sum() / cam_ok.sum().clamp(min=1.0)
    if "mark" in logits and "target_mark" in batch:
        # Marking a place is rare: those ticks count much more than "don't mark" ticks.
        tm = cut(batch["target_mark"])
        mark = F.cross_entropy(cut(logits["mark"]).flatten(0, 1).float(), tm.flatten(), reduction="none").view_as(w)
        mark_w = w * torch.where(tm > 0, torch.full_like(w, 50.0), torch.ones_like(w))
        total = total + 0.2 * (mark * mark_w).sum() / denom

    with torch.no_grad():
        pred_keys = (cut(logits["keys"]) > 0).float()
        key_acc = ((pred_keys == cut(batch["target_keys"])).float().mean(-1) * w).sum() / denom
        yaw_acc = ((cut(logits["yaw"]).argmax(-1) == cut(batch["target_yaw"])).float() * cam_ok).sum() / cam_ok.sum().clamp(min=1.0)
    return total, {"key_acc": float(key_acc), "camera_acc": float(yaw_acc)}


class StopFlag:
    """Set when the mod writes "stop" to our stdin (or stdin closes, i.e. the mod is gone)."""

    def __init__(self):
        self.stop = False
        threading.Thread(target=self._watch, daemon=True).start()

    def _watch(self):
        for line in sys.stdin:
            if line.strip() == "stop":
                break
        self.stop = True


def train(profile: str, recordings: list, steps: int, resources: str = "balanced", fresh: bool = False,
          device: str | None = None, stop: StopFlag | None = None, eval_every: int = 250, seed: int = 0,
          batch_size: int | None = None) -> dict:
    res = RESOURCES[resources]
    torch.set_num_threads(res["threads"])
    torch.manual_seed(seed)
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    use_amp = device == "cuda"

    train_data = SequenceSampler(recordings, SEQ_LEN, "train", seed=seed)
    val_data = SequenceSampler(recordings, SEQ_LEN, "val", seed=seed + 1)
    if len(train_data) == 0:
        raise ValueError("Not enough recorded ticks to train on (need at least a few seconds per recording)")

    info = checkpoint.read_info(profile) if not fresh else None
    if info is not None:
        try:
            model, info = checkpoint.load(profile, "latest", device)
        except ValueError as e:
            emit("log", message=f"{e}. Starting fresh instead.")
            info = None
    if info is not None:
        step0 = int(info.get("step", 0))
        best = float(info.get("val_loss") or math.inf)
        emit("log", message=f"Continuing from step {step0}")
    else:
        model = LitePolicy().to(device)
        info, step0, best = {}, 0, math.inf
        emit("log", message="Starting a fresh network")
    optimizer = torch.optim.AdamW(model.parameters(), lr=3e-4, weight_decay=1e-4)
    opt_path = os.path.join(checkpoint.core_dir(profile), "optimizer.pt")
    if info and os.path.isfile(opt_path):
        try:
            optimizer.load_state_dict(torch.load(opt_path, map_location=device, weights_only=True))
        except (RuntimeError, ValueError):
            emit("log", message="Optimizer state didn't match; starting it fresh")
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    emit("start", parameters=count_parameters(model), device=device, train_ticks=len(train_data),
         val_ticks=len(val_data), steps=steps, batch=batch_size or res["batch"])
    batch_size = batch_size or res["batch"]
    started = time.time()
    last_val = info.get("val_loss") if info else None
    recent = []
    step = 0
    for step in range(1, steps + 1):
        if stop is not None and stop.stop:
            emit("log", message="Stopping on request")
            break
        batch = to_tensors(train_data.batch(batch_size), device)
        model.train()
        with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=use_amp):
            logits, _ = model(batch)
            loss, metrics = loss_fn(logits, batch)
        optimizer.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(optimizer)
        scaler.update()
        recent.append(float(loss.detach()))
        recent = recent[-20:]

        if step % 10 == 0 or step == 1:
            elapsed = time.time() - started
            emit("progress", step=step, total=steps, global_step=step0 + step, loss=sum(recent) / len(recent),
                 val_loss=last_val, eta_s=elapsed / step * (steps - step), **metrics)
        if step % eval_every == 0 or step == steps:
            last_val = evaluate(model, val_data, batch_size, device, use_amp) if len(val_data) else sum(recent) / len(recent)
            improved = last_val < best
            best = min(best, last_val)
            save_checkpoint(profile, model, optimizer, step0 + step, last_val, best, recordings, improved)
            emit("checkpoint", step=step0 + step, val_loss=last_val, best=improved)
        if res["pause"]:
            # Leave the CPU/GPU some breathing room (quieter fans).
            time.sleep(res["pause"])
    else:
        step = steps

    if step % eval_every != 0 and step != steps:
        last_val = evaluate(model, val_data, batch_size, device, use_amp) if len(val_data) else None
        improved = last_val is not None and last_val < best
        save_checkpoint(profile, model, optimizer, step0 + step, last_val, min(best, last_val or best), recordings, improved)
    hours = (time.time() - started) / 3600
    result = {"steps": step, "global_step": step0 + step, "val_loss": last_val, "hours": hours}
    emit("done", **result)
    return result


def save_checkpoint(profile, model, optimizer, global_step, val_loss, best, recordings, improved):
    info = {"step": global_step, "val_loss": val_loss, "best_val_loss": best,
            "recordings": [os.path.basename(r.rstrip("/\\")) for r in recordings]}
    checkpoint.save(profile, model, info, best=improved or not os.path.isfile(
        os.path.join(checkpoint.core_dir(profile), "model.safetensors")), optimizer=optimizer)


@torch.no_grad()
def evaluate(model, data, batch_size, device, use_amp, batches: int = 4) -> float:
    model.eval()
    total = 0.0
    for _ in range(batches):
        batch = to_tensors(data.batch(batch_size), device)
        with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=use_amp):
            logits, _ = model(batch)
            loss, _ = loss_fn(logits, batch)
        total += float(loss)
    return total / batches


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="minigamebots_brain train")
    p.add_argument("--profile", required=True)
    p.add_argument("--recording", action="append", required=True)
    p.add_argument("--steps", type=int, default=2000)
    p.add_argument("--resources", choices=sorted(RESOURCES), default="balanced")
    p.add_argument("--fresh", action="store_true")
    p.add_argument("--device")
    p.add_argument("--base", choices=["lite", "vpt"], default="lite")
    p.add_argument("--foundation", help="installed foundation folder (with --base vpt)")
    args = p.parse_args(argv)
    try:
        if args.base == "vpt":
            from . import vpt_tune

            if not args.foundation:
                raise ValueError("Choose a foundation model to train on")
            vpt_tune.train(args.profile, args.recording, args.steps, args.foundation, args.resources, args.fresh,
                           args.device, StopFlag())
        else:
            train(args.profile, args.recording, args.steps, args.resources, args.fresh, args.device, StopFlag())
        return 0
    except (ValueError, FileNotFoundError) as e:
        emit("error", message=str(e))
        return 1
    except Exception as e:  # Show the real problem in the mod rather than a silent exit.
        import traceback

        emit("error", message=f"{type(e).__name__}: {e}")
        traceback.print_exc()
        return 1
