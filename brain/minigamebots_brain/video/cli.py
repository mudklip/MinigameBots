"""`python -m minigamebots_brain video ...`: train the input guesser, import videos."""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys


def emit(kind, **data):
    print(json.dumps({"type": kind, **data}), flush=True)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="minigamebots_brain video")
    sub = p.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("idm", help="train the input guesser on recordings that have pictures")
    t.add_argument("--recordings", required=True)
    t.add_argument("--lab", required=True)
    t.add_argument("--steps", type=int, default=1500)
    i = sub.add_parser("import", help="turn a video into labelled demos and questions")
    i.add_argument("--video", required=True)
    i.add_argument("--recordings", required=True)
    i.add_argument("--lab", required=True)
    i.add_argument("--tags", default="")
    args = p.parse_args(argv)
    try:
        if args.cmd == "idm":
            from . import idm

            dirs = [d for d in glob.glob(os.path.join(args.recordings, "*", ""))
                    if os.path.isfile(os.path.join(d, "meta.json")) and not os.path.basename(os.path.normpath(d)).startswith("video-")]
            emit("done", **idm.train(dirs, os.path.join(args.lab, "idm"), args.steps, log=lambda m: emit("log", message=m)))
        else:
            from . import pipeline

            tags = [x.strip() for x in args.tags.split(",") if x.strip()]
            emit("done", **pipeline.run(args.video, args.recordings, args.lab, tags, log=lambda m: emit("log", message=m)))
        return 0
    except Exception as e:
        emit("error", message=f"{type(e).__name__}: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
