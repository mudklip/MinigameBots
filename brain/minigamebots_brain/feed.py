"""What it just gained, lost or learned, for the optional on-screen popups (only the player sees
them: the mod draws them after the AI's snapshot). Points arrive summed per rule per second;
held rewards as held / paid / cancelled; knowledge as a line of text."""

import time

_items: list = []
_sums: dict = {}
_sum_start = 0.0


def push(kind: str, text: str, amount: float | None = None) -> None:
    """kind: "points", "held", "paid", "cancelled" or "know"."""
    _items.append({"kind": kind, "text": text, "amount": None if amount is None else round(float(amount), 3)})
    del _items[:-60]


def points(name: str, amount: float) -> None:
    """Adds to this second's total for a rule."""
    global _sum_start
    if not _sums:
        _sum_start = time.monotonic()
    _sums[name] = _sums.get(name, 0.0) + amount


def drain() -> list:
    global _sum_start
    if _sums and time.monotonic() - _sum_start >= 1.0:
        for name, amount in _sums.items():
            if abs(amount) >= 1e-4:
                push("points", name, amount)
        _sums.clear()
    out = list(_items)
    _items.clear()
    return out
