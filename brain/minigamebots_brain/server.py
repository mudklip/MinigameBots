"""The brain process. The mod starts it with a port and a one-time token; it connects back to
the mod over localhost, then answers each observation with an action until told to stop."""

import argparse
import json
import threading
import os
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
        self.swaps = 0
        self.swap_ms = None
        self.started = time.monotonic()

    def handle(self, message: dict) -> bool:
        kind = message.get("type")
        if kind == "obs":
            self.on_obs(message)
        elif kind == "world_step":
            self.on_world_step(message)
        elif kind == "set_policy":
            self.set_policy(message.get("name", "idle"), message.get("args") or {})
        elif kind in ("chat", "make_character"):
            threading.Thread(target=self.on_chat, args=(message,), daemon=True).start()
        elif kind == "lessons":
            threading.Thread(target=self.on_lessons, args=(message,), daemon=True).start()
        elif kind in ("voice", "speak"):
            threading.Thread(target=self.on_voice, args=(message,), daemon=True).start()
        elif kind == "command":
            threading.Thread(target=self.on_command, args=(message,), daemon=True).start()
        elif kind in ("fit_rewards", "design"):
            threading.Thread(target=self.on_design, args=(message,), daemon=True).start()
        elif kind == "ping":
            self.channel.send({"type": "pong", "id": message.get("id")})
        elif kind == "status":
            self.send_status(force=True)
        elif kind == "shutdown":
            log("Shutting down on request")
            self.close_policy()
            return False
        else:
            log(f"Ignoring unknown message type {kind!r}")
        return True

    def set_policy(self, name: str, args: dict) -> None:
        """Swaps what plays. The process (Python, PyTorch, the GPU context, the mind) stays loaded
        between plays; only the policy changes. The old one saves what it learned first."""
        self.close_policy()
        if name == "manager" and args.get("mode") == "personality" and args.get("profile"):
            self._personality_mode(args)
        self.defeats = None
        if name in ("learned", "manager", "practice", "multiworld") and args.get("profile"):
            # Learning from whoever beats it (defeats.py), for whatever plays fights.
            from .defeats import DefeatWatcher

            root = os.path.abspath(args["profile"])
            for _ in range(4):
                if os.path.isfile(os.path.join(root, "manifest.json")):
                    self.defeats = DefeatWatcher(root)
                    break
                root = os.path.dirname(root)
        start = time.perf_counter()
        try:
            self.policy = policies.create(name, args)
            self.swap_ms = round((time.perf_counter() - start) * 1000, 1)
            log(f"Policy: {name} {args or ''} (ready in {self.swap_ms} ms)")
            self.channel.send({"type": "policy", "name": name, "ok": True, "swap_ms": self.swap_ms})
        except Exception as e:
            # Only this policy failed: the brain stays up, idle, ready for the next one.
            log(f"Couldn't start policy {name}: {e}\n" + traceback.format_exc())
            self.policy = policies.create("idle")
            self.channel.send({"type": "policy", "name": name, "ok": False, "error": str(e)})
        self.swaps += 1
        self.send_status(force=True)

    def close_policy(self) -> None:
        if getattr(self, "defeats", None) is not None:
            self.defeats.close()
            self.defeats = None
        """Lets the current policy save and let go (on swap and on shutdown)."""
        old, self.policy = self.policy, policies.create("idle")
        if old.name == "idle":
            return
        try:
            old.close()
        except Exception:
            log(f"Couldn't close policy {old.name}:\n" + traceback.format_exc())
        del old
        free_memory()

    def on_world_step(self, message: dict) -> None:
        """Headless training worlds: the training server's views of every world in, every learner's
        input out, without the game client in between (fast background training)."""
        obs = {"multiworld": message.get("copies") or [], "_events": [], "tick": message.get("tick", 0)}
        try:
            action = self.policy.act(obs, None)
            puppets = action.puppets or []
        except Exception:
            self.errors += 1
            if self.errors <= 5:
                log("World step error:\n" + traceback.format_exc())
            puppets = []
        self.channel.send({"type": "world_step_result", "puppets": puppets})

    def on_obs(self, message: dict) -> None:
        tick = message["tick"]
        start = time.perf_counter()
        frame = None
        if self.frames is not None and message.get("slot", -1) >= 0:
            frame = self.frames.read(message["slot"], message.get("frameTick", -1))
        obs = message.get("obs") or {}
        # Game events since the last tick ride along with the observation; policies that learn
        # from rewards read them from here, others ignore them.
        obs["_events"] = message.get("events") or []
        if getattr(self, "defeats", None) is not None:
            try:
                self.defeats.step(obs, obs["_events"])
            except Exception:
                log("Defeat watcher error:\n" + traceback.format_exc())
        try:
            action = self.policy.act(obs, frame)
        except Exception:
            self.errors += 1
            if self.errors <= 5:
                log("Policy error:\n" + traceback.format_exc())
            action = NOOP
        msg = action.to_message(tick)
        puppet = obs.get("puppet")
        if puppet:
            try:
                msg["puppet"] = self._selfplay(puppet.get("profile", "")).act(obs, int(puppet["id"]), puppet.get("round"))
            except Exception:
                self.errors += 1
                if self.errors <= 5:
                    log("Self-play error:\n" + traceback.format_exc())
        self.channel.send(msg)
        self.ticks += 1
        self.think_ms.append((time.perf_counter() - start) * 1000)
        self.think_ms = self.think_ms[-200:]
        self.send_status()
        self.send_feed()

    def on_command(self, message: dict) -> None:
        """A command you typed (/ai ...): understood off the play loop, answered with goals."""
        from .mind import commands, llm as llm_mod

        root = os.path.join(os.getcwd(), "mind")
        model = getattr(self, "llm", None)
        try:
            if model is None and llm_mod.installed(root):
                self.channel.send({"type": "command_result", "id": message.get("id"), "progress": True,
                                   "reply": "Loading the mind model (first time only)..."})
                model = self._mind()
            result = commands.understand(str(message.get("text", "")), str(message.get("me", "")), model)
        except Exception as e:
            log("Mind error:\n" + traceback.format_exc())
            result = {"goals": [], "stop": False, "via": "none", "reply": f"My mind had a problem: {e}"}
        if message.get("heard"):
            result["reply"] = f'Heard "{message["heard"]}". ' + result["reply"]
        self.channel.send({"type": "command_result", "id": message.get("id"), **result})

    def on_voice(self, message: dict) -> None:
        """Push-to-talk audio -> text -> the same handling as /ai; or a reply -> speech."""
        from .mind import voice

        root = os.path.join(os.getcwd(), "mind")
        try:
            if message.get("type") == "speak":
                if not voice.installed(root)["speak"]:
                    return
                if getattr(self, "mouth", None) is None:
                    self.mouth = voice.Mouth(root)
                path = os.path.join(os.getcwd(), "run", "speech.wav")
                os.makedirs(os.path.dirname(path), exist_ok=True)
                self.channel.send({"type": "speech", "path": self.mouth.say(str(message.get("text", ""))[:200], path)})
                return
            if not voice.installed(root)["hear"]:
                self.channel.send({"type": "command_result", "id": message.get("id"), "goals": [], "stop": False,
                                   "reply": "Install voice first (F10 > Brain > Voice)."})
                return
            if getattr(self, "ears", None) is None:
                self.ears = voice.Ears(root)
            text = self.ears.hear(str(message.get("path", "")))
        except Exception as e:
            log("Voice error:\n" + traceback.format_exc())
            self.channel.send({"type": "command_result", "id": message.get("id"), "goals": [], "stop": False,
                               "reply": f"Voice problem: {e}"})
            return
        if not text:
            self.channel.send({"type": "command_result", "id": message.get("id"), "goals": [], "stop": False,
                               "reply": "I didn't hear anything."})
            return
        self.on_command({"id": message.get("id"), "text": text, "me": message.get("me", ""), "heard": text})

    def _personality_mode(self, args: dict) -> None:
        """Works out once which mode fits the AI's personality (with the mind if it's installed and
        works, else from keywords); kept in drives.json until the personality changes."""
        from .policies import drives

        text = str(args.get("personality", ""))
        known = drives.load(args["profile"]).get("personality_hash") == drives.personality_hash(text)
        llm = None
        if not known:
            try:
                model = self._mind()
                if model is not None:
                    llm = lambda p: model.generate([{"role": "user", "content": p}], max_new_tokens=8)  # noqa: E731
            except Exception:
                llm = None  # The mind doesn't work here: keywords instead.
        try:
            drives.resolve(args["profile"], text, llm)
        except OSError:
            pass

    def _mind(self):
        """The language model, loaded on first use; None if it isn't installed."""
        from .mind import llm as llm_mod

        root = os.path.join(os.getcwd(), "mind")
        if getattr(self, "llm", None) is None and llm_mod.installed(root):
            self.llm = llm_mod.LLM(root)
        return getattr(self, "llm", None)

    def on_lessons(self, message: dict) -> None:
        """Daily lessons from the day's facts (the mind phrases them; plain sentences without it)."""
        from .mind import lessons

        out = {"type": "lessons_result", "id": message.get("id")}
        try:
            model = None
            try:
                model = self._mind()
            except Exception:
                model = None
            out.update(lessons.write(model, list(message.get("facts") or [])))
        except Exception as e:
            out["error"] = str(e)
        self.channel.send(out)

    def on_chat(self, message: dict) -> None:
        """Chat in character, or make a character: needs the mind (Brain > Install mind)."""
        from .mind import chat

        out = {"type": "chat_reply" if message.get("type") == "chat" else "character", "id": message.get("id")}
        try:
            model = self._mind()
            if model is None:
                out["error"] = "Install the mind first (F10 > Brain > Mind)"
            elif message.get("type") == "chat":
                out["text"] = chat.reply(model, message.get("character") or {}, str(message.get("ai_name", "")),
                                         list(message.get("recent") or []), str(message.get("text", "")))
            else:
                out["character"] = chat.make_character(model, str(message.get("ai_name", "")), list(message.get("skills") or []))
        except Exception as e:
            log("Mind error:\n" + traceback.format_exc())
            out["error"] = str(e)
        self.channel.send(out)

    def on_design(self, message: dict) -> None:
        """The training designer (designer.py) and the reward fitter (rl/rewardfit.py)."""
        out = {"type": message.get("type") + "_result", "id": message.get("id")}
        try:
            if message.get("type") == "fit_rewards":
                from .rl import rewardfit

                out.update(rewardfit.fit(message.get("skill") or {}, str(message.get("recordings", ""))))
            else:
                from . import designer

                mind = None
                try:
                    mind = self._mind() if message.get("use_mind", True) else None
                except Exception:
                    mind = None
                out.update(designer.design(str(message.get("request", "")), message.get("context") or {}, mind))
        except Exception as e:
            log("Designer error:\n" + traceback.format_exc())
            out["error"] = str(e)
        self.channel.send(out)

    def _selfplay(self, profile: str):
        """The self-play opponent for this AI (made on first use)."""
        sp = getattr(self, "selfplay", None)
        if sp is None or sp.profile != profile:
            from .selfplay import SelfPlay

            sp = self.selfplay = SelfPlay(profile)
        return sp

    def send_feed(self) -> None:
        """Popups: what it just gained, lost or learned (at most twice a second)."""
        now = time.monotonic()
        if now - getattr(self, "last_feed", 0.0) < 0.5:
            return
        self.last_feed = now
        from . import feed

        items = feed.drain()
        if items:
            self.channel.send({"type": "feed", "items": items})

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
            "swaps": self.swaps,
            "swap_ms": self.swap_ms,
            "uptime_s": round(now - self.started),
            "policy_status": self.policy.status(),
        })


