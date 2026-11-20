import unittest

from minigamebots_brain.mind import lessons


class FakeLLM:
    def generate(self, messages, max_new_tokens=0, sample=False):
        return "- Steve wins most fights: back off when he pulls his axe out.\n- Avoid the bridge at spawn.\nIDEA: Fight Steve only at full health."


class LessonsTest(unittest.TestCase):
    FACTS = [{"type": "careful_with", "subject": "Steve", "facts": "knocked me out 3 times, I got him once"},
             {"type": "avoid_place", "subject": "around 120, -40", "facts": "knocked out there 2 times"}]

    def test_plain_sentences_without_the_mind(self):
        out = lessons.write(None, self.FACTS)
        self.assertEqual(len(out["lessons"]), 2)
        self.assertIn("Steve", out["lessons"][0])
        self.assertEqual(out["idea"], "")

    def test_the_mind_phrases_them_and_adds_one_idea(self):
        out = lessons.write(FakeLLM(), self.FACTS)
        self.assertTrue(out["lessons"][0].startswith("Steve wins"))
        self.assertIn("full health", out["idea"])


if __name__ == "__main__":
    unittest.main()
