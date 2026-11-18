import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain.learn import features, knowledge  # noqa: E402


class KnowledgeTest(unittest.TestCase):
    def test_all_files_load_and_entries_have_content(self):
        t = knowledge.topics()
        for topic in ("ores", "combat", "techniques", "mobs", "world", "farms", "redstone", "survival"):
            self.assertIn(topic, t)
            for name, entry in t[topic].items():
                self.assertTrue(entry, f"{topic}.{name} is empty")

    def test_ores(self):
        d = knowledge.ore("minecraft:diamond")
        self.assertEqual(d["best"], -59)
        self.assertIn("caves", d["method"])
        self.assertEqual(knowledge.ore("netherite_scrap")["dimension"], "minecraft:the_nether")
        self.assertIsNone(knowledge.ore("dirt"))

    def test_search_finds_the_right_topic(self):
        self.assertEqual(knowledge.search("gold farm nether roof")[0][1], "gold_farm")
        self.assertEqual(knowledge.search("crossbow reload inventory")[0][1], "crossbow_inventory_reload")

    def test_techniques_say_what_inputs_they_need(self):
        for name, entry in knowledge.topics()["techniques"].items():
            self.assertIn("inputs", entry, name)

    def test_calculator_features(self):
        from test_learn import pig_obs

        o = pig_obs()
        o["self"].update({"attackReady": 0.5, "critReady": True, "fallDamage": 6.0})
        o["entities"][0].update({"myHitsToKill": 4, "theirHitsToKill": 10, "fightOdds": 0.6})
        f = features.featurize(o)
        self.assertEqual(list(f["self_calc"]), [0.5, 1.0, 0.3])
        self.assertAlmostEqual(float(f["ent_calc"][0, 0]), 0.25)
        self.assertAlmostEqual(float(f["ent_calc"][0, 1]), 0.1)
        self.assertAlmostEqual(float(f["ent_calc"][0, 2]), 0.6)


if __name__ == "__main__":
    unittest.main()
