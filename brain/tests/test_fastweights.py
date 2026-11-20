import os
import tempfile
import unittest

import numpy as np

from minigamebots_brain.policies.fastweights import FastWeights


def fight_obs(name):
    return {"entities": [{"type": "minecraft:player", "player": True, "name": name, "hitByMe": 3, "hitMe": 10}]}


class FastWeightsTest(unittest.TestCase):
    def test_learns_what_works_against_one_opponent_only(self):
        with tempfile.TemporaryDirectory() as d:
            fw = FastWeights(os.path.join(d, "fast_weights.json"))
            rng = np.random.default_rng(0)
            sizes = {"keys": 4, "attacks": 3, "uses": 2}
            # Against Steve, pressing key 2 lands hits; anything else gets it hurt.
            for t in range(400):
                who = fw.opponent(fight_obs("Steve"))
                b = fw.bias(who, sizes)
                p = 1 / (1 + np.exp(-(np.zeros(4) + b["keys"])))
                on = rng.random(4) < p
                ap = np.exp(b["attacks"]) / np.exp(b["attacks"]).sum()
                up = np.exp(b["uses"]) / np.exp(b["uses"]).sum()
                fw.remember(who, on, p, 0, ap, 0, up)
                ev = [{"type": "damage_dealt", "data": {"amount": 4}}] if on[2] else [{"type": "damage_taken", "data": {"amount": 2}}]
                fw.learn(ev)
            steve = fw.bias("Steve", sizes)["keys"]
            self.assertGreater(steve[2], 0.5)  # Key 2 is now more likely against Steve...
            self.assertTrue(np.all(np.abs(fw.bias("Alex", sizes)["keys"]) < 1e-9))  # ...but not against Alex.
            self.assertLessEqual(np.abs(steve).max(), 2.0)  # Only a nudge.
            fw.save()
            again = FastWeights(os.path.join(d, "fast_weights.json"))
            self.assertAlmostEqual(again.bias("Steve", sizes)["keys"][2], steve[2], places=5)

    def test_no_opponent_no_change(self):
        fw = FastWeights(None)
        self.assertIsNone(fw.opponent({"entities": [{"type": "minecraft:player", "player": True, "name": "Far", "hitMe": -1}]}))
        self.assertIsNone(fw.opponent({"entities": [{"type": "minecraft:player", "player": True, "name": "Mate", "hitMe": 2,
                                                     "sameTeam": True}]}))


if __name__ == "__main__":
    unittest.main()
