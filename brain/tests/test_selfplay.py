import os
import tempfile
import unittest

from minigamebots_brain.selfplay import SelfPlay, opponent_view


def obs():
    return {"self": {"yaw": 0.0, "pitch": 0.0, "health": 15.0, "vx": 0.0, "vy": 0.0, "vz": 0.0},
            "selectedSlot": 0, "inventory": [{"slot": 0, "id": "minecraft:diamond_sword"}],
            "entities": [{"id": 7, "type": "minecraft:mannequin", "dx": 0.0, "dy": -1.62, "dz": 2.5, "yaw": 180.0,
                          "pitch": 0.0, "health": 12.0, "visible": True, "heldItem": "minecraft:iron_sword"},
                         {"id": 8, "type": "minecraft:pig", "dx": 1.0, "dy": -1.62, "dz": 5.0, "visible": True}]}


class SelfPlayTest(unittest.TestCase):
    def test_view_from_the_dummy(self):
        v = opponent_view(obs(), 7)
        self.assertEqual(v["self"]["health"], 12.0)
        me = v["entities"][0]
        self.assertEqual(me["type"], "minecraft:player")
        self.assertAlmostEqual(me["dz"], -2.5)
        self.assertAlmostEqual(me["dy"], -1.62)  # Same ground: my feet are one eye height below its eyes.
        pig = next(e for e in v["entities"] if e["type"] == "minecraft:pig")
        self.assertAlmostEqual(pig["dz"], 2.5)
        self.assertIsNone(opponent_view(obs(), 99))

    def test_teacher_plays_when_there_are_no_versions(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "core"))
            sp = SelfPlay(d, seed=1)
            out = sp.act(obs(), 7, round_id=1)
            self.assertEqual(out["id"], 7)
            self.assertEqual(out["opponent"], "teacher")
            # The dummy faces the player (yaw 180 = north) so it walks or swings at them.
            self.assertTrue(set(out["keys"]) & {"forward", "attack", "jump"}, out)


if __name__ == "__main__":
    unittest.main()
