import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain.actions import Action  # noqa: E402
from minigamebots_brain.core.rules import RuleFinder  # noqa: E402
from minigamebots_brain.rl.objective import RewardEngine  # noqa: E402


def obs(t, held="minecraft:snowball"):
    return {"tick": t, "self": {}, "inventory": [{"slot": 0, "id": held}], "selectedSlot": 0,
            "text": {"sidebarTitle": "§eSnowball Fight"}}


def play_round(f, t0, i_win=True):
    """One round: I throw, my snowball hits, someone is out (or I get hit and I'm out)."""
    t = t0
    f.observe(obs(t), [], Action(keys={"use"}))
    t += 10
    if i_win:
        f.observe(obs(t), [{"type": "i_hit", "data": {"with": "minecraft:snowball"}}])
        t += 2
        f.observe(obs(t), [{"type": "kill", "data": {"target": "minecraft:player"}}])
    else:
        f.observe(obs(t), [{"type": "hurt_by", "data": {"with": "minecraft:snowball"}}])
        t += 2
        f.observe(obs(t), [{"type": "death", "data": {}}])
    for k in range(200):  # Quiet time between rounds.
        f.observe(obs(t + 1 + k), [])
    return t + 201


class RuleFinderTest(unittest.TestCase):
    def test_finds_snowball_rules_in_a_few_rounds(self):
        f = RuleFinder("Me")
        t = 0
        for win in (True, False, True, False):
            t = play_round(f, t, win)
        found = {(r["cause"], r["effect"]) for r in f.rules()}
        self.assertIn(("i_hit:snowball", "i_killed"), found)
        self.assertIn(("hurt_by:snowball", "i_died"), found)
        self.assertIn(("threw:snowball", "i_killed"), found)
        self.assertTrue(any("snowball" in d for d in f.describe()))
        self.assertIn("bow", f.borrow())  # Throwing: borrows projectile aiming.

    def test_coincidence_is_not_a_rule(self):
        f = RuleFinder("Me")
        for t in range(0, 4000):
            evs = [{"type": "block_broken", "data": {"block": "minecraft:dirt"}}] if t % 50 == 0 else []
            if t % 900 == 450:
                evs.append({"type": "death", "data": {}})
            f.observe(obs(t, "minecraft:stone"), evs)
        self.assertFalse(any(r["cause"].startswith("broke") for r in f.rules()))

    def test_objective_rewards_good_causes_and_is_valid(self):
        f = RuleFinder("Me")
        t = 0
        for win in (True, False, True, False):
            t = play_round(f, t, win)
        o = f.objective()
        by = {r["note"]: r["value"] for r in o["rules"]}
        self.assertGreater(by["rule: i_hit snowball -> i_killed"], 0)
        self.assertLess(by["rule: hurt_by snowball -> i_died"], 0)
        e = RewardEngine(o)
        r = e.step(20, [{"type": "i_hit", "data": {"with": "minecraft:snowball"}}])
        self.assertGreater(r.reward, 0)

    def test_remembered_per_game(self):
        f = RuleFinder("Me")
        t = 0
        for win in (True, True):
            t = play_round(f, t, win)
        key = RuleFinder.game_key(obs(0))
        self.assertEqual(key, "snowball_fight")
        with tempfile.TemporaryDirectory() as tmp:
            f.save(tmp, key)
            g = RuleFinder.load(tmp, key, "Me")
            self.assertEqual(g.rules(), f.rules())

    def test_win_titles_and_my_score(self):
        f = RuleFinder("Me")
        _, eff = f.causes_and_effects(obs(0), [{"type": "title", "data": {"text": "§6You Win!"}},
                                               {"type": "scoreboard_changed", "data": {"name": "Me", "delta": 1}}])
        self.assertEqual(eff, ["won", "score:me:+"])


if __name__ == "__main__":
    unittest.main()


class ExamTest(unittest.TestCase):
    def test_rounds_until_it_plays_well(self):
        f = RuleFinder("Me")
        for won in (0, 0, 1, 0, 1, 1, 0, 1):
            f.round_over(bool(won))
        self.assertEqual(f.learned_after, 6)  # Rounds 2..6: 3 of 5 won.
        self.assertEqual(f.summary()["rounds"], 8)


class PredictTest(unittest.TestCase):
    def test_targets_look_ahead_and_mask_the_unknown(self):
        import numpy as np

        from minigamebots_brain.core import predict

        chunk = {t: ({"events": []}, None) for t in range(0, 200)}
        chunk[30] = ({"events": [{"type": "damage_taken", "data": {"amount": 2}}]}, None)
        tg, mask = predict.targets(chunk, 0, 40)
        k = predict.NAMES.index("hurt")
        self.assertEqual(tg[15, k], 1.0)  # 15 ticks before: within 1 s.
        self.assertEqual(tg[5, k], 0.0)  # 25 ticks before: too far.
        self.assertEqual(tg[35, k], 0.0)
        tg, mask = predict.targets(chunk, 150, 40)
        self.assertEqual(mask[39, predict.NAMES.index("won")], 0.0)  # The future past the chunk is unknown.
        self.assertTrue(np.all(mask[:, k][:20] == 1.0))

    def test_model_predicts_and_loss_trains(self):
        try:
            import torch
        except ImportError:
            self.skipTest("needs PyTorch")
        from minigamebots_brain.core import predict
        from minigamebots_brain.learn.model import LitePolicy
        from minigamebots_brain.learn import train

        m = LitePolicy()
        self.assertEqual(m.head_predict.out_features, predict.SIZE)
        logits = {"predict": torch.zeros(2, 70, predict.SIZE, requires_grad=True)}
        for k, n in (("keys", 22), ("attacks", 4), ("uses", 4), ("yaw", 31), ("pitch", 31)):
            logits[k] = torch.zeros(2, 70, n)
        batch = {"weight": torch.ones(2, 70), "camera_ok": torch.ones(2, 70), "target_keys": torch.zeros(2, 70, 22),
                 "target_attacks": torch.zeros(2, 70, dtype=torch.long), "target_uses": torch.zeros(2, 70, dtype=torch.long),
                 "target_yaw": torch.zeros(2, 70, dtype=torch.long), "target_pitch": torch.zeros(2, 70, dtype=torch.long),
                 "target_predict": torch.ones(2, 70, predict.SIZE), "predict_mask": torch.ones(2, 70, predict.SIZE)}
        total, stats = train.loss_fn(logits, batch)
        self.assertIn("predict_loss", stats)
        total.backward()
        self.assertLess(float(logits["predict"].grad.sum()), 0)  # Pushes towards "it will happen".
