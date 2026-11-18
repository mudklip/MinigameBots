import unittest

from minigamebots_brain.learn.aimsim import AimSim
from minigamebots_brain.learn import features


class AimSimTest(unittest.TestCase):
    def test_batches_match_the_training_format(self):
        b = AimSim(16, seed=1).batch(3)
        self.assertEqual(b["self"].shape[:2], (3, 16))
        self.assertEqual(b["frames"].shape[2:], (features.FRAME_SIZE, features.FRAME_SIZE, 3))
        self.assertFalse(b["has_frame"].any())
        self.assertGreater(b["target_keys"].sum(), 0)  # The expert does press keys.


if __name__ == "__main__":
    unittest.main()
