# MinigameBots — Design Document

Status: **Design v1 — approved for nothing yet.** No code is written until the owner says go.
This document is the plan development will follow. When reality disagrees with it during
development, this document gets updated first, then the code.

---

## 0. Goals and constraints

### What we are building

Two Fabric mods for Minecraft Java **26.3**:

| Jar | Side | Purpose |
|---|---|---|
| **MinigameBots** (`minigamebots`) | Client | The AI itself and everything around it: virtual input, perception, memory, profiles, recording, video learning, training control, all GUIs. Works anywhere a normal client can join. |
| **MinigameBots Training Grounds** (`minigamebots-training`) | Server (also runs in singleplayer) | Optional. Builds training rooms: arenas, instant resets, practice targets, aim trainers, drills, scripted opponents, ground-truth rewards, tick control. |

### The end goal

An AI that, once trained, plays as a **real player with no human help**: it is given a goal
("mine diamonds", "protect Alex", "win this minigame") and works towards it, handling
interruptions (creepers, attackers, full inventory) the way a person would, with human-like
input. There is no ceiling on skill; it depends on how it is trained.

### Hard constraints

| Constraint | Consequence for the design |
|---|---|
| **RTX 2060, 6 GB VRAM**, old mid-range PC | Small active models, fp16, adapters, swap skill modules, lockstep training, fast mini-sims for drills. |
| **16 GB system RAM; hard ceiling 10 GB, preferably far less; the PC gets loud under load** | Resource profiles with **Quiet** as the default (section 7.1); RAM, CPU and GPU caps; skills stream from disk instead of being cached in RAM. |
| No bf16 on Turing (RTX 20xx) GPUs | Use fp16 mixed precision with loss scaling. |
| **No money budget** | Only free, open tools and weights. |
| **Fully local** | No cloud AI, no API, no tokens. The one exception is the one-time download of the runtime and pretrained weights at first setup. |
| **Client-side** | The AI mod never requires a server mod. Works on singleplayer, LAN, own servers and friends' servers. |
| **Real input, no packet bots** | All actions go through Minecraft's own input handling, so the client sends exactly what a human's client sends. |
| **Runs in the background** | The AI never touches the real keyboard or mouse; the window can be unfocused or minimized; several instances can run at once. |
| **Everything inside Minecraft** | Every feature has an in-game screen. The trainer is a background process the mod starts, controls and stops. |
| **About a month** of on-and-off training to "functional autonomous player" | Start from pretrained weights, learn from demos first, reinforcement learning (RL) second, and measure progress with exams. |

### Verified platform facts (Nov 2026)

- Minecraft 26.3 requires **Java 25**.
- 26.3 uses **SDL3** (through LWJGL 3.4) for windowing and input, not GLFW. Input hooks target that layer.
- Fabric Loader 0.20.x and Fabric API 0.166.x+26.3 exist.
- Flashback has 26.3 builds (0.43.x).
- 26.x uses Mojang's official names (no obfuscation mapping step).

### Unknowns to settle (they don't block development)

- CPU model. An old CPU most likely limits how fast the game itself can run during training, more than the GPU does.

---

### Guiding principle: the tool is easy, the skill is in the behaviour

Training a great AI takes skill, but that skill must be about **understanding behaviour** (what
to reward, what to show it, why it does something odd), never about **operating the program**.
Every feature therefore follows these rules:

- **Menus, not commands.** Everything is reachable from in-game screens; `/mgb` commands are only
  developer shortcuts.
- **Plain language.** Every option says what it does and when to use it.
- **Warn about common mistakes** (e.g. "this objective will probably teach it to avoid fights",
  "this reward can be farmed by standing still") before they cost hours of training.
- **Make behaviour visible.** Training sessions and exams can be replayed in the recording viewer
  with the AI's inputs and rewards tick by tick, so "why does it run away?" is something you watch.
- **Exams point at problems** ("aim score dropped since the last checkpoint"), not just numbers.
- **Undo is cheap.** Checkpoints let you roll back a session that made things worse, in one click.
- **Presets first, details on demand.** Sensible defaults for every plan and objective; advanced
  settings are there but never required.

---

## 1. System overview

