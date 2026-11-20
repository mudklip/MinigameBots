# A small, brain-like mind: design

Goal: turn the AI from "a network that maps what it sees to keys" into something closer to a
mind: it remembers, predicts, feels, focuses, sleeps on what it learned, and keeps improving,
while staying cheap enough for an RTX 2060 6 GB next to Minecraft. Once it has reached its
peak at something, it must never get worse than a human at it again.

The trick the brain uses, and this design copies: **almost nothing runs most of the time.**
Small fast parts run constantly; bigger slow parts wake up only when something matters;
memories cost nothing until something reminds you of them.

## 1. Three speeds of thinking

| Loop | Rate | What it does | Cost |
|---|---|---|---|
| **Reflexes** | 20/s while busy, 5/s when calm | The skill networks (what exists now): aim, move, hit, block | ~1 ms, small network |
| **Awareness** | 2-4/s | Predicts the next second; notices surprises; picks what to attend to; drives/feelings | tiny network + rules |
| **Strategy** | every 30-60 s, or when woken | Goals, plans, who to fight, where to go (the strategist layer) | rules + optional learned scorer |
| **Reflection** | offline (idle, game closed, overnight) | "Sleep": replays and learns from the day, tidies memory | uses spare time only |

**Surprise wakes the slow parts.** The awareness loop keeps a small prediction of what happens
next (where the enemy will be, will I take damage, will the block break). When reality differs
a lot from the prediction, that surprise:
- raises attention (reflexes go back to 20/s),
- writes the moment into memory as important,
- wakes the strategist early ("they have a crossbow now, rethink").

When nothing surprising happens, it coasts at a low rate and costs almost nothing, like walking
on autopilot.

## 2. Memory, like a person's

All memories are small records, not neural activity. They cost nothing until a cue brings them up.

- **Working memory (seconds):** the network's internal state (already there) plus a few
  "slots" for the most important things right now: my target, its counted health, where the
  danger is, what I was doing. Like keeping a phone number in your head.
- **Episodic memory (days):** short records of things that happened, with how it felt:
  `who, where, when, what happened, outcome, feeling (good/bad, how strong)`. Example:
  *"Steve, near the river base, yesterday: hit me with an axe from behind, I lost, fear 0.8."*
  Only surprising or emotional moments are stored, so it stays small (thousands of records,
  a few MB).
- **Recall by cue (content-addressable):** seeing Steve, his insignia, or the river base looks up
  memories by that key (a hash table: instant, no network). That's how "I see him and remember
  I dislike him" works without thinking about him all the time.
- **Person models (semantic memory):** during sleep, episodes about one player are boiled down
  into a small profile: *dangerous 0.9, uses axe, crits a lot, runs at low health, betrayed me
  twice, wears the blue banner*. A profile is ~30 numbers. Profiles feed the fight decisions,
  trust (insignia/betrayal), and predictions of what that player will do (theory of mind).
