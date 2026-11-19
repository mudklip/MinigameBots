import os
import tempfile
import unittest
import wave

import numpy as np

from minigamebots_brain.mind import voice


class VoiceTest(unittest.TestCase):
    def test_wav_reading_and_resampling(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "a.wav")
            with wave.open(p, "wb") as w:
                w.setnchannels(2)
                w.setsampwidth(2)
                w.setframerate(32000)
                w.writeframes((np.ones((3200, 2)) * 16384).astype("<i2").tobytes())
            a = voice.read_wav(p)
            self.assertEqual(len(a), 1600)  # 0.1 s at 16 kHz.
            self.assertAlmostEqual(float(a.mean()), 0.5, places=3)
            self.assertEqual(voice.installed(d), {"hear": False, "speak": False})


if __name__ == "__main__":
    unittest.main()
