import time

from ..actions import Action
from .base import Policy


class TorchSmokePolicy(Policy):
    """Runs a small neural network on every frame and steers by its output.

    The output is meaningless (the network is untrained); this only proves the frame really
    reaches PyTorch on the GPU (or CPU) each tick, and measures how long that takes.
    """

    name = "torch_smoke"

    def __init__(self, device: str | None = None):
        import torch
        from torch import nn

        self.torch = torch
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        torch.manual_seed(0)
        self.net = nn.Sequential(
            nn.Conv2d(3, 16, 8, stride=4), nn.ReLU(),
            nn.Conv2d(16, 32, 4, stride=2), nn.ReLU(),
            nn.AdaptiveAvgPool2d(4), nn.Flatten(),
            nn.Linear(32 * 16, 2), nn.Tanh(),
        ).to(self.device).eval()
        self.times = []

    def act(self, obs, frame):
        if frame is None or isinstance(frame, (bytes, bytearray)):
            return Action()
        torch = self.torch
        start = time.perf_counter()
        with torch.no_grad():
            x = torch.from_numpy(frame.copy()).to(self.device).permute(2, 0, 1).unsqueeze(0).float() / 255.0
            out = self.net(x)[0].cpu()
        self.times.append((time.perf_counter() - start) * 1000)
        self.times = self.times[-100:]
        return Action(keys={"forward"}, yaw=float(out[0]) * 3.0)

    def status(self):
        avg = sum(self.times) / len(self.times) if self.times else None
        return {"device": self.device, "inference_ms": None if avg is None else round(avg, 2)}
