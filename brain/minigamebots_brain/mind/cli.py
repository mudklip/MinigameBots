"""`python -m minigamebots_brain mind install|check --root <brain>/mind`."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys


def emit(kind, **d):
    print(json.dumps({"type": kind, **d}), flush=True)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="minigamebots_brain mind")
    p.add_argument("action", choices=["install", "check", "install-voice"])
    p.add_argument("--root", required=True)
    p.add_argument("--size", choices=["standard", "lite"], default="standard")
    args = p.parse_args(argv)
    from . import llm

    try:
        if args.action == "install-voice":
            from . import voice

            voice.install(args.root, log=lambda m: emit("log", message=m))
            emit("done", installed=True)
            return 0
        if args.action == "check":
            emit("done", installed=llm.installed(args.root))
            return 0
        emit("log", message="Installing the language model libraries...")
        r = subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "transformers>=4.45", "huggingface_hub>=0.24",
                            "accelerate>=0.33"], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("pip failed: " + (r.stderr or r.stdout)[-400:])
        llm.install(args.root, log=lambda m: emit("log", message=m), size=args.size)
        emit("done", installed=True)
        return 0
    except Exception as e:
        emit("error", message=f"{type(e).__name__}: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
