import json
import os
import tempfile
import unittest

from minigamebots_brain import players
from minigamebots_brain.learn.recording import SequenceSampler


def source_recording(d, ticks=160):
    rows = []
    for t in range(ticks):
        steve = {"id": 5, "type": "minecraft:player", "player": True, "name": "Steve", "dx": 0.0, "dy": -1.62,
                 "dz": 3.0 + 0.2 * t, "yaw": 0.0, "pitch": 0.0, "onGround": True, "health": 20.0,
                 "swinging": t % 20 >= 10, "visible": True}
        pig = {"id": 6, "type": "minecraft:pig", "dx": 0.0, "dy": -1.62, "dz": 5.0 + 0.2 * t,
               "hurtTime": 10 if t % 20 == 11 else 0, "visible": True}
        obs = {"self": {"x": 0.0, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0, "health": 20.0},
               "entities": [steve, pig]}
        events = [{"type": "tg_inputs", "data": {"players": [{"name": "Steve", "keys": ["forward", "sprint"]}]}}] if t == 50 else []
        rows.append({"input": {"keys": []}, "obs": obs, "events": events})
    players.write(d, rows, {"id": "src"})
    with open(os.path.join(d, "meta.json")) as f:
        m = json.load(f)
    m["source"] = "human"
    with open(os.path.join(d, "meta.json"), "w") as f:
        json.dump(m, f)


class PlayersTest(unittest.TestCase):
    def test_other_players_become_weighted_demos(self):
        with tempfile.TemporaryDirectory() as root:
            source_recording(os.path.join(root, "rec1"))
            out = players.convert(root)
            self.assertEqual(out["players"], ["Steve"])
            self.assertEqual(out["exact_ticks"], 1)
            d = os.path.join(root, "players-rec1-Steve-0")
            rows = [r for _, r, _ in __import__("minigamebots_brain.learn.recording", fromlist=["x"]).read_chunk(
                os.path.join(d, "chunk-00000.bin.gz"))]
            self.assertIn("forward", rows[20]["input"]["keys"])  # Walking where he looks (south).
            self.assertTrue(any("attack" in r["input"]["keys"] for r in rows))
            self.assertGreater(max(r["w"] for r in rows), 1.0)  # Moments before his hits count more.
            # Seen from Steve: the pig is ahead of him.
            pig = next(e for e in rows[30]["obs"]["entities"] if e["type"] == "minecraft:pig")
            self.assertAlmostEqual(pig["dz"], 2.0, places=3)
            b = SequenceSampler([d], 32, "train").batch(2)
            self.assertFalse(b["has_frame"].any())


if __name__ == "__main__":
    unittest.main()


class PrivilegedTest(unittest.TestCase):
    def test_truth_used_for_tracks_but_never_for_play(self):
        import numpy as np
        from minigamebots_brain.learn import features

        obs = {"self": {"x": 0.0, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0},
               "entities": [{"id": 5, "type": "minecraft:player", "player": True, "name": "Alex", "dx": 9.0, "dy": -1.6,
                             "dz": 9.0, "visible": False}],
               "privileged": [{"id": 5, "name": "Alex", "dx": 1.0, "dy": -1.6, "dz": 2.0, "health": 7.0}]}
        t = players.with_truth(obs)
        self.assertEqual(t["entities"][0]["dx"], 1.0)  # True position, not the stale last-seen one.
        self.assertNotIn("privileged", t)
        a = features.featurize(obs)
        b = features.featurize({k: v for k, v in obs.items() if k != "privileged"})
        for k in a:
            self.assertTrue(np.array_equal(a[k], b[k]), k)


class SpectatorLearningTest(unittest.TestCase):
    def test_ratings_once_only_action_and_game_tag(self):
        with tempfile.TemporaryDirectory() as root:
            d = os.path.join(root, "rec1")
            source_recording(d, ticks=400)
            # Steve stands idle (no swings, no hits) for the last 200 ticks: trimmed away.
            from minigamebots_brain.learn.recording import read_chunk
            rows = [r for _, r, _ in read_chunk(os.path.join(d, "chunk-00000.bin.gz"))]
            for r in rows[200:]:
                for e in r["obs"]["entities"]:
                    e["swinging"] = False
                    e["hurtTime"] = 0
            for r in rows:
                r["obs"]["text"] = {"sidebarTitle": "§eBEDWARS"}
            players.write(d, rows, {"id": "src", "world": "mc.example.net"})
            with open(os.path.join(d, "meta.json")) as f:
                m = json.load(f)
            m["source"] = "human"
            m["world"] = "mc.example.net"
            with open(os.path.join(d, "meta.json"), "w") as f:
                json.dump(m, f)
            out = players.convert(root)
            self.assertEqual(out["players"], ["Steve"])
            self.assertLess(out["seconds"], 400 / 20 - 3)  # Idle stretch dropped.
            ratings = json.load(open(os.path.join(root, "player_ratings.json")))
            self.assertGreater(ratings["Steve"]["landed"], 0)
            self.assertGreater(players.rating(ratings["Steve"]), 0.5)  # Lands hits, takes none.
            meta = json.load(open(os.path.join(root, "players-rec1-Steve-0", "meta.json")))
            self.assertEqual(meta["game"], "BEDWARS")
            self.assertIn("mc.example.net", meta["name"])
            again = players.convert(root)
            self.assertEqual(again["recordings"], 0)  # Converted once only.

    def test_weak_players_count_less(self):
        self.assertLess(players.rating({"landed": 1, "taken": 20, "deaths": 3}), players.rating({"landed": 20, "taken": 2}))
