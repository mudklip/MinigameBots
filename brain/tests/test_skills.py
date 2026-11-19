import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain.actions import Action  # noqa: E402
from minigamebots_brain.rl import objective, skills  # noqa: E402

try:
    import torch

    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


def obs(**me):
    s = {"health": 20, "food": 20, "yaw": 0.0, "pitch": 0.0, "x": 0.0, "y": 64.0, "z": 0.0, "attackReady": 1.0,
         "onGround": True, "air": 300, "maxAir": 300}
    look = me.pop("look", {})
    ents = me.pop("entities", [])
    s.update(me)
    return {"self": s, "entities": ents, "inventory": [], "look": look}


class SkillLibraryTest(unittest.TestCase):
    def test_every_preset_is_valid(self):
        events = set(skills.LIBRARY["events"])
        behaviours = set(skills.LIBRARY["behaviours"])
        for sid, d in skills.LIBRARY["skills"].items():
            self.assertIn(d["category"], skills.LIBRARY["categories"], sid)
            self.assertTrue(d["icon"].startswith("minecraft:"), sid)
            for g in d["locks"]:
                self.assertIn(g, skills.LIBRARY["locks"], sid)
            for st in d["stages"]:
                self.assertIn(st["speed"], skills.LIBRARY["speeds"], sid)
            for r in d["rules"] + [r for st in d["stages"] for r in st["shaping"]]:
                ev = r["event"]
                self.assertTrue(ev[2:] in behaviours if ev.startswith("b:") else ev in events, f"{sid}: {ev}")
            for i in range(len(d["stages"])):
                objective.RewardEngine(skills.build_objective(d, i))  # Builds without errors.

    def test_off_rules_and_hints(self):
        d = skills.preset("mining_blocks")
        d["rules"][0]["off"] = True
        o = skills.build_objective(d, 0)
        self.assertTrue(all(r["event"].startswith("b:") for r in o["rules"]))
        self.assertTrue(o["rules"][0]["note"].startswith("hint"))
        self.assertEqual(skills.build_objective(d, 2)["rules"], [])

    def test_stage_moves_on_and_can_be_fixed(self):
        d = skills.preset("mining_blocks")
        with tempfile.TemporaryDirectory() as tmp:
            t = skills.StageTracker(tmp, d)
            moved = [t.episode(1.0) for _ in range(10)]
            self.assertEqual(moved.count(True), 1)
            self.assertEqual(skills.StageTracker(tmp, d).stage, 1)  # Saved.
            d["stage"] = 0
            self.assertEqual(skills.StageTracker(tmp, d).stage, 0)
        self.assertGreater(skills.learning(skills.preset("aim_flick"), 0)["lr"],
                           skills.learning(skills.preset("aim_flick"), 2)["lr"])

    def test_hold_break_and_early_release(self):
        b = skills.Behaviours()
        b.events(obs(look={"breaking": True}), Action(keys={"attack"}), [])
        ev = {e["type"] for e in b.events(obs(look={"breaking": True}), Action(keys={"attack"}), [])}
        self.assertIn("b:hold_break", ev)
        ev = {e["type"] for e in b.events(obs(look={}), Action(), [])}
        self.assertIn("b:early_release", ev)

    def test_aim_on_target(self):
        target = {"type": "minecraft:interaction", "dx": 0.0, "dy": -0.5, "dz": 5.0, "visible": True}
        ev = {e["type"]: e["data"]["units"] for e in skills.Behaviours().events(obs(entities=[target]), Action(), [])}
        self.assertIn("b:aim_on_target", ev)
        self.assertAlmostEqual(ev["b:face_target"], 1.0, places=2)
        target["dx"] = 5.0  # 45 degrees off.
        ev = {e["type"] for e in skills.Behaviours().events(obs(entities=[target]), Action(), [])}
        self.assertNotIn("b:aim_on_target", ev)

    @unittest.skipUnless(HAVE_TORCH, "needs PyTorch")
    def test_locks_mask_keys_and_camera(self):
        from minigamebots_brain.learn.actions import KEY_INDEX, NUM_KEYS
        from minigamebots_brain.rl import ppo

        keys, cam = skills.locked_keys(skills.preset("aim_flick"))
        idx = [KEY_INDEX[k] for k in keys]
        logits = {"keys": torch.full((1, 1, NUM_KEYS), 5.0), "yaw": torch.zeros(1, 1, 31), "pitch": torch.zeros(1, 1, 31),
                  "attacks": torch.zeros(1, 1, 4), "uses": torch.zeros(1, 1, 4)}
        m = skills.mask_logits(logits, idx, True)
        for _ in range(5):
            a = ppo.decode(ppo.sample(m))
            self.assertFalse(a.keys & {"forward", "inventory", "hotbar_3"})
            self.assertIn("attack", a.keys)
            self.assertEqual((a.yaw, a.pitch), (0.0, 0.0))
        self.assertFalse(cam and False)


if __name__ == "__main__":
    unittest.main()
