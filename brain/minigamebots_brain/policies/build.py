"""Building: puts a design (a block plan from the inventor) into the world with its own hands.

Everything goes through normal input, like a player: walk within reach, pick the block on the
hotbar, turn to look at the face of the block it builds against, right-click; hold left-click to
dig. It checks each block in its block sense (the 11x11x11 cube around it) and moves on or tries
again. Order: digging first (top down), then placing bottom-up, always against something solid.
Deep pits (deeper than it can reach from the edge) are done like a player does them: a one-wide
shaft straight down beside the pit, dig the bottom out from in there, pour any lava from a block
above it (so it can't flow onto it), then climb out by jumping and placing blocks under itself,
which fills the shaft back in.

args: design {"palette", "blocks"} (from invent.py), anchor [x, y, z] (world block where the
design's (0, 0, 0) goes: ground level at the design's centre).
"""

import math
import os

from ..actions import Action
from ..learn.features import decode_blocks
from .base import Policy
from .follow import wrap_degrees

# Block sense categories (BlockSense.Category on the Java side).
UNKNOWN, AIR = 0, 1
WATER, LAVA = 4, 5
# Blocks it can stand on to climb back out of a shaft (it digs most of them on the way down).
FILLERS = ("minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone", "minecraft:netherrack",
           "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:stone_bricks", "minecraft:sandstone")
R = 5
REACH = 4.3
ITEM_FOR = {"minecraft:lava": "minecraft:lava_bucket", "minecraft:water": "minecraft:water_bucket"}
NEIGHBOURS = ((0, -1, 0), (1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0))
DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
# How each kind of block takes its facing from the placement (Minecraft's own rules).
TOWARD = ("dispenser", "dropper", "piston", "sticky_piston", "barrel")  # Faces the player (3D).
AWAY = ("observer",)  # Faces the way the player looks (3D).
# Thin blocks: the look ray passes over them, and nothing can be built against them.
THIN = ("pressure_plate", "torch", "carpet", "rail", "button", "lever", "tripwire", "redstone_wire", "repeater", "comparator")
HORIZONTAL_OPPOSITE = ("chest", "trapped_chest", "furnace", "smoker", "blast_furnace")  # Faces the player, flat.


def _props(block: str) -> dict:
    if "[" not in block:
        return {}
    return dict(kv.split("=", 1) for kv in block[block.index("[") + 1:-1].split(",") if "=" in kv)


def _nearest(v, horizontal=False, margin=1.0):
    """Minecraft's nearest looking direction (or horizontal direction) of a look vector; with a
    margin > 1, None unless that direction clearly wins (so a small shift can't flip it)."""
    axes = [(abs(v[0]), (1 if v[0] > 0 else -1, 0, 0)), (abs(v[2]), (0, 0, 1 if v[2] > 0 else -1))]
    if not horizontal:
        axes.append((abs(v[1]), (0, 1 if v[1] > 0 else -1, 0)))
    axes.sort(reverse=True)
    if axes[0][0] < axes[1][0] * margin:
        return None
    return axes[0][1]


