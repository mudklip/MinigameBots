import json
import os
import tempfile
import unittest

from minigamebots_brain import players
from minigamebots_brain.rl import rewardfit


def make_recording(root):
    rows, marks = [], []
    for t in range(1200):
        ev = []
        if t % 100 == 10:
            ev = [{"type": "damage_dealt", "data": {"amount": 4}}]
            if t < 600:
                marks.append({"type": "good", "tick": t})
        if t % 100 == 60:
            ev = [{"type": "damage_taken", "data": {"amount": 4, "cause": "melee"}}]
            if t < 600:
                marks.append({"type": "mistake", "tick": t})
        rows.append({"input": {}, "obs": {"self": {"x": 0, "y": 64, "z": 0}}, "events": ev, "frameAge": -1})
    d = os.path.join(root, "r1")
    players.write(d, rows, {"id": "r1", "name": "r1"})
    with open(os.path.join(d, "meta.json")) as f:
        meta = json.load(f)
    meta["marks"] = marks
    meta["source"] = "human"
    with open(os.path.join(d, "meta.json"), "w") as f:
        json.dump(meta, f)


class RewardFitTest(unittest.TestCase):
    def test_learns_that_taking_damage_is_bad(self):
        with tempfile.TemporaryDirectory() as root:
            make_recording(root)
            # The current rules are wrong: taking damage is rewarded.
            skill = {"rules": [{"event": "damage_dealt", "value": 1}, {"event": "damage_taken", "value": 1}]}
            res = rewardfit.fit(skill, root)
            self.assertEqual(res["windows"], {"good": 6, "mistake": 6})
            self.assertLessEqual(res["now"], 0.5)
            self.assertGreater(res["fitted"], 0.9)
            taken = [r for r in res["rules"] if r["name"] == "damage_taken"][0]
            self.assertLess(taken["suggested"], 0)

    def test_needs_marks(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertIn("No marked moments", rewardfit.fit({"rules": [{"event": "kill", "value": 1}]}, root)["why"])


if __name__ == "__main__":
    unittest.main()


class StageBackTest(unittest.TestCase):
    def test_steps_back_when_struggling_and_must_earn_it_again(self):
        from minigamebots_brain.rl.skills import StageTracker

        with tempfile.TemporaryDirectory() as d:
            defn = {"stages": [{"advance": {"episodes": 3, "avg": 4}}, {"advance": {"episodes": 20, "avg": 4}}, {}]}
            st = StageTracker(d, defn)
            for _ in range(3):
                st.episode(5)
            self.assertEqual(st.stage, 1)
            for _ in range(10):
                st.episode(0.2)  # Far below what passing stage 0 took.
            self.assertEqual(st.stage, 0)
            for _ in range(3):
                st.episode(5)
            self.assertEqual(st.stage, 0)  # Not yet: twice as many tries now.
            for _ in range(3):
                st.episode(5)
            self.assertEqual(st.stage, 1)
