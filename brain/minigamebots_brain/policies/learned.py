import numpy as np

from ..actions import Action
from .base import Policy


def create_learned(profile: str, temperature: float = 1.0, device: str | None = None, foundations: str | None = None):
    """The right player for a trained AI: the Lite network, or VPT plus the AI's own layers."""
    from ..learn import checkpoint

    info = checkpoint.read_info(profile) or {}
    if info.get("arch") == "vpt-tune":
        from .vpt import VPTPolicy

        return VPTPolicy(profile=profile, foundations=foundations, temperature=temperature, device=device)
    return LearnedPolicy(profile, temperature, device)


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
        if self.device == "cpu":
            # Playing on the CPU: 8-bit weights for the dense layers (about 4x smaller, faster);
            # training keeps full precision.
            try:
                import torch

                self.model = torch.ao.quantization.quantize_dynamic(self.model, {torch.nn.Linear}, dtype=torch.qint8)
            except Exception:
                pass
        self.temperature = max(0.0, float(temperature))
        self.state = None
        self.decisions = 0
        # Quick adapting to each opponent, on top of the trained network (see fastweights.py).
        from .fastweights import FastWeights

        import os

        self.fast = FastWeights(os.path.join(profile, "fast_weights.json") if os.path.isdir(profile) else None)
        self.opponent = None
        # Reading each opponent's style, live and remembered (see opponent.py); kept for the whole
        # AI (the profile folder, next to manifest.json), shared by all its skills.
        from .opponent import OpponentModel

        root = os.path.abspath(profile)
        for _ in range(4):
            if os.path.isfile(os.path.join(root, "manifest.json")) or os.path.dirname(root) == root:
                break
            root = os.path.dirname(root)
        self.styles = OpponentModel(os.path.join(root, "styles.json") if os.path.isfile(os.path.join(root, "manifest.json")) else None)
        self.reading = None

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
        # What happened since the last decision teaches the fast weights for this opponent.
        self.fast.learn(obs.get("_events"))
        who = self.fast.opponent(obs)
        if self.opponent is not None and who != self.opponent:
            self.styles.end_fight(self.opponent)  # That fight is over: remember how they fought.
        self.opponent = who
        key_logits = logits["keys"][0, 0].float().cpu().numpy()
        bias = self.fast.bias(self.opponent, {"keys": len(key_logits), "attacks": int(logits["attacks"].shape[-1]),
                                              "uses": int(logits["uses"].shape[-1])})
        # Countering their style: added to the nudges, not remembered as the fast weights' own choices.
        self.reading = self.styles.observe(obs, who)
        counter = self.styles.bias(self.reading, obs, actions.KEY_INDEX, len(key_logits), int(logits["attacks"].shape[-1]))
        bias = {"keys": bias["keys"] + counter["keys"], "attacks": bias["attacks"] + counter["attacks"], "uses": bias["uses"]}
        keys = 1.0 / (1.0 + np.exp(-(key_logits + bias["keys"])))
        if self.temperature == 0:
            keys_on = keys > 0.5
        else:
            keys_on = np.random.random(len(keys)) < keys
        self.decisions += 1
        attack, attack_p = self._pick_p(logits["attacks"], bias["attacks"])
        use, use_p = self._pick_p(logits["uses"], bias["uses"])
        self.fast.remember(self.opponent, keys_on, keys, attack, attack_p, use, use_p)
        if self.decisions % 600 == 0:
            self.fast.save()
        action = actions.decode_action(keys_on, attack, use, self._pick(logits["yaw"]), self._pick(logits["pitch"]))
        if "mark" in logits:
            m = self._pick(logits["mark"])
            if m > 0:
                action.mark = features.WAYPOINT_TYPES[m - 1]
        return action

    def _pick_p(self, logit, bias):
        """Like _pick, with the fast-weight nudge; also returns the probabilities (for learning)."""
        l = logit[0, 0].float().cpu().numpy() + bias
        t = max(self.temperature, 1e-3)
        p = np.exp((l - l.max()) / t)
        p = p / p.sum()
        if self.temperature == 0:
            return int(l.argmax()), p
        return int(np.random.choice(len(p), p=p)), p

    def _pick(self, logit):
        torch = self.torch
        l = logit[0, 0].float()
        if self.temperature == 0:
            return int(l.argmax())
        probs = torch.softmax(l / self.temperature, dim=-1)
        return int(torch.multinomial(probs, 1))

    def close(self):
        """Swapped out: keep what it learned about its opponents since the last save."""
        if self.opponent is not None:
            self.styles.end_fight(self.opponent)
        self.fast.save()
        self.styles.save()

    def status(self):
        return {"step": self.info.get("step"), "val_loss": self.info.get("val_loss"), "device": self.device,
                "adapting_to": self.opponent, "adapted": round(self.fast.strength(self.opponent), 2),
                "reading": self.reading.summary() if self.reading is not None and self.opponent else None}
