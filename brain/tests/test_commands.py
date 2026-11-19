import unittest

from minigamebots_brain.mind import commands, llm


class CommandsTest(unittest.TestCase):
    def u(self, text, me="Steve"):
        return commands.understand(text, me)

    def test_common_phrasings(self):
        r = self.u("mine 10 iron then go home")
        self.assertEqual(r["goals"], [{"type": "mine", "item": "iron", "count": 10}, {"type": "go_to", "waypoint": "base"}])
        self.assertEqual(self.u("kill three pigs")["goals"], [{"type": "hunt", "target": "minecraft:pig", "count": 3}])
        self.assertEqual(self.u("follow me")["goals"], [{"type": "follow", "player": "Steve"}])
        self.assertEqual(self.u("protect Alex and explore for 3 minutes")["goals"],
                         [{"type": "protect", "player": "Alex"}, {"type": "explore", "minutes": 3}])
        self.assertTrue(self.u("stop")["stop"])
        self.assertEqual(self.u("come back")["goals"], [{"type": "go_to", "waypoint": "base"}])

    def test_unknown_without_model(self):
        r = self.u("build me a castle")
        self.assertEqual(r["via"], "none")
        self.assertEqual(r["goals"], [])

    def test_model_answers_are_checked(self):
        class Fake:
            def to_goals(self, text, me):
                return [{"type": "mine", "item": "diamonds", "count": 999}, {"type": "op_me"},
                        {"type": "follow", "player": "bad name!"}], False

        r = commands.understand("could you fetch some sparkly gems", "Steve", Fake())
        self.assertEqual(r["via"], "model")
        self.assertEqual(r["goals"], [{"type": "mine", "item": "diamond", "count": 64}])  # Clamped; bad ones dropped.
        self.assertEqual(llm.parse_answer('Sure! ["stop"]'), ([], True))
        self.assertEqual(llm.parse_answer("no json here"), ([], False))


if __name__ == "__main__":
    unittest.main()
