import random

from ..actions import Action
from .base import Policy


class IdlePolicy(Policy):
    """Does nothing. `think_ms` makes it take that long per tick, to test how the mod and
    Training Grounds cope with a slow brain (e.g. "match AI speed")."""

    name = "idle"

    def __init__(self, think_ms: float = 0.0, mark: str | None = None):
        self.think_s = max(0.0, float(think_ms)) / 1000.0
        self.mark = mark  # Test the "mark this place" meta-action once.

    def act(self, obs, frame):
        if self.think_s:
            import time

            time.sleep(self.think_s)
        action = Action(mark=self.mark)
        self.mark = None
        return action


class SpinPolicy(Policy):
    """Turns on the spot at a steady speed."""

    name = "spin"

    def __init__(self, degrees_per_tick: float = 6.0):
        self.speed = float(degrees_per_tick)

    def act(self, obs, frame):
        return Action(yaw=self.speed)


class RandomPolicy(Policy):
    """Wanders: walks, turns and jumps in random stretches."""

    name = "random"

    def __init__(self, seed: int | None = None):
        self.rng = random.Random(seed)
        self.remaining = 0
        self.current = Action()

    def act(self, obs, frame):
        if self.remaining <= 0:
            self.remaining = self.rng.randint(10, 40)
            keys = set()
            if self.rng.random() < 0.7:
                keys.add("forward")
            if self.rng.random() < 0.2:
                keys.add(self.rng.choice(["left", "right"]))
            if self.rng.random() < 0.3:
                keys.add("sprint")
            self.current = Action(keys=keys, yaw=self.rng.uniform(-4, 4), pitch=self.rng.uniform(-0.5, 0.5))
        self.remaining -= 1
        keys = set(self.current.keys)
        if self.rng.random() < 0.05:
            keys.add("jump")
        # Keep pitch near level so it doesn't stare at the sky.
        pitch = self.current.pitch - 0.05 * float(obs.get("self", {}).get("pitch", 0.0))
        return Action(keys=keys, yaw=self.current.yaw, pitch=pitch)
