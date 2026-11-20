"""Creativity: inventing traps and shelters by combining and changing known parts, testing each
idea for real in a Training Grounds sandbox, and keeping the best ones.

A *design* is a small block plan, not hand movements:
    {"id", "kind": "trap"|"shelter", "name", "parts": [...], "palette": [block ids],
     "blocks": [[dx, dy, dz, palette index], ...],   # dy 0 = ground level, below = dug into ground
     "path": [[x1, z1], [x2, z2]],                   # traps: where the victim walks
     "inside": [dx, dy, dz],                         # shelters: a spot inside
     "items": {"dx,dy,dz": [item, count]}}           # dispensers' contents
The sandbox builds it on a stone block (ground from dy -6 to -1), runs the game for a few seconds
(a dummy walks the path; for shelters it checks they're closed in and lit) and reports what
happened. Scoring happens here: traps by harm and catching, how hidden and how cheap; shelters by
being closed in, lit, roomy enough and cheap. The best of each kind stay in a hall of fame per
quality (deadliest, most hidden, cheapest...), so it keeps many different good ideas; failed
ideas are remembered so they aren't tried again. Saved in <profile>/inventions.json.

CLI (used by the mod):
    python -m minigamebots_brain invent propose --profile DIR --kind trap --n 4
    python -m minigamebots_brain invent record --profile DIR --results FILE
"""

import argparse
import copy
import hashlib
import json
import os
import random
import sys
import time

# Rough cost of a block (how hard it is to get in survival).
COST = {"minecraft:tnt": 5, "minecraft:dispenser": 4, "minecraft:arrow": 0.2, "minecraft:fire_charge": 1, "minecraft:lava": 3, "minecraft:cobweb": 3,
        "minecraft:magma_block": 2, "minecraft:stone_pressure_plate": 1, "minecraft:oak_pressure_plate": 0.5,
        "minecraft:oak_door": 1, "minecraft:torch": 0.3, "minecraft:glass": 1, "minecraft:oak_planks": 0.3,
        "minecraft:cobblestone": 0.2, "minecraft:dirt": 0.1, "minecraft:stone_bricks": 0.4, "minecraft:air": 0,
        "minecraft:oak_trapdoor": 1, "minecraft:white_carpet": 0.5, "minecraft:smooth_stone": 0.3}
# Blocks that give a trap away if they can be seen.
SUSPICIOUS = {"minecraft:tnt": 3, "minecraft:dispenser": 2, "minecraft:lava": 3, "minecraft:cobweb": 1,
              "minecraft:magma_block": 1, "minecraft:oak_pressure_plate": 0.6, "minecraft:stone_pressure_plate": 0.3,
              "air_hole": 1.5}
FLOOR = "minecraft:smooth_stone"  # The sandbox ground (dy -1 and below).
SIZE = 15


class Plan:
    """Builds a design block by block (later blocks win)."""

    def __init__(self, kind: str):
        self.kind = kind
        self.blocks: dict = {}
        self.items: dict = {}
        self.parts: list = []

    def put(self, x, y, z, block):
        if max(abs(x), abs(z)) <= SIZE // 2 and -6 <= y <= 8:
            self.blocks[(x, y, z)] = block

    def design(self, extra: dict) -> dict:
        palette = sorted(set(self.blocks.values()))
        d = {"kind": self.kind, "parts": self.parts, "palette": palette,
             "blocks": [[x, y, z, palette.index(b)] for (x, y, z), b in sorted(self.blocks.items())],
             "items": {f"{x},{y},{z}": v for (x, y, z), v in self.items.items()}}
        d.update(extra)
        d["id"] = fingerprint(d)
        d["name"] = name_of(d)
        return d


def fingerprint(d: dict) -> str:
    core = json.dumps([d["kind"], d["palette"], d["blocks"], d.get("items")], sort_keys=True)
    return hashlib.sha1(core.encode()).hexdigest()[:12]


def name_of(d: dict) -> str:
    return " + ".join(p.split(":")[0].replace("_", " ") for p in d["parts"]) or d["kind"]


# --- the part library (traps) ---

