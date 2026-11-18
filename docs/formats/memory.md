# AI memory, version 1

Each AI remembers each world separately, in `<profile>/memories/<world>/`. The world is
`sp-<save folder>` in singleplayer or `mp-<server address>` on a server, so memories never mix.
Exports leave memories out unless asked.

## memory.json

| Field | Content |
|---|---|
| `waypoints` | Up to 64 places: `id`, `type` (base, chest, mine, portal, danger, farm, custom), `name`, `dimension`, `x`/`y`/`z`, `notes`, `created`, `lastVisited` (ms), `confidence` (0-1), `pinned` (never forgotten or moved by the AI), `byAi` |
| `players` | Player name -> `FRIEND` / `NEUTRAL` / `ENEMY` |
| `insignias` | Insignia id -> relation |
| `insigniaLooks` | Insignia id -> what it looks like ("shield red + white stripe_top") |
| `autoMarkAttackers` | Whoever hits the AI or the person it protects becomes an enemy (name and insignia) |
| `otherTeamsAreEnemies` | A player on another scoreboard team counts as an enemy |
| `trust` | Which signal wins when they disagree: `NAME_TEAM_INSIGNIA` (default), `NAME_INSIGNIA_TEAM`, `TEAM_NAME_INSIGNIA`, `INSIGNIA_NAME_TEAM` |

Players start **neutral**. Same insignia as the AI's own counts as an ally unless marked otherwise.

**Insignia** = what someone visibly wears that marks a side: the shield's base colour and banner
pattern layers (offhand or main hand), a banner worn on the head, and dyed leather armour
colours. Its id is a hash of that description; empty when nothing distinctive is worn.

## map-<dimension>.bin.gz

The coarse map: gzip of `int version (1)`, `int stamp`, `int count`, then `count` x (`long chunk
key (x << 32 | z)`, `int value`). Value bits: 0-3 biome group (other, plains, forest, desert,
snow, mountain, ocean, swamp, jungle, nether, end, cave), 4-5 danger 0-3 (hostiles seen), 6 ore
seen, 7 water, 8 chest or other interactive block seen, 16-31 visit stamp. At most 500,000
chunks (about 2 MB); the least recently visited are dropped first. Filled while the AI
perceives (playing, recording or watching), once a second.

## What the AI sees and does

- Observation `waypoints`: the 4 nearest in the dimension: `type`, `name`, `dx`/`dy`/`dz` from
  the eyes, `distance`, `pinned`.
- Each entity: `insignia`, `relation` (-1 enemy, 0 neutral, 1 friend, resolved by the trust
  order), `allyInsignia`, `enemyInsignia`.
- The network gets these through zero-initialised extra inputs (older trained AIs load and
  behave exactly as before) and has a "mark" output: none, or one of the waypoint types. It
  learns marking from recordings (the "mark base" hotkey) and practice (events
  `waypoint_marked`, `waypoint_reached`, `insignia_marked_enemy` can be rewarded). The brain sends
  it as `"mark": "<type>"` on an action; the mod adds it at most every 5 s, and a mark of the
  same type within 8 blocks moves that waypoint instead of adding one.
