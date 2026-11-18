# Brain protocol, version 1

How the mod and the brain (a local Python process) talk. Everything stays on this PC.

## Starting

1. The mod opens a server socket on `127.0.0.1` with a random port and makes a random one-time token.
2. It starts `python -m minigamebots_brain run --port <port> --token <token> --frames <file>`
   with the brain's code on `PYTHONPATH`, using the Python environment made by the Setup screen.
3. The brain connects and sends `hello` with the token. Anything that connects without the right
   token is refused.

Brain output (stdout and stderr) goes to `minigamebots/logs/brain.log`; setup output to
`minigamebots/logs/install.log`.

## Messages

Each message is a 4-byte big-endian length followed by UTF-8 JSON with a `type` field.

| Direction | type | Fields |
|---|---|---|
| brain → mod | `hello` | `token`, `protocol`, `version`, `system` (self-test report) |
| mod → brain | `obs` | `tick` (the tick the action is for), `slot`, `frameTick`, `obs` (Observation, same JSON as in recordings) |
| brain → mod | `action` | `tick`, `keys` (list), `attacks`, `uses`, `yaw`, `pitch` (same as recording input) |
| mod → brain | `set_policy` | `name`, `args` |
| brain → mod | `policy` | `name`, `ok`, `error` |
| brain → mod | `status` | `policy`, `ticks`, `errors`, `think_ms`, `policy_status` (about every 2 s) |
| mod → brain | `ping` / brain → mod `pong` | `id` |
| mod → brain | `shutdown` | |

## Frames

Frames don't go over the socket. The mod writes them into a memory-mapped file the brain reads
(`minigamebots/brain/run/frames.bin`), little-endian:

- Header (32 bytes): `MGBF`, version 1, slots (8), frame size, slot size in bytes, then zeros.
- Slot *i* at `32 + i * slotBytes`: `seq` (int32), `tick` (int64), `size` (int32), then
  `size * size * 3` RGB bytes, rows top to bottom.

`seq` is odd while the mod writes a slot and even when done; the brain re-reads if `seq` changed
during its copy, and ignores a slot whose `tick` isn't the `frameTick` it asked for.

## Timing

The mod sends an observation at the end of each tick and uses the answer as soon as it arrives
(a tick waits at most 4 ms for an answer already on its way). Each action carries the tick it was
decided for, so the brain's thinking time counts towards the Humanizer's reaction delay instead
of adding to it. If no answer comes, the AI keeps holding the same keys for 3 ticks without new
clicks or turns, then releases everything. If the brain process dies, the AI stops and you get
control back.
