"""The training designer: from what you want in plain words to a checked training program.

The mind (if installed) only understands the request: which playbook entries it's about (minigames,
problems). Everything after that is fixed rules over parts that exist: the scope (one rule, one
skill, or a whole program), the steps (rewards with their settings, what it learns from, where it
practises, what to record, which plan), and checks (every reward exists; nothing pays it for
losing). Without the mind, keywords pick the entries (the form does the same with chips).

design(request, context, mind) -> {"scope", "title", "summary", "steps": [...], "checks": [...], "used_mind"}
context: {"skills": [ids it has], "marked": n marked moments, "verdict": {"state", "why"}}
"""

import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BAD_TO_REWARD = ("death", "damage_taken", "b:fell_into_void", "b:item_wasted", "b:wasted_swing", "b:hit_ally", "b:in_lava",
                 "b:drowning", "b:starving", "b:idle")


def playbook() -> dict:
    with open(os.path.join(HERE, "playbook.json"), encoding="utf-8") as f:
        return json.load(f)


def catalog() -> set:
    with open(os.path.join(HERE, "rl", "skills.json"), encoding="utf-8") as f:
        d = json.load(f)
    keys = {"b:" + k for k in d["behaviours"]} | set(d["events"])
    return keys | {k.split("?")[0] for k in keys}


def match_keywords(request: str, pb: dict):
    text = request.lower()
    modes = [k for k, v in pb["modes"].items() if any(re.search(r"\b" + re.escape(n) + r"\b", text) for n in v["names"])]
    problems = [k for k, v in pb["problems"].items() if any(n in text for n in v["names"])]
    return modes, problems


def match_mind(request: str, pb: dict, mind):
    """Ask the mind which entries the request is about; it can only answer with listed ids."""
    listing = "; ".join(f"{k} ({', '.join(v['names'][:3])})" for k, v in pb["modes"].items())
    plisting = "; ".join(f"{k} ({', '.join(v['names'][:2])})" for k, v in pb["problems"].items())
    prompt = (f"A player wants to train their Minecraft AI. Request: \"{request}\".\n"
              f"Minigames: {listing}.\nProblems: {plisting}.\n"
              "Answer with JSON only: {\"modes\": [ids], \"problems\": [ids]} using only the ids above.")
    try:
        answer = mind.generate([{"role": "user", "content": prompt}], max_new_tokens=60)
        m = re.search(r"\{.*\}", answer, re.S)
        data = json.loads(m.group(0)) if m else {}
    except Exception:
        return None
    modes = [x for x in data.get("modes") or [] if x in pb["modes"]]
    problems = [x for x in data.get("problems") or [] if x in pb["problems"]]
    return modes, problems


def _rule(spec: dict) -> dict:
    key = spec["key"]
    event, where = key, {}
    if "?" in key:
        event, cond = key.split("?", 1)
        for kv in cond.split("&"):
            k, _, v = kv.partition("=")
            where[k] = True if v == "true" else False if v == "false" else v
    r = {"event": event, "where": where, "value": spec.get("value", 1), "per": spec.get("per", "event"),
         "note": key.replace("b:", "").replace("_", " ")}
    if spec.get("safe"):
        r["safe"] = {"seconds": spec["safe"]}
    if spec.get("beyond"):
        r["beyond"] = True
    if spec.get("budget"):
        r["budget"] = {"limit": spec["budget"]}
    if spec.get("min_use") is not None:
        r["min_use"] = spec["min_use"]
    return r


def check(steps: list) -> list:
    """Every reward exists; nothing pays it for losing."""
    known = catalog()
    notes = []
    for st in steps:
        if st["kind"] != "rules":
            continue
        kept = []
        for r in st["rules"]:
            if r["event"] not in known:
                notes.append(f"Left out '{r['note']}': no such reward.")
                continue
            if r["event"] in BAD_TO_REWARD and float(r["value"]) > 0:
                r["value"] = -abs(float(r["value"]))
                notes.append(f"'{r['note']}' made a penalty: it must never pay for that.")
            kept.append(r)
        st["rules"] = kept
    return notes or ["All rewards exist and none pays it for losing."]


