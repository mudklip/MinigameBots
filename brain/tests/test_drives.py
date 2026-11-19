import os
import random
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain.policies import drives  # noqa: E402
from minigamebots_brain.policies.manager import ManagerPolicy  # noqa: E402


def player(i=7, hit=-1, held="minecraft:air", rel=0, dz=3.0, **kw):
    e = {"id": i, "type": "minecraft:player", "player": True, "name": "Steve", "dx": 0.0, "dy": 0.0, "dz": dz,
         "visible": True, "hitMe": hit, "heldItem": held, "relation": rel, "onGround": True, "fightOdds": 0.3,
         "health": 20.0}
    e.update(kw)
    return e


def obs(tick, ents, events=(), **me):
    s = {"health": 20, "food": 20, "armor": 0, "yaw": 0.0, "pitch": 0.0}
    s.update(me)
    return {"tick": tick, "self": s, "entities": ents, "inventory": [], "_events": list(events)}


class HitJudgeTest(unittest.TestCase):
    def test_one_fist_punch_is_a_joke(self):
        j = drives.HitJudge()
        j.update(1, obs(1, [player(hit=10)]), [])
        j.update(2, obs(2, [player(hit=0)]), [{"type": "damage_taken", "data": {"amount": 1}}])
        self.assertTrue(j.joke(7))

    def test_sword_combo_is_real(self):
        j = drives.HitJudge()
        t = 0
        for _ in range(3):
            j.update(t, obs(t, [player(hit=20, held="minecraft:diamond_sword")]), [])
            t += 1
            j.update(t, obs(t, [player(hit=0, held="minecraft:diamond_sword")]), [{"type": "damage_taken", "data": {"amount": 4}}])
            t += 10
        self.assertTrue(j.real(7))

    def test_enemy_is_real_even_with_a_fist(self):
        j = drives.HitJudge()
        j.update(1, obs(1, [player(hit=10, rel=-1)]), [])
        j.update(2, obs(2, [player(hit=0, rel=-1)]), [])
        self.assertTrue(j.real(7))


class ModesTest(unittest.TestCase):
    def test_neutral_ignores_a_joke_but_fights_back_for_real(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="neutral")
            m.act(obs(9, [player(hit=10)]), None)
            m.act(obs(10, [player(hit=0)], [{"type": "damage_taken", "data": {"amount": 1}}]), None)
            self.assertNotEqual(m.mode, "fight")
            t = 20
            for _ in range(3):
                m.act(obs(t - 1, [player(hit=20, held="minecraft:iron_sword")]), None)
                m.act(obs(t, [player(hit=0, held="minecraft:iron_sword")], [{"type": "damage_taken", "data": {"amount": 4}}]), None)
                t += 10
            self.assertEqual(m.mode, "fight")

    def test_peaceful_runs_from_a_real_attack(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="peaceful")
            t = 20
            for _ in range(3):
                m.act(obs(t - 1, [player(hit=20, held="minecraft:iron_sword")]), None)
                m.act(obs(t, [player(hit=0, held="minecraft:iron_sword")], [{"type": "damage_taken", "data": {"amount": 4}}]), None)
                t += 10
            self.assertEqual(m.mode, "flee")

    def test_hunter_gears_up_then_hunts(self):
        d = drives.Drives("hunter")
        g = d.pick_goal(obs(0, []), random.Random(1))
        self.assertEqual(g["type"], "mine")
        geared = obs(0, [player(dz=8)], armor=20)
        geared["inventory"] = [{"slot": 0, "id": "minecraft:diamond_sword"}]
        self.assertEqual(d.pick_goal(geared, random.Random(1))["type"], "hunt_players")

    def test_hunter_prefers_the_weaker_counted_target(self):
        d = drives.Drives("hunter")
        o = obs(0, [player(i=1, dz=5), player(i=2, dz=6)])
        o["healthMemory"] = [{"id": 1, "est": 18, "sure": 0.9}, {"id": 2, "est": 4, "sure": 0.9}]
        self.assertEqual(d.prey(o)["id"], 2)


class PersonalityTest(unittest.TestCase):
    def test_keywords_and_once_only(self):
        self.assertEqual(drives.from_keywords("A ruthless, aggressive PvP sweat"), "hunter")
        self.assertEqual(drives.from_keywords("gentle and shy"), "peaceful")
        with tempfile.TemporaryDirectory() as tmp:
            calls = []

            def llm(p):
                calls.append(p)
                return "Guard."

            self.assertEqual(drives.resolve(tmp, "loyal knight", llm)["from_personality"], "guard")
            drives.resolve(tmp, "loyal knight", llm)
            self.assertEqual(len(calls), 1)  # Same personality: not asked again.
            self.assertEqual(drives.resolve(tmp, "calm farmer", lambda p: "no idea")["from_personality"], "peaceful")


if __name__ == "__main__":
    unittest.main()


class BehaviourTest(unittest.TestCase):
    def test_guard_goes_back_when_too_far(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="guard")
            o = obs(10, [])
            o["waypoints"] = [{"type": "base", "name": "base", "dx": 40.0, "dz": 0.0}]
            m.act(o, None)
            self.assertEqual(m.current().type, "go_to")

    def test_farm_reflex_harvests_and_replants(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, goals=[{"type": "farm", "minutes": 1}], mode="neutral")
            o = obs(1, [])
            o["look"] = {"block": "minecraft:wheat"}
            self.assertIn("attack", m.act(o, None).keys)
            o = obs(2, [])
            o["look"] = {"block": "minecraft:farmland"}
            o["inventory"] = [{"slot": 2, "id": "minecraft:wheat_seeds"}]
            o["selectedSlot"] = 2
            self.assertIn("use", m.act(o, None).keys)
