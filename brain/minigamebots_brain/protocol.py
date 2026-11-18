"""Messages between the mod and the brain: 4-byte big-endian length, then UTF-8 JSON."""

import json
import socket
import struct

MAX_MESSAGE = 16 * 1024 * 1024


class ConnectionClosed(Exception):
    pass


def encode(message: dict) -> bytes:
    body = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return struct.pack(">I", len(body)) + body


class Channel:
    """A message channel over a connected socket."""

    def __init__(self, sock: socket.socket):
        self.sock = sock
        self._buffer = bytearray()

    def send(self, message: dict) -> None:
        self.sock.sendall(encode(message))

    def _read_exact(self, n: int) -> bytes:
        while len(self._buffer) < n:
            chunk = self.sock.recv(max(65536, n - len(self._buffer)))
            if not chunk:
                raise ConnectionClosed()
            self._buffer.extend(chunk)
        data = bytes(self._buffer[:n])
        del self._buffer[:n]
        return data

    def receive(self) -> dict:
        (length,) = struct.unpack(">I", self._read_exact(4))
        if length > MAX_MESSAGE:
            raise ValueError(f"message too large: {length} bytes")
        return json.loads(self._read_exact(length).decode("utf-8"))