- **Place memory:** the existing waypoints and map, plus feelings about places ("died here
  twice": avoid; "diamonds here": go back).
- **Skills (procedural memory):** the trained networks, as now.
- **Forgetting:** unimportant memories fade (a forgetting curve); strong or repeated ones stay.
  Keeps memory small and recent, like ours.

## 3. Feelings that steer, cheaply

The drives from 1.5.0 (fear, anger, hunger, duty, boredom) become the glue:
- memories carry a feeling; recalling them nudges the current feelings (seeing someone who hurt
  it raises fear or anger instantly);
- feelings set attention and priorities: what to look at, what to do, how much to risk;
- curiosity is a feeling too: surprise in a safe situation = "explore that", which is how it
  discovers new tricks on its own (intrinsic reward for learning).

## 4. Learning without forgetting ("never weaker once it's good")

- **Champion rule:** a new version only replaces the current one if it beats it in exams
  (already partly there: best-checkpoint saving). Never downgrade automatically.
- **Exam battery per skill:** fixed tests for every skill it has mastered (aim score, duel win
  rate vs set bots, mining speed...). Every new version must pass all of them, not just the skill
  being trained. If one gets worse, the update is rejected or repaired.
- **No catastrophic forgetting:** while learning something new, it replays a little of every old
  skill's data (rehearsal), and the weights that matter most for mastered skills are protected
  (they're expensive to change).
- **Human floor:** for each mastered skill, a recorded "human reference" score (yours, or good
  players' from spectator learning). The exam battery fails any version that drops below it.
- **Short-term adaptation without touching the core:** a tiny add-on per opponent or per server
  (a few thousand numbers, "fast weights") learns their quirks during a fight or session and is
  thrown away or kept with their person model. The core skill never gets worse from one weird
  opponent.

## 5. Sleep (offline consolidation)

When the game is idle, closed, or overnight:
1. **Replay** the day's important moments (most surprising/emotional first) to train the skills.
2. **Dream:** use the small world model to imagine variations of hard moments ("what if I had
   blocked there?") and practice on those: many extra practice rounds for almost no cost, no game
   needed.
3. **Consolidate:** turn episodes into person and place profiles; forget the trivial.
4. **Distill:** if a bigger or privileged teacher learned something, compress it into the small
   playing network.
5. **Exam battery** before accepting the new version (section 4).

## 6. Keeping it small

- Skills as separate small experts; a router wakes only one or two at a time (already the
  Manager's skill switching).
- Detailed attention only on the few most important entities (already: entity priority);
  everyone else is a coarse "crowd" radar.
- 8-bit weights for playing (training stays in higher precision): about 4x smaller, faster on CPU.
- Lower thinking rate when calm (section 1).
- The language model (mind) is never in the moment-to-moment loop: only for chat, personality
  and occasional plan suggestions.

Budget target: playing uses under 1 GB RAM and under 1 GB VRAM; reflexes about 1 ms per tick;
memory lookups instant; sleep uses idle time only.

**Rule: Baritone is never part of the AI.** It's only a teacher during training (demos the AI
learns from). Everything the AI does while playing comes from its own networks, memory and
rules.

## 7. What it isn't

It's still machine learning, not a conscious mind. "Never weaker than a human" is enforced by
the exam battery and the champion rule: it's a guarantee on the things it's tested on, not on
every possible situation. Long cunning plans still come mostly from the strategist's rules
until the strategist has learned enough.

## 8. Order to build it

1. Episodic memory + recall by cue + person profiles + forgetting (cheap, big effect; also
   powers insignia trust).
2. Awareness loop: next-second prediction, surprise, adaptive thinking rate.
3. Exam battery per skill + human floor + champion rule for every skill.
4. Rehearsal and weight protection in all training.
5. Sleep: replay and consolidation; then dreaming with the world model.
6. Fast-weight adaptation per opponent.
7. Strategist layer on top, using all of the above.
8. 8-bit playing weights.

## 9. Creativity: its own builds, traps and redstone

Creativity here = **imagine many options cheaply, test them for real, keep what works, and
remember it.** Designing is kept separate from building, so it's cheap.

1. **Designs, not hand movements.** A design is a small block plan (up to about 16 x 16 x 16)
   plus a short "how it's used" note. Inventing happens on these plans, not by fumbling with
   blocks; its own learned building skill puts them in the world. (Baritone's schematic builder may
   only act as a teacher in training, recording building demos for that skill to learn from; it is
   never part of the AI itself.)
2. **A library of parts (motifs).** Pieces it knows: walls, roofs, doors, stairs; pressure plate
   + TNT, piston door, dispenser + lava, trapdoor drop, observer clock, tripwire, hidden pit...
   Seeded from the built-in knowledge (redstone.json), then grown from what it sees: builds and
   contraptions it watches players use (spectator learning), your recordings, schematics.
3. **Inventing = combining and mutating.** New designs are made by snapping parts together,
   changing sizes and materials, moving triggers, hiding things, and small random changes
   (evolution). This is where genuinely new combinations come from.
4. **Testing for real, instantly (Training Grounds sandbox).** Each candidate is built in a
   hidden test arena, the game itself runs it (real redstone, real physics, sped up), and dummies
   walk in or fight there. It measures what matters:
   - traps: did it catch/kill, how often, how fast, how hidden (how visible from a few blocks),
     how cheap, how hard to escape;
   - builds: shelter (enclosed, lit so no mobs spawn), defence, cost, build time, and looks (scored
     by your "which is better?" picks, like the realism style model).
5. **Keep many good ideas, not one** (a "hall of fame" per kind: the cheapest trap, the most
   hidden, the deadliest, the fastest to build...). Diversity is what makes it look creative.
6. **Remember inventions.** Good designs go into memory with notes ("worked on Steve twice",
   "needs 3 TNT", "only on flat ground"); bad ideas are remembered too so it doesn't retry them.
7. **Using them.** The strategist picks an invention when it fits: memory says enemies keep
   walking a path -> choose a hidden trap that fits the terrain and what's in the inventory,
   adapt it (test again if changed), build it, watch what happens, learn from the result.
8. **Inventing happens in "sleep".** The sandbox search runs when you're idle or overnight, so
   playing stays fast. Each test is just the game simulating a few blocks: cheap.
9. **The mind (language model), optional:** can suggest ideas in words ("a pressure plate under
   gravel above lava"), which become designs and must still pass the sandbox test. A small model
   is weak at 3D, so it's only a source of seeds.

Rules of conduct apply: traps and builds only where allowed, never on others' bases unless the
server is for that. Realistic expectations: it will get good at combining known pieces in new
ways and fitting them to the situation; inventing big new redstone machines from scratch is
beyond this.

**Build order:** after the mind basics (memory, sleep), add: block-plan format + part library ->
sandbox tester in Training Grounds -> evolution with a hall of fame -> its own building skill
(trained partly on Baritone building demos) -> strategist uses inventions.

## 10. Best all-rounder on the same PC

The PC won't grow, so trade **time, data and cleverness** for hardware:

1. **One shared core, tiny skill add-ons.** One network learns general game sense (seeing,
   moving, items, danger) once; each skill is a small add-on (adapter, a few hundred KB) on top.
   New skills learn faster (they reuse the core), and dozens of skills fit in memory.
2. **Headless training worlds.** Run Training Grounds on a background server with no rendering,
   sped up (/tick sprint), the brain steering its bodies directly: many times faster than
   real time, mostly CPU, and possible while you play or overnight.
3. **Fast simplified simulators** for pretraining (like the aim simulator): a Python 1v1 combat
   model with hits, knockback, cooldowns and crits runs thousands of fights a second; the real
   game then fine-tunes ("sim-to-real").
4. **Slow big teacher, fast small player.** Overnight, train a bigger model (slow is fine
   offline) and distill it into the small network that plays.
5. **Knowledge as tools, not learned.** Exact things are computed, not learned: damage
   calculator (exists), crafting/smelting recipe planner (wood -> iron -> diamond -> nether ->
   end), enchanting, brewing, mob facts. Almost free; the networks decide, tools calculate.
6. **Items understood by their properties** (damage, speed, durability, food value, block
   hardness), not just their names, so it handles items it has never used, new versions and
   modpacks.
7. **Robust to any server:** training with random ping/lag, different knockback, kits and
   click limits, so skills transfer to real servers.
8. **Free data at scale:** spectator learning and Video Lab running in idle time; spectating
   lots of real games is the biggest skill source that costs no GPU while playing.
9. **Auto curriculum + exam battery:** always train the weakest skill, never let mastered ones
   slip (section 4).
10. **Sharing:** export/import skills and whole AIs, so time other people's PCs spent training
    can be reused (with the exam battery checking anything imported).
11. **Budget manager:** one scheduler for GPU/CPU between the game, playing, training and the
    language model: train when idle, back off when FPS drops.

Priority for this hardware: 2 (headless worlds) and 1 (shared core) give the biggest jump, then
5 and 6, then 3 and 4.

## 11. The Core: common sense first (top priority)

The **Core** is the AI's first and most important skill: general understanding of how Minecraft
works, trained before anything else, that every other skill and every new game builds on. It
turns the project from a PvP robot into a general player. In the Skills list it is the first
card, above every category, with the **light block** icon (minecraft:light).

### What it learns (common sense)
Trained on everything it can get (your recordings, spectator learning, videos, training worlds,
simulators), with self-taught goals that need no labels:
- **What happens next:** predict the next seconds (positions, health, blocks, items) = a small
  world model.
- **Cause and effect:** what an action does (hit -> knockback, eat -> food, place -> block,
  throw -> projectile flies and hits), and who caused what.
- **What matters:** predict scoreboard, chat, titles and deaths from what's happening; learn
  that things like health, score, lives and "last one standing" are what games are about.
- **Objects by their properties** (section 10.6): an unseen item is understood from what it does.

This becomes the shared core network (section 10.1) that all skills sit on.

### Understanding a new minigame quickly: the rule finder
A small, cheap, symbolic part that watches events and finds rules from very few examples:
- it counts what follows what: "a snowball hit me -> I died" (1 of 1 times),
  "my snowball hit Steve -> Steve died, +1 on the sidebar 'Kills'" (2 of 2), and compares it with
  how often those things happen anyway (so coincidences don't count);
- it reads the text the game shows (sidebar titles like "Kills"/"Lives", titles like "You won!",
  chat, item names) by keywords; the mind (language model) can help read explanations, if on;
- after a handful of rounds it has rules like **"snowball hit = elimination (both ways)"** and
  **"most kills / last alive wins"**, each with how sure it is.

### Turning rules into play, at once
- Rules become an **automatic objective**: what leads to winning or score = reward; what leads
  to dying or losing = penalty; plus drives (fear of snowballs, eagerness to throw them).
- It **borrows skills that fit:** snowball throwing ~ projectile aiming (Archery), dodging ~
  strafing (PvP), spleef ~ breaking blocks under others; the Core says which skills relate.
- A small **per-game add-on** keeps learning the specifics while it plays (section 4 fast
  weights), and the game's rules + add-on are **remembered**: recognised next time by the
  server/lobby/sidebar, so it starts out already knowing that game.

### Training the Core (no setup)
1. **Pretrain** on all collected data (self-taught goals above), overnight.
2. **Minigame generator in Training Grounds:** instant random games built from rule pieces
   (one-hit snowballs, sumo knockback, spleef, TNT run, king of the hill, capture the flag,
   parkour race, last-alive, team modes...). Practising on endless new games teaches it to
   *learn games fast* (meta-learning), not just to play one.
3. **Exam:** unseen generated games; score = how many rounds until it plays well.

Cost stays low: the Core is the shared small network (no extra player cost); the rule finder
is counting and lookups; pretraining and the minigame generator use idle/overnight time.

### Where it goes in the plan
Make it the first big feature: Core (shared network + self-taught pretraining) -> rule finder ->
automatic objectives and skill borrowing -> minigame generator -> per-game memory. The memory
and sleep parts (sections 2 and 5) and the headless worlds (10.2) support it.

## 12. Reaching further (all run on an RTX 2060 6 GB, 16 GB RAM)

Narrow, Minecraft-only versions of abilities the AI lacks. Only things that work on this PC are
listed; anything needing data-centre scale is left out on purpose.

1. **Cause-and-effect rules.** Extend the Core's rule finder from "X tends to follow Y" to "X
   causes Y" by testing: in a Training Grounds sandbox, do the cause or leave it out and see
   whether the effect still happens. Rules become explainable ("I was knocked out because the
   floor vanished after I stood still") and carry over to similar games. Cost: counting plus a
   few short sandbox tests during idle time.
2. **Learned strategist.** The strategist layer (section 1) learns its choices (gear up, travel,
   fight, retreat, ambush) by playing whole sessions in sped-up headless training worlds; the
   plan structure stays hand-written, the choices are learned. A decision every 30-60 s is small
   enough to train overnight on this PC.
3. **Rules told in words.** "Snowballs knock you out", typed or said, is turned by the mind
   (language model, optional) or by keyword patterns into a rule-finder rule, so a new game starts
   with that rule instead of discovering it. Runs once per sentence.
4. **Faster learning of new games.** More generated games and kits in the minigame generator, so
   practice across many games makes it quicker at the next one (measured by the Core exam:
   rounds until it plays well). Uses idle/overnight time.
5. **Sleep with daily reflection.** While idle or overnight: replay the day's important moments to
   train; consolidate memories into person and place profiles; once a day the mind (if installed)
   reads the day's memories and writes a few lessons ("Steve runs at low health: chase him").
   Each lesson is only trusted after what actually happens confirms it. One short language-model
   run per day.

Order: 5 (sleep, builds on memory) -> 1 (cause and effect, builds on the Core) -> 3 -> 4 -> 2
(needs headless worlds first).
