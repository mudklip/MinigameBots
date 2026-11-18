import base64
import gzip
import io
import json
import math
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import numpy as np  # noqa: E402

from minigamebots_brain.learn import actions, features  # noqa: E402

try:
    import torch  # noqa: F401
    from PIL import Image  # noqa: F401

    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


def pig_obs(yaw=0.0, dx=0.0, dz=5.0, pitch=0.0):
    blocks = bytearray(11 ** 3)
    return {
        "self": {"health": 20, "maxHealth": 20, "food": 20, "yaw": yaw, "pitch": pitch},
        "inventory": [{"slot": 0, "id": "minecraft:iron_sword", "count": 1, "damage": 25, "maxDamage": 250}],
        "selectedSlot": 0,
        "entities": [{"type": "minecraft:pig", "dx": dx, "dy": -1.0, "dz": dz, "health": 10, "maxHealth": 10,
                      "visible": True, "heldItem": "minecraft:air"}],
        "blocks": {"categories": base64.b64encode(bytes(blocks)).decode()},
    }


def write_recording(directory, ticks, frame_every=1, marks=(), trim=(-1, -1)):
    """A synthetic recording: a pig circles the player, who turns to face it."""
    from PIL import Image

    os.makedirs(directory, exist_ok=True)
    per_chunk = 100
    chunks = (ticks + per_chunk - 1) // per_chunk
    for c in range(chunks):
        with gzip.open(os.path.join(directory, f"chunk-{c:05d}.bin.gz"), "wb") as f:
            for t in range(c * per_chunk, min(ticks, (c + 1) * per_chunk)):
                angle = t * 0.05
                dx, dz = 5 * math.sin(angle), 5 * math.cos(angle)
                yaw = math.degrees(-angle) + 20 * math.sin(t * 0.3)
                obs = pig_obs(yaw=yaw, dx=dx, dz=dz)
                bearing = math.degrees(math.atan2(*reversed(features.to_local(dx, dz, yaw))))
                record = {"input": {"keys": ["forward"] if t % 2 == 0 else [], "attacks": 0, "uses": 0,
                                    "yaw": max(-10, min(10, bearing * 0.3)), "pitch": 0.0},
                          "obs": obs, "events": [], "frameAge": 0}
                body = json.dumps(record).encode()
                f.write(struct.pack(">ii", t, len(body)))
                f.write(body)
                if t % frame_every == 0 or t % per_chunk == 0:
                    img = Image.new("RGB", (128, 128), (t % 255, 100, 50))
                    buf = io.BytesIO()
                    img.save(buf, "JPEG")
                    data = buf.getvalue()
                    f.write(struct.pack(">i", len(data)))
                    f.write(data)
                else:
                    f.write(struct.pack(">i", -1))
    meta = {"id": os.path.basename(directory), "ticks": ticks, "ticksPerChunk": per_chunk, "chunks": chunks,
            "trimStart": trim[0], "trimEnd": trim[1], "marks": [{"tick": t, "type": k} for t, k in marks]}
    with open(os.path.join(directory, "meta.json"), "w") as f:
        json.dump(meta, f)


class FeatureTest(unittest.TestCase):
    def test_local_coordinates(self):
        # Facing +Z (yaw 0): a target at +Z is straight ahead; at -X is to the right.
        ahead, right = features.to_local(0, 5, 0)
        self.assertAlmostEqual(5, ahead)
        self.assertAlmostEqual(0, right)
        ahead, right = features.to_local(-5, 0, 0)
        self.assertAlmostEqual(5, right)
        # Facing -X (yaw 90): a target at -X is straight ahead.
        ahead, right = features.to_local(-5, 0, 90)
        self.assertAlmostEqual(5, ahead, places=5)

    def test_entity_ahead_looks_the_same_whatever_the_facing(self):
        a = features.featurize(pig_obs(yaw=0, dx=0, dz=5))
        b = features.featurize(pig_obs(yaw=90, dx=-5, dz=0))
        np.testing.assert_allclose(a["ent_vals"][0, :6], b["ent_vals"][0, :6], atol=1e-5)

    def test_block_cube_rotates_with_facing(self):
        cube = np.zeros((11, 11, 11), dtype=np.uint8)
        cube[5, 5 + 1, 5] = 7  # One block at dz=+1 (south of the player).
        cube[5, 5, 5 - 1] = 3  # One block at dx=-1 (west).
        raw = {"categories": base64.b64encode(cube.tobytes()).decode()}
        facing_south = features.rotate_blocks(features.decode_blocks(raw), 0)
        self.assertEqual(7, facing_south[5, 6, 5], "in front stays in front")
        facing_west = features.rotate_blocks(features.decode_blocks(raw), 90)
        self.assertEqual(3, facing_west[5, 6, 5], "the west block is in front when facing west")

    def test_unknown_items_still_get_stable_ids(self):
        a = features.bucket("mymod:laser_sword", features.ITEM_BUCKETS)
        self.assertEqual(a, features.bucket("mymod:laser_sword", features.ITEM_BUCKETS))
        self.assertNotEqual(0, a)
        self.assertEqual(0, features.bucket("", features.ITEM_BUCKETS))

    def test_empty_observation_is_safe(self):
        f = features.featurize({})
        self.assertEqual((features.SELF_DIM,), f["self"].shape)
        self.assertFalse(f["ent_mask"].any())


