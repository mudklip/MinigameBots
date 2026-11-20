"""Daily lessons: once per sleep, the mind (the small language model) puts what the memory data
shows into a few short lessons in plain words, and may add one idea of its own.

The facts come from code, not from the model: who keeps knocking it out, who it beats, where bad
things keep happening (worked out on the Java side from its memories). The model only phrases
them (a small model is good at that and unreliable at reasoning), so a lesson is never made up.
Its own extra idea is marked "idea": shown, but never trusted or used for decisions. Without the
model, plain sentences from templates are used.
"""

import re

TEMPLATES = {
    "careful_with": "{subject} keeps beating me ({facts}): be careful with them.",
    "can_beat": "I usually beat {subject} ({facts}): I can take that fight.",
    "avoid_place": "Bad things keep happening {subject} ({facts}): avoid it.",
}

PROMPT = """You are a Minecraft AI writing short lessons for yourself from facts about your day.
For each fact, write ONE short lesson (max 15 words), first person, practical, on its own line,
starting with "- ". Use only the facts given. Then, if one useful extra idea follows from the
facts, write it on a last line starting with "IDEA: " (max 15 words). Nothing else."""


def plain(facts: list) -> list:
    return [TEMPLATES.get(f.get("type"), "{subject}: {facts}").format(subject=f.get("subject", ""), facts=f.get("facts", ""))
            for f in facts]


def write(llm, facts: list) -> dict:
    """{"lessons": [text per fact, same order], "idea": text or ""}."""
    texts = plain(facts)
    idea = ""
    if llm is None or not facts:
        return {"lessons": texts, "idea": idea}
    lines = "\n".join(f"{i + 1}. {t}" for i, t in enumerate(texts))
    try:
        out = llm.generate([{"role": "system", "content": PROMPT}, {"role": "user", "content": "Facts:\n" + lines}],
                           max_new_tokens=40 * len(facts) + 30)
    except Exception:
        return {"lessons": texts, "idea": idea}
    got = [re.sub(r"^\s*[-*\d.]+\s*", "", ln).strip() for ln in out.splitlines() if ln.strip().startswith(("-", "*"))]
    m = re.search(r"IDEA:\s*(.+)", out)
    if m:
        idea = m.group(1).strip()[:120]
    # Keep the model's wording only where it lines up with the facts; otherwise the plain sentence.
    lessons = [got[i][:120] if i < len(got) and len(got[i]) > 8 else texts[i] for i in range(len(texts))]
    return {"lessons": lessons, "idea": idea}
