"""Imitation training on top of the VPT foundation model ("VPT" base in the Training screen).

The foundation stays frozen and shared; only its top layers (see vpt/agent.TRAINABLE_PREFIXES,
about 23M parameters for 1x) learn from this AI's recordings. Those layers are all the profile
stores, so each AI stays small and several AIs share one foundation download.

VPT sees only the picture, like a human watching the screen; it doesn't use the mod's
structured observation. Run through train.py with --base vpt.
"""
import json
import math
import os
import time

import torch
import torch.nn.functional as F
from safetensors.torch import save_file

from ..vpt import actions as vpt_actions
from ..vpt import agent, foundation as fnd
from .recording import SequenceSampler
from .train import RESOURCES, emit

SEQ_LEN = 32
BURN_IN = 8
# VPT is much bigger than Lite, so it uses smaller batches.
VPT_BATCH = {"quiet": 2, "balanced": 4, "max": 8}


def loss_fn(out: dict, batch: dict) -> tuple:
    w = batch["weight"][:, BURN_IN:]
    denom = w.sum().clamp(min=1.0)
    buttons_t = batch["vpt_buttons"][:, BURN_IN:]
    camera_t = batch["vpt_camera"][:, BURN_IN:]
    buttons = F.nll_loss(out["buttons"][:, BURN_IN:].flatten(0, 1), buttons_t.flatten(), reduction="none").view_as(w)
    camera = F.nll_loss(out["camera"][:, BURN_IN:].flatten(0, 1), camera_t.flatten(), reduction="none").view_as(w)
    # The camera head only matters on ticks where the camera moved (VPT's "camera on" buttons).
    cam_w = w * batch["camera_ok"][:, BURN_IN:] * (camera_t != vpt_actions.CAMERA_NULL).float()
    total = (buttons * w).sum() / denom + (camera * cam_w).sum() / cam_w.sum().clamp(min=1.0)
    with torch.no_grad():
        b_acc = ((out["buttons"][:, BURN_IN:].argmax(-1) == buttons_t).float() * w).sum() / denom
        c_acc = ((out["camera"][:, BURN_IN:].argmax(-1) == camera_t).float() * cam_w).sum() / cam_w.sum().clamp(min=1.0)
    return total, {"key_acc": float(b_acc), "camera_acc": float(c_acc)}


def _forward(model, batch, device):
    frames = torch.from_numpy(batch["frames"]).to(device)
    first = torch.zeros(frames.shape[:2], dtype=torch.bool, device=device)
    first[:, 0] = True
    out, _ = model(frames, first, model.initial_state(frames.shape[0]), temperature=1.0)
    return out


def _tensors(batch, device):
    keys = ("weight", "camera_ok", "vpt_buttons", "vpt_camera")
    return {k: torch.from_numpy(batch[k]).to(device) for k in keys}


