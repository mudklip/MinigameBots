"""Ready-made Objectives. Parameters like {target} are filled in when one is chosen."""

import copy
import json
import os

# Shared with the mod's Practice screen (it reads the same file from the jar).
with open(os.path.join(os.path.dirname(__file__), "templates.json"), encoding="utf-8") as _f:
    TEMPLATES = json.load(_f)


def build(name: str, params: dict | None = None) -> dict:
    """A template with its parameters filled in."""
    if name not in TEMPLATES:
        raise ValueError(f"unknown objective template '{name}', choose from {sorted(TEMPLATES)}")
    obj = copy.deepcopy(TEMPLATES[name])
    values = dict(obj.get("params", {}))
    values.update(params or {})
    return _fill(obj, values)


def _fill(x, values):
    if isinstance(x, str):
        for k, v in values.items():
            x = x.replace("{" + k + "}", str(v))
        return x
    if isinstance(x, list):
        return [_fill(i, values) for i in x]
    if isinstance(x, dict):
        return {k: _fill(v, values) for k, v in x.items()}
    return x
