import os

import numpy as np

from ..actions import Action
from .base import Policy

# VPT samples at temperature 2 by default; the mod's 0..1 "temperature" scales that.
VPT_BASE_TEMPERATURE = 2.0


class VPTPolicy(Policy):
    """Plays with OpenAI's VPT foundation model, optionally fine-tuned for one AI profile.

    foundation  -- folder of an installed foundation (or its id, with `foundations` the root)
    profile     -- an AI profile whose core/ holds fine-tuned VPT layers (optional)
    temperature -- 0 = always the most likely action, 1 = VPT's normal variety
    """

    name = "vpt"

    def __init__(self, foundation: str | None = None, profile: str | None = None, foundations: str | None = None,
                 temperature: float = 1.0, device: str | None = None):
        import torch

        from ..vpt import agent, foundation as fnd

        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        info = None
        if profile is not None:
            info = agent.read_info(profile)
            foundations = foundations or agent.foundations_root_for(profile)
            foundation = foundation or info.get("foundation")
        if not foundation:
            raise ValueError("No foundation model given")
        directory = foundation if os.path.isdir(foundation) else os.path.join(foundations or "", foundation)
        model, self.meta = fnd.load(directory, "cpu")
        if profile is not None:
            agent.load_profile_layers(model, profile)
        self.model = model.to(self.device)
        self.info = info or {}
        self.runner = agent.VPTRunner(self.model, self.device)
        self.temperature = max(0.0, float(temperature)) * VPT_BASE_TEMPERATURE
        self.decisions = 0

    def act(self, obs, frame):
        if obs.get("self", {}).get("dead"):
            self.runner.reset()
            return Action()
        if frame is None or isinstance(frame, (bytes, bytearray)):
            return Action()  # VPT only sees pixels; without a frame it waits.
        self.decisions += 1
        return self.runner.act(np.asarray(frame, dtype=np.uint8), self.temperature)

    def status(self):
        return {"foundation": self.meta.get("id"), "step": self.info.get("step"), "device": self.device,
                "decisions": self.decisions}
