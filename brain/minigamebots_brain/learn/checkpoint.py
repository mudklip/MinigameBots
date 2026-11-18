"""Saving and loading the network inside an AI profile: <profile>/core/.

    core/model.safetensors    best weights so far (lowest validation loss)
    core/latest.safetensors   most recent weights (to continue training)
    core/optimizer.pt         optimizer state for continuing training
    core/model.json           architecture, feature version, training summary
"""

import json
import os
import time

import torch
from safetensors.torch import load_file, save_file

from . import features
from .model import ARCH, LitePolicy


def core_dir(profile: str) -> str:
    return os.path.join(profile, "core")


def read_info(profile: str) -> dict | None:
    path = os.path.join(core_dir(profile), "model.json")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(profile: str, model: LitePolicy, info: dict, best: bool, optimizer=None) -> None:
    os.makedirs(core_dir(profile), exist_ok=True)
    state = {k: v.detach().cpu().contiguous() for k, v in model.state_dict().items()}
    _atomic(lambda p: save_file(state, p), os.path.join(core_dir(profile), "latest.safetensors"))
    if best:
        _atomic(lambda p: save_file(state, p), os.path.join(core_dir(profile), "model.safetensors"))
    if optimizer is not None:
        _atomic(lambda p: torch.save(optimizer.state_dict(), p), os.path.join(core_dir(profile), "optimizer.pt"))
    info = dict(info)
    info.update({"arch": ARCH, "features": features.FEATURES_VERSION, "config": model.config,
                 "saved": time.strftime("%Y-%m-%dT%H:%M:%S")})
    _atomic(lambda p: _write_text(p, json.dumps(info, indent=2)), os.path.join(core_dir(profile), "model.json"))


def load(profile: str, which: str = "model", device="cpu") -> tuple:
    """(model, info). `which` is "model" (best) or "latest". Raises if missing or incompatible."""
    info = read_info(profile)
    if info is None:
        raise FileNotFoundError("This AI hasn't been trained yet")
    if info.get("arch") != ARCH or info.get("features") != features.FEATURES_VERSION:
        raise ValueError("This AI was trained with an older version of the network; train it again "
                         "(its recordings still work)")
    model = LitePolicy(**info.get("config", {}))
    path = os.path.join(core_dir(profile), f"{which}.safetensors")
    if not os.path.isfile(path):
        path = os.path.join(core_dir(profile), "model.safetensors")
    from .model import OPTIONAL_PREFIXES

    missing, unexpected = model.load_state_dict(load_file(path, device="cpu"), strict=False)
    if unexpected or any(not k.startswith(OPTIONAL_PREFIXES) for k in missing):
        raise ValueError("This AI's saved network doesn't match; train it again (its recordings still work)")
    return model.to(device), info


def _write_text(path: str, text: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def _atomic(write, path: str) -> None:
    tmp = path + ".tmp"
    write(tmp)
    os.replace(tmp, path)
