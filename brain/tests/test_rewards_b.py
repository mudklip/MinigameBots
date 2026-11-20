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


from minigamebots_brain.rl.waste import WasteTracker  # noqa: E402


def st(inv, **kw):
    s = {"x": 0.0, "y": 64.0, "z": 0.0, "health": 20.0, "food": 20, "saturation": 5.0, "onGround": True}
    s.update(kw)
    return {"self": s, "inventory": [{"slot": i, "id": a, "count": n, "detail": d} for i, (a, n, d) in enumerate(inv)]}


def run(w, frames):
    out = []
    for obs, events in frames:
        out += w.step(obs, None, events)
    return out


class WasteTest(unittest.TestCase):
    def test_missed_arrow_vs_hit(self):
        w = WasteTracker()
        frames = [(st([("minecraft:arrow", 10, None)]), [])] + [(st([("minecraft:arrow", 9, None)]), [])] * 70
        out = run(w, frames)
        self.assertEqual(len(out), 1)
        self.assertAlmostEqual(out[0]["data"]["units"], round(1 / (1 + 9 / 16), 3))
        w2 = WasteTracker()
        frames = [(st([("minecraft:arrow", 10, None)]), []), (st([("minecraft:arrow", 9, None)]), []),
                  (st([("minecraft:arrow", 9, None)]), [{"type": "damage_dealt", "data": {}}])] + [(st([("minecraft:arrow", 9, None)]), [])] * 70
        self.assertEqual(run(w2, frames), [])

    def test_food_overflow_only(self):
        w = WasteTracker()
        full = [(st([("minecraft:bread", 5, None)], food=20, saturation=20), [])] + \
               [(st([("minecraft:bread", 4, None)], food=20, saturation=20), [])] * 10
        self.assertEqual(len(run(w, full)), 1)
        w2 = WasteTracker()
        hungry = [(st([("minecraft:bread", 5, None)], food=10, saturation=0), [])] + \
                 [(st([("minecraft:bread", 4, None)], food=15, saturation=6), [])] * 10
        self.assertEqual(run(w2, hungry), [])

    def test_golden_apples_scale_with_supply(self):
        def eat(n):
            w = WasteTracker()
            frames = [(st([("minecraft:golden_apple", n, None)]), [])] + [(st([("minecraft:golden_apple", n - 1, None)]), [])] * 2410
            return run(w, frames)[0]["data"]["units"]
        self.assertLess(eat(100), 0.06)
        self.assertGreater(eat(6), 0.3)

    def test_healing_at_full_health_and_min_use(self):
        w = WasteTracker()
        frames = [(st([("minecraft:potion", 2, "minecraft:strong_healing")]), [])] + \
                 [(st([("minecraft:potion", 1, "minecraft:strong_healing")]), [])] * 20
        out = run(w, frames)
        self.assertEqual(out[0]["data"]["used"], 0.0)
        from minigamebots_brain.rl.objective import RewardEngine
        eng = RewardEngine({"rules": [{"event": "b:item_wasted", "value": -1, "per": "units", "min_use": 0.5}]})
        self.assertLess(eng.step(1, out, None).reward, 0)
        ok = [{"type": "b:item_wasted", "data": {"units": 0.4, "used": 0.6}}]
        self.assertEqual(eng.step(2, ok, None).reward, 0)

    def test_pearl_that_went_nowhere(self):
        w = WasteTracker()
        frames = [(st([("minecraft:ender_pearl", 4, None)]), [])] + [(st([("minecraft:ender_pearl", 3, None)]), [])] * 125
        out = run(w, frames)
        self.assertEqual(out[0]["data"]["why"], "went nowhere")
