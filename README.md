# MinigameBots

A Minecraft AI that learns any in-game skill, from mining and building to every form of PvP,
and plays through real keyboard and mouse input like a human player.

Two Fabric mods for Minecraft Java **26.3**:

| Jar | Install on | What it does |
|---|---|---|
| `minigamebots-<version>.jar` | Your client | The AI and everything around it. Works on any server. |
| `minigamebots-training-<version>.jar` | A server, or your client for singleplayer | Optional training rooms: arenas, resets, practice targets, drills. |

Both need [Fabric Loader](https://fabricmc.net/use/) 0.20.1+ and
[Fabric API](https://modrinth.com/mod/fabric-api).

The full plan is in [docs/DESIGN.md](docs/DESIGN.md).

## Status

| Milestone | State |
|---|---|
| M1 Skeleton: two jars, versioned handshake | Done |
| M2 Body: virtual input, Humanizer, background mode, hotkeys, Observer, Event Detector | Done |
| M3 Recorder | Next |

There is no brain yet: the AI's body can be driven with test scripts (below).

## Hotkeys

Rebindable in **Options > Controls > Key Binds > MinigameBots**.

| Key | Action |
|---|---|
| F7 | Start/stop recording (arrives in M3) |
| F8 | Toggle AI control |
| F9 | Emergency stop: the AI releases every key and you're back in control |
| F10 | MinigameBots menu |

While the AI is in control your keyboard and mouse are ignored by that game window (apart from
the hotkeys), so you can use the PC for other things. The game keeps running when unfocused.

## Settings

F10 > Settings, `/mgb config`, or Mod Menu. Humanizer limits (reaction time, turn speed,
aim wobble, clicks per second), background frame rate, and perception options.

## Test commands

Client-side only; they never reach the server.

| Command | What it does |
|---|---|
| `/mgb run <script>` | Drive the AI with a test script, e.g. `/mgb run hold forward+sprint 40; look 90 0; tap jump` |
| `/mgb ai on` / `off` | Give the AI control (it stands still) / take it back |
| `/mgb observe` | Save what the AI perceives to `minigamebots/debug/` (observation.json, frame.png) |
| `/mgb watch on` / `off` | Show detected events (kills, damage, items, blocks...) in chat |
| `/mgb events [n]` | List the last n detected events |
| `/mgb config` | Open settings |

Script steps (separated by `;`): `hold <keys> <ticks>`, `tap <key>`, `look <yaw> <pitch>`,
`click <per-tick> <ticks>`, `wait <ticks>`. Keys: forward, back, left, right, jump, sneak,
sprint, attack, use, pick, drop, swap_offhand, inventory, hotbar1-9; combine with `+`.
20 ticks = 1 second.

## Downloading builds

Every push builds both jars on GitHub. Open the repository's **Actions** tab, pick the latest
**Build** run, and download **minigamebots-jars**.

## Building yourself

Requires JDK 25.

```bash
./gradlew build
```

Jars end up in `client/build/libs/` and `training/build/libs/`.

## Development

| Command | What it does |
|---|---|
| `./gradlew :client:runClient` | Dev client with both mods (singleplayer has Training Grounds) |
| `./gradlew :training:runServer` | Dev dedicated server with Training Grounds |
| `./gradlew test` | Unit tests |

Project layout:

| Folder | Contents |
|---|---|
| `common/` | Shared protocol code, bundled inside both jars |
| `client/` | MinigameBots client mod |
| `training/` | Training Grounds server mod |
| `docs/` | Design and specs |
