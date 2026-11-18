"""Teacher: a scripted expert PvPer that plays through the same virtual input as the AI.

Run it with the recorder on and every fight becomes a demonstration to imitate (Training
screen: "Record teacher demos"). It uses the techniques from the knowledge base:

- crits: jump when the attack is charged, hit on the way down (x1.5 damage)
- W-tap: sprint in, and after each hit let go of forward for a moment so the next hit is a
  sprint hit again (extra knockback)
- shield: holds the shield up while its own attack recharges, lowers it to swing
- axe on shields: switches to an axe when the target raises a shield, hits it, switches back
- strafing: circles the target, changing direction now and then

It never cheats: it only sees what the AI sees and is limited by the humanizer like the AI.
"""

from __future__ import annotations

import math
import random

from ..actions import Action
from .base import Policy
from .follow import FollowPolicy, wrap_degrees


class TeacherPolicy(Policy):
    name = "teacher"

    def __init__(self, target: str = "any", style: str = "mixed", reach: float = 2.9, seed: int | None = None, **_):
        self.target = target
        self.style = style  # "mixed", "crits" or "wtap"
        self.reach = float(reach)
        self.aim = FollowPolicy(target=target, attack=False, reach=reach)
        self.rng = random.Random(seed)
        self.strafe = 1
        self.next_switch = 0
        self.release = 0
        self.tick = 0
        self.hits = 0
        self.crit_jump = False
        self.airborne = False

    def _slot(self, obs, kinds) -> int | None:
        for item in obs.get("inventory", []):
            if item.get("slot", 99) < 9 and any(k in item.get("id", "") for k in kinds):
                return item["slot"]
        return None

    def _target(self, obs):
        best = None
        for e in obs.get("entities", []):
            if not e.get("visible", True) or e.get("sameTeam"):
                continue
            if self.target == "any":
                if not (e.get("player") or e.get("hostile") or "mannequin" in e.get("type", "")):
                    continue
            elif e.get("type") != self.target:
                continue
            d = math.sqrt(e["dx"] ** 2 + e["dy"] ** 2 + e["dz"] ** 2)
            if best is None or d < best[0]:
                best = (d, e)
        return best

    def act(self, obs, frame):
        self.tick += 1
        me = obs.get("self") or {"yaw": 0.0, "pitch": 0.0}
        pending = self.aim._pending(me)
        found = self._target(obs)
        if found is None:
            return self.aim._turn(set(), 4.0, -0.3 * float(me.get("pitch", 0.0)))  # Look around.
        dist, e = found
        flat = math.hypot(e["dx"], e["dz"])

        # Weapon: an axe against a raised shield, otherwise a sword (or an axe if that's all).
        sword = self._slot(obs, ("_sword",))
        axe = self._slot(obs, ("_axe",))
        want = axe if e.get("blocking") and axe is not None else (sword if sword is not None else axe)
        if want is not None and obs.get("selectedSlot") != want:
            return self.aim._turn({f"hotbar_{want + 1}"}, 0.0, 0.0)

        want_yaw = math.degrees(math.atan2(-e["dx"], e["dz"]))
        want_pitch = -math.degrees(math.atan2(e["dy"] + 0.9, max(flat, 1e-3)))
        yaw_err = wrap_degrees(want_yaw - float(me.get("yaw", 0.0)))
        pitch_err = want_pitch - float(me.get("pitch", 0.0))
        keys = set()

        # Movement: close in sprinting, then strafe around at fighting distance.
        if self.tick >= self.next_switch:
            self.strafe = -self.strafe
            self.next_switch = self.tick + self.rng.randint(15, 40)
        ready = float(me.get("attackReady", 1.0)) >= 0.95
        if self.release > 0:
            self.release -= 1  # W-tap: forward let go for a moment.
        elif flat > self.reach - 0.6 or (self.crit_jump and flat > 1.5):
            keys |= {"forward", "sprint"}
        # Strafe only while the attack recharges: moving sideways while swinging spoils the aim.
        if flat < self.reach + 2 and not ready:
            keys.add("left" if self.strafe > 0 else "right")

        # Aimed = the crosshair is inside its hitbox (0.6 wide): a swing that misses wastes the charge.
        tolerance = max(4.0, math.degrees(math.atan2(0.28, max(flat, 0.5))))
        in_reach = dist < self.reach + 0.4 and abs(yaw_err) < tolerance and abs(pitch_err) < 25
        on_ground = bool(me.get("onGround", True))
        use_crits = self.style == "crits" or (self.style == "mixed" and self.hits % 2 == 1)

        # Shield up while recharging (and not about to jump), lowered to swing.
        if self._has_shield(obs) and not ready and dist < 4.5 and on_ground:
            keys.add("use")
            keys.discard("sprint")

        attack = False
        if in_reach and ready:
            if use_crits:
                if on_ground and not self.crit_jump:
                    keys.add("jump")
                    keys.discard("sprint")  # A sprinting hit can't crit.
                    self.crit_jump = True
                elif not on_ground and float(me.get("vy", 0.0)) < -0.05:
                    attack = True
            else:
                attack = True
        if self.crit_jump:
            keys.discard("sprint")  # A sprinting hit can't crit.
            self.airborne = self.airborne or not on_ground
            if on_ground and self.airborne and not attack:
                self.crit_jump = self.airborne = False  # Landed without a hit: try again.
        if attack:
            keys.add("attack")
            keys.discard("use")
            self.hits += 1
            self.crit_jump = self.airborne = False
            if self.style in ("wtap", "mixed"):
                self.release = 3
        return self.aim._turn(keys, 0.7 * wrap_degrees(yaw_err - pending[0]), 0.6 * (pitch_err - pending[1]))

    @staticmethod
    def _has_shield(obs) -> bool:
        return any(i.get("slot") == 40 and "shield" in i.get("id", "") for i in obs.get("inventory", []))