```
┌──────────────────────────── Minecraft 26.3 (your PC) ───────────────────────────┐
│                                                                                  │
│  MinigameBots (client mod)                     Training Grounds (server mod,     │
│  ┌────────────┐ ┌────────────┐ ┌──────────┐    runs in the integrated server     │
│  │ Virtual    │ │ Observer   │ │ Event    │    in singleplayer)                  │
│  │ Input +    │ │ pixels +   │ │ Detector │◄──── ground-truth events,          │
│  │ Humanizer  │ │ state      │ │          │      resets, tick lockstep ─────────┤
│  └─────▲──────┘ └─────┬──────┘ └────┬─────┘                                     │
│        │ actions      │ obs          │ rewards                                   │
│  ┌─────┴──────────────▼──────────────▼─────┐   ┌──────────────────────────────┐  │
│  │ Brain Link (shared memory + local socket)│   │ GUIs, Profiles, Recorder,    │  │
│  └─────────────────────┬───────────────────┘   │ Memory, Video Lab, Knowledge │  │
│                        │                       │ Book, Watchdog               │  │
└────────────────────────┼───────────────────────┴──────────────────────────────┴──┘
                         │ localhost only
┌────────────────────────▼─────────────────────────────────────────────────────────┐
│ Brain Server (local Python + PyTorch + CUDA, started and stopped by the mod)     │
│  inference (playing) · training (imitation, RL, self-play) · video pipeline      │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Key decision: one brain runtime, in Python

The brain runs in a local Python process for **both** playing and training.

- **Why:** one model format; full PyTorch and CUDA support; the mature RL, vision and video
  tooling lives there. Writing a second inference engine in Java (DJL or ONNX) would double
  the work and drift out of sync.
- **Cost:** about 1 ms of local communication per decision, which is negligible at 20
  decisions per second.
- **Packaging:** the jar holds the brain's Python source. On first launch the mod's **Setup
  screen** downloads a standalone Python and pinned PyTorch (CUDA) into
  `.minecraft/minigamebots/runtime/` (about 3 GB, one time). After that, everything works
  offline.
- **Fallback:** if CUDA isn't available, it runs on the CPU (slow, but works for playing
  small models).

---

## 2. Repository layout

```
minigamebots/
├── docs/                  design, user guide, file-format specs
├── common/                shared Java: network payloads, Objective schema, file formats
├── client/                MinigameBots client mod      → minigamebots-<ver>.jar
├── training/              Training Grounds server mod  → minigamebots-training-<ver>.jar
├── brain/                 Python package (bundled into the client jar as resources)
│   ├── models/            core, skills, manager, IDM, realism critic
│   ├── train/             BC, PPO, self-play league, distillation, constrained RL
│   ├── video/             video import pipeline
│   └── tests/
└── tools/                 dev scripts (headless test client, data inspection)
```

- One Gradle multi-project build produces **two independent jars**. `common` is shaded or
  jar-in-jar into each, so each mod installs alone.
- The two mods talk over a **versioned** custom payload channel. A version mismatch
  disables the link gracefully instead of crashing.

### 2.1 Libraries

The mods use well-maintained public libraries rather than reinventing them. Every one below
has a 26.3 build (checked Nov 2026).

| Library | Used by | Required? | Purpose |
|---|---|---|---|
| **Fabric API** | both | Required | Events, networking, key bindings, commands, rendering hooks |
| **YACL** (Yet Another Config Lib) | client | Required | Config storage and settings screens |
| **Mod Menu** | client | Optional | Lists the mod and opens its settings |
| **Flashback** | client | Optional | Replay import (section 3.11) |

Bundled inside the client jar (jar-in-jar): **zstd-jni** (fast compression for recordings and
model files). Gson and Netty come with Minecraft.

Brain Server (Python, installed by the Setup screen): **PyTorch** (CUDA), **NumPy**,
**safetensors** (model files), **PyAV** (video decode with FFmpeg included),
**OpenCV-headless** (video preprocessing), JSON over the local socket (no extra library needed; fast enough for one observation per tick). The RL algorithms are
written in-house in a small, readable CleanRL style rather than adding a heavy RL framework.

Rule for adding a library later: actively maintained, used widely, available for the current
Minecraft version, and not required for the mod to start unless it's on this list.

---

## 3. Client mod: MinigameBots

### 3.1 Virtual input

**Principle:** the AI changes the *state* of Minecraft's own input system (key mappings,
mouse look, GUI cursor). Vanilla code then does everything else exactly as for a human:
movement physics, attack cooldown, packets. The server sees a normal client sending normal
amounts of traffic.

**Action space** (one decision per client tick, 20 Hz; designed to match OpenAI's VPT
action space so their pretrained weights can be used):

| Group | Actions |
|---|---|
| Movement | forward, back, left, right, jump, sneak, sprint |
| Interaction | attack (left click), use (right click), pick block, drop, swap offhand |
| Inventory | open/close inventory, hotbar 1–9, scroll |
| Camera | mouse Δyaw, Δpitch as foveated bins (fine near zero, coarse for flicks) |
| GUI cursor | when a screen is open: cursor movement plus left, right and shift clicks |
| Meta (non-human) | `mark_location(type)`, `forget_location(slot)`, `recall(type)`, `set_note(slot, value)`. These act on memory only, not on the game. |
| Chat | none learned. Canned replies only (section 3.11). |

Mouse movement within a tick is **interpolated across rendered frames** so the camera moves
smoothly on screen, as a human's does, instead of jumping 20 times a second.

**Humanizer** (a hard filter between the brain and the game; every value is configurable per AI):

| Limit | Default |
|---|---|
| Reaction delay | 180 ms ± variance; observations reach the brain delayed by this amount |
| Max turn speed and acceleration | Human-range curves from recorded data |
| Aim noise | Small, smooth jitter and overshoot |
| Clicks per second | ≤ 12 sustained, short bursts allowed |
| Key timing | Small natural variance on presses and releases |

"Fair perception" (on by default): the AI only receives information a human could see or
hear. See 3.3.

**Input isolation:** while the AI is in control, real keyboard and mouse input to *that*
window is ignored, except for the control hotkeys:

| Hotkey (rebindable) | Function |
|---|---|
| `F8` | Toggle AI control and human control |
| `F9` | Emergency stop: release all virtual keys and hand control to the human instantly |
| `F10` | Open the MinigameBots main menu |
| `F7` | Start or stop the Recorder |
| *(unbound)* | Mark base here (while recording), mark moment, pause/resume training |

All hotkeys are registered as normal Minecraft key bindings, so they appear in
**Options → Controls → Key Binds** under a **MinigameBots** category, and are rebindable there
like any vanilla key. The mod's own Settings screen shows the same list.

### 3.2 Background operation

| Vanilla behavior | Mod fix (active only while the AI controls the instance) |
|---|---|
| Pause menu on focus loss | Disabled |
| FPS drops when unfocused or minimized | Fixed render rate (default 20–30 FPS) |
| Captures the OS cursor | Never grabs the cursor; the mouse is virtual |
| Sound | Optional mute for background instances (sound *events* still reach the AI) |

Multiple instances (e.g. two AIs doing PvP self-play) are supported. A "low-resource
instance" preset sets small window, low render distance and minimal particles.

### 3.3 Observer: what the AI perceives

Each tick the brain receives:

1. **Pixels:** the game frame downscaled to 128×128 (configurable to 96–256). It is captured
   with asynchronous GPU readback so the game doesn't stall, and the GUI and HUD are included
   (the AI reads hearts and hotbar visually too).
2. **Structured state:** everything the HUD or F3 screen would let a human know:
   - health, hunger, saturation, armor, XP, active effects, air
   - full inventory and hotbar, selected slot. Items are described by **what they are like**,
     not just their ID, so unknown, modded and server-custom items still make sense
     (section 4.9).
   - velocity, on-ground, in water or lava, look angles; position *relative* to memory waypoints
   - open screen type and slot contents when a GUI is open
3. **Nearby entities** (up to 32, within 48 blocks): type, relative position and velocity,
   held item, armor tier, whether it's a player and on which team, whether it's visible.
   With fair perception on, an entity behind blocks is only kept for a short time after
   last sight, just as a human remembers where someone went.
4. **Local block sense:** a coarse 11×11×11 grid of block *categories* around the player
   (solid, air, liquid, dangerous, ore, interactive). Fair perception masks blocks that
   aren't visible.
5. **Sounds:** recent sound events with type and direction (what the subtitles accessibility
   option would show).
6. **Text signals:** chat lines, title and subtitle, actionbar, scoreboard sidebar, boss bars,
   tab list header and footer. These are used by the Event Detector and the
   "figure it out" objective, and turned into numbers and keyword flags for the brain.
7. **Goal input:** the current goal and its parameters (section 4.6).

### 3.4 Event Detector (client-side rewards)

This turns raw game changes into named events for the reward system:

`block_broken`, `block_placed`, `item_gained`, `item_lost`, `item_crafted`, `damage_dealt`,
`damage_taken`, `kill`, `death`, `assist`, `health_changed`, `hunger_changed`,
`entered_region`, `distance_to(target)`, `target_health(player)`, `scoreboard_changed`,
`chat_matched(pattern)`, `title_matched(pattern)`, `actionbar_matched(pattern)`,
`sound_heard(type)`, `screen_opened(type)`, `advancement_earned`, `time_elapsed`,
`stuck`, `idle`, `xp_gained`.

On servers without Training Grounds these are **inferred** client-side (e.g. "kill" means the
attacked entity died shortly after our hit). When Training Grounds is present, its
**ground-truth** events replace the inferred ones.

### 3.5 Recorder (learning from you)

- `F7` records your gameplay as **demos**: observations plus your *exact* real inputs, in the
  same format the AI uses. Demos are the best training data in the system.
- Recording is in chunks (`.mgbrec`). Frames are compressed with a video codec at 128×128,
  roughly **100–300 MB per hour**. Full-resolution recording is optional for later use.
- You can tag a recording ("pvp", "building", "bedwars"), trim it and mark moments
  ("good fight", "mistake") in-game. Marks become extra training signal.
- You can mark memory events while recording (e.g. a "Mark base here" hotkey). The AI learns
  *when* and *where* you mark from that.

### 3.6 Brain Link

- Frames travel through **shared memory**; small messages (state, actions, events, control)
  through a localhost socket. Nothing listens on the network outside this PC.
- **Latency budget:** the brain must answer within 50 ms. If it's late, the last action
  decays to "release all keys" so the bot never runs off a cliff because of a slow frame.
- The link handles brain-process crashes: it shows a toast, the bot stops safely, and the
  link auto-restarts.

### 3.7 Memory (no 100 GB brain)

| Layer | Size | Content | Lifetime |
|---|---|---|---|
| Working memory | a few KB | The network's recurrent state: the last seconds to minutes | Current session |
| Waypoints | 64 slots, about 8 KB | Position, dimension, type (base, chest, mine, portal, danger, farm, custom), up to 4 short notes, last-visited time, confidence | Saved per world |
| Coarse map | ≤ 2 MB | Explored chunks: biome group, danger level, resources seen, visited | Saved per world |

- Waypoints are written only through **meta-actions**, so marking is a learned behavior
  (what you asked for), not a script.
- **Training it to mark well:** rewards like "+ returned to base when inventory was full",
  "+ found the marked mine again", "− marked waypoint was never useful". It learns base
  *preference* from demos (where you pressed "mark base") and from Objectives (+ near water,
  + flat, + hidden, − near spawners, configurable).
- **World identity:** server address plus world/dimension fingerprint, so memories never
  bleed between worlds.
- The memory viewer GUI shows waypoints on a mini-map; you can edit, delete or pin them
  ("this is our base, never forget it").

### 3.8 AI Profiles and files

The Netflix-style **Profile screen** shows your AIs as cards (name, skin face, short stats).
A profile's skin comes from any Minecraft username, like a player head: it's fetched from
Mojang while online and kept in the profile (`skin.png`), so it keeps showing offline. A
username set while offline is fetched automatically the next time you're online.
Click one to **Play**, **Train**, **Edit**, **Duplicate**, **Merge** or **Export**.

**`.mgbot` file** (a zip, renamable and shareable):

```
Steve_Jr.mgbot
├── manifest.json        name, description, skin, author, created, version, foundation id,
│                        total training hours, exam scores, humanizer settings
├── core/                LoRA adapters on the foundation model (or a full core if trained from scratch)
├── skills/<name>/       one folder per skill module: adapter + policy head + value head
├── manager/             skill-selection network + interrupt rules
├── objectives/          the Objectives it was trained with
├── realism/             realism critic
├── history/             training curves, exam results, changelog
└── memories/ (optional) per-world memory
```

**Foundation models** (the large pretrained base, hundreds of MB) are stored **once** in
`.minecraft/minigamebots/foundations/` and referenced by ID, so each AI file is small
(typically 20–150 MB). "Export with foundation" bundles it for friends who don't have it.

Other files:

| File | Content |
|---|---|
| `.mgbrec` | Recording / demo |
| `.mgbobj` | An Objective (reward rules) |
| `.mgbkb` | A Knowledge Book (video Q&A answers under tags) |
| `.mgbplan` | A Training Plan |
| `.mgbarena` | An arena or scenario (Training Grounds) |

Everything lives under `.minecraft/minigamebots/`, browsable from the in-game screens.

### 3.9 Combining AIs

Averaging two networks together doesn't work, so combining is **modular**:

1. Every AI is a **shared core** plus **skill modules** plus a **Manager**.
2. **Merge** = pick skill modules from several AIs (PvP from one, Building from another),
   then run a short **Manager training** session so it learns when to use each.
3. **Rule:** skills can merge directly only if they were trained on the **same foundation
   ID**. Otherwise the merge falls back to **distillation**: the new AI learns to imitate
   each source AI on its own data. That's slower, but always works.
4. **Polish** (optional): distill the whole merged AI into one unified network for smoother,
   more human transitions between skills.

### 3.10 Video Lab and Knowledge Book

The **import pipeline** (all local, running in the Brain Server):

1. **Decode** the video with the bundled FFmpeg.
2. **Cut detection:** split it into shots.
3. **Shot classification:** first-person gameplay, third-person, menu, facecam,
   non-Minecraft, or sped up or slowed down. Only usable first-person gameplay is kept.
4. **Overlay masking:** detect static overlays (facecam box, watermark, subtitles) and mask them.
5. **Quality scoring:** blur, compression, resolution and FOV estimate. Low-quality segments
   are dropped or weighted down.
6. **Action labeling:** the **Inverse Dynamics Model (IDM)**, the "input guesser", predicts
   the keys and mouse moves between frames. It starts from OpenAI's released IDM, then is
   fine-tuned on your own recordings, so it improves as you record.
7. **Novelty detection and questions** (below).
8. **Output:** labeled demos, weighted lower than your own recordings.

**Asking questions:** the mod builds a **reference atlas** by rendering every item, block, mob
and GUI from your actual game (your version, mods and resource packs). Anything in a video
that doesn't match the atlas with confidence becomes a question in the Video Lab's
**Question Queue**, showing the frame with the unknown thing highlighted:

- *"What is this item?"* → vanilla with a different texture (pick the item) / custom or modded
  (give it a name and a behavior tag such as "revive", "weapon", "food", "cosmetic") / ignore
- *"Is this a vanilla screen or a custom menu?"*
- *"This HUD element changes. Is it a score, a timer, health or cosmetic?"*
- *"Is this footage vanilla mechanics or a custom server/plugin?"*

Questions are batched and ranked by how much they would help, so you're never flooded.
"Skip" is always allowed.

**Tags and the Knowledge Book:** every answer is stored under the tags you attach to a video
(`clownpierce-smp`, `custom-totems`, `faithful-pack`). Importing another video with those tags
reuses the answers automatically and only asks about new things. Knowledge Books are
shareable `.mgbkb` files.

**Limit:** without a language model it learns what players *do*, not what they *say*.
Commentary is ignored; your tags and answers provide the meaning.

### 3.11 Flashback integration (optional, auto-detected)

When Flashback is installed:

- **Import a replay:** Flashback plays it back while our Observer captures the recorded
  player's first-person view. Actions are derived from **exact** replay data: rotations give
  exact mouse movement, positions give movement keys, and swings, uses, block changes and
  hotbar changes come directly from the replay. This is much better than video.
- **Friends' gameplay:** pick any player in the replay to learn from their point of view.
- **Re-render variety:** replay the same footage with different texture packs, FOV and
  settings, for free training variety.
- **Auto rewards:** kills, damage and blocks are known exactly, so replays can be scored by an
  Objective, which lets RL-style methods learn from replays too.
- **Isolation:** the integration is a soft dependency behind an adapter. If Flashback's
  format changes, only the adapter breaks, never the mod.

Data quality ranking: **Recorder > Flashback > video.**

### 3.12 Autonomy and safety layer

This layer runs alongside the brain, always active:

- **Watchdog:** stuck detection (no progress for N seconds → nudge the Manager to change
  approach), loop detection, auto-respawn, reconnect after disconnect (configurable), and
  "lost" fallback (return to base waypoint).
- **Instincts** (a skill module with top priority): eat when hungry, retreat at low HP, avoid
  lava, falls and drowning, dodge creepers. It can override the current goal; its
  thresholds are trainable.
- **Idle behavior:** when no goal is active, it does natural player things (organize chests,
  small builds, wander nearby) instead of freezing.
- **Protected zones** (for friends' servers): regions the AI will never break blocks in or
  take items from, such as your friends' bases. Enforced by a hard filter, not just a reward.
- **Chat:** silent by default; optional canned replies matched by pattern ("gg" after a win,
  "brb" when paused). A nameplate or status hint ("🤖 AI") can be enabled for honesty with friends.
- **Session limits:** stop after N hours, stop on death count, stop if HP stays critical.

### 3.13 In-game screens (all features)

| Screen | Contents |
|---|---|
| **Setup** | First-run runtime download, GPU check, performance preset |
| **Profiles** | AI cards: Play, Train, Edit, Duplicate, Merge, Export, Import |
| **Play** | Choose a goal and parameters, start or stop, live status (current skill, confidence, Manager decision) |
| **Objectives Editor** | Build reward rules with dropdowns and sliders (section 4.5); test a rule live and see it fire |
| **Training** | Choose or edit a Training Plan, start, pause, stop, live graphs (reward, loss, win rate), checkpoint list, roll back |
| **Exams** | Run benchmark scenarios, compare checkpoints, see a score history per skill |
| **Recorder** | Recordings list, tags, trimming, marks, delete |
| **Video Lab** | Import videos or Flashback replays, pipeline progress, Question Queue |
| **Knowledge Book** | Browse and edit answers by tag, import or export |
| **Memory** | Waypoint mini-map, edit, pin, per-world list |
| **Skills** | View skill modules, rename, enable or disable, inspect stats |
| **Humanizer** | Reaction time, CPS, turn-speed curves, aim noise, fair perception |
| **Settings** | Hotkeys, resource usage, multi-instance, safety, chat |

Screens use Minecraft's own UI widgets so they look native and scale with GUI scale.
Settings and config screens are built with **YACL**, and the mod appears in **Mod Menu** with a
config button when Mod Menu is installed (section 2.1).

---

## 4. The brain

### 4.1 Model architecture

```
             goal + params ─────────┐
