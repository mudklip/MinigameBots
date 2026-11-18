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
| M3 Recorder: record demos, marks, Recorder screen, playback viewer, export/import | Done |
| M4 Brain link: in-game setup of Python + PyTorch, local brain process, test brains | Done |
| M5 Profiles: AI cards with skins, editor, per-AI human limits, export/import | Done |
| M6a Imitation learning: train an AI on your recordings, Training screen, let it play | Done |
| M6b Pretrained foundation (OpenAI VPT) | Next |

There is no learned brain yet. The brain link works with simple hand-written test brains, and
the AI's body can also be driven with test scripts (below).

## AI profiles

Each AI is a profile: **F10 > AI Profiles**. Click **+ New AI** to make one: give it a name, a
description, optionally any Minecraft username to show that player's skin (fetched while online,
then kept for offline), and its own human limits (reaction time, clicks per second, turn speed,
aim wobble). Double-click a card (or **Use this AI**) to choose which AI plays.

**Train** teaches an AI to play like your recordings (imitation learning): pick the
recordings that show what it should learn, a plan (Quick try ... Overnight) and how hard the PC
may work (Quiet, Balanced, Max), then press **Start**. Progress, accuracy and a graph show while
it trains; training keeps going if you close the screen, and you get a message when it's done.
Moments you marked as mistakes in a recording are skipped; marked good moments count double.
**Let it play** hands control to the trained AI (F9 takes it back). The Predictability setting
chooses between human-like variety and always its single best guess.

Duplicate an AI to experiment safely, or **Export** it to a single `.mgbot` file to share (its
per-world memories are left out). To import a friend's AI, put the file in
`minigamebots/profiles/import/` and press **Import**. Merge arrives with milestone 10.

## The brain

The AI thinks in a separate Python process on your PC (nothing goes online). Set it up once in
**F10 > Brain**:

- **Install (GPU)** for NVIDIA cards (downloads a standalone Python and the CUDA build of
  PyTorch, about 4-5 GB), or **Install (CPU)**.
- **Use my own Python...** builds the brain's environment from a Python 3.10+ you already have
  instead of downloading one. Your Python itself isn't changed.

Then pick a test brain and press **Give it control**:

| Test brain | What it does |
|---|---|
| follow | Equips a sword or axe, walks to the nearest pig and hits it |
| random | Wanders around |
| spin | Turns on the spot |
| idle | Does nothing |
| torch_smoke | Runs a small (untrained) neural network on every frame, to check PyTorch and the GPU |
| learned | The AI in use, playing with what it learned in training |

F9 always takes control back. After updating the mod, press **Update** in the Brain screen if
it asks for new packages. Logs: `minigamebots/logs/brain.log`, `install.log` and `training.log`. Protocol:
[docs/formats/brain-protocol.md](docs/formats/brain-protocol.md).

## Hotkeys

Rebindable in **Options > Controls > Key Binds > MinigameBots**.

| Key | Action |
|---|---|
| F7 | Start/stop recording |
| F8 | Toggle AI control |
| F9 | Emergency stop: the AI releases every key and you're back in control |
| F10 | MinigameBots menu |

Unbound by default (set them in Controls): mark good moment, mark mistake, mark base here.

While the AI is in control your keyboard and mouse are ignored by that game window (apart from
the hotkeys), so you can use the PC for other things. The game keeps running when unfocused.

## Recording demos

Press **F7** to record your own gameplay as training examples; press it again to stop. A REC
badge shows while recording. Each tick saves what the AI would see (a 128x128 frame plus game
state) and exactly what you did, in the AI's own input format. About 100-300 MB per hour.

Manage recordings in **F10 > Recordings**: view them frame by frame, rename, tag, trim the part
to train on, add marks, delete, and export to a single `.mgbrec` file to share. To import a
friend's file, put it in `minigamebots/recordings/import/` and press Import. Format details:
[docs/formats/recording.md](docs/formats/recording.md).

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
| `/mgb rec start` / `stop` / `list` / `open` | Recorder controls |
| `/mgb rec mark <type>` | Mark the current moment (good, mistake, base, or your own) |
| `/mgb brain open` / `play <brain>` / `stop` | Brain screen, give a test brain control, stop the brain |
| `/mgb profiles` | Open AI Profiles |
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
| `./gradlew test` | Unit tests (Java) |
| `./gradlew brainTest` | Unit tests (Python brain) |

Project layout:

| Folder | Contents |
|---|---|
| `common/` | Shared protocol code, bundled inside both jars |
| `client/` | MinigameBots client mod |
| `brain/` | The brain's Python code (bundled into the client jar) |
| `training/` | Training Grounds server mod |
| `docs/` | Design and specs |
