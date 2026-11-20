import tempfile
import unittest

from minigamebots_brain.policies import create
from minigamebots_brain.rl import objective, templates


def body(i):
    return {"self": {"health": 20, "maxHealth": 20, "yaw": 0, "pitch": 0, "y": 64},
            "entities": [{"type": "minecraft:player", "player": True, "dx": 1, "dy": 0, "dz": 4, "visible": True, "hostile": True},
                         {"type": "minecraft:player", "player": True, "dx": 2, "dy": 0, "dz": 0, "visible": True,
                          "sameTeam": True, "relation": 1}]}


class TeamTest(unittest.TestCase):
    def test_team_rules(self):
        eng = objective.RewardEngine(templates.build("team_fight"))
        good = eng.step(1, [{"type": "team_help", "data": {}}, {"type": "team_focus", "data": {}}], None).reward
        bad = eng.step(2, [{"type": "friendly_fire", "data": {"amount": 5.0}}], None).reward
        self.assertGreater(good, 0)
        self.assertLess(bad, 0)

    def test_each_teammate_has_its_own_state(self):
        with tempfile.TemporaryDirectory() as d:
            p = create("multiworld", {"profile": d})
            entries = [{"copy": 0, "slot": k, "team": True, "puppet_id": 10 + k, "obs": body(k), "events": []} for k in range(3)]
            a = p.act({"multiworld": entries}, None)
            self.assertEqual(sorted(x["id"] for x in a.puppets), [10, 11, 12])
            self.assertEqual(len(p.copies), 3)
            self.assertEqual(p.copies[(0, 1)].engine.objective["name"], "Team fight")
            # A knocked-out teammate still gets the team's result.
            p.act({"multiworld": [{"copy": 0, "slot": 2, "team": True,
                                   "events": [{"type": "tg_episode_end", "data": {"result": "win"}}]}]}, None)
            self.assertEqual(p.episodes, 1)


if __name__ == "__main__":
    unittest.main()


class TeamTalkTest(unittest.TestCase):
    def test_callouts_and_answering_a_call_for_help(self):
        import os
        import sys
        sys.path.insert(0, os.path.dirname(__file__))
        from test_manager import obs

        with tempfile.TemporaryDirectory() as d:
            m = create("manager", {"profile": d, "goals": [{"type": "explore"}]})
            mate = {"id": 2, "type": "minecraft:player", "player": True, "name": "Buddy", "dx": 10.0, "dy": 0, "dz": 0.0,
                    "visible": True, "sameTeam": True, "relation": 1, "hitMe": 3}
            far = {"id": 3, "type": "minecraft:player", "player": True, "name": "Red1", "dx": -6.0, "dy": 0, "dz": 6.0,
                   "visible": True, "hitMe": -1}
            near_mate = {"id": 4, "type": "minecraft:player", "player": True, "name": "Red2", "dx": 12.0, "dy": 0, "dz": 2.0,
                         "visible": True, "hitMe": -1, "relation": -1}
            m.act(obs(0, [mate, far, near_mate], [{"type": "chat", "data": {"text": "<Buddy> 2 on me, help"}}]), None)
            self.assertEqual(m._threat()["name"], "Red2")  # Goes to help, not after whoever is nearest.
            self.assertNotEqual(m.fight_target, 2)  # Its teammate's stray hit isn't a fight.
            # Two enemies hitting it, a teammate nearby: calls it out in plain chat.
            a1 = dict(far, hitMe=2)
            a2 = dict(near_mate, hitMe=2, dx=1.0, dz=2.0)
            said = [m.act(obs(t, [mate, a1, a2]), None).say for t in range(10, 40)]
            self.assertIn("2 on me, help", said)
            self.assertEqual(sum(1 for x in said if x), 1)  # Once, not spammed.
