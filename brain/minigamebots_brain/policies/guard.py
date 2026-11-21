"""Guarding a player in formation, with several AIs that never talk to each other.

Every guard gets the same order (from chat: "guard Steve 3 ring with Bob Ann Cid") and works out
its own spot with the same simple math: the listed names sorted alphabetically, its place in that
order, the shape and the distance, turned with the way the protected player faces. With close_gaps,
only the listed guards it can actually see count, so a missing guard's spot gets shared out.

Modes: guard (hold the spot, and the guard that sees itself nearest to someone attacking the
protected player fights them, then comes back), standby (hold the spot, never engage), follow (stay
behind at the distance). Safety first: it never steps onto a hole or lava because its spot is there
(it turns the spot around the circle, or waits). Scripted: no training, no Baritone; only the
fighting uses its trained network.
"""

import math

from ..actions import Action
from .base import Policy
from .follow import wrap_degrees

SHAPES = ("ring", "line", "front", "wedge", "square")
AIR, LAVA = 1, 5


def _facing(yaw_deg: float):
    y = math.radians(yaw_deg)
    return (-math.sin(y), math.cos(y)), (-math.cos(y), -math.sin(y))  # forward, right (x, z)


def slot_offset(shape: str, i: int, n: int, dist: float, yaw_deg: float, turn: float = 0.0):
    """World (x, z) offset of spot i of n from the protected player."""
    f, r = _facing(yaw_deg)
    n = max(1, n)
    if shape == "line":  # Single file behind.
        a, b = -(dist + i * 1.5), 0.0
    elif shape == "front":  # A row in front.
        a, b = dist, (i - (n - 1) / 2) * 1.5
    elif shape == "wedge":  # A V behind: the tip first, then pairs.
        k = (i + 1) // 2
        side = 0 if i == 0 else (1 if i % 2 else -1)
        a, b = -(dist + k * 1.0), side * k * 1.5
    elif shape == "square":  # Evenly round a square of half-side dist.
        t = (i / n) * 4.0 + turn / 90.0
        edge, frac = int(t) % 4, t - int(t)
        u = -1 + 2 * frac
        a, b = [(1, u), (-u, 1), (-1, -u), (u, -1)][edge]
        a, b = a * dist, b * dist
    else:  # ring: spot 0 in front, evenly round.
        ang = 2 * math.pi * i / n + math.radians(turn)
        a, b = dist * math.cos(ang), dist * math.sin(ang)
    return a * f[0] + b * r[0], a * f[1] + b * r[1]


def my_index(names: list, me: str, visible: set | None = None):
    """(index, count) of me among the guards (sorted names; only visible ones with close_gaps)."""
    pool = [n for n in names if visible is None or n.lower() == me.lower() or n.lower() in visible]
    order = sorted({n.lower() for n in pool} | {me.lower()})
    return order.index(me.lower()), len(order)


