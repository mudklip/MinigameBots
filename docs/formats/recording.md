# Recording format (`.mgbrec`), version 1

A recording is one session of gameplay saved as training data: for every game tick (1/20 s),
what was seen and what was done, in exactly the format the AI uses.

## On disk

```
.minecraft/minigamebots/recordings/
├── <id>/                    one recording, id = start time, e.g. 20261118-142530
│   ├── meta.json
│   ├── chunk-00000.bin.gz   ticks 0-1199 (one minute)
│   ├── chunk-00001.bin.gz   ticks 1200-2399
│   └── ...
├── exports/                 Export writes <name>.mgbrec here
└── import/                  put .mgbrec files here and press Import
```

A `.mgbrec` file is a zip of one recording folder (`meta.json` plus chunk files). Import only
accepts those file names, so an archive can't write anywhere else.

## meta.json

| Field | Meaning |
|---|---|
| `formatVersion` | `1` |
| `id`, `name`, `tags` | Folder name, display name, tags |
| `created` | ISO-8601 start time |
| `source` | `human` (your play) or `ai` (the AI was in control) |
| `world` | Singleplayer world name or server address |
| `minecraftVersion`, `modVersion` | Versions used to record |
| `frameSize` | Frames are `frameSize` x `frameSize` |
| `ticksPerChunk` | Ticks per chunk file (1200) |
| `ticks`, `chunks` | How many ticks and chunk files exist |
| `sizeBytes` | Size on disk |
| `trimStart`, `trimEnd` | Inclusive tick range to train on; `-1` = untrimmed |
| `marks` | `[{tick, type, note}]`, type is `good`, `mistake`, `base` or custom |
| `complete` | `false` if the game closed while recording (the chunks written so far are still valid) |

## Chunk files

Gzip-compressed sequence of records, each:

| Field | Type |
|---|---|
| tick | int32, big-endian |
| jsonLength | int32 |
| json | UTF-8 JSON, `jsonLength` bytes |
| jpegLength | int32; `-1` = same frame as the previous record |
| jpeg | JPEG bytes of the frame, `jpegLength` bytes |

The first record of every chunk always carries a full frame, so each chunk can be read alone.
A chunk cut short by a crash is valid up to its last complete record.

### Record JSON

```json
{
  "input":  {"keys": ["forward", "sprint"], "attacks": 1, "uses": 0, "yaw": 2.5, "pitch": -0.4},
  "obs":    { ...Observation, see below... },
  "events": [{"tick": 812, "type": "kill", "data": {"target": "minecraft:pig"}, "source": "client"}],
  "frameAge": 0,
  "cameraJump": true,
  "marks": ["good"]
}
```

- **input**: what was done during this tick, in the AI's action format.
  - `keys`: held keys (`forward back left right jump sneak sprint attack use pick drop
    swap_offhand inventory hotbar_1 ... hotbar_9`). One-shot keys (hotbar, inventory, drop...)
    count as pressed on the tick they appear after not being held.
  - `attacks`, `uses`: clicks this tick, a fresh press included.
  - `yaw`, `pitch`: camera turn in degrees (right and down are positive). With a screen open,
    this is cursor movement converted at the configured pixels per degree.
- **obs**: the observation from the end of the previous tick (what was known before acting):
  player state, inventory, nearby entities, block cube (`blocks.categories` is base64, 11x11x11
  bytes indexed `((dy+5)*11 + (dz+5))*11 + (dx+5)`), sounds, text, open screen, crowd radar.
  - **entities** (up to 32) are ordered by importance, not just distance: the person being
    protected, whoever hit the player or that person recently, whoever the player is fighting,
    players, hostile mobs and projectiles, then the nearest. Each has `hitMe`, `hitByMe`,
    `hitProtected` (ticks since, -1 = not in the last 30 s; exact, from the server's damage
    events), `sameTeam`, `protectedTarget` and `hostile`.
  - **crowd.counts** (72 numbers): everyone within 64 blocks without a detailed slot, counted by
    kind (players, hostile mobs, others) x band (within 8, 24, 64 blocks) x compass sector
    (45° each; 0 = south, 2 = west, 4 = north, 6 = east). Index `(kind*3 + band)*8 + sector`.
    Recordings made before this field existed simply have no crowd data.
- **events**: events detected since the previous record.
- **frameAge**: how many ticks old the frame is (the game may render slower than 20 FPS).
- **cameraJump**: present when a teleport or respawn turn was removed from `yaw`/`pitch`.
- **marks**: marks placed on this tick.

## Size

About 100-300 MB per hour at 128x128 and JPEG quality 90.
