import json
import os
import tempfile
import unittest

import numpy as np

from minigamebots_brain import dream, players


def fight_rows(n=900, seed=0):
    rng = np.random.default_rng(seed)
    rows = []
    back = False
    for t in range(n):
        if t % 30 == 0:
            back = rng.random() < 0.5  # Like a player, it keeps a choice up for a moment.
        keys = ["back"] if back else ["forward", "attack"]
        obs = {"self": {"health": 14.0, "yaw": 0.0, "attackReady": 1.0, "onGround": True},
               "entities": [{"type": "minecraft:player", "player": True, "dx": 0.0, "dy": 0.0, "dz": 2.0, "health": 20.0,
                             "visible": True, "hitMe": 5}]}
        # Rushing in gets it hurt; backing off doesn't.
        events = [] if back else [{"type": "damage_taken", "data": {"amount": 4.0}}]
        rows.append({"input": {"keys": keys, "attacks": 0 if back else 1}, "obs": obs, "events": []})
        if events and t + 1 < n:
            rows[-1]["_next"] = events
    # Move each outcome to the following tick (it happens after the choice).
    for i in range(len(rows) - 1):
        if "_next" in rows[i]:
            rows[i + 1]["events"] = rows[i].pop("_next")
    return rows


class DreamTest(unittest.TestCase):
    def test_finds_a_better_choice_for_hard_moments(self):
        with tempfile.TemporaryDirectory() as root:
            players.write(os.path.join(root, "rec1"), fight_rows(), {"id": "rec1"})
            meta_p = os.path.join(root, "rec1", "meta.json")
            m = json.load(open(meta_p))
            m["source"] = "ai"
            json.dump(m, open(meta_p, "w"))
            out = dream.dream(root, 0.0, root)
            self.assertGreater(out["accuracy"], 0.3)
            self.assertGreater(out["better"], 0)
            self.assertIn("back_off", out["tried"])  # In its head, backing off goes better than rushing in.
            meta = json.load(open(os.path.join(root, out["recording"], "meta.json")))
            self.assertEqual(meta["source"], "dream")
            self.assertLess(meta["weight"], 1.0)  # Dreams count less than real play.

    def test_no_dreaming_when_its_imagination_is_poor(self):
        with tempfile.TemporaryDirectory() as root:
            rows = fight_rows()
            rng = np.random.default_rng(3)
            for r in rows:  # Outcomes that don't depend on anything it did: nothing to learn.
                r["events"] = [{"type": "damage_taken", "data": {"amount": 4.0}}] if rng.random() < 0.5 else []
            players.write(os.path.join(root, "rec1"), rows, {"id": "rec1"})
            meta_p = os.path.join(root, "rec1", "meta.json")
            m = json.load(open(meta_p))
            m["source"] = "ai"
            json.dump(m, open(meta_p, "w"))
            out = dream.dream(root, 0.0, root)
            self.assertEqual(out["better"], 0)
            self.assertNotIn("recording", out)


if __name__ == "__main__":
    unittest.main()
