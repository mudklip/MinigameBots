import os
import tempfile
import unittest

import numpy as np

from minigamebots_brain.learn.recording import Recording, read_chunk
from minigamebots_brain.video import idm, pipeline


def fake_video(n=60, seed=0):
    """A moving 'world' with a still hotbar strip at the bottom centre."""
    rng = np.random.default_rng(seed)
    # Smooth texture (blocky noise scaled up) that scrolls, like a turning camera.
    tex = np.kron(rng.integers(0, 255, (20, 60, 3)), np.ones((16, 16, 1))).astype(np.uint8)
    frames = []
    for i in range(n):
        f = np.ascontiguousarray(np.roll(tex, 4 * i, axis=1)[:pipeline.DECODE_H, :pipeline.DECODE_W])
        f[250:, 140:340] = 60
        frames.append(f)
    return frames


class VideoTest(unittest.TestCase):
    def test_hotbar_found_only_in_gameplay(self):
        box = pipeline.hotbar_box(fake_video())
        self.assertIsNotNone(box)
        self.assertTrue(130 <= box[0] <= 150 and 330 <= box[1] <= 345)
        still = [np.full((pipeline.DECODE_H, pipeline.DECODE_W, 3), 90, dtype=np.uint8)] * 60
        self.assertIsNone(pipeline.hotbar_box(still))  # A menu or a still image.

    def test_cuts(self):
        a = fake_video(50, 1)
        b = [np.zeros_like(f) for f in fake_video(50, 2)]
        self.assertEqual(pipeline.shots(a + b), [(0, 50), (50, 100)])

    def test_recording_written_like_the_mods(self):
        with tempfile.TemporaryDirectory() as d:
            frames = [np.zeros((128, 128, 3), dtype=np.uint8)] * 10
            inputs = [{"keys": ["forward"], "attacks": 0, "uses": 0, "yaw": 1.0, "pitch": 0.0}] * 10
            pipeline.write_recording(d, frames, inputs, {"id": "v"})
            recs = read_chunk(os.path.join(d, "chunk-00000.bin.gz"))
            self.assertEqual(len(recs), 10)
            self.assertEqual(recs[3][1]["input"]["keys"], ["forward"])
            self.assertAlmostEqual(float(Recording(d).weights[0]), 0.5)  # Videos count half.

    def test_idm_shapes(self):
        m = idm.IDM()
        k, y, p = m(__import__("torch").zeros(2, 2 * idm.CONTEXT + 1, idm.SIZE, idm.SIZE, 3, dtype=__import__("torch").uint8))
        self.assertEqual(tuple(k.shape), (2, len(idm.VKEYS)))


if __name__ == "__main__":
    unittest.main()
