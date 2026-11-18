import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain.vpt import actions as vpt  # noqa: E402

try:
    import torch

    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


class VPTActionTest(unittest.TestCase):
    def test_numbering_matches_vpt(self):
        self.assertEqual(len(vpt.COMBINATIONS), 8641)
        self.assertEqual(vpt.COMBINATIONS[0], ("none",) * 9)
        self.assertEqual(vpt.COMBINATIONS[1], ("none",) * 8 + ("camera",))  # Last group varies fastest.
        self.assertEqual(vpt.COMBINATIONS[-1], "inventory")
        self.assertEqual(vpt.CAMERA_NULL, 60)

    def test_camera_bins(self):
        degrees = [vpt.camera_bin_to_degrees(b) for b in range(11)]
        self.assertEqual(degrees[5], 0.0)
        self.assertAlmostEqual(degrees[0], -10.0)
        self.assertAlmostEqual(degrees[10], 10.0)
        for b in range(11):
            self.assertAlmostEqual(degrees[b], -degrees[10 - b])
            self.assertEqual(vpt.degrees_to_camera_bin(degrees[b]), b)
        self.assertEqual(vpt.degrees_to_camera_bin(45.0), 10)  # Clipped like VPT.

    def test_round_trip(self):
        inp = {"keys": ["forward", "sprint", "jump", "hotbar_3"], "attacks": 1, "yaw": 3.2153691, "pitch": -0.615394}
        action = vpt.decode(*vpt.encode(inp))
        self.assertEqual(action.keys, {"forward", "sprint", "jump", "hotbar_3", "attack"})
        self.assertAlmostEqual(action.yaw, 3.2153691, places=4)
        self.assertAlmostEqual(action.pitch, -0.615394, places=4)

    def test_still_camera_and_conflicts(self):
        b, c = vpt.encode({"keys": ["forward", "back", "left", "sneak", "sprint", "pick"]})
        self.assertEqual(c, vpt.CAMERA_NULL)
        self.assertEqual(vpt.COMBINATIONS[b][-1], "none")
        self.assertEqual(vpt.decode(b, 0).keys, {"left", "sneak"})  # Camera off: camera index ignored.
        self.assertEqual(vpt.decode(*vpt.encode({"keys": ["inventory", "forward"]})).keys, {"inventory"})


@unittest.skipUnless(HAVE_TORCH, "needs torch")
class VPTNetworkTest(unittest.TestCase):
    def test_parameter_names_match_published_weights(self):
        from minigamebots_brain.vpt.network import VPTModel

        with torch.device("meta"):
            state = VPTModel("1x").state_dict()
        self.assertEqual(len(state), 134)
        self.assertEqual(tuple(state["pi_head.buttons.linear_layer.weight"].shape), (8641, 1024))
        self.assertEqual(tuple(state["pi_head.camera.linear_layer.weight"].shape), (121, 1024))
        self.assertEqual(tuple(state["net.img_process.cnn.stacks.0.firstconv.layer.weight"].shape), (64, 3, 3, 3))
        self.assertEqual(tuple(state["net.recurrent_layer.blocks.0.r.orc_block.r_layer.weight"].shape), (80, 1024))

    def test_foundation_and_profile_layers(self):
        """A foundation folder plus an AI's fine-tuned layers load and play."""
        import json

        import numpy as np
        from safetensors.torch import save_file

        from minigamebots_brain.policies import create
        from minigamebots_brain.vpt import agent
        from minigamebots_brain.vpt.network import VPTModel

        torch.manual_seed(0)
        model = VPTModel("1x")
        with tempfile.TemporaryDirectory() as root:
            fdir = os.path.join(root, "foundations", "vpt-1x")
            os.makedirs(fdir)
            save_file({k: v.half().contiguous() for k, v in model.state_dict().items()},
                      os.path.join(fdir, "model.safetensors"))
            with open(os.path.join(fdir, "foundation.json"), "w") as f:
                json.dump({"id": "vpt-1x", "size": "1x", "name": "test"}, f)
            profile = os.path.join(root, "profiles", "ai")
            os.makedirs(os.path.join(profile, "core"))
            names = agent.trainable_names(model)
            self.assertTrue(all(n.startswith(agent.TRAINABLE_PREFIXES) for n in names))
            layers = {n: torch.full_like(p, 0.01).half() for n, p in model.named_parameters() if n in names}
            save_file(layers, os.path.join(profile, "core", "model.safetensors"))
            with open(os.path.join(profile, "core", "model.json"), "w") as f:
                json.dump({"arch": agent.ARCH, "foundation": "vpt-1x"}, f)

            policy = create("learned", {"profile": profile, "temperature": 0.0})
            self.assertEqual(policy.name, "vpt")
            w = policy.model.state_dict()["pi_head.camera.linear_layer.weight"]
            self.assertTrue(torch.allclose(w, torch.full_like(w, 0.01), atol=1e-4))
            action = policy.act({"self": {}}, np.zeros((128, 128, 3), dtype=np.uint8))
            self.assertIsNotNone(action.to_message(1))
            self.assertEqual(policy.act({"self": {"dead": True}}, None).keys, set())


if __name__ == "__main__":
    unittest.main()
