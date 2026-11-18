import math

from ..actions import Action
from .base import Policy


def wrap_degrees(angle: float) -> float:
    return (angle + 180.0) % 360.0 - 180.0


class FollowPolicy(Policy):
    """Walks to the nearest visible entity of a type and hits it.

    A hand-written closed loop: it needs perception (entity positions), camera control and
    timing to work, so it's a good end-to-end check of the whole pipeline.
    """

    name = "follow"

    # Turns are applied a few ticks after they're ordered (reaction delay plus smoothing). The
    # policy tracks how much it has ordered versus how much the camera actually turned; the
    # difference is still on its way and isn't ordered again. A small leak forgets any mismatch
    # (turns the game clipped, teleports), so errors never build up.
    LEAK = 0.02

    def __init__(self, target: str = "minecraft:pig", attack: bool = True, gain: float = 0.5,
                 reach: float = 2.6, cooldown_ticks: int = 12):
        self.target = target
        self.attack = attack
        self.gain = float(gain)
        self.reach = float(reach)
        self.cooldown_ticks = int(cooldown_ticks)
        self.cooldown = 0
        self.last_distance = None
        self.ordered = [0.0, 0.0]
        self.observed = [0.0, 0.0]
        self.previous = None

    def nearest(self, obs):
        best = None
        for e in obs.get("entities", []):
            if not e.get("visible", True):
                continue
            if self.target != "any" and e.get("type") != self.target:
                continue
            d = math.sqrt(e["dx"] ** 2 + e["dy"] ** 2 + e["dz"] ** 2)
            if best is None or d < best[0]:
                best = (d, e)
        return best

    @staticmethod
    def weapon_slot(obs):
        """Hotbar slot (0-8) of the first sword or axe, or None."""
        for item in obs.get("inventory", []):
            if item.get("slot", 99) < 9 and any(w in item.get("id", "") for w in ("_sword", "_axe")):
                return item["slot"]
        return None

    def _pending(self, me):
        """Turn ordered but not yet seen in the camera, as (yaw, pitch)."""
        if self.previous is not None:
            self.observed[0] += wrap_degrees(me["yaw"] - self.previous[0])
            self.observed[1] += me["pitch"] - self.previous[1]
        self.previous = (me["yaw"], me["pitch"])
        pending = [self.ordered[i] - self.observed[i] for i in range(2)]
        for i in range(2):
            self.ordered[i] -= self.LEAK * pending[i]
            pending[i] *= 1 - self.LEAK
        return pending

    def _turn(self, keys, yaw, pitch, attacks=0):
        self.ordered[0] += yaw
        self.ordered[1] += pitch
        return Action(keys=keys, attacks=attacks, yaw=yaw, pitch=pitch)

    def act(self, obs, frame):
        self.cooldown = max(0, self.cooldown - 1)
        me = obs.get("self", {"yaw": 0.0, "pitch": 0.0})
        pending = self._pending(me)
        slot = self.weapon_slot(obs)
        if slot is not None and obs.get("selectedSlot") != slot:
            return self._turn({f"hotbar_{slot + 1}"}, 0.0, 0.0)
        found = self.nearest(obs)
        if found is None:
            self.last_distance = None
            return self._turn(set(), 4.0, 0.0)  # Look around for a target.
        distance, e = found
        self.last_distance = distance
        horizontal = math.hypot(e["dx"], e["dz"])
        # Minecraft: yaw 0 faces +Z, increasing clockwise; pitch is positive looking down.
        target_yaw = math.degrees(math.atan2(-e["dx"], e["dz"]))
        target_pitch = -math.degrees(math.atan2(e["dy"] + 0.5, max(horizontal, 1e-3)))
        yaw_error = wrap_degrees(target_yaw - me["yaw"])
        pitch_error = target_pitch - me["pitch"]
        # What is still wrong once the turns already on their way have happened.
        yaw_left = wrap_degrees(yaw_error - pending[0])
        pitch_left = pitch_error - pending[1]
        keys = set()
        if horizontal > self.reach - 0.4 and abs(yaw_error) < 45:
            keys.add("forward")
        attacks = 0
        if self.attack and distance < self.reach + 0.4 and abs(yaw_error) < 12 and self.cooldown == 0:
            keys.add("attack")
            self.cooldown = self.cooldown_ticks
        return self._turn(keys, self.gain * yaw_left, self.gain * pitch_left, attacks)

    def status(self):
        return {"target": self.target, "distance": None if self.last_distance is None else round(self.last_distance, 2)}
