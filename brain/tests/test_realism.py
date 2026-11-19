import json
import os
import random
import tempfile
import unittest

from minigamebots_brain import realism


def human(n=40, seed=0):
    r = random.Random(seed)
    return [{"keys": ["forward"] if r.random() < 0.8 else ["forward", "left"], "yaw": r.gauss(0, 2.5),
             "pitch": r.gauss(0, 0.8), "attacks": 1 if r.random() < 0.05 else 0} for _ in range(n)]


def robot(n=40):
    return [{"keys": ["forward"], "yaw": 12.0 if t % 10 == 0 else 0.0, "pitch": 0.0,
             "attacks": 1 if t % 10 == 0 else 0} for t in range(n)]


class RealismTest(unittest.TestCase):
    def test_critic_and_style(self):
        import numpy as np

        h = [realism.window_features(human(seed=i)) for i in range(30)]
        m = [realism.window_features(robot()) for _ in range(30)]
        x = np.array(h + m)
        data = {"mean": x.mean(0).tolist(), "std": (x.std(0) + 1e-6).tolist()}
        z = (x - np.array(data["mean"])) / np.array(data["std"])
        data["critic"] = realism.fit_logistic(z, np.r_[np.ones(30), np.zeros(30)]).tolist()
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "preferences.jsonl"), "w") as f:
                for i in range(6):
                    f.write(json.dumps({"a": human(seed=100 + i), "b": robot(), "pick": "a"}) + "\n")
            style = realism.train_style(d, data)
        self.assertIsNotNone(style)
        data["style"] = style[0]
        r = realism.Realism(data)
        self.assertGreater(r.human_score(human(seed=99)), 0.5)
        self.assertLess(r.human_score(robot()), 0.5)
        self.assertGreater(r.style_score(human(seed=98)), r.style_score(robot()))

    def test_humanizer_tuning(self):
        t = realism.tune_humanizer([human(400)])
        self.assertTrue(150 <= t["maxTurnSpeed"] <= 3000)


if __name__ == "__main__":
    unittest.main()
