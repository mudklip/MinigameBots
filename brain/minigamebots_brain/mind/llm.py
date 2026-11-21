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
# "lite": about 1 GB, faster, noticeably less clever (for tight memory, e.g. while training).
MODELS = {"standard": DEFAULT_MODEL, "lite": "Qwen/Qwen2.5-0.5B-Instruct"}
LITE_MODEL = MODELS["lite"]


def describe(e: Exception) -> str:
    """A mind error in words, with what to do (some errors, like running out of memory, have no text)."""
    text = f"{type(e).__name__}: {e}".rstrip(": ")
    low = text.lower()
    if isinstance(e, MemoryError) or "out of memory" in low or "not enough memory" in low or "alloc" in low:
        return ("Not enough memory to load the mind. Close other programs, or install the Lite mind (about 1 GB) "
                "in F10 > Brain, which needs far less. (" + text + ")")
    if "safetensors" in low or "no file named" in low or "incomplete" in low or "corrupt" in low:
        return "The mind's download looks incomplete or damaged: install it again in F10 > Brain. (" + text + ")"
    return text + " (details: minigamebots/logs/brain.log)"

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


def selected(root: str) -> str:
    """The installed model to use (the last one installed)."""
    try:
        with open(os.path.join(root, "selected.txt"), encoding="utf-8") as f:
            return f.read().strip() or DEFAULT_MODEL
    except OSError:
        return DEFAULT_MODEL


def model_dir(root: str, name: str | None = None) -> str:
    return os.path.join(root, (name or selected(root)).replace("/", "__"))


def installed(root: str) -> bool:
    """True only when the weights are there too (an interrupted download leaves just the small files)."""
    d = model_dir(root)
    return os.path.isfile(os.path.join(d, "config.json")) and any(
        f.endswith(".safetensors") for f in (os.listdir(d) if os.path.isdir(d) else []))


def download(fn, log=print, tries: int = 6):
    """Runs a Hugging Face download, retrying after dropped connections (it resumes partial files).
    One connection at a time: firewalls, antivirus and flaky Wi-Fi often reset parallel ones."""
    import time

    for attempt in range(1, tries + 1):
        try:
            return fn()
        except Exception as e:  # Connection reset, timeouts, "LocalEntryNotFound" after a failed request...
            if attempt == tries:
                raise RuntimeError(f"download kept failing ({e}). Check your internet connection, or allow Python "
                                   "through your firewall/antivirus, then press the button again: it resumes.") from e
            wait = 5 * attempt
            log(f"Connection dropped ({type(e).__name__}); retrying in {wait} s (try {attempt + 1} of {tries})...")
            time.sleep(wait)


def install(root: str, log=print, size: str = "standard") -> str:
    # Plain downloads (not the "Xet" storage), which pass through firewalls and proxies more easily.
    os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
    from huggingface_hub import snapshot_download

    name = MODELS.get(size, DEFAULT_MODEL)
    d = model_dir(root, name)
    log(f"Downloading {name} ({'about 1 GB' if size == 'lite' else 'about 3 GB'})...")
    download(lambda: snapshot_download(name, local_dir=d, max_workers=1, etag_timeout=60,
                                       allow_patterns=["*.json", "*.safetensors", "*.txt", "*.model", "merges.txt"]), log)
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, "selected.txt"), "w", encoding="utf-8") as f:
        f.write(name)
    return d


class LLM:
    def __init__(self, root: str, device: str | None = None):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer

        # The selected model first, then any other installed one (the Lite mind is far smaller), and
        # on the graphics card first, then the CPU: whatever fits. low_cpu_mem_usage loads the weights
        # in place instead of holding two copies (the usual reason a 16 GB PC runs out).
        names = [selected(root)] + [n for n in (DEFAULT_MODEL, LITE_MODEL) if n != selected(root)]
        devices = [device] if device else (["cuda", "cpu"] if torch.cuda.is_available() else ["cpu"])
        last = None
        for name in names:
            d = model_dir(root, name)
            if not (os.path.isfile(os.path.join(d, "config.json")) and any(f.endswith(".safetensors") for f in os.listdir(d))):
                continue
            for dev in devices:
                try:
                    dtype = torch.float16 if dev == "cuda" else torch.bfloat16
                    self.tok = AutoTokenizer.from_pretrained(d)
                    self.model = AutoModelForCausalLM.from_pretrained(d, torch_dtype=dtype, low_cpu_mem_usage=True).to(dev).eval()
                    self.device, self.name = dev, name
                    return
                except Exception as e:  # MemoryError, CUDA out of memory...
                    last = e
                    self.model = None
                    if dev == "cuda":
                        torch.cuda.empty_cache()
                    import gc

                    gc.collect()
        raise last if last is not None else OSError("no mind model found")

    def generate(self, messages: list[dict], max_new_tokens: int = 160, sample: bool = False) -> str:
        import torch

        enc = self.tok.apply_chat_template(messages, add_generation_prompt=True, return_tensors="pt")
        # Older transformers return the token ids; newer ones a dict-like bundle (ids + attention mask).
        if hasattr(enc, "keys") and "input_ids" in enc:
            ids = enc["input_ids"].to(self.device)
            mask = enc["attention_mask"].to(self.device) if "attention_mask" in enc else None
        else:
            ids, mask = enc.to(self.device), None
        extra = {"do_sample": True, "temperature": 0.8, "top_p": 0.9} if sample else {"do_sample": False}
        if mask is not None:
            extra["attention_mask"] = mask
        with torch.no_grad():
            out = self.model.generate(input_ids=ids, max_new_tokens=max_new_tokens, pad_token_id=self.tok.eos_token_id, **extra)
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
