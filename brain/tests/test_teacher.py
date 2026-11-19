import unittest

from minigamebots_brain.policies import create


def obs(dy=-1.62, dz=2.5, on_ground=True, vy=0.0, ready=1.0, blocking=False, slot=0):
    return {"self": {"yaw": 0.0, "pitch": 12.0, "onGround": on_ground, "vy": vy, "attackReady": ready},
            "selectedSlot": slot,
            "inventory": [{"slot": 0, "id": "minecraft:diamond_sword"}, {"slot": 1, "id": "minecraft:diamond_axe"},
                          {"slot": 40, "id": "minecraft:shield"}],
            "entities": [{"type": "minecraft:mannequin", "dx": 0.0, "dy": dy, "dz": dz, "visible": True,
                          "blocking": blocking}]}


class TeacherTest(unittest.TestCase):
    def test_crit_jumps_then_hits_falling(self):
        t = create("teacher", {"style": "crits"})
        a = t.act(obs(), None)
        self.assertIn("jump", a.keys)
        self.assertNotIn("attack", a.keys)
        a = t.act(obs(on_ground=False, vy=-0.2), None)
        self.assertIn("attack", a.keys)

    def test_wtap_lets_go_of_forward_after_a_hit(self):
        t = create("teacher", {"style": "wtap"})
        a = t.act(obs(dz=2.4), None)
        self.assertIn("attack", a.keys)
        a = t.act(obs(dz=3.5, ready=0.2), None)
        self.assertNotIn("forward", a.keys)

    def test_axe_for_shields_and_shield_while_recharging(self):
        t = create("teacher", {})
        self.assertEqual(t.act(obs(blocking=True), None).keys, {"hotbar_2"})
        self.assertIn("use", create("teacher", {}).act(obs(ready=0.3), None).keys)


if __name__ == "__main__":
    unittest.main()
