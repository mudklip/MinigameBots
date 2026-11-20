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
        # Shelters: keep the door column (the "gate") open until last, so it can go in to build
        # what can only be reached from inside (the middle of a roof), then come out and close it.
        inside = design.get("inside")
        self.gate = None
        if inside and places:
            zmin = min(s_["pos"][2] for s_ in places)
            self.gate = (self.anchor[0] + inside[0], zmin)
            gate_steps = [s_ for s_ in places if (s_["pos"][0], s_["pos"][2]) == self.gate]
            places = [s_ for s_ in places if s_ not in gate_steps] + gate_steps
        self.steps = digs + places
        xs = [s["pos"][0] for s in self.steps] or [self.anchor[0]]
        zs = [s["pos"][2] for s in self.steps] or [self.anchor[2]]
        self.box = (min(xs), max(xs), min(zs), max(zs))
        # Where to stand: a ring two blocks outside the design (a builder doesn't wall itself in).
        x0, x1, z0, z1 = self.box[0] - 2, self.box[1] + 2, self.box[2] - 2, self.box[3] + 2
        self.ring = [(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1) if x in (x0, x1) or z in (z0, z1)]
        self.idle = 0
        self.steady = 0
        self.plan = None  # (block, standing spot, face point) for the block being placed.
        self.clicked = {}  # Placements already clicked: pos -> tick (a second click would stack blocks).
        self.done = set()
        self.failed = set()
        self.missing = set()
        self.tries = {}
        self.wait = 0
        self.current = None
        self.tick = 0
        # Turns ordered but not seen in the camera yet (the humanizer smooths them over a few ticks).
        self.ordered = [0.0, 0.0]
        self.observed = [0.0, 0.0]
        self.previous = None

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

    def _pending(self, me):
        if self.previous is not None:
            self.observed[0] += wrap_degrees(float(me.get("yaw", 0)) - self.previous[0])
            self.observed[1] += float(me.get("pitch", 0)) - self.previous[1]
        self.previous = (float(me.get("yaw", 0)), float(me.get("pitch", 0)))
        pending = [self.ordered[i] - self.observed[i] for i in range(2)]
        for i in range(2):
            self.ordered[i] -= 0.15 * pending[i]  # Forget slowly what never showed up.
        return pending

    def _look(self, me, point, keys=None, speed=14.0):
        eye = (me["x"], me["y"] + 1.62, me["z"])
        pending = getattr(self, "_pend", [0.0, 0.0])
        dx, dy, dz = point[0] - eye[0], point[1] - eye[1], point[2] - eye[2]
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
        dyaw = wrap_degrees(yaw - float(me.get("yaw", 0)))
        dpitch = pitch - float(me.get("pitch", 0))
        on = abs(dyaw) < 4 and abs(dpitch) < 4
        # Only order what isn't already on its way.
        want = (dyaw - pending[0], dpitch - pending[1])
        turn = (max(-speed, min(speed, want[0] * 0.6)), max(-speed, min(speed, want[1] * 0.6)))
        self.ordered[0] += turn[0]
        self.ordered[1] += turn[1]
        return Action(keys=set(keys or ()), yaw=turn[0], pitch=turn[1]), on

    def _walk_to(self, me, pos, stop=2.6):
        dx, dz = pos[0] + 0.5 - me["x"], pos[2] + 0.5 - me["z"]
        a, _ = self._look(me, (pos[0] + 0.5, me["y"] + 1.62, pos[2] + 0.5))
        if math.hypot(dx, dz) > stop:
            a.keys.add("forward")
            if self.tick % 30 == 0:
                a.keys.add("jump")  # Over small bumps.
        return a

    def _clear(self, grid, feet, eye, point, skip) -> bool:
        """Nothing solid between the eye and the point (except the cells in skip)."""
        d = math.dist(eye, point)
        n = max(2, int(d / 0.1))
        for i in range(1, n):
            t = i / n
            c = tuple(math.floor(eye[k] + (point[k] - eye[k]) * t) for k in range(3))
            if c in skip:
                continue
            if self._filled(grid, feet, c) or c in self.clicked:
                return False
        return True

    def _interior(self):
        cells = {(s["pos"][0], s["pos"][2]) for s in self.steps if not s["dig"] and s["pos"][1] <= self.anchor[1] + 1}
        return [(x, z) for x in range(self.box[0] + 1, self.box[1]) for z in range(self.box[2] + 1, self.box[3])
                if (x, z) not in cells]

    def _gate_open(self, grid, feet) -> bool:
        if self.gate is None:
            return False
        return not any(self._filled(grid, feet, (self.gate[0], self.anchor[1] + dy, self.gate[1])) for dy in (0, 1))

    def _find_spot(self, grid, feet, pos, centre):
        spots = sorted(self.ring, key=lambda c: math.hypot(c[0] + 0.5 - centre[0], c[1] + 0.5 - centre[2]))
        if self._gate_open(grid, feet):
            spots += sorted(self._interior(), key=lambda c: math.hypot(c[0] + 0.5 - centre[0], c[1] + 0.5 - centre[2]))
        for sx, sz in spots:
            eye = (sx + 0.5, feet[1] + 1.62, sz + 0.5)
            if math.dist(eye, centre) > REACH - 0.3:
                continue
            body = {(sx, feet[1], sz), (sx, feet[1] + 1, sz), (sx, feet[1] + 2, sz)}
            for n in NEIGHBOURS:
                support = (pos[0] + n[0], pos[1] + n[1], pos[2] + n[2])
                if not self._filled(grid, feet, support):
                    continue
                # Aim a little inside the support's face, so the look ray really hits it.
                face = (centre[0] + n[0] * 0.53, centre[1] + n[1] * 0.53, centre[2] + n[2] * 0.53)
                for jump in (False, True):
                    # Jumping lifts the eye about a block: how players reach the top of a 2-high wall.
                    e = (eye[0], eye[1] + (1.0 if jump else 0.0), eye[2])
                    # The face must point at the eye (you can't click the back of a block).
                    if sum((e[k] - face[k]) * -n[k] for k in range(3)) <= 0.05:
                        continue
                    if math.dist(e, face) > REACH - 0.2:
                        continue
                    v = [face[k] - e[k] for k in range(3)]
                    length = math.sqrt(sum(x * x for x in v)) or 1.0
                    v = [x / length for x in v]
                    if -sum(v[k] * n[k] for k in range(3)) > -0.25:
                        continue  # Too edge-on: the click would slide off the face.
                    # Exactly what the game would do with this look: lands in the right cell?
                    if self._ray(e, v, grid, feet, frozenset({support})) == pos:
                        return (sx, sz), face, jump
        return None

    def _would_place(self, me, grid, feet):
        """Where a click right now would put the block: the cell before the first solid one along
        the look ray (what the game does), or None if nothing is in reach."""
        yaw, pitch = math.radians(float(me.get("yaw", 0))), math.radians(float(me.get("pitch", 0)))
        d = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
        return self._ray((me["x"], me["y"] + 1.62, me["z"]), d, grid, feet)

    def _ray(self, eye, d, grid, feet, extra=frozenset()):
        p = list(eye)
        prev = None
        for _ in range(int(REACH / 0.05)):
            p = [p[i] + d[i] * 0.05 for i in range(3)]
            c = (math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))
            if c != prev and (self._filled(grid, feet, c) or c in self.clicked or c in extra):
                return prev
            prev = c
        return None

    def _crosses(self, a, b) -> bool:
        """Does the straight walk from a to b (x, z) cut across the design's footprint?"""
        x0, x1, z0, z1 = self.box[0] - 0.3, self.box[1] + 1.3, self.box[2] - 0.3, self.box[3] + 1.3
        for i in range(21):
            t = i / 20
            x, z = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
            if x0 <= x <= x1 and z0 <= z <= z1:
                return True
        return False

    def _in_box(self, x, z) -> bool:
        return self.box[0] <= x <= self.box[1] and self.box[2] <= z <= self.box[3]

    def _via(self, me, dest):
        """Walk around the design, never through it: by a corner of the standing ring if needed;
        in and out of a shelter only through its gate."""
        here = (me["x"], me["z"])
        goal = (dest[0] + 0.5, dest[2] + 0.5)
        if self.gate is not None:
            cell = (math.floor(me["x"]), math.floor(me["z"]))
            front = (self.gate[0], self.gate[1] - 1)
            want_in, now_in = self._in_box(dest[0], dest[2]), self._in_box(*cell)
            if want_in and not now_in:
                if cell != front:
                    dest, goal = (front[0], dest[1], front[1]), (front[0] + 0.5, front[1] + 0.5)
                else:
                    return (self.gate[0], dest[1], self.gate[1])  # Step through the gate.
            elif now_in and not want_in:
                if cell != self.gate:
                    return (self.gate[0], dest[1], self.gate[1])  # Out through the gate.
                return (front[0], dest[1], front[1])
            elif want_in and now_in:
                return dest
        if not self._crosses(here, goal):
            return dest
        if not self._crosses(here, goal):
            return dest
        x0, x1, z0, z1 = self.box[0] - 2, self.box[1] + 2, self.box[2] - 2, self.box[3] + 2
        corners = [(x, z) for x in (x0, x1) for z in (z0, z1)]
        ok = [c for c in corners if not self._crosses(here, (c[0] + 0.5, c[1] + 0.5))
              and math.dist(here, (c[0] + 0.5, c[1] + 0.5)) > 0.8]  # Not the corner it's already at.
        best = min(ok or corners, key=lambda c: math.dist(here, (c[0] + 0.5, c[1] + 0.5)) + math.dist((c[0] + 0.5, c[1] + 0.5), goal))
        return (best[0], dest[1], best[1])

    def act(self, obs, frame):
        self.tick += 1
        me = obs.get("self") or {}
        if not me or me.get("dead"):
            return Action()
        feet = (math.floor(me["x"]), math.floor(me["y"]), math.floor(me["z"]))
        grid = decode_blocks(obs.get("blocks"))
        self._pend = self._pending(me)
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
            if not s["dig"] and pos in self.clicked:
                # Clicked already: placed, unless it can clearly see air there after a moment.
                if self._cat(grid, feet, pos) != AIR or self.tick - self.clicked[pos] < 15:
                    if self.tick - self.clicked[pos] >= 15:
                        self.done.add(key)
                    continue
                del self.clicked[pos]  # Really didn't take: try again.
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
            self.idle += 1  # Nothing it can place right now (missing items or out of reach for good).
            return Action()
        self.idle = 0
        pos = step["pos"]
        centre = (pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)
        eye = (me["x"], me["y"] + 1.62, me["z"])
        dist = math.dist(eye, centre)
        if not step["dig"] and pos[0] == feet[0] and pos[2] == feet[2] and pos[1] in (feet[1], feet[1] + 1):
            # Standing where the block goes: step back.
            a, _ = self._look(me, centre)
            a.keys.add("back")
            return a
        inside = self._in_box(feet[0], feet[2])
        if step["dig"]:
            if dist > REACH or inside:
                spot = min(self.ring, key=lambda c: math.hypot(c[0] + 0.5 - centre[0], c[1] + 0.5 - centre[2]))
                if (feet[0], feet[2]) != spot:
                    return self._walk_to(me, self._via(me, (spot[0], feet[1], spot[1])), stop=0.4)
                if dist > REACH:
                    self.failed.add((pos, True))
                    return Action()
        else:
            # Where to stand and which face to click: outside the design, in reach, with a clear
            # line from the eye to the face (otherwise the click lands on whatever is in the way).
            if self.plan is None or self.plan[0] != pos:
                found = self._find_spot(grid, feet, pos, centre)
                if found is None:
                    self.failed.add((pos, False))
                    return Action()
                self.plan = (pos, found[0], found[1], found[2])
            _, spot, face, _jump = self.plan
            if (feet[0], feet[2]) != spot:
                return self._walk_to(me, self._via(me, (spot[0], feet[1], spot[1])), stop=0.4)
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
        face, jump = self.plan[2], self.plan[3]
        a, on = self._look(me, face)
        if jump:
            # Jump, and click near the top of the jump.
            high = float(me["y"]) - self.anchor[1] >= 0.75
            if me.get("onGround", True) and float(me["y"]) - self.anchor[1] < 0.1:
                if obs.get("selectedSlot") == slot:
                    a.keys.add("jump")
                on = False
            elif not high:
                on = False
        if obs.get("selectedSlot") != slot:
            a.keys.add(f"hotbar_{slot + 1}")
            return a
        # Click only once the view has settled (the humanizer smooths turns over a few ticks):
        # nothing still turning, and on target two ticks running.
        pend = getattr(self, "_pend", [0.0, 0.0])
        settled = on and abs(pend[0]) < 1.0 and abs(pend[1]) < 1.0
        self.steady = self.steady + 1 if settled else 0
        if self.steady >= 2 and self._would_place(me, grid, feet) == pos:
            a.keys.add("use")
            self.steady = 0
            self.tries[(pos, step["dig"])] = self.tries.get((pos, step["dig"]), 0) + 1
            if self.tries[(pos, False)] > 3:
                self.failed.add((pos, False))
            self.clicked[pos] = self.tick
            self.wait = 4
        return a

    def status(self):
        total = len(self.steps)
        return {"placed": len(self.done), "total": total, "failed": len(self.failed), "missing": sorted(self.missing),
                "doing": None if self.current is None else
                ("dig " if self.current["dig"] else "place ") + self.current["block"].split("[")[0].split(":")[-1],
                "finished": self.current is None and (len(self.done) + len(self.failed) >= total or self.idle > 100)}
