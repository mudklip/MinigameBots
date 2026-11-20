import base64
import math
import unittest

import numpy as np

from minigamebots_brain import invent
from minigamebots_brain.policies import create

R, S = 5, 11


class World:
    """A tiny fake world: ground below y=64, the player standing still; 'use' places the block the
    builder is aiming to build, 'attack' digs (ticks of holding)."""

    def __init__(self):
        self.solid = set()
        self.me = {"x": 3.5, "y": 64.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0, "health": 20}
        self.selected = 0
        self.digging = {}
        self.placed = []
        self.vy = 0.0

    def ray(self):
        yaw, pitch = math.radians(self.me["yaw"]), math.radians(self.me["pitch"])
        d = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
        p = [self.me["x"], self.me["y"] + 1.62, self.me["z"]]
        prev = None
        for _ in range(90):
            p = [p[i] + d[i] * 0.05 for i in range(3)]
            c = (math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))
            if self.is_solid(c) and not self.thin(c):
                return prev
            prev = c
        return None

    def is_solid(self, p):
        return p in self.solid or (p[1] < 64 and ("air", p) not in self.solid)

    THIN = ("pressure_plate", "torch")

    def thin(self, p):
        return p in getattr(self, "thin_cells", set())

    def obs(self, inv):
        feet = (math.floor(self.me["x"]), math.floor(self.me["y"]), math.floor(self.me["z"]))
        g = np.zeros((S, S, S), dtype=np.uint8)
        for dy in range(-R, R + 1):
            for dz in range(-R, R + 1):
                for dx in range(-R, R + 1):
                    p = (feet[0] + dx, feet[1] + dy, feet[2] + dz)
                    g[dy + R][dz + R][dx + R] = (3 if self.thin(p) else 2) if self.is_solid(p) else 1
        return {"self": dict(self.me), "inventory": inv, "selectedSlot": self.selected,
                "blocks": {"categories": base64.b64encode(g.tobytes()).decode()}}

    def apply(self, a, policy):
        self.me["yaw"] += a.yaw
        self.me["pitch"] += a.pitch
        # Jumping (simple game physics).
        if "jump" in a.keys and self.me["y"] <= 64.0001:
            self.vy = 0.42
        self.me["y"] += self.vy
        self.vy = (self.vy - 0.08) * 0.98
        if self.me["y"] <= 64:
            self.me["y"], self.vy = 64.0, 0.0
        self.me["onGround"] = self.me["y"] <= 64.0001
        sp = 0.2 if "forward" in a.keys else -0.2 if "back" in a.keys else 0.0
        self.me["x"] += -math.sin(math.radians(self.me["yaw"])) * sp
        self.me["z"] += math.cos(math.radians(self.me["yaw"])) * sp
        for k in a.keys:
            if k.startswith("hotbar_"):
                self.selected = int(k[7:]) - 1
        step = policy.current
        if step is None:
            return
        if "use" in a.keys and not step["dig"]:
            # Like the game: the block goes against the face the look ray hits first.
            hit = self.ray()
            if hit is not None:
                self.solid.add(hit)
                self.placed.append(hit)
                if any(t in step["block"] for t in self.THIN) and hit == step["pos"]:
                    self.thin_cells = getattr(self, "thin_cells", set()) | {hit}
        if "attack" in a.keys and step["dig"]:
            self.digging[step["pos"]] = self.digging.get(step["pos"], 0) + 1
            if self.digging[step["pos"]] >= 3:
                self.solid.discard(step["pos"])
                self.solid.add(("air", step["pos"]))


class BuildTest(unittest.TestCase):
    def test_builds_a_shelter_by_hand(self):
        d = invent.shelter_from_genes({"w": 3, "l": 3, "h": 2, "mat": "minecraft:cobblestone", "roof": False})
        w = World()
        p = create("build", {"design": d, "anchor": [0, 64, 0]})
        inv = [{"slot": 2, "id": "minecraft:cobblestone", "count": 64}]
        for _ in range(3000):
            a = p.act(w.obs(inv), None)
            w.apply(a, p)
            if p.status()["finished"]:
                break
        st = p.status()
        self.assertTrue(st["finished"])
        self.assertEqual(st["placed"], st["total"])
        self.assertEqual(w.selected, 2)  # Picked the cobblestone on the hotbar itself.
        want = {tuple(b[:3]) for b in d["blocks"]}
        got = {(x, y - 64, z) for x, y, z in w.placed}
        self.assertEqual(got, want)  # Every block where it belongs, and nothing else.

    def test_builds_a_roofed_shelter_going_in_through_the_door(self):
        d = invent.shelter_from_genes({"w": 4, "l": 4, "h": 2, "mat": "minecraft:cobblestone", "roof": True,
                                       "roof_mat": "minecraft:cobblestone", "door": "doorway"})
        w = World()
        p = create("build", {"design": d, "anchor": [0, 64, 0]})
        inv = [{"slot": 0, "id": "minecraft:cobblestone", "count": 64}]
        for _ in range(8000):
            a = p.act(w.obs(inv), None)
            w.apply(a, p)
            if p.status()["finished"]:
                break
        want = {tuple(b[:3]) for b in d["blocks"] if d["palette"][b[3]] != "minecraft:air"}
        got = {(x, y - 64, z) for x, y, z in w.placed}
        self.assertEqual(got, want)  # The whole roof too, nothing extra.

    def test_digs_and_reports_missing_items(self):
        d = invent.trap_from_genes({"at": 0, "pit": {"w": 1, "depth": 2}, "plate": "minecraft:stone_pressure_plate", "tnt": 1})
        w = World()
        p = create("build", {"design": d, "anchor": [0, 64, 0]})
        for _ in range(3000):
            a = p.act(w.obs([]), None)
            w.apply(a, p)
            if p.status()["finished"] or (p.current is None and p.tick > 50):
                break
        self.assertIn("tnt", p.status()["missing"])