def placement_ok(block: str, look, clicked_face, margin=1.0) -> bool:
    """Would placing this block with this look (and clicking this face) give the facing the design
    asks for? Blocks without a facing are always fine."""
    want = _props(block).get("facing")
    if want not in DIRS:
        return True
    w = DIRS[want]
    name = block.split("[")[0].split(":")[-1]
    def neg(d):
        return None if d is None else tuple(-x for x in d)

    if name in TOWARD:
        return neg(_nearest(look, margin=margin)) == w
    if name in AWAY:
        return _nearest(look, margin=margin) == w
    if name in HORIZONTAL_OPPOSITE:
        return neg(_nearest(look, True, margin)) == w
    if name.endswith("_door") or name.endswith("_stairs"):
        return _nearest(look, True, margin) == w
    if name.endswith("_trapdoor"):
        return clicked_face == w if clicked_face[1] == 0 else neg(_nearest(look, True, margin)) == w
    if name == "hopper":
        return w == (0, -1, 0) if clicked_face[1] != 0 else neg(clicked_face) == w
    return True


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
        # Filling containers last (arrows into dispensers, bait into chests...).
        fills = []
        for key, val in (design.get("items") or {}).items():
            x, y, z = (int(v) for v in key.split(","))
            fills.append({"pos": (self.anchor[0] + x, self.anchor[1] + y, self.anchor[2] + z), "block": "fill",
                          "dig": False, "fill": [val[0], int(val[1])]})
        self.steps = digs + places + fills
        self.thin = {s_["pos"] for s_ in places if any(t in s_["block"] for t in THIN)}
        self.fill_tries = 0
        self.fill_plan = None
        self.ppd = 2.0  # Cursor pixels per degree (learned as it goes).
        self.last_cursor = None
        self.last_order = None
        xs = [s["pos"][0] for s in self.steps] or [self.anchor[0]]
        zs = [s["pos"][2] for s in self.steps] or [self.anchor[2]]
        self.box = (min(xs), max(xs), min(zs), max(zs))
        # Where to stand: a ring two blocks outside the design (a builder doesn't wall itself in).
        x0, x1, z0, z1 = self.box[0] - 2, self.box[1] + 2, self.box[2] - 2, self.box[3] + 2
        self.ring = [(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1) if x in (x0, x1) or z in (z0, z1)]
        # Also right next to it (1 out): needed for blocks whose facing only works from close by.
        y0_, y1_, w0_, w1_ = self.box[0] - 1, self.box[1] + 1, self.box[2] - 1, self.box[3] + 1
        self.ring_in = [(x, z) for x in range(y0_, y1_ + 1) for z in range(w0_, w1_ + 1) if x in (y0_, y1_) or z in (w0_, w1_)]
        self.idle = 0
        self.steady = 0
        self.plan = None  # (block, standing spot, face point) for the block being placed.
        self.plan_strict = False
        self.plan_face_dir = (0, 1, 0)
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
        self.bad_spots = {}  # pos -> standing spots that turned out not to see it.
        self.shaft = None  # Working a deep pit from a shaft beside it (see _shaft_act).
        self.shafts = 0

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

    def _hole_ahead(self, me, dx, dz) -> bool:
        """Open air under the ground just ahead (a pit it dug, a cliff): never walk into it."""
        grid, feet = getattr(self, "_grid", None), getattr(self, "_feet", None)
        if grid is None or feet is None:
            return False
        n = math.hypot(dx, dz) or 1.0
        for dist in (0.6, 1.1):
            x, z = me["x"] + dx / n * dist, me["z"] + dz / n * dist
            below = (math.floor(x), feet[1] - 1, math.floor(z))
            if (math.floor(x), math.floor(z)) == (feet[0], feet[2]):
                continue
            if self._cat(grid, feet, below) == AIR and self._cat(grid, feet, (below[0], below[1] - 1, below[2])) == AIR:
                return True
        return False

    def _walk_to(self, me, pos, stop=2.6):
        dx, dz = pos[0] + 0.5 - me["x"], pos[2] + 0.5 - me["z"]
        a, _ = self._look(me, (pos[0] + 0.5, me["y"] + 1.62, pos[2] + 0.5))
        # Turn first: walking while still facing elsewhere goes elsewhere (into a pit, say).
        yaw = math.radians(float(me.get("yaw", 0)))
        fx, fz = -math.sin(yaw), math.cos(yaw)
        if math.hypot(dx, dz) > stop and math.degrees(abs(wrap_degrees(math.degrees(math.atan2(-dx, dz)) - float(me.get("yaw", 0))))
                                                       * math.pi / 180) > 40:
            return a
        if math.hypot(dx, dz) > stop and (self._hole_ahead(me, dx, dz) or self._hole_ahead(me, fx, fz)):
            a.keys.add("left" if (self.tick // 40) % 2 else "right")  # Edge along it instead of falling in,
            a.keys.add("sneak")  # sneaking, so it can't slip off the edge.
            return a
        if math.hypot(dx, dz) > stop:
            a.keys.add("forward")
            x0, x1, z0, z1 = self.box[0] - 2.5, self.box[1] + 3.5, self.box[2] - 2.5, self.box[3] + 3.5
            if self.tick % 30 == 0 and not (x0 <= me["x"] <= x1 and z0 <= me["z"] <= z1):
                a.keys.add("jump")  # Over small bumps (never next to the build: it could land in a pit).
        return a

    def _clear(self, grid, feet, eye, point, skip, step=0.1) -> bool:
        """Nothing solid between the eye and the point (except the cells in skip)."""
        d = math.dist(eye, point)
        n = max(2, int(d / step))
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

    def _find_spot(self, grid, feet, pos, centre, block=""):
        """A standing spot and face to click; the block's facing comes out right if at all possible."""
        # Standing still beats jumping (a jump-place needs a steady aim mid-air): every way
        # without a jump first.
        for ring, strict, jumps in ((self.ring, True, (False,)), (self.ring_in, True, (False,)), (self.ring, True, (True,)),
                                    (self.ring_in, True, (True,)), (self.ring, False, (False, True))):
            found = self._find_spot_any(grid, feet, pos, centre, block, strict, ring, jumps=jumps)
            if found is not None:
                self.plan_strict = strict and "facing=" in block
                self.plan_face_dir = found[3]
                return found[:3]
        return None

    def _find_spot_any(self, grid, feet, pos, centre, block, strict, ring, jumps=(False, True)):
        spots = sorted(ring, key=lambda c: math.hypot(c[0] + 0.5 - centre[0], c[1] + 0.5 - centre[2]))
        if self._gate_open(grid, feet):
            spots += sorted(self._interior(), key=lambda c: math.hypot(c[0] + 0.5 - centre[0], c[1] + 0.5 - centre[2]))
        for sx, sz in spots:
            eye = (sx + 0.5, feet[1] + 1.62, sz + 0.5)
            if math.dist(eye, centre) > REACH - 0.3:
                continue
            body = {(sx, feet[1], sz), (sx, feet[1] + 1, sz), (sx, feet[1] + 2, sz)}
            for n in NEIGHBOURS:
                support = (pos[0] + n[0], pos[1] + n[1], pos[2] + n[2])
                if not self._filled(grid, feet, support) or support in self.thin or self._cat(grid, feet, support) == 3:
                    continue
                # Aim a little inside the support's face, so the look ray really hits it.
                face = (centre[0] + n[0] * 0.53, centre[1] + n[1] * 0.53, centre[2] + n[2] * 0.53)
                for jump in jumps:
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
                    if strict and not placement_ok(block, v, tuple(-x for x in n), margin=1.4):
                        continue  # It would face the wrong way from here.
                    # Exactly what the game would do with this look: lands in the right cell?
                    if self._ray(e, v, grid, feet, frozenset({support})) == pos:
                        return (sx, sz), face, jump, tuple(-x for x in n)
        return None

    def _aim_from(self, eye, pos, grid, feet, block=""):
        """A point on a support face that, looked at from this exact eye, puts the block in pos."""
        centre = (pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)
        offs = (0.0, -0.3, 0.3, -0.15, 0.15)
        for n in NEIGHBOURS:
            support = (pos[0] + n[0], pos[1] + n[1], pos[2] + n[2])
            if not self._filled(grid, feet, support) or support in self.thin or self._cat(grid, feet, support) == 3:
                continue
            axes = [k for k in range(3) if n[k] == 0]
            for oa in offs:
                for ob in offs:
                    face = [centre[k] + n[k] * 0.53 for k in range(3)]
                    face[axes[0]] += oa
                    face[axes[1]] += ob
                    if sum((eye[k] - face[k]) * -n[k] for k in range(3)) <= 0.05 or math.dist(eye, face) > REACH - 0.2:
                        continue
                    v = [face[k] - eye[k] for k in range(3)]
                    length = math.sqrt(sum(x * x for x in v)) or 1.0
                    v = [x / length for x in v]
                    if "facing=" in block and not placement_ok(block, v, tuple(-x for x in n)):
                        continue
                    if self._ray(eye, v, grid, feet, frozenset({support})) == pos:
                        return tuple(face), tuple(-x for x in n)
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
            if c != prev and c not in self.thin and self._cat(grid, feet, c) != 3 and (
                    self._filled(grid, feet, c) or c in self.clicked or c in extra):
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
        if self.gate is None and self._in_box(math.floor(here[0]), math.floor(here[1])):
            # Standing over the edge of the design (or on it): straight out to the nearest standing
            # spot first, never across it.
            out = min(self.ring_in + self.ring, key=lambda c: math.dist(here, (c[0] + 0.5, c[1] + 0.5)))
            return (out[0], dest[1], out[1])
        x0, x1, z0, z1 = self.box[0] - 2, self.box[1] + 2, self.box[2] - 2, self.box[3] + 2
        corners = [(x, z) for x in (x0, x1) for z in (z0, z1)]
        ok = [c for c in corners if not self._crosses(here, (c[0] + 0.5, c[1] + 0.5))
              and math.dist(here, (c[0] + 0.5, c[1] + 0.5)) > 0.8]  # Not the corner it's already at.
        # Best a corner that sees the goal from there (else it dithers back and forth between two).
        seeing = [c for c in ok if not self._crosses((c[0] + 0.5, c[1] + 0.5), goal)]
        best = min(seeing or ok or corners,
                   key=lambda c: math.dist(here, (c[0] + 0.5, c[1] + 0.5)) + math.dist((c[0] + 0.5, c[1] + 0.5), goal))
        return (best[0], dest[1], best[1])

    def act(self, obs, frame):
        a = self._act(obs, frame)
        trace = os.environ.get("MGB_BUILD_TRACE")
        if trace:  # Debugging aid: one line whenever what it's doing changes.
            me = obs.get("self") or {}
            key = (self.shaft and (self.shaft.get("phase"), self.shaft.get("col"), self.shaft.get("out")),
                   self.current and self.current.get("pos"), tuple(sorted(a.keys)))
            if key != getattr(self, "_last_trace", None):
                self._last_trace = key
                with open(trace, "a", encoding="utf-8") as f:
                    f.write(f"{self.tick} {key} at {me.get('x', 0):.2f} {me.get('y', 0):.2f} {me.get('z', 0):.2f} "
                            f"ground={me.get('onGround')}\n")
        return a

    def _act(self, obs, frame):
        self.tick += 1
        me = obs.get("self") or {}
        if not me or me.get("dead") or "x" not in me:
            return Action()
        feet = (math.floor(me["x"]), math.floor(me["y"]), math.floor(me["z"]))
        grid = decode_blocks(obs.get("blocks"))
        self._grid, self._feet = grid, feet
        self._pend = self._pending(me)
        if self.wait > 0:
            self.wait -= 1
            return Action()
        step = None
        for s in self.steps:
            pos = s["pos"]
            key = (pos, s["dig"]) if not s.get("fill") else (pos, "fill")
            if key in self.failed or key in self.done:
                continue
            if s.get("fill"):
                if not self._filled(grid, feet, pos):
                    continue  # The container isn't there yet.
                if not self._has(obs, s["fill"][0]) and not obs.get("gui"):
                    self.missing.add(s["fill"][0].split(":")[-1])
                    continue
                step = s
                break
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
        if self.shaft is None and step is None and feet[1] <= self.anchor[1] - 2 and me.get("onGround", True) \
                and not any((s_["pos"][0], s_["pos"][2]) == (feet[0], feet[2]) for s_ in self.steps) and self._filler(obs):
            # Done, but down in a hole (it slipped): climb out on blocks, like out of a shaft.
            self.shaft = {"col": (feet[0], feet[2]), "out": (feet[0], feet[2]), "target": feet[1], "phase": "work",
                          "top": self.anchor[1], "need": self.anchor[1] - feet[1]}
        if self.shaft is not None:
            return self._shaft_act(obs, me, grid, feet)
        if step is None:
            self.idle += 1  # Nothing it can place right now (missing items or out of reach for good).
            return Action()
        self.idle = 0
        pos = step["pos"]
        centre = (pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)
        if step.get("fill"):
            return self._fill(obs, me, grid, feet, step, centre)
        if obs.get("gui"):
            return Action(keys={"inventory"})  # A screen is open that it doesn't need: close it.
        eye = (me["x"], me["y"] + 1.62, me["z"])
        dist = math.dist(eye, centre)
        if not step["dig"] and pos[0] == feet[0] and pos[2] == feet[2] and pos[1] in (feet[1], feet[1] + 1):
            # Standing where the block goes: step back.
            a, _ = self._look(me, centre)
            a.keys.add("back")
            return a
        inside = self._in_box(feet[0], feet[2])
        if step["dig"]:
            # Dig only with a clear line to the block (attack breaks whatever the crosshair hits first).
            if dist > REACH or inside or self._dig_point(eye, pos, grid, feet) is None:
                bad = self.bad_spots.setdefault(pos, set())
                spots = [c for c in self.ring_in + self.ring if c not in bad
                         and self._dig_point((c[0] + 0.5, feet[1] + 1.62, c[1] + 0.5), pos, grid, feet, margin=0.3) is not None]
                if not spots:
                    if not self._start_shaft(obs, grid, feet, pos):
                        self.failed.add((pos, True))
                    return Action()
                spot = min(spots, key=lambda c: math.hypot(c[0] + 0.5 - me["x"], c[1] + 0.5 - me["z"]))
                if (feet[0], feet[2]) != spot:
                    return self._walk_to(me, self._via(me, (spot[0], feet[1], spot[1])), stop=0.4)
                # There, but off-centre and the edge hides it: to the middle of the spot, sneaking.
                self.tries[("centre", pos)] = self.tries.get(("centre", pos), 0) + 1
                if self.tries[("centre", pos)] > 60:
                    bad.add(spot)
                    self.tries[("centre", pos)] = 0
                a = self._walk_to(me, (spot[0], feet[1], spot[1]), stop=0.1)
                a.keys.add("sneak")
                return a
        else:
            # Where to stand and which face to click: outside the design, in reach, with a clear
            # line from the eye to the face (otherwise the click lands on whatever is in the way).
            if self.plan is None or self.plan[0] != pos:
                found = self._find_spot(grid, feet, pos, centre, step["block"])
                if found is None:
                    if pos[1] >= self.anchor[1] - 2 or not self._start_shaft(obs, grid, feet, pos):
                        self.failed.add((pos, False))
                    return Action()
                self.plan = (pos, found[0], found[1], found[2])
            _, spot, face, _jump = self.plan
            if (feet[0], feet[2]) != spot:
                return self._walk_to(me, self._via(me, (spot[0], feet[1], spot[1])), stop=0.4)
        if step["dig"]:
            a, on = self._look(me, self._dig_point(eye, pos, grid, feet) or centre)
            pend = getattr(self, "_pend", [0.0, 0.0])
            if on and abs(pend[0]) < 1.0 and abs(pend[1]) < 1.0:
                # Settled (a turn still easing in would sweep the crosshair onto another block),
                # and the crosshair really is on it: never on what it stands on.
                yaw, pitch = math.radians(float(me.get("yaw", 0))), math.radians(float(me.get("pitch", 0)))
                look = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
                hit = self._first_hit(eye, look, grid, feet)
                under = {(math.floor(me["x"] + ox), feet[1] - 1, math.floor(me["z"] + oz)) for ox in (-0.3, 0.3) for oz in (-0.3, 0.3)}
                on = hit == pos and hit not in under
            else:
                on = False
            if on:
                a.keys.add("attack")
                self.tries[(pos, step["dig"])] = self.tries.get((pos, step["dig"]), 0) + 1
                if self.tries[(pos, True)] > 120:
                    self.failed.add((pos, True))
            return a
        return self._click_place(obs, me, grid, feet, step)

    def _click_place(self, obs, me, grid, feet, step) -> Action:
        pos = step["pos"]
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
        facing_ok = True
        if self.plan_strict:
            yaw, pitch = math.radians(float(me.get("yaw", 0))), math.radians(float(me.get("pitch", 0)))
            look = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
            facing_ok = placement_ok(step["block"], look, self.plan_face_dir)
            if not facing_ok and self.steady >= 6:
                self.plan = None  # From where it actually stands it can't: plan again.
        if self.steady >= 4 and facing_ok and self._would_place(me, grid, feet) != pos:
            # Settled, but from where it really stands the click would land elsewhere: aim again.
            better = self._aim_from((me["x"], me["y"] + 1.62, me["z"]), pos, grid, feet, step["block"])
            self.steady = 0
            self.tries[("aim", pos)] = self.tries.get(("aim", pos), 0) + 1
            if better is not None and self.tries[("aim", pos)] <= 6:
                self.plan = (pos, self.plan[1], better[0], self.plan[3])
                self.plan_face_dir = better[1]
            elif self.tries[("aim", pos)] > 6:
                self.failed.add((pos, False))
                self.plan = None
            return a
        if self.steady >= 2 and facing_ok and self._would_place(me, grid, feet) == pos:
            a.keys.add("use")
            self.steady = 0
            self.tries[(pos, step["dig"])] = self.tries.get((pos, step["dig"]), 0) + 1
            if self.tries[(pos, False)] > 3:
                self.failed.add((pos, False))
            self.clicked[pos] = self.tick
            self.wait = 4
        return a

    # --- deep pits: a shaft beside them ---

    def _filler(self, obs):
        for f in FILLERS:
            slot = self._slot(obs, f)
            if slot is not None:
                return f, slot
        return None

    def _start_shaft(self, obs, grid, feet, pos) -> bool:
        """Plan a shaft next to a deep cell it can't reach from the edge. False if there's no good
        column for one, or nothing to climb back out on."""
        if self.shafts >= 3 or pos[1] >= self.anchor[1]:
            return False
        design = {}
        for s_ in self.steps:
            design.setdefault((s_["pos"][0], s_["pos"][2]), []).append(s_["pos"][1])
        cx, cz = (self.box[0] + self.box[1]) / 2, (self.box[2] + self.box[3]) / 2
        cols = []
        for n in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            c = (pos[0] + n[0], pos[2] + n[1])
            ys = design.get(c, [])
            if any(y < self.anchor[1] - 1 for y in ys):
                continue  # Part of the pit itself (or something built down there): not for a shaft.
            # The way in: straight out from the pit, away from the design's middle.
            out = (c[0] + n[0], c[1] + n[1])
            while self._in_box(*out):
                out = (out[0] + n[0], out[1] + n[1])
            cols.append((len(ys), math.hypot(c[0] - cx, c[1] - cz) * -1, c, out))
        if not cols:
            return False
        lowest = min(s_["pos"][1] for s_ in self.steps if (s_["pos"], s_["dig"]) not in self.done
                     and (s_["pos"], s_["dig"]) not in self.failed and s_["pos"][1] < self.anchor[1]
                     and abs(s_["pos"][0] - pos[0]) + abs(s_["pos"][2] - pos[2]) <= 2)
        need = self.anchor[1] - 1 - lowest
        filler = self._filler(obs)
        if filler is None:
            self.missing.add("dirt")  # To climb back out of the shaft.
            return False
        _, _, col, out = min(cols)
        self.shafts += 1
        self.shaft = {"col": col, "out": out, "target": lowest + 1, "phase": "approach", "top": None, "need": need}
        return True

    def _shaft_act(self, obs, me, grid, feet) -> Action:
        sh = self.shaft
        col = sh["col"]
        here = (feet[0], feet[2])
        if sh["phase"] == "approach":
            if here in (sh["out"], col):
                sh["phase"] = "enter"
            else:
                return self._walk_to(me, self._via(me, (sh["out"][0], feet[1], sh["out"][1])), stop=0.4)
        if sh["phase"] == "enter":
            off = math.hypot(me["x"] - col[0] - 0.5, me["z"] - col[1] - 0.5)
            if here != col or off > 0.15 or not me.get("onGround", True):
                a = self._walk_to(me, (col[0], feet[1], col[1]), stop=0.12)
                if here == col:
                    a.keys.add("sneak")  # Slowly to the middle, and never over the pit's edge.
                return a
            sh["top"] = feet[1]
            sh["phase"] = "down"
        if sh["phase"] == "down":
            if not me.get("onGround", True):
                return Action()  # Still dropping.
            below = (col[0], feet[1] - 1, col[1])
            land = (col[0], feet[1] - 2, col[1])
            if feet[1] <= sh["target"]:
                sh["phase"] = "work"
            elif self._cat(grid, feet, below) in (WATER, LAVA) or self._cat(grid, feet, land) in (WATER, LAVA, AIR):
                sh["phase"] = "work"  # Never dig down into liquid or a drop: work from here.
            else:
                off = math.hypot(me["x"] - col[0] - 0.5, me["z"] - col[1] - 0.5)
                if here != col or off > 0.2:
                    a = self._walk_to(me, (col[0], feet[1], col[1]), stop=0.12)  # Slid off the middle: back first.
                    a.keys.add("sneak")
                    return a
                self.current = {"pos": below, "block": "minecraft:air", "dig": True}
                a, on = self._look(me, (col[0] + 0.5, below[1] + 0.98, col[1] + 0.5))
                pend = getattr(self, "_pend", [0.0, 0.0])
                if (on or float(me.get("pitch", 0)) > 80) and abs(pend[0]) < 1.0 and abs(pend[1]) < 1.0:
                    a.keys.add("attack")
                return a
        # Work: whatever deep step it can do from here; otherwise one block up.
        on_ground = me.get("onGround", True)
        if on_ground:
            sh["level"] = feet[1]
        eye = (me["x"], me["y"] + 1.62, me["z"])
        for s_ in (self.steps if on_ground else ()):
            pos = s_["pos"]
            key = (pos, s_["dig"])
            if s_.get("fill") or key in self.done or key in self.failed or pos[1] >= self.anchor[1] - 1:
                continue
            centre = (pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)
            if s_["dig"]:
                if not self._filled(grid, feet, pos):
                    self.done.add(key)
                    continue
                if (pos[0], pos[2]) == col or math.dist(eye, centre) > REACH or not self._clear(grid, feet, eye, centre, {pos}):
                    continue
                self.current = s_
                a, on = self._look(me, centre)
                if on:
                    a.keys.add("attack")
                    self.tries[key] = self.tries.get(key, 0) + 1
                    if self.tries[key] > 120:
                        self.failed.add(key)
                return a
            if self._filled(grid, feet, pos) or pos in self.clicked or (pos[0], pos[2]) == col:
                continue
            liquid = s_["block"].split("[")[0] in ITEM_FOR
            if liquid and pos[1] >= feet[1]:
                continue  # Only pour from above it, so the liquid can't reach it.
            if not self._has(obs, self._item(s_["block"])):
                continue
            if self.plan is None or self.plan[0] != pos:
                found = self._find_spot_any(grid, feet, pos, centre, s_["block"], False, [col], jumps=(False,))
                if found is None:
                    continue
                self.plan = (pos, found[0], found[1], False)
            self.current = s_
            return self._click_place(obs, me, grid, feet, s_)
        # Nothing more from this height: climb one block (jump, place a block under itself).
        level = sh.get("level", feet[1])
        if on_ground and level >= sh["top"]:
            self.shaft = None
            self.current = None
            return Action()
        cell = (col[0], level, col[1])
        block = None
        for s_ in self.steps:
            if s_["pos"] == cell and not s_["dig"] and (cell, False) not in self.done and self._slot(obs, self._item(s_["block"])) is not None:
                block = s_["block"]  # The design wants a block here anyway: climb on that.
        if block is None:
            f = self._filler(obs)
            if f is None:
                self.missing.add("dirt")
                self.idle += 1
                return Action()  # Stuck down here until it has a block to climb on.
            block = f[0]
        slot = self._slot(obs, self._item(block))
        self.current = {"pos": cell, "block": block, "dig": False}
        yaw = math.radians(float(me.get("yaw", 0)))
        a, _ = self._look(me, (me["x"] - math.sin(yaw) * 0.05, me["y"] + 1.62 - 3, me["z"] + math.cos(yaw) * 0.05))
        if obs.get("selectedSlot") != slot:
            a.keys.add(f"hotbar_{slot + 1}")
            return a
        if float(me.get("pitch", 0)) < 86:  # Nearly straight down, so the click stays in its own column.
            return a
        if on_ground:
            a.keys.add("jump")
        elif level < sh["top"] and float(me["y"]) - level >= 1.0 and (cell not in self.clicked or self.tick - self.clicked[cell] > 10):
            a.keys.add("use")
            self.clicked[cell] = self.tick
            self.wait = 3
        return a

    def _dig_point(self, eye, pos, grid, feet, margin=0.0):
        """The middle of the nearest open face of the block that this eye can see, or None."""
        centre = (pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)
        best = None
        for n in NEIGHBOURS:
            if self._filled(grid, feet, (pos[0] + n[0], pos[1] + n[1], pos[2] + n[2])):
                continue
            face = (centre[0] + n[0] * 0.45, centre[1] + n[1] * 0.45, centre[2] + n[2] * 0.45)
            if sum((eye[k] - face[k]) * n[k] for k in range(3)) <= 0.05:
                continue  # The back of that face.
            d = math.dist(eye, face)
            if d > REACH - margin:
                continue
            # Clear with room to spare: the centre and points spread over the face (a line that
            # only just skims past an edge catches that edge in the game, and the aim isn't perfect).
            axes = [k for k in range(3) if n[k] == 0]
            pts = [face]
            for oa, ob in ((0.3, 0.3), (-0.3, 0.3), (0.3, -0.3), (-0.3, -0.3)):
                q = list(face)
                q[axes[0]] += oa
                q[axes[1]] += ob
                pts.append(tuple(q))
            if not all(self._clear(grid, feet, eye, q, {pos}, step=0.03) for q in pts):
                continue
            if best is None or d < best[0]:
                best = (d, face)
        return None if best is None else best[1]

    def _first_hit(self, eye, d, grid, feet):
        """The first block the look ray hits (thin blocks like plates are looked over)."""
        p = list(eye)
        for _ in range(int(REACH / 0.05)):
            p = [p[i] + d[i] * 0.05 for i in range(3)]
            c = (math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))
            if c not in self.thin and self._cat(grid, feet, c) != 3 and self._filled(grid, feet, c):
                return c
        return None

    def _fill(self, obs, me, grid, feet, step, centre) -> Action:
        """Open the container, shift-click the item in from its own inventory, close it."""
        pos = step["pos"]
        item, count = step["fill"]
        gui = obs.get("gui")
        key = (pos, "fill")
        if gui and gui.get("slotPos"):
            mine = {int(p[0]): (p[1], p[2]) for p in gui["slotPos"] if p[3] == 1}
            theirs = {int(p[0]) for p in gui["slotPos"] if p[3] == 0}
            inside = sum(int(it.get("count", 0)) for it in gui.get("slots") or [] if it.get("slot") in theirs and it.get("id") == item)
            if inside >= min(count, 1) or self.fill_tries > 6:
                if inside == 0:
                    self.failed.add(key)
                else:
                    self.done.add(key)
                self.fill_tries = 0
                return Action(keys={"inventory"})  # Done here: close it.
            src = next((it for it in gui.get("slots") or [] if it.get("slot") in mine and it.get("id") == item), None)
            if src is None:
                self.missing.add(item.split(":")[-1])
                self.failed.add(key)
                return Action(keys={"inventory"})
            tx, ty = mine[int(src["slot"])]
            cx, cy = float(gui.get("cursorX", 0)), float(gui.get("cursorY", 0))
            # Learn how far the cursor moves per degree from what the last move did.
            if self.last_cursor is not None and self.last_order is not None and abs(self.last_order[0]) > 0.5:
                moved = cx - self.last_cursor[0]
                if moved * self.last_order[0] > 0:
                    self.ppd = 0.7 * self.ppd + 0.3 * max(0.3, min(12.0, moved / self.last_order[0]))
            self.last_cursor = (cx, cy)
            dx, dy = tx - cx, ty - cy
            if abs(dx) > 2 or abs(dy) > 2:
                order = (max(-30.0, min(30.0, dx / self.ppd * 0.7)), max(-30.0, min(30.0, dy / self.ppd * 0.7)))
                self.last_order = order
                return Action(yaw=order[0], pitch=order[1])
            self.last_order = None
            self.fill_tries += 1
            self.wait = 4
            return Action(keys={"sneak", "attack"})  # Shift-click: the whole stack moves into the container.
        # Not open yet: stand where it can see the container, get in reach and right-click it
        # (not sneaking, or it would place a block).
        eye = (me["x"], me["y"] + 1.62, me["z"])
        if self.fill_plan is None or self.fill_plan[0] != pos:
            spot = None
            for sx, sz in sorted(self.ring + self.ring_in, key=lambda c: math.hypot(c[0] + 0.5 - centre[0], c[1] + 0.5 - centre[2])):
                e = (sx + 0.5, feet[1] + 1.62, sz + 0.5)
                if math.dist(e, centre) > REACH - 0.3:
                    continue
                v = [centre[k] - e[k] for k in range(3)]
                n_ = math.sqrt(sum(x * x for x in v)) or 1.0
                if self._first_hit(e, [x / n_ for x in v], grid, feet) == pos:
                    spot = (sx, sz)
                    break
            if spot is None:
                self.failed.add(key)
                return Action()
            self.fill_plan = (pos, spot)
        spot = self.fill_plan[1]
        if (feet[0], feet[2]) != spot:
            return self._walk_to(me, self._via(me, (spot[0], feet[1], spot[1])), stop=0.3)
        a, on = self._look(me, centre)
        pend = getattr(self, "_pend", [0.0, 0.0])
        if on and abs(pend[0]) < 1 and abs(pend[1]) < 1:
            yaw, pitch = math.radians(float(me.get("yaw", 0))), math.radians(float(me.get("pitch", 0)))
            d = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
            if self._first_hit(eye, d, grid, feet) == pos:
                a.keys.add("use")
                self.fill_tries += 1
                self.wait = 6
                if self.fill_tries > 8:
                    self.failed.add(key)
                    self.fill_tries = 0
        return a

    def status(self):
        total = len(self.steps)
        return {"placed": len(self.done), "total": total, "failed": len(self.failed), "missing": sorted(self.missing),
                "doing": None if self.current is None else
                ("fill " + self.current["fill"][0].split(":")[-1]) if self.current.get("fill") else
                ("dig " if self.current["dig"] else "place ") + self.current["block"].split("[")[0].split(":")[-1],
                "finished": self.current is None and (len(self.done) + len(self.failed) >= total or self.idle > 100)}
