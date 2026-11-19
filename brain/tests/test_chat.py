import unittest

from minigamebots_brain.mind import chat


class FakeLLM:
    def __init__(self, out):
        self.out = out
        self.seen = None

    def generate(self, msgs, max_new_tokens=0, sample=False):
        self.seen = msgs
        return self.out


class ChatTest(unittest.TestCase):
    def test_filter(self):
        self.assertEqual(chat.clean("Steve: gg nice fight\nmore"), "gg nice fight")
        self.assertEqual(chat.clean("/op me"), "")
        self.assertEqual(chat.clean("-"), "")
        self.assertEqual(chat.clean("what's your password lol"), "")
        self.assertEqual(chat.clean("i love creepers", avoid="creepers"), "")
        self.assertEqual(chat.clean("§chello"), "hello")

    def test_reply_uses_the_character(self):
        llm = FakeLLM("haha yeah, diamonds everywhere")
        out = chat.reply(llm, {"name": "Blaze", "personality": "cocky", "avoid": "politics"}, "Steve",
                         ["<Alex> hi"], "<Alex> Blaze did you find diamonds?")
        self.assertEqual(out, "haha yeah, diamonds everywhere")
        self.assertIn("You are Blaze", llm.seen[0]["content"])
        self.assertIn("politics", llm.seen[0]["content"])

    def test_make_character(self):
        c = chat.make_character(FakeLLM('Sure! {"name":"Pip","personality":"cheerful miner","style":"lowercase",'
                                        '"background":"lives in a cave"}'), "Steve", ["mining"])
        self.assertEqual(c["name"], "Pip")
        self.assertEqual(chat.make_character(FakeLLM("no json"), "Steve", [])["name"], "Steve")


if __name__ == "__main__":
    unittest.main()
