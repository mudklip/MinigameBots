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


def steady(m):
    """No random spells (strafe mixing, hedging) for a test that checks the counters themselves."""
    m.mix, m.mix_until, m.hedge, m.hedge_until = 0.0, 10 ** 9, True, 10 ** 9


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
        steady(m)
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



class BluffTest(unittest.TestCase):
    def test_drops_a_counter_that_keeps_getting_it_hit(self):
        m = OpponentModel(None)
        steady(m)
        for t in range(80):
            r = m.observe(foe(t), "Steve")  # Learns: strafes right.
            bias(m, r, foe(t))
        before = r.counter_scale("strafe")
        # Each time it circles against that strafe, they hit it: the "habit" is bait.
        for i in range(15):
            for k in range(10):
                o = foe(k, hitMe=k)
                r = m.observe(o, "Steve")
                bias(m, r, o)
        self.assertLess(r.works["strafe"], -0.3)
        self.assertLess(r.counter_scale("strafe"), 0.5 * before)

    def test_spots_a_baiter(self):
        m = OpponentModel(None)
        for t in range(150):
            r = m.observe(foe(t, strafe=0.2), "Steve")
        # Their hits always come when they break the habit (strafe left all of a sudden).
        for i in range(12):
            for k in range(6):
                r = m.observe(foe(k, strafe=0.2), "Steve")
            r = m.observe(foe(0, strafe=-0.2, hitMe=0), "Steve")
            r = m.observe(foe(0, strafe=0.2, hitMe=5), "Steve")
        self.assertGreater(r.baiter, 0.25)
        self.assertIn("baits", r.summary())

    def test_catches_a_fake_retreat_but_not_a_kiter(self):
        m = OpponentModel(None)
        steady(m)
        for t in range(60):
            r = m.observe(foe(t, closing=0.15), "Steve")  # A rusher, at full health...
        for t in range(10):
            r = m.observe(foe(t, closing=-0.3), "Steve")  # ...suddenly running like he's low.
        self.assertEqual(r.judged, "fake_retreat")
        self.assertEqual(r.bluffs, 1)
        self.assertLess(bias(m, r, foe(0, closing=-0.3))["keys"][KEY_INDEX["forward"]], 0)  # Doesn't chase.
        k = OpponentModel(None)
        for t in range(80):
            rk = k.observe(foe(t, closing=-0.3, name="Kiter"), "Kiter")  # Always keeps away: his style.
        self.assertIsNone(rk.judged)
        self.assertEqual(rk.bluffs, 0)

    def test_knockback_is_not_a_retreat(self):
        m = OpponentModel(None)
        for t in range(60):
            r = m.observe(foe(t, closing=0.15), "Steve")
        for i in range(4):  # Hit, and flying back each time.
            for k in range(10):
                r = m.observe(foe(k, closing=-0.4, hitByMe=k), "Steve")
        self.assertEqual(r.bluffs, 0)
        self.assertGreater(r.traits["aggression"].value(), 0.05)  # Still a rusher.

    def test_catches_a_bluff_when_low_even_with_health_hidden(self):
        for shown in (5.0, 20.0):
            m = OpponentModel(None)
            steady(m)
            if shown == 20.0:
                # The server always shows 20: it goes by the hits it landed.
                r = m.observe(foe(0, health=20.0, hitByMe=-1), "Steve")
                for i in range(5):
                    for k in range(6):
                        r = m.observe(foe(k, health=20.0, hitByMe=k, closing=0.0), "Steve")
            for t in range(10):
                r = m.observe(foe(t, health=shown, closing=0.3, hitByMe=20 + t), "Steve")  # Its last hit, a second ago.
            self.assertEqual(r.judged, "bluff_strength", shown)
            b = bias(m, r, foe(0, health=shown, closing=0.3))["keys"]
            self.assertGreater(b[KEY_INDEX["forward"]], 0)  # Presses instead of backing off.

    def test_unpredictable_players_get_a_lighter_first_impression(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "styles.json")
            m = OpponentModel(path)
            strafe = 0.2
            for fight in range(5):
                for t in range(60):
                    m.observe(foe(t, strafe=strafe), "Steve")
                m.end_fight("Steve")
                strafe = -strafe  # Different every fight.
            self.assertLess(m.saved["Steve"]["consistency"], 0.6)
            r = OpponentModel(path).reading("Steve")
            self.assertLess(r.traits["strafe"].n, 15 * 0.6)

    def test_hedges_its_counters(self):
        m = OpponentModel(None)
        for t in range(60):
            r = m.observe(foe(t), "Steve")
        m.mix, m.mix_until = 0.0, 10 ** 9
        on = 0
        for t in range(3000):
            m.tick += 1
            b = bias(m, r, foe(0))["keys"]
            on += b[KEY_INDEX["left"]] > 0.3
        self.assertTrue(0.5 * 3000 < on < 0.95 * 3000, on)


if __name__ == "__main__":
    unittest.main()


class CombineTest(unittest.TestCase):
    def test_trust_follows_results_and_total_is_capped(self):
        import numpy as np

        m = OpponentModel(None)
        r = m.reading("Steve")
        fast = {"keys": np.full(4, 3.0), "attacks": np.zeros(2), "uses": np.zeros(2)}
        counter = {"keys": np.full(4, 1.0), "attacks": np.zeros(2)}
        out = m.combine(r, fast, counter)
        self.assertTrue((out["keys"] <= 2.0).all())  # Capped: only ever a nudge.
        # Every time the nudges steer, it gets hit: trust in them drops.
        for i in range(10):
            m.combine(r, fast, counter)
            for k in range(6):
                m.observe(foe(k, hitMe=k), "Steve")
        self.assertLess(r.trust["fast"], 1.0)
        self.assertLess(r.trust["counter"], 1.0)