class GuardPolicy(Policy):
    name = "guard"

    def __init__(self, protectee: str = "", distance: float = 3.0, shape: str = "ring", names=None, me: str = "",
                 mode: str = "guard", close_gaps: bool = True, profile: str | None = None, **_):
        self.protectee = protectee
        self.dist = max(1.0, min(12.0, float(distance)))
        self.shape = shape if shape in SHAPES else "ring"
        self.names = list(names or [])
        self.me = me
        self.mode = mode
        self.close_gaps = close_gaps
        self.profile = profile
        self.fighter = None
        self.engaged = None
        self.turn = 0.0
        self.doing = "waiting"

    # --- seeing ---

    def _find(self, obs, name):
        from .fastweights import who_key

        for e in obs.get("entities") or []:
            if who_key(e).lower() == name.lower() or (name == self.protectee and e.get("protectedTarget")):
                return e
        return None

    def _cat(self, obs, dx, dy, dz):
        from ..learn.features import decode_blocks

        g = obs.get("blocks")
        if not g:
            return None
        try:
            grid = decode_blocks(g)
        except Exception:
            return None
        r = grid.shape[0] // 2
        ix, iy, iz = int(math.floor(dx)) + r, dy + r, int(math.floor(dz)) + r
        if not (0 <= ix < grid.shape[0] and 0 <= iy < grid.shape[0] and 0 <= iz < grid.shape[0]):
            return None
        return int(grid[iy][iz][ix])

    def _unsafe(self, obs, me, ox, oz):
        """A spot (relative x, z) over a drop of 2+ or lava."""
        fx, fz = float(me.get("x", 0)) % 1.0, float(me.get("z", 0)) % 1.0
        x, z = ox + fx, oz + fz
        below, below2, at = self._cat(obs, x, -1, z), self._cat(obs, x, -2, z), self._cat(obs, x, 0, z)
        return LAVA in (below, at) or (below == AIR and below2 == AIR)

    # --- acting ---

    def _look_at(self, me, dx, dz, dy=0.0):
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(dy, max(0.1, math.hypot(dx, dz))))
        dyaw = wrap_degrees(yaw - float(me.get("yaw", 0)))
        dp = pitch - float(me.get("pitch", 0))
        return Action(yaw=max(-20, min(20, dyaw * 0.5)), pitch=max(-15, min(15, dp * 0.5))), abs(dyaw)

    def _walk(self, obs, me, dx, dz):
        d = math.hypot(dx, dz)
        a, off = self._look_at(me, dx, dz)
        if d < 0.45:
            return a, True
        if off < 40:
            # Never into a hole straight ahead.
            yaw = math.radians(float(me.get("yaw", 0)))
            ahead = (-math.sin(yaw) * 0.9, math.cos(yaw) * 0.9)
            if not self._unsafe(obs, me, ahead[0], ahead[1]):
                a.keys.add("forward")
                if d > 5:
                    a.keys.add("sprint")
                elif d < 1.3:
                    a.keys.add("sneak")
        return a, False

    def _fight(self, obs, frame, me, e):
        if self.fighter is None and self.profile:
            try:
                from . import create_learned

                self.fighter = create_learned(profile=self.profile, temperature=0.7)
            except Exception:
                self.fighter = False
        if self.fighter:
            return self.fighter.act(obs, frame)
        dx, dz = float(e.get("dx", 0)), float(e.get("dz", 0))
        a, off = self._look_at(me, dx, dz, float(e.get("dy", 0)) + 1.2)
        if math.hypot(dx, dz) > 2.5:
            a.keys.add("forward")
        elif off < 15 and float(me.get("attackReady", 1.0)) >= 0.9:
            a.attacks = 1
        return a

    def act(self, obs, frame):
        me = obs.get("self") or {}
        if self.mode == "stop" or not me or me.get("dead"):
            return Action()
        p = self._find(obs, self.protectee)
        if p is None:
            self.doing = f"can't see {self.protectee}"
            return Action()
        visible = {str(e.get("name", "")).lower() for e in obs.get("entities") or []}
        # Engage: whoever just hit the protected player; only the guard nearest to them goes.
        if self.mode == "guard":
            att = None
            if self.engaged is not None:
                att = next((e for e in obs.get("entities") or [] if e.get("id") == self.engaged), None)
                if att is None or not (0 <= att.get("hitProtected", -1) < 200) or math.hypot(
                        float(att.get("dx", 0)) - float(p.get("dx", 0)), float(att.get("dz", 0)) - float(p.get("dz", 0))) > 16:
                    self.engaged, att = None, None
            if att is None:
                for e in obs.get("entities") or []:
                    name = str(e.get("name", "")).lower()
                    if 0 <= e.get("hitProtected", -1) < 40 and not e.get("sameTeam") and name not in {n.lower() for n in self.names}:
                        mine = math.hypot(float(e.get("dx", 0)), float(e.get("dz", 0)))
                        others = [math.hypot(float(g.get("dx", 0)) - float(e.get("dx", 0)), float(g.get("dz", 0)) - float(e.get("dz", 0)))
                                  for g in obs.get("entities") or [] if str(g.get("name", "")).lower() in {n.lower() for n in self.names}]
                        if all(mine <= o + 0.5 for o in others):
                            self.engaged, att = e.get("id"), e
                            break
            if att is not None:
                self.doing = f"fighting {att.get('name') or att.get('type')}"
                return self._fight(obs, frame, me, att)
        # The spot.
        if self.mode == "follow":
            i, n, shape = 0, 1, "line"
        else:
            i, n = my_index(self.names, self.me, visible if self.close_gaps else None)
            shape = self.shape
        for attempt in range(10):
            ox, oz = slot_offset(shape, i, n, self.dist, float(p.get("yaw", 0)), self.turn)
            tx, tz = float(p.get("dx", 0)) + ox, float(p.get("dz", 0)) + oz
            if not self._unsafe(obs, me, tx, tz):
                break
            if shape not in ("ring", "square"):
                self.doing = "spot is unsafe: waiting"
                a, _ = self._look_at(me, float(p.get("dx", 0)), float(p.get("dz", 0)))
                return a
            self.turn += 20  # Turn the formation a bit and try again.
        a, there = self._walk(obs, me, tx, tz)
        if there:
            # In place: face outward, away from the protected player.
            out = self._look_at(me, -float(p.get("dx", 0)), -float(p.get("dz", 0)))[0]
            a.yaw, a.pitch = out.yaw * 0.5, out.pitch
            a.keys.add("sneak")
            self.doing = f"in place ({shape}, spot {i + 1} of {n})"
        else:
            self.doing = f"moving to spot {i + 1} of {n}"
        return a

    def status(self):
        return {"doing": self.doing, "protectee": self.protectee, "mode": self.mode, "shape": self.shape}
