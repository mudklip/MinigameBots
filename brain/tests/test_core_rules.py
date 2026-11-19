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
