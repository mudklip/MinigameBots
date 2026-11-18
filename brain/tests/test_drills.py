import unittest

from minigamebots_brain.rl import objective as obj, templates


def hit(**data):
    return {"type": "tg_damage_dealt", "data": dict({"amount": 4.0, "blocked": False}, **data)}


class DrillTest(unittest.TestCase):
    def test_templates_are_clean(self):
        for name in ("crit_drill", "wtap_drill", "shield_drill"):
            self.assertEqual(obj.analyze(templates.build(name)), [], name)

    def test_crit_drill_prefers_crits_over_spam(self):
        e = obj.RewardEngine(templates.build("crit_drill"))
        crit = e.step(1, [hit(crit=True, charge=1.0)]).reward
        e2 = obj.RewardEngine(templates.build("crit_drill"))
        spam = e2.step(1, [hit(crit=False, charge=0.3)]).reward
        self.assertGreater(crit, 0.5)
        self.assertLess(spam, 0)

    def test_shield_drill(self):
        e = obj.RewardEngine(templates.build("shield_drill"))
        broke = e.step(1, [hit(blocked=True, shield_disabled=True)]).reward
        bounced = obj.RewardEngine(templates.build("shield_drill")).step(1, [hit(blocked=True, shield_disabled=False)]).reward
        self.assertGreater(broke, bounced)


if __name__ == "__main__":
    unittest.main()
