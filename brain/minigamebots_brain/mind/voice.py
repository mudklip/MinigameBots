"""Voice (optional addition O3): push-to-talk speech -> text, and replies spoken aloud. All local.

- Hearing: Whisper (base, English), through the same transformers library as the mind.
- Speaking: Piper, a small fast text-to-speech engine, with one English voice.
Installed once from the Brain screen (Install voice, about 350 MB) into <brain>/mind/voice.
"""

from __future__ import annotations

import os
import subprocess
import sys
import wave

import numpy as np

WHISPER = "openai/whisper-base.en"
PIPER_REPO = "rhasspy/piper-voices"
PIPER_VOICE = "en/en_US/lessac/medium/en_US-lessac-medium"


def root_dir(mind_root: str) -> str:
    return os.path.join(mind_root, "voice")


def installed(mind_root: str) -> dict:
    r = root_dir(mind_root)
    return {"hear": os.path.isfile(os.path.join(r, "whisper", "config.json")),
            "speak": os.path.isfile(os.path.join(r, os.path.basename(PIPER_VOICE) + ".onnx"))}


def install(mind_root: str, log=print) -> None:
    os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
    log("Installing voice libraries...")
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "transformers>=4.45", "huggingface_hub>=0.24",
                        "piper-tts>=1.2"], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("pip failed: " + (r.stderr or r.stdout)[-400:])
    from huggingface_hub import hf_hub_download, snapshot_download

    d = root_dir(mind_root)
    log("Downloading speech recognition (about 290 MB)...")
    snapshot_download(WHISPER, local_dir=os.path.join(d, "whisper"),
                      allow_patterns=["*.json", "*.safetensors", "*.txt"])
    log("Downloading a voice (about 60 MB)...")
    for ext in (".onnx", ".onnx.json"):
        p = hf_hub_download(PIPER_REPO, PIPER_VOICE + ext, local_dir=d)
        os.replace(p, os.path.join(d, os.path.basename(PIPER_VOICE) + ext))


def read_wav(path: str) -> np.ndarray:
    """16-bit PCM WAV -> float32 mono at 16 kHz (what the mod records)."""
    with wave.open(path, "rb") as w:
        rate, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        data = w.readframes(w.getnframes())
    if width != 2:
        raise ValueError("expected 16-bit audio")
    a = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    if rate != 16000 and len(a):
        a = np.interp(np.linspace(0, len(a) - 1, int(len(a) * 16000 / rate)), np.arange(len(a)), a).astype(np.float32)
    return a


class Ears:
    def __init__(self, mind_root: str):
        import torch
        from transformers import pipeline

        dev = 0 if torch.cuda.is_available() else -1
        self.asr = pipeline("automatic-speech-recognition", model=os.path.join(root_dir(mind_root), "whisper"), device=dev)

    def hear(self, wav_path: str) -> str:
        audio = read_wav(wav_path)
        if len(audio) < 1600:  # Under 0.1 s: nothing said.
            return ""
        return str(self.asr({"raw": audio, "sampling_rate": 16000})["text"]).strip()


class Mouth:
    def __init__(self, mind_root: str):
        from piper import PiperVoice

        self.voice = PiperVoice.load(os.path.join(root_dir(mind_root), os.path.basename(PIPER_VOICE) + ".onnx"))

    def say(self, text: str, out_path: str) -> str:
        with wave.open(out_path, "wb") as w:
            if hasattr(self.voice, "synthesize_wav"):  # piper-tts 1.3+
                self.voice.synthesize_wav(text, w)
            else:
                self.voice.synthesize(text, w)
        return out_path
