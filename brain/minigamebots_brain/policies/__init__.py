"""Brains that decide what to do each tick.

These are simple hand-written test brains used to check the whole loop (mod -> brain -> mod)
before any learning exists. Learned brains plug in the same way.
"""

from .base import Policy
from .follow import FollowPolicy
from .learned import LearnedPolicy, create_learned
from .simple import IdlePolicy, RandomPolicy, SpinPolicy
from .smoke import TorchSmokePolicy



def _vpt(**kwargs) -> Policy:
    from .vpt import VPTPolicy  # Imported on use: it needs torch.

    return VPTPolicy(**kwargs)


def _practice(**kwargs) -> Policy:
    from .practice import PracticePolicy  # Imported on use: it needs torch.

    return PracticePolicy(**kwargs)


POLICIES = {
    "idle": IdlePolicy,
    "random": RandomPolicy,
    "spin": SpinPolicy,
    "follow": FollowPolicy,
    "torch_smoke": TorchSmokePolicy,
    "learned": create_learned,
    "vpt": _vpt,
    "practice": _practice,
}


def create(name: str, args: dict | None = None) -> Policy:
    if name not in POLICIES:
        raise ValueError(f"unknown policy '{name}', choose from {sorted(POLICIES)}")
    return POLICIES[name](**(args or {}))
