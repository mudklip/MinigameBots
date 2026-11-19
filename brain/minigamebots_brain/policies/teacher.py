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
        self.crit_failed = False
        self.swung = False
        self.swung_at = 0
        self.prev_yaw = None
        self.prev_target = None
        self.yaw_rate = 0.0

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
        _, e = found

        # Weapon: an axe against a raised shield, otherwise a sword (or an axe if that's all).
        sword = self._slot(obs, ("_sword",))
        axe = self._slot(obs, ("_axe",))
        want = axe if e.get("blocking") and axe is not None else (sword if sword is not None else axe)
        if want is not None and obs.get("selectedSlot") != want:
            return self.aim._turn({f"hotbar_{want + 1}"}, 0.0, 0.0)

        # Aim where it will be when the turn lands: our turns arrive ~4 ticks late, and a target
        # strafing at fighting range moves a few degrees a tick. Measure how fast its direction
        # changes and lead by that.
        tx, tz = e["dx"], e["dz"]
        flat = math.hypot(tx, tz)
        raw_yaw = math.degrees(math.atan2(-tx, tz))
        if self.prev_yaw is not None and self.prev_target == e.get("id"):
            rate = wrap_degrees(raw_yaw - self.prev_yaw)
            if abs(rate) < 30:
                self.yaw_rate = 0.6 * self.yaw_rate + 0.4 * rate
        else:
            self.yaw_rate = 0.0
        self.prev_yaw, self.prev_target = raw_yaw, e.get("id")
        want_yaw = raw_yaw + self.yaw_rate * 3
        want_pitch = -math.degrees(math.atan2(e["dy"] + 1.1, max(flat, 1e-3)))
        yaw_err = wrap_degrees(want_yaw - float(me.get("yaw", 0.0)))
        pitch_err = want_pitch - float(me.get("pitch", 0.0))
        # Distance to its hitbox (0.6 wide), as the game measures reach.
        reach_dist = math.hypot(max(0.0, math.hypot(e["dx"], e["dz"]) - 0.3), e["dy"] + 1.1)
        keys = set()

        ready = float(me.get("attackReady", 1.0)) >= 0.9
        # After a swing, wait until the recharge shows (the reading lags a few ticks).
        if self.swung:
            if float(me.get("attackReady", 1.0)) < 0.5 or self.tick - self.swung_at > 12:
                self.swung = False
            ready = False
        on_ground = bool(me.get("onGround", True))
        if self.tick >= self.next_switch:
            self.strafe = -self.strafe
            self.next_switch = self.tick + self.rng.randint(15, 40)
        # Movement: always close in when out of reach; strafe while the attack recharges.
        if self.release > 0:
            self.release -= 1  # W-tap: forward let go for a moment.
        elif reach_dist > self.reach - 0.4 and abs(yaw_err) < 70:
            keys |= {"forward", "sprint"}
        elif reach_dist < 1.8:
            keys.add("back")  # Too close: every sidestep swings the angle; keep fighting distance.
        if not ready and 2.4 < reach_dist < self.reach + 1.5:
            keys.add("left" if self.strafe > 0 else "right")

        # Shield up while recharging, lowered to swing.
        if self._has_shield(obs) and not ready and reach_dist < 4.5 and on_ground:
            keys.add("use")
            keys.discard("sprint")

        # Swing when it's in reach and roughly in the crosshair (like a player; a near miss still
        # often connects because the target is wider than the exact centre).
        # Generous on purpose: in testing every swing it took inside this window connected.
        aimed = (abs(yaw_err) < max(15.0, math.degrees(math.atan2(0.9, max(flat, 0.5))))
                 and abs(pitch_err) < max(14.0, math.degrees(math.atan2(0.9, max(flat, 0.5)))))
        in_reach = reach_dist < self.reach
        use_crits = (self.style == "crits" or (self.style == "mixed" and self.hits % 2 == 1)) and not self.crit_failed
        attack = False
        if in_reach and ready and aimed:
            if use_crits and reach_dist < self.reach - 0.3:
                if on_ground and not self.crit_jump:
                    keys.add("jump")
                    self.crit_jump = True
                elif not on_ground and float(me.get("vy", 0.0)) < -0.03:
                    attack = True
                elif on_ground and self.airborne:
                    attack = True  # Landed without the crit: just hit.
                    self.crit_failed = True
            else:
                attack = True
        if self.crit_jump:
            keys.discard("sprint")  # A sprinting hit can't crit.
            self.airborne = self.airborne or not on_ground
            if on_ground and self.airborne and not attack:
                self.crit_jump = self.airborne = False
                self.crit_failed = True
        if attack:
            self.swung, self.swung_at = True, self.tick
            keys.add("attack")
            keys.discard("use")
            self.hits += 1
            self.crit_jump = self.airborne = self.crit_failed = False
            if self.style in ("wtap", "mixed"):
                self.release = 3
        # Turns already on their way aren't ordered again (they land a few ticks late).
        return self.aim._turn(keys, 0.6 * wrap_degrees(yaw_err - pending[0]), 0.5 * (pitch_err - pending[1]))

    @staticmethod
    def _has_shield(obs) -> bool:
        return any(i.get("slot") == 40 and "shield" in i.get("id", "") for i in obs.get("inventory", []))
