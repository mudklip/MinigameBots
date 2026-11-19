# MinigameBots

A Minecraft AI that learns any in-game skill, from mining and building to every form of PvP,
and plays through a virtual keyboard and mouse like a human player. Everything runs on your
own PC: no online AI services.

Two Fabric mods for Minecraft Java **26.3** (Fabric Loader 0.20.1+, Fabric API, YACL):

| Jar | Install on | What it does |
|---|---|---|
| `minigamebots-<version>.jar` | Your client | The AI and everything around it. Works on any server. |
| `minigamebots-training-<version>.jar` | A server, or your client for singleplayer | Training Grounds: arenas, dummies, aim trainer, matches, league, self-play, multi-world training. |

## Install

Download the release zip, then follow its `README.txt`: run the included Fabric installer,
copy the `mods` folder's jars into `.minecraft/mods`, start the game. In game, press **F10**
and open **Guide**: a getting-started checklist and short pages for every feature (press
**F1** on any MinigameBots screen for its page).

## What it can do

- **Learn from you:** record yourself (F7) and train (imitation learning, Lite or OpenAI VPT base).
- **Learn without you:** scripted teacher demos (expert fighter, Baritone travel/mining), a
  fast aim simulator, practice with rewards (PPO), a league that targets its weak spots,
  self-play against its past versions, and training worlds (many arenas at once, all watched
  live on one screen).
- **Learn from others:** gameplay videos (Video Lab, with an input guesser and a Question
  Queue) and other players seen in your recordings or Flashback replays.
- **Skills, one by one:** 36 ready-made skills plus your own; choose what each is rewarded
  for, which actions it may not use, its stages, hints and learning speed.
- **Attitudes and fair play:** neutral, hunt players, guard, peaceful or from its personality;
  tells a friend's joke punch from a real attack; counts other players' health from what it
  saw (hits, armour, totems, golden apples) instead of reading it.
- **Backups:** of the whole AI; go back completely or only for some parts (e.g. one network).
- **Play on its own:** goals and a Manager (hunt, mine, explore, follow, protect...), free play,
  memory (waypoints, allies and enemies by banner/shield insignia), built-in Minecraft knowledge.
- **Stay human and safe:** humanizer limits, a realism critic and your style picks, protected
  zones, session limits, F9 emergency stop.
- **Optional mind (local language model):** `/ai` commands in plain words, chat in character,
  and push-to-talk voice.

## For developers

- Design and decisions: [docs/DESIGN.md](docs/DESIGN.md); formats in [docs/formats](docs/formats).
- Build: `./gradlew build` (Java 25). The brain's tests run with the brain environment
  (`BRAIN_PYTHON`), and are skipped without numpy/PyTorch.
- Brain (Python): `brain/minigamebots_brain`, bundled into the client jar and unpacked on use.
- Optional Baritone jar for the release zip: `scripts/build-baritone.sh`.

Third-party licences for the bundled libraries are in the release zip's `licenses` folder.
