import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import numpy as np  # noqa: E402

from minigamebots_brain.actions import Action  # noqa: E402
from minigamebots_brain.learn import features  # noqa: E402

try:
    import torch  # noqa: F401

    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


def obs_with_memory():
    from test_learn import pig_obs

    o = pig_obs()
    o["waypoints"] = [{"type": "base", "name": "home", "dx": 0.0, "dy": 0.0, "dz": 20.0, "distance": 20.0, "pinned": True},
                      {"type": "mine", "name": "", "dx": -10.0, "dy": -5.0, "dz": 0.0, "distance": 11.2, "pinned": False}]
    o["entities"][0].update({"relation": -1, "allyInsignia": False, "enemyInsignia": True, "insignia": "1a2b3c4d"})
    return o


class MemoryFeatureTest(unittest.TestCase):
    def test_waypoints_and_relations(self):
        f = features.featurize(obs_with_memory())
        self.assertEqual(f["wp_vals"].shape, (features.MAX_WAYPOINTS, features.WAYPOINT_DIM))
        self.assertEqual(list(f["wp_mask"]), [True, True, False, False])
        self.assertEqual(f["wp_vals"][0, features.WAYPOINT_TYPES.index("base")], 1.0)
        n = len(features.WAYPOINT_TYPES)
        # Facing yaw 0 (south, +Z): the base 20 blocks south is straight ahead.
        self.assertAlmostEqual(float(f["wp_vals"][0, n]), 1.0, places=4)
        self.assertEqual(f["wp_vals"][0, n + 5], 1.0)  # pinned
        self.assertEqual(list(f["ent_rel"][0]), [-1.0, 0.0, 1.0, 1.0])

    def test_old_observations_still_work(self):
        from test_learn import pig_obs

        f = features.featurize(pig_obs())
        self.assertFalse(f["wp_mask"].any())
        self.assertFalse(f["ent_rel"].any())

    def test_mark_meta_action_message(self):
        self.assertNotIn("mark", Action().to_message(1))
        self.assertEqual(Action(mark="base").to_message(1)["mark"], "base")


@unittest.skipUnless(HAVE_TORCH, "needs torch")
class MemoryModelTest(unittest.TestCase):
    def test_older_checkpoint_loads_and_behaves_the_same(self):
        import torch
        from safetensors.torch import save_file

        from minigamebots_brain.learn import checkpoint
        from minigamebots_brain.learn.model import LitePolicy, OPTIONAL_PREFIXES, to_tensors

        torch.manual_seed(0)
        model = LitePolicy()
        with tempfile.TemporaryDirectory() as profile:
            checkpoint.save(profile, model, {"step": 1}, best=True)
            # Simulate a checkpoint from before the memory parts existed.
            from safetensors.torch import load_file

            path = os.path.join(profile, "core", "model.safetensors")
            old = {k: v for k, v in load_file(path).items() if not k.startswith(OPTIONAL_PREFIXES)}
            save_file(old, path)
            loaded, _ = checkpoint.load(profile, "model", "cpu")
        batch = {k: np.asarray(v)[None, None] for k, v in features.featurize(obs_with_memory()).items()}
        batch["frames"] = np.zeros((1, 1, 128, 128, 3), dtype=np.uint8)
        x = to_tensors(batch, "cpu")
        with torch.no_grad():
            a, _ = model(x)
            no_mem = {k: v for k, v in x.items() if k not in ("wp_vals", "wp_mask", "ent_rel")}
            b, _ = loaded(no_mem)
            c, _ = loaded(x)
        self.assertTrue(torch.allclose(a["keys"], b["keys"], atol=1e-5))
        self.assertTrue(torch.allclose(b["keys"], c["keys"], atol=1e-5))  # Memory inputs start with no effect.
        p_mark = torch.softmax(c["mark"][0, 0], -1)
        self.assertGreater(float(p_mark[0]), 0.999)  # Almost never marks until it learns to.

    def test_marks_in_recordings_become_targets(self):
        from test_learn import write_recording

        from minigamebots_brain.learn.recording import SequenceSampler

        with tempfile.TemporaryDirectory() as d:
            rec = os.path.join(d, "rec")
            write_recording(rec, 80, marks=[(40, "base"), (50, "good")])
            s = SequenceSampler([rec], length=32, split="train", holdout=0.0, seed=0)
            w = s.window(0, 0, 30)
            self.assertEqual(int(w["target_mark"][10]), features.WAYPOINT_TYPES.index("base") + 1)
            self.assertEqual(int(w["target_mark"][20]), 0)  # "good" is a training mark, not a place.


if __name__ == "__main__":
    unittest.main()
