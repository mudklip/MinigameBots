import json
import random
import tempfile
import unittest

from minigamebots_brain import invent


class InventTest(unittest.TestCase):
    def test_designs_are_valid_and_new(self):
        with tempfile.TemporaryDirectory() as d:
            inv = invent.Inventions(d)
            for kind in ("trap", "shelter"):
                ds = invent.propose(inv, kind, 6, random.Random(3))
                self.assertEqual(len(ds), 6)
                self.assertEqual(len({x["id"] for x in ds}), 6)  # All different.
                for x in ds:
                    self.assertLess(len(json.dumps(x)), 30000)  # Fits in one request to the server.
                    for b in x["blocks"]:
                        self.assertLessEqual(max(abs(b[0]), abs(b[2])), 7)

    def test_hidden_beats_visible_and_failures_are_not_retried(self):
        open_pit = invent.trap_from_genes({"at": 0, "pit": {"w": 2, "depth": 3, "lava": True}})
        plate_tnt = invent.trap_from_genes({"at": 0, "plate": "minecraft:stone_pressure_plate", "tnt": 1})
        self.assertGreater(invent.visibility(open_pit), invent.visibility(plate_tnt))
        with tempfile.TemporaryDirectory() as d:
            inv = invent.Inventions(d)
            dud = invent.trap_from_genes({"at": 0, "web": 1})
            s = inv.record(dud, {"damage": 0, "killed": False, "caught": False})
            self.assertFalse(s["works"])
            good = inv.record(plate_tnt, {"damage": 20, "killed": True, "caught": False})
            self.assertTrue(good["works"])
            inv.save()
            again = invent.Inventions(d)
            self.assertTrue(again.known(dud["id"]))
            self.assertEqual(again.data["hall"]["trap"]["deadliest"][0][0], plate_tnt["id"])
            # New ideas build on the best one, and never repeat the dud.
            new = invent.propose(again, "trap", 5, random.Random(1))
            self.assertNotIn(dud["id"], [x["id"] for x in new])

    def test_shelter_scoring(self):
        d = invent.shelter_from_genes({"w": 5, "l": 5, "h": 3, "mat": "minecraft:cobblestone", "door": "door", "torch": True})
        safe = invent.score(d, {"enclosed": True, "light": 13, "room": 27})
        leaky = invent.score(d, {"enclosed": False, "light": 13, "room": 27})
        self.assertTrue(safe["works"])
        self.assertFalse(leaky["works"])
        self.assertGreater(safe["q"]["overall"], leaky["q"]["overall"])


if __name__ == "__main__":
    unittest.main()
