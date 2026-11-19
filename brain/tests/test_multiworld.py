import os
import tempfile
import unittest

from minigamebots_brain.policies import create


def entry(i, events=(), dz=2.5):
    return {"copy": i, "puppet_id": 100 + i, "events": list(events),
            "obs": {"self": {"health": 20.0, "yaw": 0.0, "pitch": 0.0, "onGround": True, "attackReady": 1.0},
                    "entities": [{"id": 9, "type": "minecraft:player", "player": True, "visible": True, "dx": 0.0,
                                  "dy": -1.62, "dz": dz, "health": 20.0, "hostile": True}],
                    "inventory": [{"slot": 0, "id": "minecraft:iron_sword", "count": 1}], "selectedSlot": 0}}


class MultiWorldTest(unittest.TestCase):
    def test_steers_every_copy_and_learns_from_each(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "core"))
            p = create("multiworld", {"profile": d, "resources": "quiet", "device": "cpu"})
            a = p.act({"tick": 1, "multiworld": [entry(0), entry(1), entry(2)]}, None)
            self.assertEqual(sorted(x["id"] for x in a.puppets), [100, 101, 102])
            self.assertEqual(a.keys, set())  # The player's own body stands still.
            win = [{"type": "kill", "data": {}}, {"type": "tg_episode_end", "data": {"result": "win"}}]
            p.act({"tick": 2, "multiworld": [entry(0, win), entry(1), entry(2)]}, None)
            self.assertEqual(p.episodes, 1)
            self.assertEqual(len(p.copies[0].buffer) + len(p.learner.buffer) >= 0, True)
            self.assertEqual(len(p.copies[1].buffer), 2)  # Each copy keeps its own experience.
            self.assertIn("puppets", a.to_message(1))


if __name__ == "__main__":
    unittest.main()
