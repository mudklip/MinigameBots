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


class MemoryTest(unittest.TestCase):
    def test_a_grudge_makes_one_punch_real(self):
        j = drives.HitJudge()
        o1 = obs(1, [player(hit=10)])
        o2 = obs(2, [player(hit=0)])
        for o in (o1, o2):
            o["people"] = [{"id": 7, "betrayals": 1, "like": -0.5}]
        j.update(1, o1, [])
        j.update(2, o2, [{"type": "damage_taken", "data": {"amount": 1}}])
        self.assertTrue(j.real(7))

    def test_remembered_danger_raises_fear_and_hunters_avoid_it(self):
        d = drives.Drives("neutral")
        t = player(hit=0, held="minecraft:iron_sword")
        o = obs(0, [t], health=10)
        calm = d.needs(o, t, True)["fear"]
        o["people"] = [{"id": 7, "danger": 1.0}]
        self.assertGreater(d.needs(o, t, True)["fear"], calm)
        h = drives.Drives("hunter")
        o = obs(0, [player(i=1, dz=5), player(i=2, dz=5)])
        o["people"] = [{"id": 1, "danger": 1.0}, {"id": 2, "iKnockedOut": 3}]
        self.assertEqual(h.prey(o)["id"], 2)


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


class SportAndRiskTest(unittest.TestCase):
    def test_in_a_match_a_punch_is_real_but_no_grudge_or_place_fear(self):
        j = drives.HitJudge()
        o1, o2 = obs(1, [player(hit=10)]), obs(2, [player(hit=0)])
        for o in (o1, o2):
            o["risk"] = {"sport": True}
        j.update(1, o1, [])
        j.update(2, o2, [{"type": "damage_taken", "data": {"amount": 1}}])
        self.assertTrue(j.real(7))
        d = drives.Drives("neutral")
        t = player(hit=0)
        o = obs(0, [t])
        o["risk"] = {"sport": True}
        o["people"] = [{"id": 7, "danger": 1.0}]
        o["places"] = [{"here": True, "feeling": -1.0}]
        calm = d.needs(obs(0, [t]), t, True)["fear"]
        self.assertAlmostEqual(d.needs(o, t, True)["fear"], calm)

    def test_busy_area_on_pvp_server_keeps_it_alert(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, goals=[{"type": "explore", "minutes": 5}], mode="neutral")
            for t in range(1, 12):
                o = obs(t, [])
                o["risk"] = {"mode": "population", "level": 0.7}
                m.act(o, None)
            self.assertEqual(m.thinking, 20)


class AwarenessTest(unittest.TestCase):
    def test_thinks_slowly_when_calm_and_fast_when_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, goals=[{"type": "explore", "minutes": 5}], mode="neutral")
            for t in range(1, 12):
                m.act(obs(t, []), None)
            self.assertEqual(m.thinking, 5)
            m.act(obs(12, [player(dz=4)]), None)
            self.assertEqual(m.thinking, 20)

    def test_bad_place_raises_fear(self):
        d = drives.Drives("neutral")
        t = player(hit=0, held="minecraft:iron_sword")
        o = obs(0, [t])
        calm = d.needs(o, t, True)["fear"]
        o["places"] = [{"here": True, "feeling": -1.0}]
        self.assertGreater(d.needs(o, t, True)["fear"], calm)


class AnnoyanceAndAftermathTest(unittest.TestCase):
    def _poke(self, m, t, **kw):
        """One joke punch from Steve (id 7) at tick t."""
        m.act(obs(t - 1, [player(hit=30, **kw)]), None)
        m.act(obs(t, [player(hit=0, **kw)], [{"type": "damage_taken", "data": {"amount": 1}}]), None)

    def test_joke_hits_build_up_in_steps(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="neutral")
            stages = []
            for k in range(4):
                self._poke(m, 100 + k * 200)  # Spread out: each one alone is a joke.
                stages.append(m.drives.judge.stage(7))
            self.assertEqual(stages[0], "calm")
            self.assertIn("notice", stages)
            self.assertEqual(stages[-1], "fed_up")
            self.assertTrue(m.drives.judge.real(7))

    def test_peaceful_is_more_patient_than_hunter(self):
        a, b = drives.Drives("peaceful"), drives.Drives("hunter")
        for d in (a, b):
            for k in range(3):
                t = 100 + k * 200
                d.judge.update(t - 1, obs(t - 1, [player(hit=30)]), [])
                d.judge.update(t, obs(t, [player(hit=0)]), [])
        self.assertLess(a.judge.annoy.get(7, 0), b.judge.annoy.get(7, 0))

    def test_annoyed_steps_away_and_warns(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="neutral")
            m.drives.judge.annoy[7] = 0.85
            o = obs(1000, [player(hit=-1)])
            a = None
            for t in range(1000, 1011):
                o = obs(t, [player(hit=-1)])
                a = m.act(o, None)
                if a.say:
                    break
            self.assertEqual(m.mode, "back_off")
            self.assertEqual(a.say, "stop it")

    def test_mercy_after_a_knockout_leaves_and_gives_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="peaceful")
            o = obs(10, [player(dz=1.0)], [{"type": "kill", "data": {"name": "Steve"}}], x=0.0, z=0.0)
            m.act(o, None)
            self.assertEqual(m.aftermath["mode"], "mercy")
            o = obs(20, [], [{"type": "item_gained", "data": {"item": "minecraft:diamond_sword", "count": 1}}], x=0.0, z=0.0)
            o["inventory"] = [{"slot": 3, "id": "minecraft:diamond_sword"}]
            o["selectedSlot"] = 3
            m.act(o, None)
            self.assertEqual(m.mode, "leave_spot")  # Near the spot: walk away first.
            o = obs(30, [], x=0.0, z=20.0)
            o["inventory"] = [{"slot": 3, "id": "minecraft:diamond_sword"}]
            o["selectedSlot"] = 3
            acts = [m.act(dict(o, tick=30 + k), None) for k in range(12)]
            self.assertEqual(m.mode, "give_back")
            self.assertTrue(any("drop" in a.keys for a in acts))

    def test_ruthless_fights_them_again_when_they_come_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="neutral", after="ruthless")
            m.act(obs(10, [], [{"type": "kill", "data": {"name": "Steve"}}]), None)
            m.act(obs(20, [player(dz=10.0, hit=-1)]), None)
            self.assertEqual(m.mode, "fight")

    def test_mercy_lets_go_when_they_back_off(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = ManagerPolicy(tmp, free_play=True, mode="neutral", after="mercy")
            whys = []
            for t in range(0, 300, 10):
                if t < 60:  # A real fight: sword hits that hurt.
                    hit = 0 if t % 20 == 0 else 10
                    ev = [{"type": "damage_taken", "data": {"amount": 4}}] if hit == 0 else []
                    m.act(obs(t, [player(dz=3.0, hit=hit, held="minecraft:iron_sword")], ev), None)
                else:  # Then they walk away and stop hitting.
                    m.act(obs(t, [player(dz=3.0 + (t - 60) / 10, hit=60 + t - 60, held="minecraft:iron_sword")]), None)
                if t == 50:
                    self.assertEqual(m.mode, "fight")
                whys.append(m.why)
            self.assertNotEqual(m.mode, "fight")
            self.assertTrue(any("backed off" in w for w in whys))
