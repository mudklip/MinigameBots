import base64
import math
import unittest

import numpy as np

from minigamebots_brain.policies.guard import GuardPolicy, my_index, slot_offset


class FormationTest(unittest.TestCase):
    def test_ring_spots_are_distinct_and_at_distance(self):
        spots = [slot_offset("ring", i, 4, 3.0, 0.0) for i in range(4)]
        for s in spots:
            self.assertAlmostEqual(math.hypot(*s), 3.0, places=5)
        self.assertEqual(len({(round(x, 3), round(z, 3)) for x, z in spots}), 4)
        # Spot 0 is in front of a player facing south (+z).
        self.assertAlmostEqual(spots[0][1], 3.0, places=5)

    def test_every_guard_gets_its_own_spot_without_talking(self):
        names = ["Cid", "Bob", "Ann"]
        idx = {me: my_index(names, me)[0] for me in names}
        self.assertEqual(sorted(idx.values()), [0, 1, 2])
        self.assertEqual(idx["Ann"], 0)

    def test_formation_turns_with_the_protected_player(self):
        a = slot_offset("line", 0, 1, 3.0, 0.0)    # facing south: behind is north (-z)
        b = slot_offset("line", 0, 1, 3.0, 90.0)   # facing west: behind is east (+x)
        self.assertAlmostEqual(a[1], -3.0, places=5)
        self.assertAlmostEqual(b[0], 3.0, places=5)

    def test_close_gaps_with_the_guards_it_can_see(self):
        self.assertEqual(my_index(["Ann", "Bob", "Cid"], "Cid", visible={"bob"}), (1, 2))  # Ann isn't here.

    def test_all_shapes_work(self):
        for shape in ("ring", "line", "front", "wedge", "square"):
            spots = {tuple(round(v, 2) for v in slot_offset(shape, i, 5, 3.0, 30.0)) for i in range(5)}
            self.assertEqual(len(spots), 5, shape)


def grid(hole_at=None):
    g = np.full((11, 11, 11), 1, dtype=np.uint8)
    g[:5, :, :] = 2  # Ground under the feet.
    if hole_at:
        x, z = hole_at
        g[3:5, z + 5, x + 5] = 1
    return {"categories": base64.b64encode(g.tobytes()).decode()}


def obs(steve_dx=0.0, steve_dz=6.0, extra=(), blocks=None):
    ents = [{"id": 1, "name": "Steve", "player": True, "dx": steve_dx, "dy": 0, "dz": steve_dz, "yaw": 0.0, "protectedTarget": True}]
    ents += list(extra)
    return {"self": {"x": 0.5, "y": 64, "z": 0.5, "yaw": 0.0, "pitch": 0.0, "onGround": True}, "entities": ents,
            "blocks": blocks or grid()}


class GuardActTest(unittest.TestCase):
    def test_walks_to_its_spot(self):
        g = GuardPolicy(protectee="Steve", distance=3, shape="line", names=["Ann"], me="Ann")
        a = g.act(obs(), None)
        self.assertIn("forward", a.keys)  # Spot is 3 behind Steve (z 3): straight ahead.

    def test_never_steps_into_a_hole(self):
        g = GuardPolicy(protectee="Steve", distance=3, shape="line", names=["Ann"], me="Ann")
        a = g.act(obs(blocks=grid(hole_at=(0, 1))), None)
        self.assertNotIn("forward", a.keys)

    def test_only_the_nearest_guard_engages(self):
        bad = {"id": 9, "name": "Griefer", "player": True, "dx": 1.0, "dy": 0, "dz": 1.0, "hitProtected": 5}
        far_guard = GuardPolicy(protectee="Steve", names=["Ann", "Bob"], me="Ann")
        bob_near = {"id": 2, "name": "Bob", "player": True, "dx": 1.2, "dy": 0, "dz": 1.0}
        far_guard.act(obs(extra=[dict(bad, dx=8.0, dz=8.0), bob_near]), None)
        self.assertIsNone(far_guard.engaged)  # Bob is closer to the attacker: Ann holds.
        near_guard = GuardPolicy(protectee="Steve", names=["Ann", "Bob"], me="Ann")
        near_guard.act(obs(extra=[bad]), None)
        self.assertEqual(near_guard.engaged, 9)
        standby = GuardPolicy(protectee="Steve", names=["Ann"], me="Ann", mode="standby")
        standby.act(obs(extra=[bad]), None)
        self.assertIsNone(standby.engaged)


if __name__ == "__main__":
    unittest.main()
