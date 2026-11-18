"""The action format, identical to GameInput/AgentAction on the Java side."""

from dataclasses import dataclass, field

KEYS = (
    "forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use", "pick",
    "drop", "swap_offhand", "inventory",
    "hotbar_1", "hotbar_2", "hotbar_3", "hotbar_4", "hotbar_5", "hotbar_6", "hotbar_7", "hotbar_8", "hotbar_9",
)


@dataclass
class Action:
    keys: set = field(default_factory=set)
    attacks: int = 0  # Extra attack clicks this tick (a fresh press of "attack" already clicks once).
    uses: int = 0
    yaw: float = 0.0  # Degrees, right is positive.
    pitch: float = 0.0  # Degrees, down is positive.

    def to_message(self, tick: int) -> dict:
        unknown = set(self.keys) - set(KEYS)
        if unknown:
            raise ValueError(f"unknown keys: {sorted(unknown)}")
        return {
            "type": "action",
            "tick": tick,
            "keys": sorted(self.keys),
            "attacks": int(self.attacks),
            "uses": int(self.uses),
            "yaw": float(self.yaw),
            "pitch": float(self.pitch),
        }


NOOP = Action()