def train(profile: str, recordings: list, steps: int, foundation: str, resources: str = "balanced",
          fresh: bool = False, device: str | None = None, stop=None, eval_every: int = 250, seed: int = 0,
          batch_size: int | None = None) -> dict:
    res = RESOURCES[resources]
    torch.set_num_threads(res["threads"])
    torch.manual_seed(seed)
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    use_amp = device == "cuda"
    if device == "cpu":
        emit("log", message="No usable GPU found: VPT training on the CPU is very slow")

    train_data = SequenceSampler(recordings, SEQ_LEN, "train", mirror=0.5, seed=seed, vpt_targets=True)
    val_data = SequenceSampler(recordings, SEQ_LEN, "val", seed=seed + 1, vpt_targets=True)
    if len(train_data) == 0:
        raise ValueError("Not enough recorded ticks to train on (need at least a few seconds per recording)")

    model, meta = fnd.load(foundation, "cpu")
    info = None if fresh else _read_info(profile)
    if info is not None and (info.get("arch") != agent.ARCH or info.get("foundation") != meta["id"]):
        emit("log", message="This AI was trained on a different base. Starting fresh on VPT instead.")
        info = None
    if info is not None:
        agent.load_profile_layers(model, profile, "latest")
        step0, best = int(info.get("step", 0)), float(info.get("val_loss") or math.inf)
        emit("log", message=f"Continuing from step {step0}")
    else:
        info, step0, best = {}, 0, math.inf
        emit("log", message=f"Starting from the {meta.get('name', meta['id'])} foundation")
    model.to(device)
    names = set(agent.trainable_names(model))
    for n, p in model.named_parameters():
        p.requires_grad_(n in names)
    params = [p for n, p in model.named_parameters() if n in names]
    optimizer = torch.optim.AdamW(params, lr=5e-5, weight_decay=0.0)
    opt_path = os.path.join(profile, "core", "optimizer.pt")
    if info and os.path.isfile(opt_path):
        try:
            optimizer.load_state_dict(torch.load(opt_path, map_location=device, weights_only=True))
        except (RuntimeError, ValueError, KeyError):
            emit("log", message="Optimizer state didn't match; starting it fresh")
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    batch_size = batch_size or VPT_BATCH[resources]
    emit("start", parameters=sum(p.numel() for p in params), device=device, train_ticks=len(train_data),
         val_ticks=len(val_data), steps=steps, batch=batch_size, base=meta["id"])
    started = time.time()
    last_val = info.get("val_loss") if info else None
    recent = []
    step = 0
    for step in range(1, steps + 1):
        if stop is not None and stop.stop:
            emit("log", message="Stopping on request")
            break
        raw = train_data.batch(batch_size)
        batch = _tensors(raw, device)
        model.train()
        with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=use_amp):
            out = _forward(model, raw, device)
            loss, metrics = loss_fn(out, batch)
        optimizer.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(params, 1.0)
        scaler.step(optimizer)
        scaler.update()
        recent = (recent + [float(loss.detach())])[-20:]
        if step % 10 == 0 or step == 1:
            elapsed = time.time() - started
            emit("progress", step=step, total=steps, global_step=step0 + step, loss=sum(recent) / len(recent),
                 val_loss=last_val, eta_s=elapsed / step * (steps - step), **metrics)
        if step % eval_every == 0 or step == steps:
            last_val = evaluate(model, val_data, batch_size, device, use_amp) if len(val_data) else sum(recent) / len(recent)
            improved = last_val < best
            best = min(best, last_val)
            save(profile, model, names, optimizer, meta, step0 + step, last_val, best, recordings, improved)
            emit("checkpoint", step=step0 + step, val_loss=last_val, best=improved)
        if res["pause"]:
            time.sleep(res["pause"])
    else:
        step = steps
    if step % eval_every != 0 and step != steps:
        last_val = evaluate(model, val_data, batch_size, device, use_amp) if len(val_data) else None
        improved = last_val is not None and last_val < best
        save(profile, model, names, optimizer, meta, step0 + step, last_val, min(best, last_val or best),
             recordings, improved)
    result = {"steps": step, "global_step": step0 + step, "val_loss": last_val,
              "hours": (time.time() - started) / 3600}
    emit("done", **result)
    return result


@torch.no_grad()
def evaluate(model, data, batch_size, device, use_amp, batches: int = 4) -> float:
    model.eval()
    total = 0.0
    for _ in range(batches):
        raw = data.batch(batch_size)
        with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=use_amp):
            loss, _ = loss_fn(_forward(model, raw, device), _tensors(raw, device))
        total += float(loss)
    return total / batches


def _read_info(profile: str) -> dict | None:
    path = os.path.join(profile, "core", "model.json")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(profile, model, names, optimizer, meta, global_step, val_loss, best, recordings, improved):
    core = os.path.join(profile, "core")
    os.makedirs(core, exist_ok=True)
    layers = {k: v.detach().to("cpu", torch.float16).contiguous() for k, v in model.state_dict().items() if k in names}
    _atomic(lambda p: save_file(layers, p), os.path.join(core, "latest.safetensors"))
    if improved or not os.path.isfile(os.path.join(core, "model.safetensors")):
        _atomic(lambda p: save_file(layers, p), os.path.join(core, "model.safetensors"))
    _atomic(lambda p: torch.save(optimizer.state_dict(), p), os.path.join(core, "optimizer.pt"))
    info = {"arch": agent.ARCH, "foundation": meta["id"], "foundation_size": meta.get("size"),
            "trainable": list(agent.TRAINABLE_PREFIXES), "step": global_step, "val_loss": val_loss,
            "best_val_loss": best, "recordings": [os.path.basename(r.rstrip("/\\")) for r in recordings],
            "saved": time.strftime("%Y-%m-%dT%H:%M:%S")}
    _atomic(lambda p: _write_text(p, json.dumps(info, indent=2)), os.path.join(core, "model.json"))


def _write_text(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def _atomic(write, path):
    tmp = path + ".tmp"
    write(tmp)
    os.replace(tmp, path)
