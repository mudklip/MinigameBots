"""The optional local language model behind the mind: small, runs on your PC, never online.

Default model: Qwen2.5-1.5B-Instruct (Apache-2.0), about 3 GB, installed from the Brain screen
into <brain>/mind/<model>. On the GPU it answers in about a second; while training uses the
GPU (or with no GPU) it runs on the CPU, slower. It only turns your words into goals; it never
presses keys.
"""

from __future__ import annotations

import json
import os
import re

DEFAULT_MODEL = "Qwen/Qwen2.5-1.5B-Instruct"

SYSTEM = """You turn a Minecraft player's instruction to their AI helper into a JSON list of goals.
Allowed goals (use only these):
{"type":"mine","item":"diamond|iron|coal|gold|copper|redstone|lapis|emerald|ancient_debris","count":N}
{"type":"hunt","target":"minecraft:<mob>","count":N}
{"type":"go_to","waypoint":"base or a place name"}
{"type":"follow","player":"<name>"}  {"type":"protect","player":"<name>"}
{"type":"explore","minutes":N}  {"type":"survive","minutes":N}  {"type":"fight"}  {"type":"free_play"}
The player's own name is {me}. To stop everything answer ["stop"]. If it can't be done with these, answer [].
Answer with the JSON list only."""

EXAMPLES = [
    ("grab me a few diamonds and then come back", '[{"type":"mine","item":"diamond","count":3},{"type":"go_to","waypoint":"base"}]'),
    ("i'm hungry, get some food", '[{"type":"hunt","target":"minecraft:cow","count":2}]'),
    ("stick with me and keep me safe", '[{"type":"protect","player":"{me}"}]'),
    ("ok that's enough", '["stop"]'),
]


def model_dir(root: str, name: str = DEFAULT_MODEL) -> str:
    return os.path.join(root, name.replace("/", "__"))


def installed(root: str) -> bool:
    return os.path.isfile(os.path.join(model_dir(root), "config.json"))


def install(root: str, log=print) -> str:
    from huggingface_hub import snapshot_download

    d = model_dir(root)
    log(f"Downloading {DEFAULT_MODEL} (about 3 GB)...")
    snapshot_download(DEFAULT_MODEL, local_dir=d, allow_patterns=["*.json", "*.safetensors", "*.txt", "*.model", "merges.txt"])
    return d


class LLM:
    def __init__(self, root: str, device: str | None = None):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer

        d = model_dir(root)
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        dtype = torch.float16 if self.device == "cuda" else torch.bfloat16
        self.tok = AutoTokenizer.from_pretrained(d)
        self.model = AutoModelForCausalLM.from_pretrained(d, torch_dtype=dtype).to(self.device).eval()

    def generate(self, messages: list[dict], max_new_tokens: int = 160) -> str:
        import torch

        ids = self.tok.apply_chat_template(messages, add_generation_prompt=True, return_tensors="pt").to(self.device)
        with torch.no_grad():
            out = self.model.generate(ids, max_new_tokens=max_new_tokens, do_sample=False,
                                      pad_token_id=self.tok.eos_token_id)
        return self.tok.decode(out[0, ids.shape[1]:], skip_special_tokens=True)

    def to_goals(self, text: str, me: str):
        msgs = [{"role": "system", "content": SYSTEM.replace("{me}", me or "the player")}]
        for q, a in EXAMPLES:
            msgs += [{"role": "user", "content": q}, {"role": "assistant", "content": a.replace("{me}", me or "Steve")}]
        msgs.append({"role": "user", "content": text[:300]})
        return parse_answer(self.generate(msgs))


def parse_answer(answer: str):
    """(goals, stop) from the model's answer; anything unusable -> ([], False)."""
    m = re.search(r"\[.*\]", answer, re.S)
    if not m:
        return [], False
    try:
        data = json.loads(m.group(0))
    except ValueError:
        return [], False
    if data == ["stop"]:
        return [], True
    return [g for g in data if isinstance(g, dict)], False
