"""Foundation models: the big shared pretrained base, stored once per game folder.

    <minigamebots>/foundations/<id>/foundation.json   what it is (read by the mod)
    <minigamebots>/foundations/<id>/model.safetensors  the weights (float16)

Installing downloads OpenAI's published VPT weights, checks their SHA-256, loads them as
plain tensors (``weights_only``, never unpickling code), checks every tensor fits the
network, and stores them as safetensors so later loads never touch pickle at all.

    python -m minigamebots_brain foundation install --id vpt-1x --root <foundations dir>

Progress is printed as JSON lines like training. Writing "stop" to stdin cancels.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import threading
import time
import urllib.error
import urllib.request

BASE_URL = "https://openaipublic.blob.core.windows.net/minecraft-rl/models/"

CATALOG = {
    "vpt-1x": {
        "name": "VPT 1x (recommended)",
        "description": "OpenAI's Minecraft foundation model, 71M parameters. Runs well on a 6 GB GPU.",
        "size": "1x",
        "file": "foundation-model-1x.weights",
        "bytes": 284045685,
        "sha256": "016a7f435aed16e03d33d9ea086baec68f9d64ce391057304273fde78975d1ee",
    },
    "vpt-2x": {
        "name": "VPT 2x (large)",
        "description": "248M parameters. Smarter but slower; needs a strong GPU to play smoothly.",
        "size": "2x",
        "file": "foundation-model-2x.weights",
        "bytes": 994032309,
        "sha256": "5b20bd5af5cf1efd322dad727264bbca8fa77c13f51fa5baf2c4e73b13baee43",
    },
}
META = "foundation.json"
WEIGHTS = "model.safetensors"


def emit(event: str, **fields) -> None:
    print(json.dumps({"type": event, **fields}), flush=True)


class Cancelled(Exception):
    pass


def foundation_dir(root: str, fid: str) -> str:
    return os.path.join(root, fid)


def read_meta(directory: str) -> dict | None:
    path = os.path.join(directory, META)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def install(fid: str, root: str, source: str | None = None, cancel=None) -> dict:
    """Downloads (or copies from `source`), verifies and converts one catalog foundation."""
    if fid not in CATALOG:
        raise ValueError(f"unknown foundation '{fid}', choose from {sorted(CATALOG)}")
    entry = CATALOG[fid]
    directory = foundation_dir(root, fid)
    os.makedirs(directory, exist_ok=True)
    raw = os.path.join(directory, "download.part")
    try:
        _fetch(source or BASE_URL + entry["file"], raw, entry, cancel)
        emit("log", message="Checking the download")
        digest = _sha256(raw)
        if entry.get("sha256") and digest != entry["sha256"]:
            raise ValueError("The download is damaged or not the expected file (checksum mismatch)")
        emit("log", message="Converting to the mod's format")
        _convert(raw, os.path.join(directory, WEIGHTS), entry["size"])
    finally:
        if os.path.exists(raw):
            os.remove(raw)
    meta = {
        "id": fid, "name": entry["name"], "description": entry["description"], "kind": "vpt",
        "size": entry["size"], "sha256": digest, "sizeBytes": os.path.getsize(os.path.join(directory, WEIGHTS)),
        "installed": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    tmp = os.path.join(directory, META + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    os.replace(tmp, os.path.join(directory, META))  # Written last: its presence means "installed".
    emit("done", **meta)
    return meta


def remove(fid: str, root: str) -> None:
    directory = foundation_dir(root, fid)
    if os.path.isdir(directory):
        shutil.rmtree(directory)


def _fetch(source: str, dest: str, entry: dict, cancel, attempts: int = 5) -> None:
    if os.path.isfile(source):
        shutil.copyfile(source, dest)
        return
    if os.path.exists(dest):
        os.remove(dest)  # Leftover from an interrupted earlier install.
    expected = int(entry.get("bytes") or 0)
    for attempt in range(1, attempts + 1):
        try:
            _download(source, dest, expected, cancel)
            break
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            if attempt == attempts:
                raise ValueError(f"Download failed ({e}); check the connection and try again") from e
            wait = 2 ** attempt
            emit("log", message=f"Connection problem ({e}); retrying in {wait} s")
            time.sleep(wait)
    if expected and os.path.getsize(dest) != expected:
        raise ValueError("The download was cut off; try again")


def _download(source: str, dest: str, expected: int, cancel) -> None:
    """Downloads into `dest`, continuing a partial file if the server allows it."""
    have = os.path.getsize(dest) if os.path.exists(dest) else 0
    headers = {"User-Agent": "minigamebots"}
    if have:
        headers["Range"] = f"bytes={have}-"
    request = urllib.request.Request(source, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        if have and response.status != 206:
            have = 0  # Server ignored the range: start over.
        total = expected or (have + int(response.headers.get("Content-Length") or 0))
        done, last = have, 0.0
        started, start_done = time.time(), have
        with open(dest, "ab" if have else "wb") as out:
            while True:
                if cancel is not None and cancel.stop:
                    raise Cancelled()
                block = response.read(1 << 20)
                if not block:
                    break
                out.write(block)
                done += len(block)
                now = time.time()
                if now - last > 0.5:
                    last = now
                    rate = (done - start_done) / max(now - started, 1e-6)
                    emit("progress", done=done, total=total, eta_s=(total - done) / rate if total and rate else None)


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _convert(raw: str, dest: str, size: str) -> None:
    import torch
    from safetensors.torch import save_file

    from .network import VPTModel

    state = torch.load(raw, map_location="cpu", weights_only=True)
    if not isinstance(state, dict) or not all(isinstance(v, torch.Tensor) for v in state.values()):
        raise ValueError("Not a VPT weights file")
    with torch.device("meta"):
        expected = VPTModel(size).state_dict()
    if set(state) != set(expected) or any(tuple(state[k].shape) != tuple(expected[k].shape) for k in expected):
        raise ValueError("The weights don't match the VPT network")
    half = {k: (v.half() if v.is_floating_point() else v).contiguous() for k, v in state.items()}
    save_file(half, dest + ".tmp", metadata={"vpt_size": size})
    os.replace(dest + ".tmp", dest)


def load(directory: str, device: str = "cpu", dtype=None):
    """(VPTModel, meta) for an installed foundation folder."""
    import torch
    from safetensors.torch import load_file

    from .network import VPTModel

    meta = read_meta(directory)
    if meta is None:
        raise FileNotFoundError("That foundation isn't installed (open Brain > Foundations)")
    model = VPTModel(meta.get("size", "1x"))
    state = load_file(os.path.join(directory, WEIGHTS), device="cpu")
    model.load_state_dict({k: v.float() for k, v in state.items()})
    model.eval()
    return model.to(device=device, dtype=dtype or torch.float32), meta


class _StopFlag:
    def __init__(self):
        self.stop = False
        threading.Thread(target=self._watch, daemon=True).start()

    def _watch(self):
        for line in sys.stdin:
            if line.strip() == "stop":
                break
        self.stop = True


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="minigamebots_brain foundation")
    sub = p.add_subparsers(dest="cmd", required=True)
    i = sub.add_parser("install")
    i.add_argument("--id", required=True)
    i.add_argument("--root", required=True)
    i.add_argument("--source", help="use an already downloaded .weights file instead of downloading")
    r = sub.add_parser("remove")
    r.add_argument("--id", required=True)
    r.add_argument("--root", required=True)
    sub.add_parser("catalog")
    args = p.parse_args(argv)
    try:
        if args.cmd == "install":
            install(args.id, args.root, args.source, _StopFlag())
        elif args.cmd == "remove":
            remove(args.id, args.root)
            emit("done", id=args.id)
        else:
            emit("catalog", foundations={k: {kk: v[kk] for kk in ("name", "description", "bytes")}
                                         for k, v in CATALOG.items()})
        return 0
    except Cancelled:
        emit("error", message="Cancelled")
        return 1
    except Exception as e:  # Shown in the mod.
        emit("error", message=f"{e}" if isinstance(e, (ValueError, OSError)) else f"{type(e).__name__}: {e}")
        return 1
