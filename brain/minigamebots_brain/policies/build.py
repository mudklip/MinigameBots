"""Building: puts a design (a block plan from the inventor) into the world with its own hands.

Everything goes through normal input, like a player: walk within reach, pick the block on the
hotbar, turn to look at the face of the block it builds against, right-click; hold left-click to
dig. It checks each block in its block sense (the 11x11x11 cube around it) and moves on or tries
again. Order: digging first (top down), then placing bottom-up, always against something solid.

args: design {"palette", "blocks"} (from invent.py), anchor [x, y, z] (world block where the
design's (0, 0, 0) goes: ground level at the design's centre).
"""

import math

from ..actions import Action
from ..learn.features import decode_blocks
from .base import Policy
from .follow import wrap_degrees

# Block sense categories (BlockSense.Category on the Java side).
UNKNOWN, AIR = 0, 1
R = 5
REACH = 4.3
ITEM_FOR = {"minecraft:lava": "minecraft:lava_bucket", "minecraft:water": "minecraft:water_bucket"}
NEIGHBOURS = ((0, -1, 0), (1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0))


class BuildPolicy(Policy):
    name = "build"

    def __init__(self, design: dict | None = None, anchor=None, **_):
        design = design or {"palette": [], "blocks": []}
        self.anchor = tuple(int(a) for a in (anchor or (0, 0, 0)))
        pal = design.get("palette") or []
        steps = []
        for b in design.get("blocks") or []:
            block = pal[b[3]]
            pos = (self.anchor[0] + b[0], self.anchor[1] + b[1], self.anchor[2] + b[2])
            dig = block.startswith("minecraft:air")
            if not dig and b[1] < 0:
                steps.append({"pos": pos, "block": "minecraft:air", "dig": True})  # Make room in the ground first.
            steps.append({"pos": pos, "block": block, "dig": dig})
        digs = sorted([s for s in steps if s["dig"]], key=lambda s: -s["pos"][1])
        places = sorted([s for s in steps if not s["dig"]], key=lambda s: (s["pos"][1], abs(s["pos"][0] - self.anchor[0])
                                                                           + abs(s["pos"][2] - self.anchor[2])))
        self.steps = digs + places
        self.done = set()
        self.failed = set()
        self.missing = set()
        self.tries = {}
        self.wait = 0
        self.current = None
        self.tick = 0

    # --- seeing ---

    def _cat(self, grid, feet, pos):
        d = (pos[0] - feet[0], pos[1] - feet[1], pos[2] - feet[2])
        if max(abs(v) for v in d) > R:
            return None
        return int(grid[d[1] + R][d[2] + R][d[0] + R])

    def _filled(self, grid, feet, pos) -> bool:
        if (pos, False) in self.done:
            return True
        c = self._cat(grid, feet, pos)
        if c is None or c == UNKNOWN:
            return pos[1] < self.anchor[1] and (pos, True) not in self.done  # Ground (unless dug out).
        return c != AIR

    @staticmethod
    def _item(block: str) -> str:
        base = block.split("[")[0]
        return ITEM_FOR.get(base, base)

    def _slot(self, obs, item):
        for it in obs.get("inventory") or []:
            if it.get("id") == item and 0 <= it.get("slot", -1) < 9:
                return it["slot"]
        return None

    def _has(self, obs, item) -> bool:
        return any(it.get("id") == item for it in obs.get("inventory") or [])

    # --- acting ---

    def _look(self, me, point, keys=None, speed=14.0):
        eye = (me["x"], me["y"] + 1.62, me["z"])
        dx, dy, dz = point[0] - eye[0], point[1] - eye[1], point[2] - eye[2]
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
        dyaw = wrap_degrees(yaw - float(me.get("yaw", 0)))
        dpitch = pitch - float(me.get("pitch", 0))
        turn = (max(-speed, min(speed, dyaw * 0.6)), max(-speed, min(speed, dpitch * 0.6)))
        return Action(keys=set(keys or ()), yaw=turn[0], pitch=turn[1]), abs(dyaw) < 4 and abs(dpitch) < 4

    def _walk_to(self, me, pos, stop=2.6):
        dx, dz = pos[0] + 0.5 - me["x"], pos[2] + 0.5 - me["z"]
        a, _ = self._look(me, (pos[0] + 0.5, me["y"] + 1.62, pos[2] + 0.5))
        if math.hypot(dx, dz) > stop:
            a.keys.add("forward")
            if self.tick % 30 == 0:
                a.keys.add("jump")  # Over small bumps.
        return a

    def act(self, obs, frame):
        self.tick += 1
        me = obs.get("self") or {}
        if not me or me.get("dead"):
            return Action()
        feet = (math.floor(me["x"]), math.floor(me["y"]), math.floor(me["z"]))
        grid = decode_blocks(obs.get("blocks"))
        if self.wait > 0:
            self.wait -= 1
            return Action()
        step = None
        for s in self.steps:
            pos = s["pos"]
            key = (pos, s["dig"])
            if key in self.failed or key in self.done:
                continue
            filled = self._filled(grid, feet, pos)
            if (s["dig"] and not filled) or (not s["dig"] and filled):
                self.done.add(key)
                continue
            if not s["dig"]:
                item = self._item(s["block"])
                if not self._has(obs, item):
                    self.missing.add(item.split(":")[-1])
                    continue
                if not any(self._filled(grid, feet, (pos[0] + n[0], pos[1] + n[1], pos[2] + n[2])) for n in NEIGHBOURS):
                    continue  # Nothing to build against yet: later.
            step = s
            break
        self.current = step
        if step is None:
            return Action()
        pos = step["pos"]
        centre = (pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)
        eye = (me["x"], me["y"] + 1.62, me["z"])
        dist = math.dist(eye, centre)
        if not step["dig"] and pos[0] == feet[0] and pos[2] == feet[2] and pos[1] in (feet[1], feet[1] + 1):
            # Standing where the block goes: step back.
            a, _ = self._look(me, centre)
            a.keys.add("back")
            return a
        if dist > REACH:
            return self._walk_to(me, pos)
        if step["dig"]:
            a, on = self._look(me, centre)
            if on:
                a.keys.add("attack")
                self.tries[(pos, step["dig"])] = self.tries.get((pos, step["dig"]), 0) + 1
                if self.tries[(pos, True)] > 120:
                    self.failed.add((pos, True))
            return a
        item = self._item(step["block"])
        slot = self._slot(obs, item)
        if slot is None:
            self.missing.add(item.split(":")[-1])  # In the inventory but not the hotbar.
            self.failed.add((pos, False))
            return Action()
        # Click the face of a solid neighbour (below first: the most natural way to build).
        for n in NEIGHBOURS:
            support = (pos[0] + n[0], pos[1] + n[1], pos[2] + n[2])
            if self._filled(grid, feet, support):
                face = (centre[0] + n[0] * 0.48, centre[1] + n[1] * 0.48, centre[2] + n[2] * 0.48)
                break
        a, on = self._look(me, face)
        if obs.get("selectedSlot") != slot:
            a.keys.add(f"hotbar_{slot + 1}")
            return a
        if on:
            a.keys.add("use")
            self.tries[(pos, step["dig"])] = self.tries.get((pos, step["dig"]), 0) + 1
            if self.tries[(pos, False)] > 4:
                self.failed.add((pos, False))
            self.wait = 3  # Let the block appear before checking.
        return a

    def status(self):
        total = len(self.steps)
        return {"placed": len(self.done), "total": total, "failed": len(self.failed), "missing": sorted(self.missing),
                "doing": None if self.current is None else
                ("dig " if self.current["dig"] else "place ") + self.current["block"].split("[")[0].split(":")[-1],
                "finished": self.current is None and len(self.done) + len(self.failed) >= total}
