import numpy as np

from ..actions import Action
from .base import Policy


class LearnedPolicy(Policy):
    """Plays with the network trained for an AI profile (see learn/train.py).

    temperature: 1 = sample actions like the recordings (human-like variety), 0 = always the most
    likely action (more predictable), in between = some of each.
    """

    name = "learned"

    def __init__(self, profile: str, temperature: float = 1.0, device: str | None = None):
        import torch

        from ..learn import checkpoint

        self.torch = torch
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.model, self.info = checkpoint.load(profile, "model", self.device)
        self.model.eval()
        self.temperature = max(0.0, float(temperature))
        self.state = None
        self.decisions = 0

    def act(self, obs, frame):
        from ..learn import actions, features
        from ..learn.model import to_tensors

        torch = self.torch
        if obs.get("self", {}).get("dead"):
            self.state = None  # Fresh memory after respawning.
            return Action()
        f = features.featurize(obs)
        if frame is None or isinstance(frame, (bytes, bytearray)):
            frame_arr = np.zeros((features.FRAME_SIZE, features.FRAME_SIZE, 3), dtype=np.uint8)
        else:
            frame_arr = np.ascontiguousarray(frame)
        batch = {k: np.asarray(v)[None, None] for k, v in f.items()}
        batch["frames"] = frame_arr[None, None]
        with torch.no_grad():
            logits, self.state = self.model(to_tensors(batch, self.device), self.state)
        keys = torch.sigmoid(logits["keys"][0, 0]).cpu().numpy()
        if self.temperature == 0:
            keys_on = keys > 0.5
        else:
            keys_on = np.random.random(len(keys)) < keys
        self.decisions += 1
        return actions.decode_action(keys_on, self._pick(logits["attacks"]), self._pick(logits["uses"]),
                                     self._pick(logits["yaw"]), self._pick(logits["pitch"]))

    def _pick(self, logit):
        torch = self.torch
        l = logit[0, 0].float()
        if self.temperature == 0:
            return int(l.argmax())
        probs = torch.softmax(l / self.temperature, dim=-1)
        return int(torch.multinomial(probs, 1))

    def status(self):
        return {"step": self.info.get("step"), "val_loss": self.info.get("val_loss"), "device": self.device}
