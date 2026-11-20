import json
import os
import tempfile
import unittest

from minigamebots_brain.defeats import DefeatWatcher


def obs(t, swing):
    return {"self": {"x": 0.0, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0, "health": 20, "onGround": True},
            "text": {"sidebarTitle": "Crystal PvP"},
            "entities": [{"id": 7, "name": "Steve", "player": True, "type": "minecraft:player", "dx": 2.0 + (t % 20) / 10,
                          "dy": 0.0, "dz": 1.0, "yaw": 180.0, "pitch": 10.0, "vx": 0.1, "vy": 0.0, "vz": 0.0,
                          "onGround": True, "health": 20, "heldItem": "minecraft:end_crystal", "swinging": swing,
                          "hurtTime": 0, "visible": True}]}


class DefeatTest(unittest.TestCase):
    def test_beaten_by_a_player_makes_demos_of_them(self):
        with tempfile.TemporaryDirectory() as d:
            root = os.path.join(d, "profiles", "ai")
            os.makedirs(root)
            open(os.path.join(root, "manifest.json"), "w").write("{}")
            os.makedirs(os.path.join(d, "recordings"))
            w = DefeatWatcher(root)
            result = None
            for t in range(240):
                ev = []
                if t > 190 and t % 10 == 0:
                    ev = [{"type": "damage_taken", "data": {"amount": 4.0, "cause": "explosion", "by": "Steve"}}]
                r = w.step(obs(t, t % 10 == 9), ev)
                result = result or r
            self.assertIsNotNone(result)
            self.assertEqual(result["player"], "Steve")
            self.assertEqual(result["kit"], "crystal")
            made = [x for x in os.listdir(os.path.join(d, "recordings")) if x.startswith("defeat-")]
            self.assertGreaterEqual(result["demos"], 1)
            self.assertEqual(len(made), result["demos"])
            self.assertFalse(any(x.startswith("fight-") for x in os.listdir(os.path.join(d, "recordings"))))
            if made:
                meta = json.load(open(os.path.join(d, "recordings", made[0], "meta.json")))
                self.assertTrue(meta["defeat"])
                self.assertEqual(meta["kit"], "crystal")
            tally = json.load(open(os.path.join(root, "hurt_by.json")))
            self.assertEqual(tally["Crystal PvP"]["causes"]["explosion"], 12.0)
            self.assertIn("minecraft:end_crystal", tally["Crystal PvP"]["held"])


if __name__ == "__main__":
    unittest.main()


class UsesWhatBeatItTest(unittest.TestCase):
    def test_counts_using_the_item_that_beat_it(self):
        from minigamebots_brain.actions import Action
        from minigamebots_brain.rl.skills import Behaviours

        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "hurt_by.json"), "w") as f:
                json.dump({"Crystal PvP": {"causes": {"explosion": 30}, "held": {"minecraft:end_crystal": 30, "minecraft:stick": 1}}}, f)
            b = Behaviours({})
            b.load_beaten(d)
            o = {"self": {"x": 0, "y": 64, "z": 0}, "inventory": [{"slot": 0, "id": "minecraft:end_crystal", "count": 8}],
                 "selectedSlot": 0}
            kinds = {e["type"] for e in b.events(o, Action(keys={"use"}), [])}
            self.assertIn("b:uses_what_beat_it", kinds)
            kinds = {e["type"] for e in b.events(o, Action(), [])}
            self.assertNotIn("b:uses_what_beat_it", kinds)  # Just holding it isn't using it.
