# Training Grounds events (ground truth), protocol v1

When a player has the MinigameBots client mod and is on a server (or singleplayer world) with
Training Grounds, the server sends exact events over the `minigamebots:tg/event` channel.
Both sides advertise the `events` feature in the handshake; players without the client mod
never receive anything.

Each event is `TgEventPayload(kind, json)`: `kind` is a short name, `json` a flat JSON object
(at most 4096 characters). The client turns it into a `GameEvent` named `tg_<kind>` with source
`server`, next to the events it infers itself (which have source `client`).

| kind | Sent to | Fields |
|---|---|---|
| `damage_taken` | the player who was hurt | `amount`, `blocked`, `source` (damage type), `projectile`, `weapon` (attacker's main hand item id), `health` (after), `attacker_type`, `attacker_name`, `attacker_player` |
| `damage_dealt` | the player who hurt something | `amount`, `blocked`, `source`, `projectile`, `weapon`, `victim_type`, `victim_name`, `victim_player`, `victim_health`, `distance` |
| `kill` | the killer | `victim_type`, `victim_name`, `victim_player`, `source`, `weapon` |
| `death` | the player who died | `source`, `killer_type`, `killer_name`, `killer_player` (if any) |
| `dummy_hit` | the player who hit a training dummy | `amount`, `blocked`, `combo`, `dps`, `level` |
| `aim_hit` | the player in an aim drill | `mode` (`flick`/`track`/`switch`), `ms` (time from the target appearing to the hit) |
| `aim_result` | at the end of a drill | `mode`, `hits`, `clicks`, `expired`, `accuracy`, `avg_ms`, `tracking`, `score` |
| `arena_reset` | every player inside an arena when it resets | `arena` |
| `episode_start` | every player in a match round, after the arena reset | `scenario`, `episode` (round number), `team` (colour), `teams`, `teammates`, `rule`, `time_limit` |
| `episode_end` | every player in the round | `scenario`, `result` (`win`/`loss`/`draw`), `reason`, `winner` (colour, empty on a draw), `duration_s`, `kills`, `deaths`, `damage_dealt`, `damage_taken`, `team_kills` |

In a match, a player who would die is knocked out instead (becomes a spectator until the round
ends), and still gets `death` (and the attacker `kill`). Dummies credited for hits on Peaceful
are reported as the attacker, as on other difficulties.

## Client status (client to server)

With the `status` feature, a linked client sends `TgStatusPayload(aiActive, sustainableTps)` on
`minigamebots:tg/status` about once a second: whether its AI is in control, and how many ticks
per second its brain keeps up with (1000 / (measured round trip + 5 ms), between 2 and 20).
A match with "Match AI speed" on sets the server tick rate to the slowest playing AI (never
below 4 or above 20), and back to 20 when the round ends. Minecraft slows the client to the
same rate, so the whole game runs at the AI's pace.

Unknown kinds and fields are ignored, so new ones can be added without breaking older clients.

# World files

Training Grounds keeps its data inside the world, in `<world>/minigamebots-training/`:

| File | Content |
|---|---|
| `arenas/<name>.json` | Box (min corner, size, dimension), spawn points, kit (vanilla item JSON per slot) |
| `arenas/<name>.nbt` | Block snapshot (vanilla structure template format, gzip) |
| `dummies.json` | Every training dummy: entity UUID, dimension, anchor point and settings |
| `scenarios/<name>.json` | A match: arena, teams (colour, spawns, kit, dummies and their level), win rule, kill target, time limit, looping, match AI speed, and results so far |

Temporary entities (damage numbers, aim targets) carry the tags `minigamebots_temp` and a
per-run tag; leftovers from an earlier run are removed when they load.
