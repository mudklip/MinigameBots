import base64
import unittest

import numpy as np

from minigamebots_brain.rl.objective import RewardEngine
from minigamebots_brain.rl.skills import Behaviours


def me(y=64.0, **kw):
    s = {"x": 0.0, "y": y, "z": 0.0, "onGround": True, "health": 20, "food": 20}
    s.update(kw)
    return {"self": s}


class SafeAndBeyondTest(unittest.TestCase):
    def test_safe_reward_is_paid_only_if_it_stays_safe(self):
        eng = RewardEngine({"rules": [{"event": "b:going_down", "value": 1, "per": "units", "safe": {"seconds": 1}}]})
        out = eng.step(1, [{"type": "b:going_down", "data": {"units": 3}}], me())
        self.assertEqual(out.reward, 0)  # Held back.
        paid = sum(eng.step(t, [], me()).delayed and 1 or 0 for t in range(2, 30))
        self.assertEqual(paid, 1)
        self.assertAlmostEqual(eng.episode_return, 3)
        # Same again, but it gets hurt within the second: nothing.
        eng.step(40, [{"type": "b:going_down", "data": {"units": 3}}], me())
        eng.step(45, [{"type": "damage_taken", "data": {"amount": 4}}], me())
        self.assertEqual(eng.cancelled[0][1], 3)
        for t in range(46, 80):
            eng.step(t, [], me())
        self.assertAlmostEqual(eng.episode_return, 3)

    def test_beyond_its_best_counts_only_new_depth(self):
        eng = RewardEngine({"rules": [{"event": "b:going_down", "value": 1, "per": "units", "beyond": True}]})
        r1 = eng.step(1, [{"type": "b:going_down", "data": {"units": 2}}], me(62)).reward
        # Jumps back up and drops to the same depth again: no new depth, no points.
        r2 = eng.step(2, [{"type": "b:going_down", "data": {"units": 2}}], me(62)).reward
        r3 = eng.step(3, [{"type": "b:going_down", "data": {"units": 3}}], me(59)).reward
        self.assertAlmostEqual(r1, 2)
        self.assertEqual(r2, 0)
        self.assertAlmostEqual(r3, 3)

    def test_rules_without_a_measure_count_once_a_round(self):
        eng = RewardEngine({"rules": [{"event": "b:landed_in_water", "value": 5, "beyond": True}]})
        a = eng.step(1, [{"type": "b:landed_in_water", "data": {"units": 1}}], me()).reward
        b = eng.step(2, [{"type": "b:landed_in_water", "data": {"units": 1}}], me()).reward
        self.assertEqual((a, b), (5, 0))


def blocks_with_drop_east():
    g = np.full((11, 11, 11), 2, dtype=np.uint8)  # Solid everywhere...
    g[5:, :, :] = 1  # ...air from the feet up...
    g[3:5, 5, 6] = 1  # ...and a 2-deep hole in the floor right next to it (east).
    return {"categories": base64.b64encode(g.tobytes()).decode()}


class NewBehavioursTest(unittest.TestCase):
    def kinds(self, b, obs, events=(), action=None):
        return {e["type"]: e["data"] for e in b.events(obs, action, list(events))}

    def test_new_behaviours(self):
        b = Behaviours({})
        self.kinds(b, me(64))
        o = me(63, standingOn="minecraft:gold_block", zone="hill")
        o["blocks"] = blocks_with_drop_east()
        out = self.kinds(b, o)
        self.assertAlmostEqual(out["b:going_down"]["units"], 1)
        self.assertEqual(out["b:standing_on"]["block"], "minecraft:gold_block")
        self.assertEqual(out["b:in_zone"]["zone"], "hill")
        self.assertIn("b:near_edge", out)
        hits = [{"type": "damage_dealt", "data": {"amount": 3}}]
        self.assertNotIn("b:combo", self.kinds(b, me(63), hits))
        self.kinds(b, me(63), hits)
        self.assertIn("b:combo", self.kinds(b, me(63), hits))
        self.assertNotIn("b:combo", self.kinds(b, me(63), [{"type": "damage_taken", "data": {"amount": 1}}] + hits))
        self.kinds(b, me(63, onGround=False, vy=-0.8))
        self.assertIn("b:landed_in_water", self.kinds(b, me(62, onGround=False, inWater=True)))
        self.assertIn("b:fell_into_void", self.kinds(b, me(-70, onGround=False), [{"type": "damage_taken", "data": {"cause": "void"}}]))
        self.assertNotIn("b:fell_into_void", self.kinds(b, me(-72, onGround=False), [{"type": "damage_taken", "data": {"cause": "void"}}]))


if __name__ == "__main__":
    unittest.main()
