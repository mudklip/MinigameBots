import os
import socket
import struct
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from minigamebots_brain import policies  # noqa: E402
from minigamebots_brain.actions import Action  # noqa: E402
from minigamebots_brain.frames import FrameRing  # noqa: E402
from minigamebots_brain.policies.follow import FollowPolicy, wrap_degrees  # noqa: E402
from minigamebots_brain.protocol import Channel, encode  # noqa: E402


def self_obs(yaw=0.0, pitch=0.0, entities=()):
    return {"self": {"yaw": yaw, "pitch": pitch}, "entities": list(entities)}


def pig(dx, dy, dz, visible=True):
    return {"type": "minecraft:pig", "dx": dx, "dy": dy, "dz": dz, "visible": visible}


class ProtocolTest(unittest.TestCase):
    def test_round_trip_over_socket(self):
        a, b = socket.socketpair()
        sender, receiver = Channel(a), Channel(b)
        big = {"type": "obs", "blob": "x" * 200_000}
        threading.Thread(target=lambda: (sender.send({"type": "hello"}), sender.send(big))).start()
        self.assertEqual({"type": "hello"}, receiver.receive())
        self.assertEqual(big, receiver.receive())
        a.close()
        b.close()

    def test_length_prefix(self):
        self.assertEqual(struct.pack(">I", 2) + b"{}", encode({}))


class ActionTest(unittest.TestCase):
    def test_message(self):
        msg = Action(keys={"jump", "forward"}, attacks=1, yaw=2.5).to_message(7)
        self.assertEqual(["forward", "jump"], msg["keys"])
        self.assertEqual(7, msg["tick"])

    def test_rejects_unknown_keys(self):
        with self.assertRaises(ValueError):
            Action(keys={"fly"}).to_message(0)


class FrameRingTest(unittest.TestCase):
    def test_reads_matching_tick_only(self):
        size, slots = 4, 2
        slot_bytes = 16 + size * size * 3
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(struct.pack("<4s4i3i", b"MGBF", 1, slots, size, slot_bytes, 0, 0, 0))
            for i in range(slots):
                f.write(struct.pack("<iqi", 2, 100 + i, size))
                f.write(bytes([i * 10]) * (size * size * 3))
            path = f.name
        ring = FrameRing(path)
        frame = ring.read(1, 101)
        self.assertIsNotNone(frame)
        self.assertEqual(10, bytes(frame.tobytes() if hasattr(frame, "tobytes") else frame)[0])
        self.assertIsNone(ring.read(1, 999), "stale frame must not be returned")
        self.assertIsNone(ring.read(5, 101))
        ring.close()
        os.unlink(path)


class PolicyTest(unittest.TestCase):
    def test_all_policies_produce_valid_actions(self):
        for name in ["idle", "random", "spin", "follow"]:
            p = policies.create(name)
            for _ in range(50):
                p.act(self_obs(entities=[pig(1, 0, 3)]), None).to_message(0)

    def test_unknown_policy(self):
        with self.assertRaises(ValueError):
            policies.create("nope")

    def test_follow_turns_towards_target(self):
        p = FollowPolicy()
        # Pig straight ahead (+Z) while facing +Z: no turn, walk forward.
        a = p.act(self_obs(yaw=0, entities=[pig(0, 0, 6)]), None)
        self.assertAlmostEqual(0.0, a.yaw, places=3)
        self.assertIn("forward", a.keys)
        # Pig to the east (+X): Minecraft yaw for +X is -90, so turn left (negative yaw).
        a = p.act(self_obs(yaw=0, entities=[pig(6, 0, 0)]), None)
        self.assertLess(a.yaw, 0)
        # Pig to the west (-X): turn right.
        a = p.act(self_obs(yaw=0, entities=[pig(-6, 0, 0)]), None)
        self.assertGreater(a.yaw, 0)

    def test_follow_attacks_when_close_and_aimed(self):
        p = FollowPolicy()
        a = p.act(self_obs(yaw=0, pitch=20, entities=[pig(0, -1.6, 2.0)]), None)
        self.assertIn("attack", a.keys)
        b = p.act(self_obs(yaw=0, pitch=20, entities=[pig(0, -1.6, 2.0)]), None)
        self.assertNotIn("attack", b.keys, "cooldown between hits")

    def test_follow_ignores_hidden_targets(self):
        p = FollowPolicy()
        a = p.act(self_obs(entities=[pig(0, 0, 3, visible=False)]), None)
        self.assertEqual(set(), a.keys)

    def test_follow_equips_a_weapon_first(self):
        p = FollowPolicy()
        obs = self_obs(entities=[pig(0, 0, 3)])
        obs["inventory"] = [{"slot": 4, "id": "minecraft:iron_sword"}]
        obs["selectedSlot"] = 0
        self.assertEqual({"hotbar_5"}, p.act(obs, None).keys)
        obs["selectedSlot"] = 4
        self.assertNotIn("hotbar_5", p.act(obs, None).keys)

    def test_follow_aim_settles_despite_reaction_delay(self):
        """Simulate a camera that applies each turn 4 ticks late: the aim must settle, not swing."""
        for delay in (2, 4, 6):
            p = FollowPolicy(attack=False)
            yaw = -90.0
            queue = [0.0] * delay
            worst_overshoot = 0.0
            for tick in range(400):
                # Pig far away straight ahead along +Z: target yaw 0.
                a = p.act(self_obs(yaw=yaw, pitch=0, entities=[pig(0, 0, 30)]), None)
                queue.append(a.yaw)
                yaw += queue.pop(0)
                worst_overshoot = max(worst_overshoot, yaw)
                if tick == 119:
                    self.assertLess(abs(yaw), 1.5, f"too slow with delay {delay}: {yaw}")
            self.assertLess(abs(yaw), 0.1, f"didn't settle with delay {delay}: {yaw}")
            self.assertLess(worst_overshoot, 15.0, f"swung past the target with delay {delay}")

    def test_wrap(self):
        self.assertAlmostEqual(-170, wrap_degrees(190))
        self.assertAlmostEqual(170, wrap_degrees(-190))


if __name__ == "__main__":
    unittest.main()
