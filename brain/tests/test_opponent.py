import math
import os
import tempfile
import unittest

from minigamebots_brain.learn.actions import KEY_INDEX, NUM_KEYS
from minigamebots_brain.policies.opponent import OpponentModel


def foe(t, strafe=0.2, closing=0.15, dist=3.0, **kw):
    """Steve, straight ahead (south of a player facing south), moving as told."""
    # Me looking along +z; Steve at (0, dist). My right is -x. Moving to my right: vx < 0.
    e = {"name": "Steve", "player": True, "dx": 0.0, "dz": dist, "vx": -strafe, "vz": -closing,
         "health": 20, "maxHealth": 20, "onGround": True, "hitMe": -1, "hitByMe": -1}
    e.update(kw)
    return {"self": {"yaw": 0.0}, "entities": [e]}


def bias(m, r, obs):
    return m.bias(r, obs, KEY_INDEX, NUM_KEYS, 4)


class OpponentTest(unittest.TestCase):
    def test_reads_a_rusher_that_strafes_right_and_counters(self):
        m = OpponentModel(None)
        for t in range(60):
            r = m.observe(foe(t), "Steve")
        v = {k: tr.value() for k, tr in r.traits.items()}
        self.assertGreater(v["strafe"], 0.1)
        self.assertGreater(v["aggression"], 0.1)
        self.assertIn("rushes", r.summary())
        self.assertIn("strafes right", r.summary())
        m.mix = 0.0
        m.mix_until = 10 ** 9
        b = bias(m, r, foe(60))["keys"]
        self.assertGreater(b[KEY_INDEX["left"]], b[KEY_INDEX["right"]])  # Circles against them.
        self.assertGreater(b[KEY_INDEX["back"]], 0)  # Keeps the spacing from a rusher.

    def test_notices_a_change_of_style_within_seconds(self):
        m = OpponentModel(None)
        for t in range(200):
            r = m.observe(foe(t, strafe=0.2), "Steve")
        for t in range(40):  # Two seconds of strafing the other way.
            r = m.observe(foe(t, strafe=-0.2), "Steve")
        self.assertLess(r.traits["strafe"].value(), 0)
        self.assertGreaterEqual(r.traits["strafe"].switches, 1)

    def test_blocks_after_hits_and_jump_crits(self):
        m = OpponentModel(None)
        for i in range(12):
            for k in range(14):
                hb = k if k <= 12 else -1
                r = m.observe(foe(i * 14 + k, hitByMe=hb, blocking=(2 <= k <= 9), hitMe=k, onGround=(k != 0)), "Steve")
        self.assertGreater(r.traits["block"].value(), 0.6)
        self.assertGreater(r.traits["crit"].value(), 0.6)
        # Right after hitting a blocker: hold the swing.
        a = bias(m, r, foe(0, hitByMe=3, blocking=True))["attacks"]
        self.assertLess(a[1], 0)

    def test_remembers_each_player_between_fights(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "styles.json")
            m = OpponentModel(path)
            for t in range(100):
                m.observe(foe(t, strafe=-0.2, closing=-0.1), "Steve")
            m.end_fight("Steve")
            m2 = OpponentModel(path)
            r = m2.observe(foe(0, strafe=0.0, closing=0.0), "Steve")
            self.assertLess(r.traits["strafe"].value(), -0.1)  # Starts prepared.
            self.assertIn("keeps away", m2.saved["Steve"]["summary"])
            self.assertEqual(r.fights, 2)

    def test_ignores_far_away_and_unknown(self):
        m = OpponentModel(None)
        self.assertIsNone(m.observe(foe(0), None))
        r = m.observe(foe(0, dist=20), "Steve")
        self.assertEqual(r.traits["range"].n, 0)
        b = bias(m, r, foe(0, dist=20))
        self.assertTrue((b["keys"] == 0).all())

    def test_one_opponent_even_with_health_in_the_name(self):
        from minigamebots_brain.policies.fastweights import who_key

        self.assertEqual(who_key({"name": "godly Dummy ❤ 18.5"}), "godly Dummy")
        self.assertEqual(who_key({"name": "§cSteve §f20 ❤"}), "Steve")
        self.assertEqual(who_key({"name": "Steve"}), "Steve")
        self.assertEqual(who_key({"name": "Player123"}), "Player123")  # Real usernames keep their digits.
        self.assertEqual(who_key({"name": "Notch 12.5hp"}), "Notch")


if __name__ == "__main__":
    unittest.main()
