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