def free_memory() -> None:
    """Gives a swapped-out policy's memory back (its network can be large on the GPU)."""
    import gc

    gc.collect()
    torch = sys.modules.get("torch")  # Only if something already loaded it.
    if torch is not None:
        try:
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception:
            pass


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="minigamebots_brain")
    sub = parser.add_subparsers(dest="command")
    run = sub.add_parser("run", help="connect to the mod and play")
    run.add_argument("--port", type=int, required=True)
    run.add_argument("--token", required=True)
    run.add_argument("--frames", help="shared-memory frame file")
    sub.add_parser("selftest", help="print a JSON report of Python, PyTorch and GPU")
    sub.add_parser("train", help="imitation training on recordings (see learn/train.py)", add_help=False)
    sub.add_parser("foundation", help="install or check a foundation model (see vpt/foundation.py)", add_help=False)
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] == "train":
        from .learn import train

        return train.main(argv[1:])
    if argv and argv[0] == "mind":
        from .mind import cli

        return cli.main(argv[1:])
    if argv and argv[0] == "fitrewards":
        from .rl import rewardfit

        return rewardfit.main(argv[1:])
    if argv and argv[0] == "design":
        from . import designer

        return designer.main(argv[1:])
    if argv and argv[0] == "dream":
        from . import dream

        return dream.main(argv[1:])
    if argv and argv[0] == "invent":
        from . import invent

        return invent.main(argv[1:])
    if argv and argv[0] == "players":
        from . import players

        return players.main(argv[1:])
    if argv and argv[0] == "video":
        from .video import cli

        return cli.main(argv[1:])
    if argv and argv[0] == "realism":
        from . import realism

        return realism.main(argv[1:])
    if argv and argv[0] == "foundation":
        from .vpt import foundation

        return foundation.main(argv[1:])
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
        brain.close_policy()
    finally:
        if frames is not None:
            frames.close()
        sock.close()
    return 0