class ActionEncodingTest(unittest.TestCase):
    def test_camera_bins_round_trip_and_are_fine_near_zero(self):
        self.assertEqual(actions.CAMERA_BINS // 2, actions.camera_to_bin(0.0))
        self.assertAlmostEqual(0.0, actions.bin_to_camera(actions.CAMERA_BINS // 2))
        for degrees in (-40, -5, -1, 1, 5, 40):
            back = actions.bin_to_camera(actions.camera_to_bin(degrees))
            self.assertLess(abs(back - degrees), max(1.0, abs(degrees) * 0.25), degrees)
        small = actions.bin_to_camera(actions.CAMERA_BINS // 2 + 1)
        self.assertLess(small, 1.0, "smallest step under a degree")

    def test_input_targets(self):
        t = actions.encode_input({"keys": ["forward", "attack"], "attacks": 3, "uses": 0, "yaw": 2.0, "pitch": -1.0})
        self.assertEqual(1.0, t["keys"][actions.KEY_INDEX["forward"]])
        self.assertEqual(2, t["attacks"], "a fresh press already gives one click")
        self.assertGreater(t["yaw"], actions.CAMERA_BINS // 2)
        self.assertLess(t["pitch"], actions.CAMERA_BINS // 2)


@unittest.skipUnless(HAVE_TORCH, "needs PyTorch and Pillow")
class RecordingAndTrainingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.rec = os.path.join(self.tmp, "rec1")
        write_recording(self.rec, 600, frame_every=2, marks=[(300, "mistake"), (500, "good")])

    def test_reader_repeats_frames_and_survives_truncation(self):
        from minigamebots_brain.learn import recording

        records = recording.read_chunk(os.path.join(self.rec, "chunk-00000.bin.gz"))
        self.assertEqual(100, len(records))
        self.assertIsNotNone(records[1][2], "tick without its own frame reuses the previous one")
        path = os.path.join(self.rec, "chunk-00001.bin.gz")
        with open(path, "rb") as f:
            data = f.read()
        with open(path, "wb") as f:
            f.write(data[: len(data) // 2])
        partial = recording.read_chunk(path)
        self.assertTrue(0 < len(partial) < 100)

    def test_marks_weight_ticks(self):
        from minigamebots_brain.learn import recording

        rec = recording.Recording(self.rec)
        self.assertEqual(0.0, rec.weights[300], "mistakes aren't imitated")
        self.assertEqual(recording.GOOD_WEIGHT, rec.weights[500])
        self.assertEqual(1.0, rec.weights[100])

    def test_sampler_shapes(self):
        from minigamebots_brain.learn.recording import SequenceSampler

        s = SequenceSampler([self.rec], length=16, split="train", seed=1)
        b = s.batch(3)
        self.assertEqual((3, 16, 128, 128, 3), b["frames"].shape)
        self.assertEqual((3, 16, actions.NUM_KEYS), b["target_keys"].shape)
        self.assertEqual((3, 16, 11, 11, 11), b["blocks"].shape)

    def test_training_lowers_loss_and_saves_a_playable_checkpoint(self):
        from minigamebots_brain.learn import checkpoint, train
        from minigamebots_brain.policies.learned import LearnedPolicy

        profile = os.path.join(self.tmp, "profile")
        import contextlib

        with contextlib.redirect_stdout(io.StringIO()) as out:
            result = train.train(profile, [self.rec], steps=40, resources="max", eval_every=20, device="cpu",
                                 batch_size=2)
        lines = [json.loads(l) for l in out.getvalue().splitlines() if l.startswith("{")]
        progress = [l for l in lines if l["type"] == "progress"]
        self.assertLess(progress[-1]["loss"], progress[0]["loss"], "loss goes down")
        self.assertEqual(40, result["steps"])
        info = checkpoint.read_info(profile)
        self.assertEqual("lite-v1", info["arch"])

        policy = LearnedPolicy(profile, temperature=0.0, device="cpu")
        frame = np.zeros((128, 128, 3), dtype=np.uint8)
        for _ in range(3):
            msg = policy.act(pig_obs(yaw=0, dx=-3, dz=3), frame).to_message(0)
            self.assertIn("keys", msg)

        # Continuing training picks up where it left off.
        with contextlib.redirect_stdout(io.StringIO()):
            again = train.train(profile, [self.rec], steps=10, resources="max", eval_every=10, device="cpu",
                                batch_size=2)
        self.assertEqual(50, again["global_step"])


if __name__ == "__main__":
    unittest.main()
