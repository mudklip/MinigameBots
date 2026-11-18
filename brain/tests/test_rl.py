import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain.rl import objective as obj  # noqa: E402
from minigamebots_brain.rl import templates  # noqa: E402

try:
    import torch  # noqa: F401

    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


def ev(kind, **data):
    return {"type": kind, "data": data}


class ObjectiveTest(unittest.TestCase):
    def test_matching(self):
        self.assertTrue(obj.matches({"target": "minecraft:pig"}, {"target": "minecraft:pig"}))
        self.assertFalse(obj.matches({"target": "minecraft:pig"}, {"target": "minecraft:cow"}))
        self.assertTrue(obj.matches({"amount": {"min": 2}}, {"amount": 3}))
        self.assertFalse(obj.matches({"amount": {"max": 2}}, {"amount": 3}))
        self.assertTrue(obj.matches({"text": {"contains": "won"}}, {"text": "You WON the game"}))
        self.assertTrue(obj.matches({"dead": False}, {"dead": False}))
        self.assertFalse(obj.matches({"dead": False}, {"dead": True}))

    def test_values_per_unit_cap_and_cooldown(self):
        e = obj.RewardEngine({"rules": [
            {"event": "damage_dealt", "value": 0.5, "per": "amount", "cap": 3},
            {"event": "kill", "value": 2, "cooldown": 1},
        ]})
        self.assertAlmostEqual(e.step(1, [ev("damage_dealt", amount=4)]).reward, 2.0)
        self.assertAlmostEqual(e.step(2, [ev("damage_dealt", amount=4)]).reward, 1.0)  # Capped at 3.
        self.assertAlmostEqual(e.step(3, [ev("damage_dealt", amount=4)]).reward, 0.0)
        self.assertAlmostEqual(e.step(4, [ev("kill")]).reward, 2.0)
        self.assertAlmostEqual(e.step(10, [ev("kill")]).reward, 0.0)  # Cooldown (20 ticks).
        self.assertAlmostEqual(e.step(30, [ev("kill")]).reward, 2.0)
        e.end_episode()
        self.assertAlmostEqual(e.step(31, [ev("damage_dealt", amount=4)]).reward, 2.0)  # Caps reset.

    def test_forgiven_penalty_disappears(self):
        e = obj.RewardEngine({"rules": [
            {"event": "damage_taken", "value": -1, "per": "amount", "forgive": {"by": ["kill"], "within": 2}},
            {"event": "kill", "value": 5},
        ]})
        r = e.step(1, [ev("damage_taken", amount=3)])
        self.assertEqual(r.reward, 0.0)
        r = e.step(20, [ev("kill")])
        self.assertEqual(r.reward, 5.0)
        self.assertEqual(r.delayed, [])
        self.assertEqual(e.step(100, []).delayed, [])
        self.assertEqual(e.end_episode(), [])

    def test_unforgiven_penalty_lands_at_its_moment(self):
        e = obj.RewardEngine({"rules": [
            {"event": "damage_taken", "value": -1, "per": "amount", "forgive": {"by": ["kill"], "within": 2}}]})
        e.step(5, [ev("damage_taken", amount=3)])
        self.assertEqual(e.step(30, []).delayed, [])
        self.assertEqual(e.step(45, []).delayed, [(5, -3.0)])
        e.step(50, [ev("damage_taken", amount=1)])
        self.assertEqual(e.end_episode(), [(50, -1.0)])  # Still held at the end: applied.

    def test_priority_tiers_soften_lower_penalties(self):
        e = obj.RewardEngine({"rules": [
            {"event": "protect", "value": 1, "tier": 1},
            {"event": "damage_taken", "value": -1, "tier": 3},
        ]})
        self.assertEqual(e.step(1, [ev("damage_taken")]).reward, -1.0)  # Nothing more important going on.
        e.step(2, [ev("protect")])
        self.assertAlmostEqual(e.step(3, [ev("damage_taken")]).reward, -1.0 * 0.25 ** 2)
        self.assertAlmostEqual(e.step(3 + obj.TIER_MEMORY_TICKS + 1, [ev("damage_taken")]).reward, -1.0)

    def test_budget_is_free_until_exceeded_and_adapts(self):
        e = obj.RewardEngine({"rules": [{"event": "damage_taken", "value": 1, "per": "amount", "budget": {"limit": 5}}]})
        self.assertEqual(e.step(1, [ev("damage_taken", amount=4)]).reward, 0.0)
        self.assertAlmostEqual(e.step(2, [ev("damage_taken", amount=3)]).reward, -2.0)  # 2 over the limit.
        e.end_episode()
        self.assertAlmostEqual(e.budget_weight[0], 1.5)  # Broke the budget: it matters more now.
        self.assertAlmostEqual(e.step(3, [ev("damage_taken", amount=6)]).reward, -1.5)
        e.end_episode()
        e.step(4, [ev("damage_taken", amount=1)])
        e.end_episode()
        self.assertAlmostEqual(e.budget_weight[0], 2.25 * 0.9)

    def test_second_event_uses_player_state(self):
        e = obj.RewardEngine({"rules": [{"event": "second", "where": {"dead": False}, "value": 0.1}]})
        self.assertAlmostEqual(e.step(20, [], {"self": {"dead": False}}).reward, 0.1)
        self.assertEqual(e.step(21, [], {"self": {"dead": False}}).reward, 0.0)
        self.assertEqual(e.step(40, [], {"self": {"dead": True}}).reward, 0.0)

    def test_warnings(self):
        self.assertTrue(obj.analyze({"rules": [{"event": "damage_taken", "value": -1}]}))
        self.assertTrue(any("avoid" in w for w in obj.analyze({"rules": [
            {"event": "damage_taken", "value": -1}, {"event": "block_broken", "value": 1}]})))
        self.assertEqual(obj.analyze(templates.build("duel")), [])
        self.assertEqual(obj.analyze(templates.build("hunt")), [])

    def test_templates_fill_params(self):
        t = templates.build("hunt", {"target": "minecraft:cow"})
        self.assertEqual(t["name"], "Hunt minecraft:cow")
        self.assertEqual(t["rules"][0]["where"]["target"], "minecraft:cow")
        with self.assertRaises(ValueError):
            templates.build("nope")