def design(request: str, context: dict | None = None, mind=None) -> dict:
    pb = playbook()
    context = context or {}
    modes, problems = match_keywords(request, pb)
    used_mind = False
    if mind is not None:
        got = match_mind(request, pb, mind)
        if got is not None and (got[0] or got[1]):
            used_mind = True
            modes = list(dict.fromkeys(got[0] + modes))
            problems = list(dict.fromkeys(got[1] + problems))
    verdict = context.get("verdict") or {}
    if verdict.get("state") in ("FLAT", "DROPPING") and "not_improving" not in problems:
        problems.append("not_improving")
    if not modes and not problems:
        return {"scope": "none", "title": "Not sure what to train",
                "summary": "Name a minigame (Sumo, Spleef, bridging, crystal PvP...) or a problem (wastes pearls, falls off...).",
                "steps": [], "checks": [], "used_mind": used_mind}
    steps = []
    have = set(context.get("skills") or [])
    target = pb["modes"][modes[0]]["skill"] if modes else (sorted(have)[0] if have else "sword_pvp")
    # Scope: the smallest thing that meets the need.
    scope = "rule" if not modes else "skill" if len(modes) == 1 and not problems else "program"
    for pid in problems:
        p = pb["problems"][pid]
        if p.get("rules"):
            steps.append({"kind": "rules", "skill": target, "rules": [_rule(s) for s in p["rules"]],
                          "title": f"Add to {target.replace('_', ' ')}: " + ", ".join(s["key"].split("?")[0].replace("b:", "").replace("_", " ") for s in p["rules"]),
                          "why": p["why"]})
        if p.get("fit"):
            steps.append({"kind": "fit", "skill": target, "title": "Re-check its rewards against your marked clips",
                          "why": p["why"] + (f" ({context.get('marked', 0)} marked moments so far.)" if context.get("marked") is not None else "")})
            steps.append({"kind": "setup", "skill": target, "setup": "duel:league", "title": "Practise against the league (mixed opponents)",
                          "why": "More varied opponents is the usual cure for a plateau."})
    for mid in modes:
        m = pb["modes"][mid]
        sk = m["skill"]
        if sk not in have:
            steps.append({"kind": "skill", "skill": sk, "title": f"Add the skill '{sk.replace('_', ' ')}'", "why": f"The ready-made skill for {mid.replace('_', ' ')}."})
        steps.append({"kind": "rules", "skill": sk, "rules": [_rule(s) for s in m["rules"]],
                      "title": f"Rewards for {mid.replace('_', ' ')}", "why": m["tips"]})
        steps.append({"kind": "learn_from", "skill": sk, "weights": m["learn_from"], "title": "What it learns from",
                      "why": "Your play counts most; " + ("players who beat it count a lot here." if m["learn_from"]["defeats"] >= 1
                                                          else "watched players and defeats help a bit.")})
        if m.get("setup") and m["setup"] != "none":
            steps.append({"kind": "setup", "skill": sk, "setup": m["setup"], "title": f"Practise in: {m['setup']}", "why": "Where this skill practises."})
        steps.append({"kind": "record", "title": m["record"], "why": "Your own play is the best teacher."})
        steps.append({"kind": "plan", "plan": m["plan"], "title": f"Run the {m['plan']} plan (Training Hub)", "why": "It runs training, practice and exams by itself."})
        if scope == "rule":
            break
    checks = check(steps)
    title = (" + ".join(x.replace("_", " ").title() for x in modes) or "Fix: " + ", ".join(p.replace("_", " ") for p in problems))
    summary = {"rule": "Just a reward change: small and quick.", "skill": "One skill, set up for this.",
               "program": "A few connected steps."}[scope]
    return {"scope": scope, "title": title, "summary": summary, "steps": steps, "checks": checks, "used_mind": used_mind}


def main(argv) -> int:
    ap = argparse.ArgumentParser(prog="design")
    ap.add_argument("--request", required=True)
    ap.add_argument("--context", default="{}")
    a = ap.parse_args(argv)
    print(json.dumps(design(a.request, json.loads(a.context))))
    sys.stdout.flush()
    return 0
