import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain.policies import create  # noqa: E402

try:
    import torch  # noqa: F401

    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


def obs(tick, entities=(), events=(), **me):
    self_ = {"health": 20, "maxHealth": 20, "food": 20, "yaw": 0.0, "pitch": 0.0, "y": 64, "attackReady": 1.0}
    self_.update(me)
    return {"tick": tick, "self": self_, "entities": list(entities), "inventory": [], "_events": list(events)}


def pig(dx=0.0, dz=5.0, **kw):
    e = {"type": "minecraft:pig", "dx": dx, "dy": 0.0, "dz": dz, "visible": True, "hitMe": -1, "hostile": False}
    e.update(kw)
    return e


class ManagerTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.profile = self.dir.name

    def tearDown(self):
        self.dir.cleanup()

    def test_reflexes_hunt_then_finish_the_goal(self):
        m = create("manager", {"profile": self.profile, "goals": [{"type": "hunt", "target": "minecraft:pig", "count": 1},
                                                                    {"type": "explore", "minutes": 1}]})
        a = m.act(obs(0, [pig(dx=-3.0, dz=8.0)]), None)
        self.assertIn("forward", a.keys)  # Walks toward the pig...
        self.assertGreater(a.yaw, 0)  # ...turning toward it (it's to the west, yaw 0 faces south).
        a = m.act(obs(10, [pig(dz=2.0)]), None)
        self.assertIn("attack", a.keys)  # Close and facing it: attacks.
        m.act(obs(11, [], [{"type": "kill", "data": {"target": "minecraft:pig"}}]), None)
        m.act(obs(12), None)
        self.assertEqual(m.status()["goal"], "explore")
        self.assertIn("hunt pig 1/1", m.status()["completed"])

    def test_fights_back_and_flees_when_losing(self):
        m = create("manager", {"profile": self.profile, "goals": [{"type": "explore"}]})
        zombie = {"type": "minecraft:zombie", "dx": 0.0, "dy": 0.0, "dz": 2.0, "visible": True, "hitMe": 5,
                  "hostile": True, "fightOdds": 0.4}
        m.act(obs(0, [zombie]), None)
        self.assertEqual(m.status()["mode"], "fight")
        zombie["fightOdds"] = -0.5
        a = m.act(obs(10, [zombie], health=4), None)
        self.assertEqual(m.status()["mode"], "flee")
        self.assertIn("forward", a.keys)
        self.assertGreater(abs(a.yaw), 45.0)  # Turning away from it.

    def test_watchdog_and_idle(self):
        m = create("manager", {"profile": self.profile, "goals": [{"type": "explore"}]})
        for i in range(3):
            a = m.act(obs(10 * i, [], [{"type": "stuck", "data": {}}]), None)
            self.assertIn("jump", a.keys)  # Tries another way.
        m.act(obs(100), None)
        self.assertIn("explore", " ".join(m.status()["completed"]))  # Gave the goal up after 3.
        a = m.act(obs(205), None)  # Goals used up, free play off: idle, not frozen.
        self.assertEqual(m.status()["skill"], "idle")
        self.assertNotEqual(a.yaw, 0.0)  # Looks around.

    def test_eats_when_hungry(self):
        m = create("manager", {"profile": self.profile, "goals": [{"type": "explore"}]})
        o = obs(0, food=10)
        o["inventory"] = [{"slot": 3, "id": "minecraft:bread", "count": 5}]
        a = m.act(o, None)
        self.assertEqual(a.keys, {"hotbar_4", "use"})

    def test_free_play_picks_something_and_goes_home_at_night(self):
        m = create("manager", {"profile": self.profile, "free_play": True})
        m.act(obs(0), None)
        self.assertIsNotNone(m.status()["goal"])
        m2 = create("manager", {"profile": self.profile, "free_play": True})
        o = obs(0, dark=True)
        o["waypoints"] = [{"type": "base", "name": "home", "dx": 30.0, "dy": 0.0, "dz": 0.0, "distance": 30.0}]
        m2.act(o, None)
        self.assertEqual(m2.status()["goal"], "go_to base")
        self.assertEqual(m2.status()["why"], "it's getting dark")

    @unittest.skipUnless(HAVE_TORCH, "needs torch")
    def test_uses_trained_skills_and_tells_them_the_goal(self):
        from minigamebots_brain.learn import checkpoint
        from minigamebots_brain.learn.model import LitePolicy
        from test_learn import pig_obs

        checkpoint.save(os.path.join(self.profile, "skills", "mining"), LitePolicy(), {"step": 1}, best=True)
        m = create("manager", {"profile": self.profile, "goals": [{"type": "mine", "item": "diamond", "count": 2}],
                               "device": "cpu"})
        o = pig_obs()
        o.update(tick=0, _events=[])
        o["self"]["y"] = 40
        m.act(o, None)
        self.assertEqual(m.status()["skill"], "mining")
        self.assertEqual(m._goal_input("mine", m.queue[0])["target_dy"], -99.0)  # Diamonds: about Y -59.


if __name__ == "__main__":
    unittest.main()
