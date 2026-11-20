import unittest

from minigamebots_brain import designer


class FakeMind:
    def generate(self, messages, max_new_tokens=60):
        return 'Sure! {"modes": ["crystal", "made_up"], "problems": ["wastes_items"]}'


class DesignerTest(unittest.TestCase):
    def test_small_problem_gives_a_small_change(self):
        p = designer.design("it keeps wasting pearls", {"skills": ["sword_pvp"]})
        self.assertEqual(p["scope"], "rule")
        self.assertEqual([s["kind"] for s in p["steps"]], ["rules"])
        self.assertEqual(p["steps"][0]["rules"][0]["event"], "b:item_wasted")

    def test_a_minigame_gives_a_skill_setup(self):
        p = designer.design("make it good at sumo", {"skills": []})
        self.assertEqual(p["scope"], "skill")
        kinds = [s["kind"] for s in p["steps"]]
        self.assertIn("rules", kinds)
        self.assertIn("plan", kinds)
        self.assertIn("All rewards exist and none pays it for losing.", p["checks"])

    def test_mind_only_picks_listed_entries(self):
        p = designer.design("i want to get better at that end crystal thing", {}, FakeMind())
        self.assertTrue(p["used_mind"])
        self.assertIn("Crystal", p["title"])
        self.assertEqual(p["scope"], "program")

    def test_never_rewards_losing(self):
        steps = [{"kind": "rules", "rules": [designer._rule({"key": "death", "value": 3}), designer._rule({"key": "made_up", "value": 1})]}]
        notes = designer.check(steps)
        self.assertLess(steps[0]["rules"][0]["value"], 0)
        self.assertEqual(len(steps[0]["rules"]), 1)
        self.assertEqual(len(notes), 2)

    def test_plateau_adds_a_fit_and_league(self):
        p = designer.design("sumo", {"verdict": {"state": "FLAT"}})
        self.assertIn("fit", [s["kind"] for s in p["steps"]])

    def test_unclear_request(self):
        self.assertEqual(designer.design("hello", {})["scope"], "none")


if __name__ == "__main__":
    unittest.main()
