"""The brain process. The mod starts it with a port and a one-time token; it connects back to
the mod over localhost, then answers each observation with an action until told to stop."""

import argparse
import json
import socket
import sys
import time
import traceback

from . import PROTOCOL, VERSION, policies, system
from .actions import NOOP
from .frames import FrameRing
from .protocol import Channel, ConnectionClosed

STATUS_EVERY_SECONDS = 2.0


def log(message: str) -> None:
    # stdout is captured by the mod into minigamebots/logs/brain.log
    print(message, flush=True)


class Brain:
    def __init__(self, channel: Channel, frames: FrameRing | None):
        self.channel = channel
        self.frames = frames
        self.policy = policies.create("idle")
        self.ticks = 0
        self.errors = 0
        self.think_ms = []
        self.last_status = 0.0

    def handle(self, message: dict) -> bool:
        kind = message.get("type")
        if kind == "obs":
            self.on_obs(message)
        elif kind == "set_policy":
            self.set_policy(message.get("name", "idle"), message.get("args") or {})
        elif kind == "ping":
            self.channel.send({"type": "pong", "id": message.get("id")})
        elif kind == "status":
            self.send_status(force=True)
        elif kind == "shutdown":
            log("Shutting down on request")
            return False
        else:
            log(f"Ignoring unknown message type {kind!r}")
        return True

    def set_policy(self, name: str, args: dict) -> None:
        try:
            self.policy = policies.create(name, args)
            log(f"Policy: {name} {args or ''}")
            self.channel.send({"type": "policy", "name": name, "ok": True})
        except Exception as e:
            log(f"Couldn't start policy {name}: {e}")
            self.channel.send({"type": "policy", "name": name, "ok": False, "error": str(e)})
        self.send_status(force=True)

    def on_obs(self, message: dict) -> None:
        tick = message["tick"]
        start = time.perf_counter()
        frame = None
        if self.frames is not None and message.get("slot", -1) >= 0:
            frame = self.frames.read(message["slot"], message.get("frameTick", -1))
        try:
            action = self.policy.act(message.get("obs") or {}, frame)
        except Exception:
            self.errors += 1
            if self.errors <= 5:
                log("Policy error:\n" + traceback.format_exc())
            action = NOOP
        self.channel.send(action.to_message(tick))
        self.ticks += 1
        self.think_ms.append((time.perf_counter() - start) * 1000)
        self.think_ms = self.think_ms[-200:]
        self.send_status()

    def send_status(self, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self.last_status < STATUS_EVERY_SECONDS:
            return
        self.last_status = now
        avg = sum(self.think_ms) / len(self.think_ms) if self.think_ms else 0.0
        self.channel.send({
            "type": "status",
            "policy": self.policy.name,
            "ticks": self.ticks,
            "errors": self.errors,
            "think_ms": round(avg, 3),
            "policy_status": self.policy.status(),
        })


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="minigamebots_brain")
    sub = parser.add_subparsers(dest="command")
    run = sub.add_parser("run", help="connect to the mod and play")
    run.add_argument("--port", type=int, required=True)
    run.add_argument("--token", required=True)
    run.add_argument("--frames", help="shared-memory frame file")
    sub.add_parser("selftest", help="print a JSON report of Python, PyTorch and GPU")
    sub.add_parser("train", help="imitation training on recordings (see learn/train.py)", add_help=False)
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] == "train":
        from .learn import train

        return train.main(argv[1:])
    args = parser.parse_args(argv)

    if args.command == "selftest":
        json.dump(system.report(), sys.stdout)
        print()
        return 0
    if args.command != "run":
        parser.print_help()
        return 2

    info = system.report(benchmark=False)
    log(f"MinigameBots brain {VERSION} on Python {info['python']}, torch {info['torch']}, device {info['device']}"
        + (f" ({info['gpu']}, {info['vram_gb']} GB)" if info["gpu"] else ""))
    sock = socket.create_connection(("127.0.0.1", args.port), timeout=10)
    sock.settimeout(None)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    channel = Channel(sock)
    channel.send({"type": "hello", "token": args.token, "protocol": PROTOCOL, "version": VERSION, "system": info})

    frames = None
    if args.frames:
        try:
            frames = FrameRing(args.frames)
        except (OSError, ValueError) as e:
            log(f"No frames available ({e}); running on game state only")

    brain = Brain(channel, frames)
    try:
        while brain.handle(channel.receive()):
            pass
    except ConnectionClosed:
        log("The mod closed the connection")
    finally:
        if frames is not None:
            frames.close()
        sock.close()
    return 0