def trap_from_genes(g: dict) -> dict:
    """A trap from its "genes": which parts, their sizes and materials, where they sit."""
    p = Plan("trap")
    # The victim walks along x from -6 to 6 at z = 0; the trigger is at x = g["at"].
    at = g["at"]
    floor = g.get("floor", FLOOR)
    if g.get("floor") and g["floor"] != FLOOR:
        for x in range(-3, 4):
            for z in range(-2, 3):
                p.put(at + x, -1, z, floor)
        p.parts.append("floor:" + floor.split(":")[1])
    if g.get("pit"):
        w, depth = g["pit"]["w"], g["pit"]["depth"]
        for x in range(at - w // 2, at - w // 2 + w):
            for z in range(-(w // 2), w - w // 2):
                for y in range(-depth, 0):
                    p.put(x, y, z, "minecraft:air")
                if g["pit"].get("lava"):
                    p.put(x, -depth, z, "minecraft:lava")
        p.parts.append(f"pit:{w}x{depth}" + (" lava" if g["pit"].get("lava") else ""))
    if g.get("plate"):
        plate = g["plate"]
        p.put(at, 0, 0, plate)
        p.parts.append("plate:" + plate.split(":")[1].replace("_pressure_plate", ""))
        if g.get("tnt"):
            # TNT right under the plate (a plate powers the block under it), more TNT around it.
            p.put(at, -1, 0, "minecraft:tnt")
            for i, (dx, dz) in enumerate(((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1))[:g["tnt"] - 1]):
                p.put(at + dx, -2, dz, "minecraft:tnt")
                p.put(at, -2, 0, "minecraft:tnt")
            p.parts.append(f"tnt:{g['tnt']}")
        if g.get("dispensers"):
            # Dispensers beside the plate, facing it, full of arrows.
            sides = ((0, 1, "north"), (0, -1, "south"), (1, 0, "west"), (-1, 0, "east"))[:g["dispensers"]]
            for dx, dz, facing in sides:
                if g.get("pit") and abs(dx) <= g["pit"]["w"] // 2 and abs(dz) <= g["pit"]["w"] // 2:
                    continue
                p.put(at + dx, 0, dz, f"minecraft:dispenser[facing={facing}]")
                ammo = g.get("ammo") or "minecraft:arrow"
                p.items[(at + dx, 0, dz)] = [ammo, 16 if ammo == "minecraft:arrow" else 4]
            p.parts.append(f"dispensers:{g['dispensers']}")
    if g.get("web"):
        for dx in range(g["web"]):
            p.put(at + 1 + dx, 0, 0, "minecraft:cobweb")
        p.parts.append(f"cobweb:{g['web']}")
    if g.get("magma"):
        for x in range(at - g["magma"] // 2, at - g["magma"] // 2 + g["magma"]):
            for z in (-1, 0, 1):
                if (x, 0, z) not in p.blocks and p.blocks.get((x, -1, z)) != "minecraft:air":
                    p.put(x, -1, z, "minecraft:magma_block")  # (Not over a pit.)
        p.parts.append(f"magma:{g['magma']}")
    if not p.parts:
        p.parts.append("nothing")
    return p.design({"path": [[-6, 0], [6, 0]], "genes": g})


def random_trap_genes(rng: random.Random) -> dict:
    g = {"at": rng.randint(-1, 2)}
    kind = rng.choice(["pit", "tnt", "arrows", "web_arrows", "web_tnt", "magma", "lava_pit", "mix"])
    if kind in ("pit", "lava_pit", "mix"):
        g["pit"] = {"w": rng.choice([1, 2, 3]), "depth": rng.randint(2, 5), "lava": kind == "lava_pit" or rng.random() < 0.2}
    if kind in ("tnt", "arrows", "web_arrows", "web_tnt", "mix"):
        g["plate"] = rng.choice(["minecraft:stone_pressure_plate", "minecraft:oak_pressure_plate"])
    if kind in ("tnt", "web_tnt", "mix") and "plate" in g:
        g["tnt"] = rng.randint(1, 4)
    if kind in ("arrows", "web_arrows"):
        g["dispensers"] = rng.randint(1, 3)
        g["ammo"] = rng.choice(["minecraft:arrow", "minecraft:arrow", "minecraft:fire_charge"])
    if kind in ("web_arrows", "web_tnt"):
        g["web"] = rng.randint(1, 2)
    if kind == "magma":
        g["magma"] = rng.randint(2, 5)
    if rng.random() < 0.3:
        g["floor"] = rng.choice(["minecraft:stone_bricks", "minecraft:oak_planks"])
    return g


def mutate_trap(g: dict, rng: random.Random) -> dict:
    g = copy.deepcopy(g)
    for _ in range(rng.randint(1, 3)):
        what = rng.choice(["at", "pit", "plate", "tnt", "dispensers", "web", "magma", "floor", "drop", "ammo"])
        if what == "at":
            g["at"] = max(-3, min(3, g["at"] + rng.choice([-1, 1])))
        elif what == "pit":
            pit = g.get("pit") or {"w": 1, "depth": 2, "lava": False}
            pit["w"] = max(1, min(3, pit["w"] + rng.choice([-1, 0, 1])))
            pit["depth"] = max(1, min(5, pit["depth"] + rng.choice([-1, 1])))
            if rng.random() < 0.25:
                pit["lava"] = not pit.get("lava")
            g["pit"] = pit
        elif what == "plate":
            g["plate"] = None if g.get("plate") and rng.random() < 0.3 else rng.choice(
                ["minecraft:stone_pressure_plate", "minecraft:oak_pressure_plate"])
        elif what == "tnt":
            g["tnt"] = max(0, min(5, int(g.get("tnt") or 0) + rng.choice([-1, 1])))
        elif what == "dispensers":
            g["dispensers"] = max(0, min(4, int(g.get("dispensers") or 0) + rng.choice([-1, 1])))
        elif what == "web":
            g["web"] = max(0, min(2, int(g.get("web") or 0) + rng.choice([-1, 1])))
        elif what == "magma":
            g["magma"] = max(0, min(5, int(g.get("magma") or 0) + rng.choice([-2, 2])))
        elif what == "ammo":
            g["ammo"] = rng.choice(["minecraft:arrow", "minecraft:fire_charge"])
        elif what == "floor":
            g["floor"] = rng.choice([None, "minecraft:stone_bricks", "minecraft:oak_planks"])
        elif what == "drop":
            for k in ("pit", "web", "magma", "dispensers", "tnt"):
                if g.get(k) and rng.random() < 0.3:
                    g[k] = None
    return {k: v for k, v in g.items() if v}


def cross_trap(a: dict, b: dict, rng: random.Random) -> dict:
    """Snap parts of two traps together."""
    g = {k: copy.deepcopy(rng.choice([a, b]).get(k)) for k in set(a) | set(b)}
    g["at"] = a.get("at", 0)
    return {k: v for k, v in g.items() if v is not None and v != 0 or k == "at"}


# --- the part library (shelters) ---

MATERIALS = ["minecraft:cobblestone", "minecraft:oak_planks", "minecraft:dirt", "minecraft:stone_bricks", "minecraft:glass"]


def shelter_from_genes(g: dict) -> dict:
    p = Plan("shelter")
    w, l, h = g["w"], g["l"], g["h"]
    x0, z0 = -(w // 2), -(l // 2)
    mat = g["mat"]
    for x in range(x0, x0 + w):
        for z in range(z0, z0 + l):
            edge = x in (x0, x0 + w - 1) or z in (z0, z0 + l - 1)
            for y in range(0, h):
                if edge:
                    p.put(x, y, z, mat)
            if g.get("roof", True):
                p.put(x, h, z, g.get("roof_mat", mat))
    p.parts.append(f"walls:{w}x{l}x{h} {mat.split(':')[1]}")
    if g.get("roof", True):
        p.parts.append("roof:" + g.get("roof_mat", mat).split(":")[1])
    door = g.get("door")
    if door:
        dx = x0 + w // 2
        if door == "door":
            p.put(dx, 0, z0, "minecraft:oak_door[facing=south,half=lower]")
            p.put(dx, 1, z0, "minecraft:oak_door[facing=south,half=upper]")
        else:
            p.put(dx, 0, z0, "minecraft:air")
            p.put(dx, 1, z0, "minecraft:air")
        p.parts.append("door" if door == "door" else "doorway")
    if g.get("torch"):
        p.put(x0 + 1, 0, z0 + l - 2 if l > 3 else z0 + 1, "minecraft:torch")
        p.parts.append("torch")
    inside = [x0 + w // 2, 0, z0 + l // 2]
    return p.design({"inside": inside, "genes": g})


def random_shelter_genes(rng: random.Random) -> dict:
    return {"w": rng.randint(3, 7), "l": rng.randint(3, 7), "h": rng.randint(2, 4), "mat": rng.choice(MATERIALS),
            "roof": rng.random() < 0.85, "roof_mat": rng.choice(MATERIALS), "door": rng.choice([None, "door", "doorway"]),
            "torch": rng.random() < 0.6}


def mutate_shelter(g: dict, rng: random.Random) -> dict:
    g = copy.deepcopy(g)
    for _ in range(rng.randint(1, 3)):
        what = rng.choice(["w", "l", "h", "mat", "roof", "roof_mat", "door", "torch"])
        if what in ("w", "l"):
            g[what] = max(3, min(9, g[what] + rng.choice([-1, 1])))
        elif what == "h":
            g["h"] = max(2, min(5, g["h"] + rng.choice([-1, 1])))
        elif what in ("mat", "roof_mat"):
            g[what] = rng.choice(MATERIALS)
        elif what == "roof":
            g["roof"] = not g.get("roof", True)
        elif what == "door":
            g["door"] = rng.choice([None, "door", "doorway"])
        else:
            g["torch"] = not g.get("torch")
    return g


def build(kind: str, genes: dict) -> dict:
    return trap_from_genes(genes) if kind == "trap" else shelter_from_genes(genes)


# --- static checks (no game needed) ---

def cost(d: dict) -> float:
    pal = d["palette"]
    c = sum(COST.get(pal[b[3]].split("[")[0], 0.5) for b in d["blocks"])
    c += sum(COST.get(v[0], 0.2) * v[1] for v in (d.get("items") or {}).values())
    return round(c, 2)


def visibility(d: dict) -> float:
    """How much of the trap shows: suspicious blocks with open air above or beside them at ground
    level, and open holes in the floor (0 = looks like plain ground)."""
    pal = d["palette"]
    blocks = {(b[0], b[1], b[2]): pal[b[3]].split("[")[0] for b in d["blocks"]}

    def open_air(x, y, z):
        here = blocks.get((x, y, z))
        if here is not None:
            return here in ("minecraft:air",)
        return y >= 0  # Outside the design: ground below 0, air above.

    v = 0.0
    for (x, y, z), b in blocks.items():
        if b == "minecraft:air" and y == -1:
            v += SUSPICIOUS["air_hole"] / 2
            continue
        weight = SUSPICIOUS.get(b, 0)
        if not weight:
            continue
        if y >= 0 or open_air(x, y + 1, z) or any(open_air(x + dx, y, z + dz) and y >= -1
                                                    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            v += weight
    return round(v, 2)


# --- scoring the sandbox results ---

def score(d: dict, r: dict) -> dict:
    """Qualities of a tested design (higher is better for each)."""
    c = cost(d)
    if d["kind"] == "trap":
        harm = float(r.get("damage", 0)) / 20 + (1.0 if r.get("killed") else 0.0)
        caught = 1.0 if r.get("caught") else 0.0
        works = harm > 0.15 or caught > 0
        vis = visibility(d)
        q = {"deadliest": round(harm + 0.3 * caught, 3),
             "most_hidden": round((harm + caught) / (1 + vis), 3) if works else 0.0,
             "cheapest": round((harm + caught) / (1 + c / 10), 3) if works else 0.0,
             "holds": round(caught + 0.5 * float(r.get("stuck_seconds", 0)) / 10, 3)}
        q["overall"] = round(0.45 * q["deadliest"] + 0.3 * q["most_hidden"] + 0.25 * q["cheapest"], 3)
        return {"works": works, "cost": c, "visibility": vis, "q": q}
    safe = bool(r.get("enclosed")) and float(r.get("light", 0)) >= 8 and int(r.get("room", 0)) >= 2
    room = int(r.get("room", 0))
    q = {"cheapest_safe": round(1 / (1 + c / 10), 3) if safe else 0.0,
         "roomiest_safe": round(min(1.0, room / 40), 3) if safe else 0.0,
         "best_lit": round(min(15, float(r.get("light", 0))) / 15 * (1.0 if r.get("enclosed") else 0.3), 3)}
    q["overall"] = round((0.5 * q["cheapest_safe"] + 0.3 * q["roomiest_safe"] + 0.2 * q["best_lit"]) if safe else 0.05 * q[
        "best_lit"], 3)
    return {"works": safe, "cost": c, "q": q}


# --- the memory of inventions ---

class Inventions:
    KEEP = 3  # Per quality.

    def __init__(self, profile: str):
        self.path = os.path.join(profile, "inventions.json")
        self.data = {"hall": {"trap": {}, "shelter": {}}, "failed": [], "tested": 0, "designs": {}}
        if os.path.isfile(self.path):
            try:
                with open(self.path) as fh:
                    self.data.update(json.load(fh))
            except (OSError, ValueError):
                pass

    def save(self) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w") as fh:
            json.dump(self.data, fh)
        os.replace(tmp, self.path)

    def known(self, did: str) -> bool:
        return did in self.data["failed"] or did in self.data["designs"]

    def best_genes(self, kind: str) -> list:
        ids = {i for lst in self.data["hall"].get(kind, {}).values() for i, _ in lst}
        return [self.data["designs"][i]["genes"] for i in ids if i in self.data["designs"]]

    def record(self, d: dict, result: dict) -> dict:
        s = score(d, result)
        self.data["tested"] += 1
        if not s["works"]:
            self.data["failed"] = (self.data["failed"] + [d["id"]])[-2000:]
            return s
        entry = {k: d[k] for k in ("id", "kind", "name", "parts", "palette", "blocks", "items", "genes")}
        entry.update({k: d[k] for k in ("path", "inside") if k in d})
        entry.update(result=result, cost=s["cost"], q=s["q"], at=int(time.time()))
        if "visibility" in s:
            entry["visibility"] = s["visibility"]
        self.data["designs"][d["id"]] = entry
        hall = self.data["hall"].setdefault(d["kind"], {})
        for quality, value in s["q"].items():
            lst = [x for x in hall.get(quality, []) if x[0] != d["id"]] + [[d["id"], value]]
            hall[quality] = sorted(lst, key=lambda x: -x[1])[:self.KEEP]
        # Forget designs that are in no hall of fame (keeps the file small).
        keep = {i for k in self.data["hall"].values() for lst in k.values() for i, _ in lst}
        self.data["designs"] = {i: v for i, v in self.data["designs"].items() if i in keep}
        return s


def propose(inv: Inventions, kind: str, n: int, rng: random.Random) -> list:
    """New candidates: half new combinations, half changes and crossings of the best so far."""
    best = inv.best_genes(kind)
    out, seen, tries = [], set(), 0
    while len(out) < n and tries < n * 50:
        tries += 1
        if best and rng.random() < 0.6:
            g = rng.choice(best)
            if kind == "trap" and len(best) > 1 and rng.random() < 0.3:
                g = cross_trap(g, rng.choice(best), rng)
            g = mutate_trap(g, rng) if kind == "trap" else mutate_shelter(g, rng)
        else:
            g = random_trap_genes(rng) if kind == "trap" else random_shelter_genes(rng)
        if kind == "trap":
            g.setdefault("at", 0)
        d = build(kind, g)
        if d["id"] in seen or inv.known(d["id"]):
            continue  # Already tried (or the same as one in this batch).
        seen.add(d["id"])
        out.append(d)
    return out


def main(argv) -> int:
    ap = argparse.ArgumentParser(prog="invent")
    ap.add_argument("action", choices=["propose", "record", "show"])
    ap.add_argument("--profile", required=True)
    ap.add_argument("--kind", default="trap", choices=["trap", "shelter"])
    ap.add_argument("--n", type=int, default=4)
    ap.add_argument("--results")
    a = ap.parse_args(argv)
    inv = Inventions(a.profile)
    if a.action == "propose":
        print(json.dumps({"designs": propose(inv, a.kind, a.n, random.Random())}))
    elif a.action == "record":
        with open(a.results) as fh:
            batch = json.load(fh)  # [{"design": {...}, "result": {...}}]
        scored = [dict(id=b["design"]["id"], name=b["design"]["name"], **inv.record(b["design"], b["result"])) for b in batch]
        inv.save()
        print(json.dumps({"scored": scored, "tested": inv.data["tested"]}))
    else:
        hall = {}
        for kind, qs in inv.data["hall"].items():
            hall[kind] = {q: [dict(inv.data["designs"][i], value=v) for i, v in lst if i in inv.data["designs"]]
                          for q, lst in qs.items()}
        print(json.dumps({"hall": hall, "tested": inv.data["tested"], "failed": len(inv.data["failed"])}))
    sys.stdout.flush()
    return 0
