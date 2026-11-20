import os
import sys
import tempfile
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))

from minigamebots_brain.policies import create  # noqa: E402
from minigamebots_brain.policies.strategy import PLANS, Strategist  # noqa: E402


def obs(tick, entities=(), events=(), **me):
    self_ = {"health": 20, "maxHealth": 20, "food": 20, "yaw": 0.0, "pitch": 0.0, "x": 0.5, "y": 64, "z": 0.5, "armor": 0}
    self_.update(me)
    return {"tick": tick, "self": self_, "entities": list(entities), "inventory": [], "_events": list(events)}


def player(name, odds, dx=6.0):
    return {"id": hash(name) % 1000, "type": "minecraft:player", "player": True, "name": name, "dx": dx, "dy": 0,
            "dz": 0.0, "visible": True, "fightOdds": odds, "hitMe": -1}


class StrategistTest(unittest.TestCase):
    def test_rules_make_sense(self):
        s = Strategist(None, learn=False)
        self.assertEqual(s.decide(0, obs(0, health=4), None), "heal")
        o = obs(0, [player("Weak", 0.6)], armor=15)
        o["inventory"] = [{"slot": 0, "id": "minecraft:iron_sword"}]
        self.assertIn(s.decide(10, o, None), ("pick_fights", "hunt"))
        o2 = obs(0, [player("Steve", 0.0)], armor=15)
        o2["inventory"] = [{"slot": 0, "id": "minecraft:iron_sword"}]
        self.assertEqual(s.decide(20, o2, {"type": "manhunt", "player": "Steve"}), "hunt")
        self.assertEqual(s.why, "target in sight")

    def test_learns_which_plan_pays_off_and_remembers(self):
        with tempfile.TemporaryDirectory() as d:
            s = Strategist(d, learn=True, seed=1)
            o = obs(0, [player("X", 0.0)])
            # Ambushing keeps paying off here; everything else goes badly.
            for k in range(80):
                t = k * 1000
                plan = s.decide(t, o, None)
                good = plan == "ambush"
                s.on_events(t + 900, [{"type": "kill", "data": {"target": "minecraft:player"}}] if good
                            else [{"type": "death", "data": {}}], o["self"], None)
            s.learn = False
            self.assertEqual(s.decide(10 ** 6, o, None), "ambush")
            s.save()
            again = Strategist(d, learn=False)
            self.assertGreater(again.n.sum(), 50)
            self.assertEqual(again.decide(0, o, None), "ambush")

    def test_surprise_wakes_it(self):
        s = Strategist(None)
        s.decide(0, obs(0), None)
        self.assertTrue(s.on_events(5, [{"type": "death", "data": {}}], {"health": 20}, None))
        self.assertTrue(s.on_events(6, [], {"health": 5}, None))


class ManagerStrategyTest(unittest.TestCase):
    def test_manhunt_goal_plans_and_finishes(self):
        with tempfile.TemporaryDirectory() as d:
            m = create("manager", {"profile": d, "goals": [{"type": "manhunt", "player": "Steve"}]})
            o = obs(0, [player("Steve", 0.1)], armor=15)
            o["inventory"] = [{"slot": 0, "id": "minecraft:iron_sword"}]
            m.act(o, None)
            st = m.status()["strategy"]
            self.assertEqual(st["plan"], "hunt")
            self.assertEqual(m.status()["goal"], "hunt_players Steve")
            self.assertEqual(m.status()["long_goal"], "manhunt Steve")
            m.act(obs(5, events=[{"type": "kill", "data": {"target": "minecraft:player", "name": "Steve"}}]), None)
            m.act(obs(6), None)
            self.assertIn("manhunt Steve", " ".join(m.status()["completed"]))
            self.assertTrue(os.path.isfile(os.path.join(d, "strategist.json")))

    def test_plain_task_list_is_left_alone_and_ambush_keeps_still(self):
        with tempfile.TemporaryDirectory() as d:
            m = create("manager", {"profile": d, "goals": [{"type": "mine", "item": "iron", "count": 3}]})
            m.act(obs(0), None)
            self.assertIsNone(m.status()["strategy"])
            m2 = create("manager", {"profile": d, "goals": [{"type": "ambush", "minutes": 1}]})
            a = m2.act(obs(0), None)
            self.assertIn("sneak", a.keys)
            self.assertNotIn("forward", a.keys)


if __name__ == "__main__":
    unittest.main()


class InventionsInPlayTest(unittest.TestCase):
    def _keep(self, d, kind, design):
        from minigamebots_brain import invent

        inv = invent.Inventions(d)
        res = {"enclosed": True, "light": 14, "room": 9} if kind == "shelter" else {"damage": 20, "killed": True}
        inv.record(design, res)
        inv.save()

    def test_builds_a_kept_shelter_at_night_and_makes_it_home(self):
        from minigamebots_brain import invent

        with tempfile.TemporaryDirectory() as d:
            shelter = invent.shelter_from_genes({"w": 3, "l": 3, "h": 2, "mat": "minecraft:cobblestone", "roof": True,
                                                 "roof_mat": "minecraft:cobblestone", "torch": True})
            self._keep(d, "shelter", shelter)
            m = create("manager", {"profile": d, "free_play": True, "learn_strategy": False})
            o = obs(0, dark=True)
            o["inventory"] = [{"slot": 0, "id": "minecraft:cobblestone", "count": 64},
                              {"slot": 1, "id": "minecraft:torch", "count": 4}]
            m.act(o, None)
            self.assertEqual(m.status()["strategy"]["plan"], "build_shelter")
            m.act(dict(o, tick=1), None)
            self.assertIsNotNone(m.status()["building"])
            # Without the blocks it can't, so it doesn't even consider it.
            m2 = create("manager", {"profile": d, "free_play": True, "learn_strategy": False})
            o2 = obs(0, dark=True)
            m2.act(o2, None)
            self.assertNotEqual(m2.status()["strategy"]["plan"], "build_shelter")

    def test_traps_only_when_allowed(self):
        from minigamebots_brain import invent

        with tempfile.TemporaryDirectory() as d:
            trap = invent.trap_from_genes({"at": 0, "plate": "minecraft:stone_pressure_plate", "tnt": 1, "web": 1})
            self._keep(d, "trap", trap)
            o = obs(0, armor=10)
            o["risk"] = {"level": 1.0}
            o["inventory"] = [{"slot": 0, "id": "minecraft:tnt", "count": 4},
                              {"slot": 1, "id": "minecraft:stone_pressure_plate", "count": 2},
                              {"slot": 2, "id": "minecraft:cobweb", "count": 2}]
            careful = create("manager", {"profile": d, "free_play": True, "learn_strategy": False})
            careful.act(o, None)
            self.assertNotEqual(careful.status()["strategy"]["plan"], "set_trap")  # Default: shelters only.
            allowed = create("manager", {"profile": d, "goals": [{"type": "kill_players", "count": 5}],
                                         "learn_strategy": False, "inventions": "all"})
            allowed.act(o, None)
            self.assertEqual(allowed.status()["strategy"]["plan"], "set_trap")
