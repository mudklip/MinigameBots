import copy
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import numpy as np  # noqa: E402

from minigamebots_brain.learn import actions, features  # noqa: E402
from minigamebots_brain.vpt import actions as vpt  # noqa: E402


def scene(dx):
    from test_learn import pig_obs

    o = pig_obs(yaw=0.0, dx=dx, dz=4.0)
    o["entities"][0]["vx"] = 0.1 if dx > 0 else -0.1
    o["self"]["vx"] = 0.2 if dx > 0 else -0.2
    crowd = [0] * features.CROWD_DIM
    sector = 2 if dx > 0 else 6  # west / east
    crowd[sector] = 3
    o["crowd"] = {"counts": crowd}
    o["waypoints"] = [{"type": "base", "dx": dx * 2, "dy": 0.0, "dz": 10.0, "distance": 12.0}]
    return o


class MirrorTest(unittest.TestCase):
    def test_flipped_features_match_the_mirrored_world(self):
        a = features.mirror(features.featurize(scene(3.0)))
        b = features.featurize(scene(-3.0))
        for k in ("ent_vals", "wp_vals", "crowd"):
            self.assertTrue(np.allclose(a[k], b[k], atol=1e-5), k)
        self.assertTrue(np.allclose(a["self"], b["self"], atol=1e-5))

    def test_flipping_twice_changes_nothing(self):
        f = features.featurize(scene(3.0))
        f["frames"] = np.random.randint(0, 255, (128, 128, 3), dtype=np.uint8)
        g = features.mirror(features.mirror(f))
        for k in f:
            self.assertTrue(np.array_equal(f[k], g[k]), k)

    def test_targets_swap_left_right_and_turning(self):
        t = actions.encode_input({"keys": ["left", "forward"], "yaw": 5.0, "pitch": 2.0})
        m = actions.mirror_targets(t)
        back = actions.decode_action(m["keys"] > 0.5, 0, 0, int(m["yaw"]), int(m["pitch"]))
        self.assertEqual(back.keys, {"right", "forward"})
        self.assertAlmostEqual(back.yaw, -actions.bin_to_camera(int(t["yaw"])), places=4)
        self.assertEqual(int(m["pitch"]), int(t["pitch"]))

    def test_vpt_buttons_and_camera(self):
        b, c = vpt.encode({"keys": ["left", "forward", "attack"], "yaw": 3.2, "pitch": -0.6})
        a = vpt.decode(vpt.mirror_buttons(b), vpt.mirror_camera(c))
        self.assertEqual(a.keys, {"right", "forward", "attack"})
        self.assertAlmostEqual(a.yaw, -vpt.decode(b, c).yaw, places=4)
        self.assertEqual(vpt.mirror_buttons(vpt.mirror_buttons(b)), b)

    def test_sampler_mirrors_windows(self):
        from test_learn import write_recording

        from minigamebots_brain.learn.recording import SequenceSampler

        with tempfile.TemporaryDirectory() as d:
            rec = os.path.join(d, "r")
            write_recording(rec, 80)
            plain = SequenceSampler([rec], length=16, holdout=0.0, seed=1).window(0, 0, 10)
            from minigamebots_brain.learn.recording import mirror_window

            m = mirror_window(plain)
            self.assertTrue(np.array_equal(m["frames"], plain["frames"][:, :, ::-1]))
            s = SequenceSampler([rec], length=16, holdout=0.0, seed=1, mirror=1.0)
            self.assertEqual(s.batch(2)["frames"].shape, (2, 16, 128, 128, 3))


if __name__ == "__main__":
    unittest.main()
