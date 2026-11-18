"""Reads game frames from the shared-memory file the mod writes.

Layout (little-endian), see FrameRing.java:
  header (32 bytes): b"MGBF", version, slots, frame_size, slot_bytes, 0, 0, 0
  slot i at 32 + i * slot_bytes: seq (int32), tick (int64), size (int32), then RGB pixels.
The mod bumps seq to an odd value while writing and to an even value when done.
"""

import mmap
import struct

HEADER = 32
SLOT_HEADER = 16
MAGIC = b"MGBF"

try:
    import numpy as np
except ImportError:  # The brain works without numpy; policies then get raw bytes.
    np = None


class FrameRing:
    def __init__(self, path: str):
        self._file = open(path, "rb")
        self._map = mmap.mmap(self._file.fileno(), 0, access=mmap.ACCESS_READ)
        magic, version, slots, size, slot_bytes = struct.unpack_from("<4s4i", self._map, 0)
        if magic != MAGIC or version != 1:
            raise ValueError("not a MinigameBots frame file")
        self.slots = slots
        self.frame_size = size
        self.slot_bytes = slot_bytes

    def read(self, slot: int, tick: int):
        """The frame in `slot` if it is the one for `tick`, else None.

        Returns a (size, size, 3) uint8 array with numpy, otherwise raw RGB bytes.
        """
        if not 0 <= slot < self.slots:
            return None
        base = HEADER + slot * self.slot_bytes
        for _ in range(3):
            seq1, frame_tick, size = struct.unpack_from("<iqi", self._map, base)
            if seq1 % 2 == 1:
                continue  # Being written right now.
            start = base + SLOT_HEADER
            pixels = self._map[start:start + size * size * 3]
            (seq2,) = struct.unpack_from("<i", self._map, base)
            if seq1 == seq2:
                if frame_tick != tick:
                    return None
                if np is not None:
                    return np.frombuffer(pixels, dtype=np.uint8).reshape(size, size, 3)
                return pixels
        return None

    def close(self):
        self._map.close()
        self._file.close()
