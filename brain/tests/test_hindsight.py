import unittest

from minigamebots_brain.learn import features, hindsight


def chunk(events_at: dict, ticks: int = 300) -> dict:
    out = {}
    for t in range(ticks):
        obs = {"self": {"yaw": 0.0, "pitch": 0.0, "y": 64.0},
               "entities": [{"type": "minecraft:pig", "dx": 1.0, "dy": -1.6, "dz": 5.0 - t * 0.01}]}
        out[t] = ({"obs": obs, "events": events_at.get(t, [])}, None)
    return out


class HindsightTest(unittest.TestCase):
    def test_labels_moments_before_an_achievement(self):
        c = chunk({120: [{"type": "kill", "data": {"target": "minecraft:pig"}}]})
        goals = hindsight.label(c, 0, 32)
        self.assertEqual(goals[0]["type"], "hunt")
        self.assertAlmostEqual(goals[0]["target_dz"], 5.0)
        f = features.featurize(dict(c[0][0]["obs"], goal=goals[0]))
        self.assertEqual(f["goal"][features.GOAL_TYPES.index("hunt")], 1.0)

    def test_nothing_achieved_means_no_goal(self):
        self.assertEqual(hindsight.label(chunk({}), 0, 8), [None] * 8)
        late = chunk({290: [{"type": "kill", "data": {"target": "minecraft:pig"}}]})
        self.assertIsNone(hindsight.label(late, 0, 8, horizon=100)[0])

    def test_kinds(self):
        self.assertEqual(hindsight.achievement({"type": "kill", "data": {"target": "minecraft:zombie"}})["type"], "fight")
        mine = hindsight.achievement({"type": "item_gained", "data": {"item": "minecraft:diamond", "count": 1}})
        self.assertEqual(mine["type"], "mine")
        self.assertIsNone(hindsight.achievement({"type": "item_gained", "data": {"item": "minecraft:dirt"}}))
        c = chunk({50: [{"type": "item_gained", "data": {"item": "minecraft:diamond"}}]})
        self.assertLess(hindsight.label(c, 0, 4)[0]["target_dy"], -60)  # Diamonds are deep below y=64.


if __name__ == "__main__":
    unittest.main()