if __name__ == "__main__":
    unittest.main()


class FacingAndFillTest(unittest.TestCase):
    def test_minecraft_facing_rules(self):
        from minigamebots_brain.policies.build import placement_ok

        south = (0, -0.3, 1)  # Looking south, a little down.
        self.assertTrue(placement_ok("minecraft:dispenser[facing=north]", south, (0, 1, 0)))  # Faces the player.
        self.assertFalse(placement_ok("minecraft:dispenser[facing=south]", south, (0, 1, 0)))
        self.assertTrue(placement_ok("minecraft:observer[facing=south]", south, (0, 1, 0)))  # Faces away.
        self.assertTrue(placement_ok("minecraft:oak_door[facing=south,half=lower]", south, (0, 1, 0)))
        self.assertTrue(placement_ok("minecraft:chest[facing=north]", south, (0, 1, 0)))
        self.assertTrue(placement_ok("minecraft:dispenser[facing=up]", (0, -0.9, 0.2), (0, 1, 0)))  # Looking down.
        self.assertTrue(placement_ok("minecraft:stone", south, (0, 1, 0)))

    def test_trap_dispensers_face_the_plate_and_get_filled(self):
        from minigamebots_brain.policies.build import placement_ok

        d = invent.trap_from_genes({"at": 0, "plate": "minecraft:stone_pressure_plate", "dispensers": 2,
                                    "ammo": "minecraft:arrow"})
        w = World()
        w.containers = {}
        w.gui = None
        w.cursor = [200.0, 200.0]
        p = create("build", {"design": d, "anchor": [0, 64, 0]})
        inv = [{"slot": 0, "id": "minecraft:dispenser", "count": 4}, {"slot": 1, "id": "minecraft:stone_pressure_plate", "count": 2},
               {"slot": 2, "id": "minecraft:arrow", "count": 64}]
        looks = []
        for _ in range(6000):
            o = w.obs(inv)
            if w.gui is not None:
                o["gui"] = {"screen": "DispenserScreen", "cursorX": w.cursor[0], "cursorY": w.cursor[1],
                            "slotPos": [[0, 60, 40, 0], [9, 100, 120, 1], [36, 120, 140, 1]],
                            "slots": ([{"slot": 0, "id": "minecraft:arrow", "count": 16}] if w.containers.get(w.gui) else [])
                            + [{"slot": 38, "id": "minecraft:arrow", "count": 64}, {"slot": 36, "id": "minecraft:arrow", "count": 64}]}
            a = p.act(o, None)
            step = p.current
            if w.gui is not None:
                w.cursor[0] += a.yaw * 3.0  # This "PC" moves the cursor 3 px per degree.
                w.cursor[1] += a.pitch * 3.0
                if {"sneak", "attack"} <= a.keys and abs(w.cursor[0] - 120) <= 2 and abs(w.cursor[1] - 140) <= 2:
                    w.containers[w.gui] = True
                if "inventory" in a.keys:
                    w.gui = None
                continue
            if step is not None and step.get("fill") and "use" in a.keys:
                w.gui = step["pos"]
                continue
            if step is not None and "use" in a.keys and "dispenser" in step["block"]:
                yaw, pitch = math.radians(w.me["yaw"]), math.radians(w.me["pitch"])
                looks.append((step["block"], (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))))
            w.apply(a, p)
            if p.status()["finished"]:
                break
        self.assertTrue(looks)
        for block, look in looks:
            self.assertTrue(placement_ok(block, look, (0, 1, 0)), block)  # Every dispenser faces the plate.
        self.assertEqual(len(w.containers), 2)  # Both filled, through the container screen.
        self.assertTrue(p.status()["finished"])