pixels ─► Image encoder ─┐          │
state  ─► State encoder ─┼─► Fusion ─► Memory (recurrent) ─► core features
entities ► Entity encoder┘                                        │
                                       ┌──────────────────────────┤
                                  Manager                  Skill modules
                           (which skill, every 0.5 s,   (adapter + policy head
                            interrupt handling)          + value head each)
                                                                 │
                                                       actions + value estimate
```

- **Image encoder:** a CNN backbone. With the VPT foundation it's VPT's own visual stack;
  for the Lite tier it's a small IMPALA-style ResNet.
- **Entity encoder:** a small attention block over the entity list, so it handles any number of entities.
- **Memory:** a recurrent transformer with a fixed-size cache (or GRU in the Lite tier),
  giving minutes of short-term memory in a few KB of state.
- **Skill modules:** low-rank adapters (LoRA) plus their own policy and value heads. They're
  small (5–60 MB), so many fit on disk and in RAM, and only the active ones sit in VRAM.
- **Value head = "can I win this?":** every skill learns to estimate expected future reward.
  The Manager uses the combat skill's value estimate for fight-or-flight decisions.

### 4.2 Two tiers

| Tier | Base | When |
|---|---|---|
| **VPT foundation** (default) | OpenAI VPT pretrained weights (released for anyone to use), fine-tuned once to 26.3 visuals into a shared foundation | Best quality; saves months. Recommended. |
| **Lite** | Small from-scratch network (~10–30M parameters) | Fast experiments, fast drills, weaker PCs, or if VPT weights are unavailable |

VPT was trained on Minecraft 1.16 visuals. **Step one of the training journey** is
"Foundation Adaptation": fine-tune VPT on your 26.3 recordings and Flashback replays so it
sees the modern game correctly. It's done once and shared by every AI.

### 4.3 Fitting a 6 GB GPU

- **fp16 mixed precision** with loss scaling (Turing has no bf16).
- The foundation stays frozen during skill training; only adapters and heads train.
- **Gradient checkpointing** to trade compute for memory.
- **Only active modules in VRAM.** Others stay **on disk** and load in a fraction of a second
  when the Manager switches (skill adapters are small). A small RAM cache (default 512 MB)
  keeps the most recently used ones ready.
- **CPU offload** of optimizer state only for the occasional big job (foundation adaptation),
  and only within the active RAM budget (section 7.1).
- The Brain Server **measures free VRAM** at startup and picks batch sizes and sequence
  lengths automatically.

So the limit is the size of *one active piece*, not the total brain. Training data lives on
disk and is effectively unlimited.

### 4.4 Training methods (all selectable in Training Plans)

| Method | What it does |
|---|---|
| **Behavior Cloning (BC)** | Imitate demos (Recorder, Flashback, video). The fastest way to learn any skill. |
| **IDM training** | Improve the input guesser from your recordings, for better video labels |
| **PPO (recurrent)** | RL against an Objective; goes beyond the demos |
| **KL-to-prior** | During RL, stay close to the human-like imitation policy unless deviating clearly pays off. Keeps it human and stable (the technique VPT used). |
| **Constrained RL** | Budgets instead of penalties (section 4.5), with automatically tuned multipliers |
| **Self-play league** | PvP vs current and past versions, with prioritized matchmaking against opponents it struggles with |
| **Curriculum** | Stages with promotion criteria (e.g. "win 70% vs Dummy L3 → move to L4") |
| **Realism critic (AMP/GAIL-style)** | A discriminator trained on human data; a "looks robotic" penalty becomes part of the reward |
| **Distillation** | Compress or merge AIs (section 3.9) |
| **Manager training** | Learn skill selection over a set of skills |
| **Fast-sim pretraining** | Drills such as aim, tracking and strafing pre-trained in a tiny built-in simulator at thousands of steps per second, then fine-tuned in the real game |

A **Training Plan** is an ordered list of these with data sources, Objective, scenario,
stopping rules and exam checks. Presets: **Easy**, **Balanced**, **Advanced**, plus a
fully manual editor.

### 4.5 Reward system: Objectives

An Objective is a list of rules:

```
rule:
  event:      kill | damage_taken | block_broken(dirt) | chat_matched("You won") | ...
  filter:     conditions (entity type, item, region, weapon, team, while_skill=pvp, ...)
  value:      +/- amount (per event, per unit, or per second)
  cap:        max total per episode (prevents farming one rule)
  cooldown:   min time between counts
  priority:   tier 1 (highest) .. 4
  forgivable: { by: [kill, target_survived], within: 20s }     # optional
  budget:     { limit: 6 hearts per fight }                    # optional, replaces value
  shaping:    potential-based helper (e.g. distance to target), never changes what is optimal
