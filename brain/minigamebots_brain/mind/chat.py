"""Chat in character (optional addition O1).

The AI talks only when someone speaks to it, only while it's playing, and always through
this filter: one short line, no commands, no formatting codes, nothing from the "never talk
about" list, and a fixed blocklist. The character (name, personality, how it talks,
background, topics to avoid) is yours to write, or made by the mind for you to edit.
"""

from __future__ import annotations

import json
import re

MAX_LEN = 160
BLOCKLIST = ("password", "token", "credit card", "address", "phone number", "kill yourself", "kys")


def system_prompt(c: dict, ai_name: str) -> str:
    name = c.get("name") or ai_name or "the AI"
    parts = [f"You are {name}, a Minecraft player chatting in game chat while you play.",
             f"Personality: {c.get('personality') or 'friendly and relaxed'}.",
             f"How you talk: {c.get('style') or 'short, casual, like a real player'}."]
    if c.get("background"):
        parts.append(f"About you: {c['background']}.")
    if c.get("avoid"):
        parts.append(f"Never talk about: {c['avoid']}.")
    parts.append("Reply with ONE short chat line (under 20 words). No emojis lists, no quotes, no commands, "
                 "never ask for personal information, never claim to be a human if sincerely asked whether you are an AI. "
                 "If the message isn't for you or needs no answer, reply with just: -")
    return " ".join(parts)


def clean(text: str, avoid: str = "") -> str:
    """The safety filter every reply passes; returns "" to stay silent."""
    line = text.strip().splitlines()[0] if text.strip() else ""
    line = re.sub(r"§.", "", line).strip().strip('"').strip()
    line = re.sub(r"^[A-Za-z0-9_ ]{1,20}:\s+", "", line)  # "Name: hi" -> "hi"
    if not line or line in ("-", "...") or line.startswith("/"):
        return ""
    low = line.lower()
    banned = [b.strip().lower() for b in avoid.split(",") if b.strip()] + list(BLOCKLIST)
    if any(b and b in low for b in banned):
        return ""
    return line[:MAX_LEN]


def reply(llm, character: dict, ai_name: str, history: list[str], message: str) -> str:
    msgs = [{"role": "system", "content": system_prompt(character, ai_name)}]
    if history:
        msgs.append({"role": "user", "content": "Recent chat:\n" + "\n".join(h[:200] for h in history[-6:])})
        msgs.append({"role": "assistant", "content": "-"})
    msgs.append({"role": "user", "content": message[:300]})
    return clean(llm.generate(msgs, max_new_tokens=48, sample=True), character.get("avoid", ""))


def make_character(llm, ai_name: str, skills: list[str]) -> dict:
    """A fresh character for you to edit."""
    prompt = (f"Invent a fun, wholesome Minecraft player character named {ai_name or 'the AI'}"
              f"{' who is good at ' + ', '.join(skills) if skills else ''}. Answer with JSON only: "
              '{"name": ..., "personality": ..., "style": ..., "background": ...} '
              "(each under 15 words).")
    out = llm.generate([{"role": "user", "content": prompt}], max_new_tokens=160, sample=True)
    m = re.search(r"\{.*\}", out, re.S)
    try:
        data = json.loads(m.group(0)) if m else {}
    except ValueError:
        data = {}
    return {k: str(data.get(k, ""))[:120] for k in ("name", "personality", "style", "background")} | {
        "name": str(data.get("name") or ai_name)[:16]}
