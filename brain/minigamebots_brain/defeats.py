"""Learning from whoever beats it.

While it plays, it keeps the last 30 seconds of what it saw. When a fight goes badly (it dies, or one
player deals 10+ damage within 10 seconds), that stretch is turned into demos of *that player's*
play, the same way watched players are (players.py: what they pressed, rebuilt from how they moved),
weighted by how much damage they did and tagged with the game and the kit they used ("crystal",
"sword", "bow"...). Those demos count less than your own play, and how much they count is set per
skill ("Learns from" on the skill card). It isn't a reward or a penalty: just example play.

It also keeps a tally of what hurt it, per game (<profile>/hurt_by.json: cause -> damage, and what the
player who hurt it held), which "Uses what beat it" and its lessons read.
"""

import collections
import json
import os
import shutil
import time

WINDOW = 600  # ticks kept (30 s)
HEAVY = 10.0  # damage from one player within RECENT ticks that counts as being beaten
RECENT = 200
COOLDOWN = 1200  # at most one clip per player per minute


def kit_of(track_items: list) -> str:
    items = " ".join(track_items)
    for key, name in (("end_crystal", "crystal"), ("respawn_anchor", "anchor"), ("mace", "mace"), ("bow", "bow"),
                      ("crossbow", "bow"), ("trident", "trident"), ("_axe", "axe"), ("_sword", "sword")):
        if key in items:
            return name
    return "hand"


class DefeatWatcher:
    def __init__(self, profile_root: str, game: str = ""):
        self.root = profile_root
        self.recordings = os.path.join(os.path.dirname(os.path.dirname(profile_root)), "recordings")
        self.buffer = collections.deque(maxlen=WINDOW)
        self.hits = []  # (tick, by, amount)
        self.last_clip = {}
        self.tick = 0
        self.tally_path = os.path.join(profile_root, "hurt_by.json")
        try:
            with open(self.tally_path, encoding="utf-8") as f:
                self.tally = json.load(f)
        except (OSError, ValueError):
            self.tally = {}

    def step(self, obs: dict, events: list):
        self.tick += 1
        clean = {k: v for k, v in obs.items() if k not in ("_events", "privileged")}
        self.buffer.append({"input": {}, "obs": clean, "events": events, "frameAge": -1})
        game = str((obs.get("text") or {}).get("sidebarTitle") or "") or "any"
        died = False
        for ev in events:
            t, d = ev.get("type"), ev.get("data") or {}
            if t == "damage_taken":
                amount = float(d.get("amount", 0) or 0)
                by = str(d.get("by") or "")
                g = self.tally.setdefault(game, {"causes": {}, "held": {}})
                g["causes"][d.get("cause", "other")] = round(g["causes"].get(d.get("cause", "other"), 0) + amount, 1)
                if by:
                    held = next((str(e.get("heldItem", "")) for e in obs.get("entities") or [] if e.get("name") == by), "")
                    if held:
                        g["held"][held] = round(g["held"].get(held, 0) + amount, 1)
                    self.hits.append((self.tick, by, amount))
            elif t == "death":
                died = True
        self.hits = [h for h in self.hits if self.tick - h[0] <= RECENT]
        dealt = collections.Counter()
        for _, by, amount in self.hits:
            dealt[by] += amount
        top = dealt.most_common(1)
        if top and (top[0][1] >= HEAVY or (died and top[0][1] > 0)):
            name, dmg = top[0]
            if self.tick - self.last_clip.get(name, -10 ** 9) >= COOLDOWN:
                self.last_clip[name] = self.tick
                self.hits = []
                return self._clip(name, dmg, game)
        return None

    def _clip(self, name: str, dmg: float, game: str):
        from . import feed, players

        self._save_tally()
        rows = list(self.buffer)
        if len(rows) < players.MIN_TICKS:
            return None
        rid = "fight-" + time.strftime("%Y%m%d-%H%M%S")
        src = os.path.join(self.recordings, rid)
        try:
            players.write(src, rows, {"id": rid, "name": f"Fight with {name}"})
            with open(os.path.join(src, "meta.json"), encoding="utf-8") as f:
                meta = json.load(f)
            meta["source"] = "fight"
            by_name = players.tracks(src)
            held = [str(t["e"].get("heldItem", "")) for seg in by_name.get(name, []) for t in seg]
            kit = kit_of(held)
            made, ticks, _, _ = players.convert_one(
                self.recordings, src, rid, meta, by_name, players.load_ratings(self.recordings), only={name},
                boost=1.0 + min(2.0, dmg / 10.0), prefix="defeat",
                extra={"defeat": True, "kit": kit, "game_tag": game})
        except Exception:
            made, ticks, kit = 0, 0, ""
        finally:
            shutil.rmtree(src, ignore_errors=True)  # Only the opponent's demos are kept.
        if made:
            feed.push("know", f"Beaten by {name} ({kit}): learning from how they did it")
        return {"player": name, "demos": made, "seconds": round(ticks / 20, 1), "kit": kit}

    def _save_tally(self):
        try:
            with open(self.tally_path, "w", encoding="utf-8") as f:
                json.dump(self.tally, f, indent=1)
        except OSError:
            pass

    def close(self):
        self._save_tally()