@unittest.skipUnless(HAVE_TORCH, "needs torch")
class PPOTest(unittest.TestCase):
    def test_gae(self):
        import numpy as np

        from minigamebots_brain.rl.ppo import gae

        adv, ret = gae(np.array([1.0, 0.0, 1.0], dtype=np.float32), np.zeros(3, dtype=np.float32),
                       np.array([0.0, 1.0, 0.0], dtype=np.float32), 0.0, 1.0, 1.0)
        self.assertAlmostEqual(float(ret[0]), 1.0)  # The episode ended at step 1.
        self.assertAlmostEqual(float(ret[2]), 1.0)

    def test_rollout_longer_than_whole_chunks_trains(self):
        """A rollout that grew past a multiple of the chunk size (the learner was busy) still trains."""
        import numpy as np
        import torch

        from minigamebots_brain.learn.model import LitePolicy
        from minigamebots_brain.rl import ppo

        from test_learn import pig_obs

        torch.manual_seed(0)
        model = LitePolicy()
        learner = ppo.PPOLearner(model, None, "cpu", rollout=64, epochs=1)
        hidden = model.initial_state(1, "cpu")
        for t in range(ppo.CHUNK * 2 + 5):
            feats = ppo.featurize(pig_obs(), np.zeros((128, 128, 3), dtype=np.uint8))
            batch = {k: torch.from_numpy(v[None, None]) for k, v in feats.items()}
            with torch.no_grad():
                logits, new_hidden = model(batch, hidden)
                act = ppo.encode_sample(ppo.sample(logits))
            learner.record(t, feats, act, -1.0, 0.0, hidden)
            learner.reward(0.1)
            hidden = new_hidden
        stats = learner._train(learner.buffer, 0.0)
        self.assertEqual(stats["steps"], ppo.CHUNK * 2)

    def test_practice_plays_learns_and_saves(self):
        import numpy as np
        import torch

        from minigamebots_brain.learn import checkpoint
        from minigamebots_brain.learn.model import LitePolicy
        from minigamebots_brain.policies import create

        from test_learn import pig_obs

        torch.manual_seed(0)
        with tempfile.TemporaryDirectory() as profile:
            checkpoint.save(profile, LitePolicy(), {"step": 7}, best=True)
            p = create("practice", {"profile": profile, "objective": "hunt", "resources": "balanced", "device": "cpu"})
            p.learner.rollout_len = 64
            p.learner.epochs = 1
            p.SAVE_EVERY_UPDATES = 1
            frame = np.zeros((128, 128, 3), dtype=np.uint8)
            for t in range(140):
                obs = pig_obs()
                obs["tick"] = t
                obs["_events"] = [ev("kill", target="minecraft:pig")] if t % 50 == 49 else []
                if t == 100:
                    obs["_events"].append(ev("death"))
                a = p.act(obs, frame)
                self.assertIsNotNone(a.to_message(t))
                if t == 64:
                    p.learner.thread.join(timeout=120)
            p.learner.thread.join(timeout=120)
            p.act(dict(pig_obs(), tick=200, _events=[]), frame)
            st = p.status()
            self.assertGreaterEqual(st["updates"], 1, st)
            self.assertEqual(st["episodes"], 1)  # The death; free-play tries close after a minute.
            self.assertNotIn("last_error", st)
            info = checkpoint.read_info(profile)
            self.assertEqual(info["phase"], "practice")
            self.assertEqual(info["step"], 7)  # Imitation progress is kept.
            model, _ = checkpoint.load(profile, "latest", "cpu")  # Loads with its value head.


if __name__ == "__main__":
    unittest.main()