```

**The anti-avoidance toolkit** (your "take hits if it means winning" requirement):

1. **Forgivable penalties (escrow):** the penalty is held. If any listed success event happens
   within the window, it's cancelled. Otherwise it's applied **at the original moment** (the
   trainer rewrites the stored rollout before learning from it, so credit goes to the right
   action). Windows must be shorter than the rollout length (default ≤ 30 s); longer windows
   apply at resolution time.
2. **Priority tiers:** while a higher-tier goal is active (e.g. "protect target"), penalties
   from lower tiers are scaled down (configurable, default ×0.25 per tier below).
3. **Budgets (constraints):** "stay under N damage per fight on average". There's zero
   pressure while under budget, and the trainer raises the pressure automatically when it's
   over. Its caution is learned, not maximized.
4. **Anti-passivity:** small penalties for stalling, fleeing winnable fights (judged by its
   own value estimate) or running out the clock, plus an "engagement" reward option.
5. **Death is the real punishment;** small damage costs stay small by default. The editor
   warns when penalties outweigh the achievable rewards ("this Objective will likely teach
   avoidance").

The **final reward** each step = Goal Objective + Instincts Objective (always on, low weight)
+ realism penalty + exploration bonus (curiosity, decays over training).

**Example: Bodyguard**

| Rule | Value |
|---|---|
| target alive | +0.1 per second |
| kill (entity attacking the target) | +5 |
| damage_taken | −0.2 per heart, forgivable by `kill`, `target_survived` within 20 s |
| target_died | −10 (tier 1) |
| death | −5 |
| distance to target > 12 | −0.05 per second (shaping) |

### 4.6 Goals and the Manager

A **Goal** = an Objective plus parameters. The built-in goal library:

| Goal | Parameters |
|---|---|
| Gather / Mine | item, count |
| Kill / Hunt | target (player, mob type) |
| Protect | player name |
| Follow | player name |
| Build | a schematic file (`.nbt` / `.litematic`): rewards for each correctly placed block |
| Explore | radius, what to look for |
| Return home / go to waypoint | waypoint |
| Survive | duration |
| Play minigame | a minigame Objective, or **Figure It Out** (section 4.8) |
| Free play | idle and natural behavior |

Goals can be **queued** ("get iron, then build a house, then guard Alex").

**The Manager**, every 0.5 seconds:
1. Checks **interrupts**: instincts (danger, hunger), threats (sound or sight of hostiles,
   being attacked), inventory full, tool broken, lost.
2. Picks a skill using its learned policy plus the skills' value estimates. For example, if
   the combat value estimate is low, it flees instead of fighting.
3. Resumes the goal when the interrupt is resolved.

Example: on a "Mine diamonds" goal it uses Navigation and Mining. It hears a creeper and
switches to Combat. It's attacked by a strong player, the combat value estimate is low, so it
flees to base. Its inventory fills, so the Inventory skill places a shulker or the Manager
heads home.

### 4.7 Exams

Exams are fixed, repeatable benchmark scenarios. They need Training Grounds for exact setup,
or a lightweight client-only version without it. Each produces a score per checkpoint:

- **Combat:** kill a zombie; 1v1 Dummy L1–L5; 1v1 previous checkpoint; aim-tracking score
- **Survival:** first-night survival; get iron tools from spawn; find and return to base
- **Building:** small schematic accuracy and time
- **Movement:** parkour course; bridge 30 blocks; water-bucket clutch
- **Realism:** realism critic score on its own gameplay (higher = more human)
- **Generalization:** an unseen randomized minigame

The Exams screen shows trends, which tell you what to train next.

### 4.8 Unseen gamemodes: "Figure It Out"

This is a universal Objective built from signals most minigames share: my scoreboard value
rising, staying alive, kills, other players being eliminated, "victory/win" titles, positive
chat patterns, and timers not running out on me.

It's trained across many **randomized minigames** generated by Training Grounds, plus any
real minigames you have. Short-term memory lets it adapt within a match ("the void kills me",
"breaking that bed made my score go up"). Curiosity drives it to try NPCs, menus and pads.

**Expectation:** decent on games similar to ones it knows; on truly novel games it will try
sensible things and pursue visible progress signals, but often won't win, especially when the
rules are explained only in text.

### 4.9 PvP with any weapon: new, modded and server-only

**Requirement:** learn every form of PvP, including weapons added in future updates, modded
weapons and custom weapons that exist only on a server without Training Grounds, and, given
free rein, invent its own meta.

**Principle: nothing about weapons is hard-coded.** There is no "sword skill" or
"bow skill". Combat is one skill that learns *how items behave*.

**1. Items are described by properties, not IDs.** Every item the AI sees becomes an
**item description vector** built from what the client can know:

| Source | Examples | Works for unknown items? |
|---|---|---|
| Rendered icon | What it looks like, rendered by the game itself, including resource-pack and custom-model items on servers | Yes |
| Tooltip | Attack damage, attack speed, enchantments, lore lines, parsed into numbers and keywords | Yes |
| Client-visible components | Use animation (bow, crossbow, spear, block, eat, etc.), max durability, cooldown, food, attributes, charge time, whether it's a projectile weapon | Yes |
| Item ID | A learned embedding, used only if the ID is familiar | Falls back to the above |
| **Experience** | What actually happened when it was used: damage dealt, knockback, projectiles, cooldowns, effects | Yes, and it improves with use |

**2. Item experience memory.** Per item type, per server, the AI stores a small summary of
observed effects (≤ a few KB). The first time it uses a new server-only weapon it knows only
the icon and tooltip; after a few uses it knows "slow, huge knockback, short range". This is
the same compact-memory idea as waypoints.

**3. Learning on servers without Training Grounds.**
- **Demos:** record yourself using the new weapon on that server; imitation teaches the basics.
- **Live learning:** while playing on any server, the AI logs experience with client-inferred
  rewards (section 3.4) and improves from it later, offline, in a low-intensity training job.
  Nothing extra is sent to the server.
- **Knowledge Book:** custom items you've tagged in the Video Lab carry over ("custom totem: revive").

**4. Training for generality in Training Grounds.**
- **Item Lab:** give any item, including modded ones, to the AI, dummies and opponents, so
  every weapon in your mod list can be drilled.
- **Weapon Randomizer:** generates procedural weapons by changing attributes and components
  (damage, attack speed, reach, knockback, cooldown, projectile behavior, use time, custom
  icon). Training against thousands of made-up weapons forces it to *read* weapon stats instead
  of memorizing vanilla ones, which is what makes truly new weapons playable on first contact.
- **Random loadouts:** kits are sampled per episode, including mixed and odd combinations.

**5. Inventing its own meta.**
- **Free-range mode** (a Training Plan option): turns off or weakens the pull towards human
  imitation (KL-to-prior), raises exploration, and lets the AI **choose its own loadout** from
  an item pool before each match.
- **Diverse league:** the self-play league rewards beating strategies the league is weak against
  and keeps a population of different styles (quality-diversity), so it doesn't collapse into one
  strategy and keeps discovering counters.
- **Meta Lab screen:** shows discovered strategies as clusters (preferred loadouts, distances,
  timings) with win rates over time, plus Flashback-style recordings of its best matches so you
  can watch what it found.
- **Realism trade-off:** free-range strategies may look inhuman. The realism critic can be kept
  on (human-looking meta) or off (pure strength).

---

## 5. Server mod: Training Grounds

This mod runs on a dedicated server or **inside singleplayer**. For singleplayer, put the jar
in your client's `mods` folder; the integrated server loads it.

### 5.1 UI without needing a client mod

Its menus use **vanilla server-driven UIs** (chest-style menus and vanilla Dialog screens)
and commands (`/tg ...`), so they work for any player, even without the client mod. When the
MinigameBots client mod is also present, it gets richer native screens (arena preview,
graphs) over the same data.

### 5.2 Arenas

- **Arena wand:** select a region, name it, and save it as a snapshot.
- **Instant reset:** restore blocks, clear dropped items and entities, reset player state
  (HP, hunger, effects, inventory kit) in well under a second for small arenas.
- **Instances:** clone an arena N times side by side so several AIs train in parallel.
- **Randomizers:** layout generators (random obstacles, platform layouts, cave generator,
  parkour generator), block-palette swaps, time and weather, so the AI doesn't memorize one map.
- **Spawn points, kits, teams,** and bounds (leaving the bounds = configurable loss).

### 5.3 Episodes and scenarios

A **Scenario** = arena + kits + spawns + opponents + win/lose conditions + timeouts + reward
events + randomization. It's saved as `.mgbarena`. Episodes start, end and reset
automatically, with a stats log.

### 5.4 Practice targets and drills

**Training Dummies** (a custom entity with an HP bar, floating damage numbers and DPS/combo
counter):

| Setting | Options |
|---|---|
| Movement | stationary, path, strafe, circle, random, jump, sprint-reset, retreat |
| Defense | none, shield blocking (timed or random), armor tiers, totems |
| Offense | none, or fights back with configurable reach, CPS, aim accuracy, reaction time |
| Difficulty presets | L1 (punching bag) → L5 (sweaty) |

**Aim Trainer:**
- Flick targets (appear and vanish), tracking targets (smooth or erratic movers), switching
  between multiple targets, and headshot zones for projectiles.
- Scores: accuracy, time-to-hit, tracking %. Humans can use it too, for comparison.

**Weapon and combat drills:**
- Crit timing, sprint-reset (W-tap) and knockback combos
- Axe shield-breaking, shield timing
- Bow, crossbow and trident range targets (static and moving, varying distance)
- Mace drills (height and timing)
- Totem pop handling, potion and gapple usage under pressure
- Crystal and anchor PvP drills (optional pack)
- Rod and projectile-knockback drills
- **Item Lab** and **Weapon Randomizer** (section 4.9): drill any item, including modded and
  procedurally generated weapons

**Movement courses:** parkour generator by difficulty, speed-bridging lanes, water-bucket
clutch towers, ladder and vine climbing, elytra ring course.

**Resource and building drills:** mining caves with seeded ores; tree farms; schematic build
plots with block-accuracy scoring; crafting stations with "craft X" tasks; inventory-sorting tasks.

**Survival scenarios:** mob waves (escalating), first-night survival, creeper ambush, Nether
survival, and a **bodyguard** scenario (protect a VIP dummy or a real player).

**Minigame templates** (each randomizable for Figure It Out training): Duels, Sumo, Spleef,
TNT-run style, Bedwars-lite, Skywars-lite, King of the Hill, Capture the Flag, Hide & Seek,
Last Man Standing, plus a **Random Minigame generator** that combines win conditions, maps and
kits.

### 5.5 Opponents

- **Scripted opponents:** server-side fake players with selectable styles and difficulty
  levels (the same knobs as the dummy's offense settings).
- **Other AI clients:** for self-play. The mod pairs connected AI clients per the league's
  matchmaking.
- **Humans:** you and friends can jump into any scenario; stats are tracked the same way.

### 5.6 Ground truth and speed

- **Ground-truth events** to the client mod: exact damage with source and weapon, kills,
  assists, block changes, scenario events, episode start and end.
- **Tick control:**
  - **Lockstep mode:** the server advances one tick only when every AI in the arena has sent
    its action (using vanilla tick freeze/step underneath). The game never runs faster than
    the AI can think, and training runs as fast as your PC allows instead of real time.
  - **Sprint mode** for scenarios that don't need every frame.
- **Episode sampler:** randomized seeds, positions and opponents per episode.

### 5.7 Commands and screens

- `/tg arena`, `/tg scenario`, `/tg dummy`, `/tg aim`, `/tg drill`, `/tg session`, `/tg stats`
- Screens: Arena Editor, Scenario Builder, Dummy Configurator, Drill Picker, Session Control
  (start, stop, lockstep, instance count), Stats and Leaderboard.

---

## 6. Protocols

| Link | Transport | Notes |
|---|---|---|
| Client mod ↔ Brain Server | Shared memory (frames) + localhost TCP with length-prefixed JSON (messages) | Versioned; local machine only; token-checked. See `docs/formats/brain-protocol.md` |
| Client mod ↔ Training Grounds | Fabric custom payload channel `minigamebots:tg/v1` | Handshake with feature list; graceful when absent |
| Brain Server ↔ files | `.minecraft/minigamebots/` | Atomic writes and checkpoint rotation |

---

## 7. Performance plan for your PC

### 7.1 Resource profiles (RAM, noise and heat)

Your limit: 16 GB total, **10 GB absolute ceiling**, much lower preferred because the PC gets
loud. Every profile is enforced with a hard cap. The Brain Server watches its own memory and
reduces batch sizes or pauses rather than exceeding the cap.

| Profile | Total RAM for this mod's processes* | GPU | CPU | Use |
|---|---|---|---|---|
| **Quiet** (default) | ≤ 6 GB | power-limited, frame cap, training duty cycle (e.g. 70% on / 30% off) | trainer limited to 2 threads | Everyday play and slow background training |
| **Balanced** | ≤ 8 GB | normal | 3 threads | Focused training sessions |
| **Max** | ≤ 10 GB | full | all but 1 thread | Overnight, when noise doesn't matter |

\* Minecraft client heap + Brain Server, excluding Minecraft's own baseline outside the heap.

How it stays low:
- **One Minecraft client** during RL in Quiet mode (each extra client costs about 1.5–2.5 GB).
  Self-play in Quiet mode runs **both sides in one brain** against one client plus a cheap
  server-side opponent, or against past versions inside the same brain process.
- **Imitation training and video import run with the game closed**, or in a dedicated
  "training only" mode that keeps Minecraft at the title screen, freeing its RAM.
- **Data streams from disk**, never loaded in full.
- **Skills load from disk on demand** with a 512 MB RAM cache.
- **Low-resource client preset** during training: small window, render distance 4–6, minimal
  particles, client heap ~2 GB.
- **Quiet hours:** schedule Max only for times you choose (e.g. while you're asleep in another room).

### 7.2 Expected speeds

| Activity | Expected setup |
|---|---|
| Playing (inference) | 1 instance, VPT-tier brain in fp16, about 1–2 GB VRAM; ~4–5 GB RAM total including Minecraft |
| Imitation training | Game closed; GPU-bound |
| RL in Training Grounds | Quiet: 1 low-resource client in lockstep, **~30–60 steps/s**. Max: 2–3 clients, **~60–150 steps/s**, likely limited by the old CPU. Roughly 1–4 million steps per overnight run. |
| Fast-sim drills | Thousands of steps/s, GPU-bound, very little RAM |
| Video import | Runs overnight; roughly real time or faster at 128×128 |

Overnight mode queues a Training Plan, checkpoints regularly, keeps the best one by exam score,
and can shut Minecraft down when finished.

---

## 8. The training journey (target: about one month)

| Week | Focus | Outcome |
|---|---|---|
| **0 (setup, 1–2 days)** | Install, runtime setup, record 3–5 hours of varied normal play, import any Flashback replays | Data to start with |
| **1** | Foundation Adaptation (VPT → 26.3); BC on your demos; IDM fine-tune; start video imports | Walks, mines, crafts and fights like a rough copy of you |
| **2** | Skill modules: Navigation, Mining, Inventory, Instincts. RL in drills and scenarios with exams | Reliable basic survival skills |
| **3** | Combat: aim fast-sim → dummy curriculum → self-play league; realism critic | Solid PvP, human-like input |
| **4** | Manager training, goals, memory and base behavior, merge and polish; Figure It Out on random minigames | Autonomous "real player" for common goals |

After that it's open-ended: more data, more self-play, more skills. There is no cap.

---

## 9. Development roadmap

Each milestone ends with tests, a working jar, and a short demo you can try.

| # | Milestone | Contents |
|---|---|---|
| M1 | **Skeleton** | Gradle multi-project, two jars, shared `common`, CI build, version handshake |
| M2 | **Body** | Virtual input, Humanizer, background operation, input isolation, hotkeys, Observer, Event Detector |
| M3 | **Recorder** | `.mgbrec` format, recording UI, playback viewer |
| M4 | **Brain link and runtime** | Setup screen, runtime download, Brain Server, shared memory link, scripted "test brain" |
| M5 | **Profiles** | `.mgbot` format, Profile screen, import and export, foundation store |
| M6a | **Imitation** | BC training with the Lite network, Training screen, checkpoints, learned policy plays (done) |
| M6b | **Foundation** | VPT weights import and adaptation to 26.3, as a foundation the Lite pipeline can use |
| M7 | **Training Grounds core** | Arenas, reset, scenarios, dummies, aim trainer, ground-truth events, lockstep |
| M8 | **Objectives and RL** | Objective editor, escrow/priority/budget rewards, PPO, KL-to-prior, exams |
| M9 | **Memory** | Meta-actions, waypoints, coarse map, memory screen |
| M10 | **Skills and Manager** | Skill modules, interrupts, goals, merge, distillation |
| M11 | **Combat league** | Self-play matchmaking, scripted opponents, fast-sim aim |
| M12 | **Realism** | Realism critic, humanizer tuning from your data |
| M13 | **Video Lab** | Pipeline, IDM labeling, reference atlas, Question Queue, Knowledge Book |
| M14 | **Flashback** | Replay import, POV capture, re-render variety |
| M15 | **Autonomy and polish** | Watchdog, idle behavior, protected zones, chat, Figure It Out, minigame templates and generator |

### How development is tested

- **In this cloud container:** compile and unit tests; a **real 26.3 client** on a virtual
  display (Xvfb, software rendering) driven by scripts, with screenshots I check myself;
  dedicated-server tests for Training Grounds; tiny CPU-only trainer smoke tests.
- **On your PC:** real GPU training, performance, feel and playing with friends. You report
  results, logs or screenshots, and I fix them.

---

## 10. Risks and honest limits

| Risk | Mitigation |
|---|---|
| VPT weights (1.16 visuals, specific sizes) may not fit 6 GB for every training mode | Adapters plus freezing plus fp16; Lite tier as fallback; verify sizes in M6 |
| RAM budget limits parallel clients, so RL is slower in Quiet mode | Lean on imitation, fast-sims and overnight Max runs |
| Server-only weapons give no ground truth | Property-based item descriptions, experience memory, demos, Weapon Randomizer training |
| An old CPU limits game speed in training | Lockstep, low-resource clients, fast-sims, imitation-heavy training |
| 26.x rendering and input internals change between updates | Keep hooks small and isolated; one place to fix per update |
| Flashback format changes | Soft dependency behind an adapter |
| Edited videos are noisy | Strict filtering; lower weight than your own data |
| Reward design can teach weird behavior | Editor warnings, exams, KL-to-prior, realism critic |
| No chat intelligence (no language model) | Canned replies; documented limitation |
| First-time setup download about 3 GB | One-time; progress shown in the Setup screen |

---

## 11. Decisions log

| Question | Decision |
|---|---|
| System RAM | 16 GB total; ≤ 10 GB ceiling; Quiet profile (≤ 6 GB) is the default |
| PvP scope | All forms, including future, modded and server-only weapons, plus self-invented metas (section 4.9) |
| Hotkeys | `F7`–`F10` defaults, rebindable in vanilla Controls and the mod's Settings screen |
| Dependencies | Popular maintained libraries are allowed (section 2.1) |
