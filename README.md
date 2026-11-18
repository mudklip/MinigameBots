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

Milestone 1 (project skeleton): both jars build, and the two mods recognize each other with a
versioned handshake. There is no AI yet.

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
