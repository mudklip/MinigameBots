# Notes: to-do list and ideas

## To fix (reported, not done yet)

1. ~~Training worlds pile up arenas in the terrain~~ and 2. ~~far worlds go "dumb"~~: fixed in
   1.7.0 (compact grid around the player, terrain put back on stop). Leftover arenas from older
   versions stay where they are; a "clean up old arenas" button would help.
3. ~~UI clipping, rest of the pass~~ (done in 1.8.0: Versions, Teacher demos, Goals sliders,
   Hub buttons fixed; the other screens checked and fine). Was: Still to check and fix at GUI scale 3 (427x240): Practice,
   Exams, Versions, Realism, Teacher demos, Recordings, Recording viewer, Edit recording,
   Character, Brain, Foundations, Video Lab question queue, Goals (slider labels clip:
   "ikes exploring"), Profiles editor, Merge, Guide.
4. ~~Disabled actions outside skill practice~~ (done in 1.8.0: Training Hub > Never... list,
   applied to everything the AI does). Was: Locks only apply while a skill is practiced or
   coached. Free play, Goals and Let it play ignore them. Add a global "never do" list (per AI)
   that applies to everything the AI does.
5. **Flicker/stutter when the brain starts** (partly fixed in 1.6.1; the brain process start
   itself still to measure) (Python + PyTorch loading onto the GPU). Measure on
   an RTX 2060 6 GB, with the mind loaded too; load in the background at lower priority, and
   warm up the GPU before the AI takes control.
6. **Mind download (WinError 10054).** Retries and resume shipped in 1.1.1; not yet confirmed
   on a real Windows PC.
7. ~~Training is thin outside PvP and aim~~ (done in 1.8.0: instant practice areas built around
   you for 20 skills; scripted teachers for them still to come). Was: Most skills have rewards and hints but no teacher
   demos or Training Grounds setups (mining course, farm plot, bridge lane, parkour course,
   build area, inventory drill). Add setups and scripted teachers for them.
8. ~~Neutral/guard attitudes need real behaviours~~ (1.8.0: guard radius, farm reflex, stash ->
   base; building still needs a trained skill). Was: Farm, build and stash goals only run their
   skills if trained; give them built-in reflexes. Guard should stay within a radius.
9. **Trained networks only see best-guess health** of other players, not the range, certainty,
   totems or golden apples. Add as inputs (needs retraining).
10. ~~Plans and skills keep separate levels~~ (1.8.0: plan levels include their skills' XP). Was: The Plans tab's PvP/Aim levels and the skill
    levels don't add up into each other; show one progress picture.

## Requirements for everything new (from the user)

- **No setup, ever:** any new game, arena or training mode must work instantly from a button.
- **Fixes first,** then features.
- **More customization** for training options and training worlds (kits, arena size/type,
  opponent mix, round length, speed, which skill each world practices...), still simple by
  default.

## Planned features (agreed)

- **#1 priority: the Core** (docs/MIND_DESIGN.md section 11). Done in 1.9.0: rule finder (counts
  cause -> effect with a coincidence check; win/lose titles, kills, deaths, scores, projectile
  hits), automatic rewards from rules, skill borrowing suggestions, per-game memory by sidebar
  title, "Learn this game" mode, Core card (light block) first in Skills. 1.10.0: Core plan and
  Core category; generated games (snowball, one_hit, sumo, king) built around you; each game
  learns into its own network, starting from the best-fitting skill's network (a). 1.11.0:
  (b) self-taught prediction head (8 signals, next 1-5 s) trained from recordings, surprise and
  curiosity; (d) exam per game (rounds until 3 of 5 won); Spleef and TNT Run. Was: (b) self-taught
  pretraining heads (predict next seconds, events, deaths) on the shared network,
  needs a model change and retraining, (c) the minigame generator in Training Grounds (instant
  random rule sets: one-hit snowballs, sumo, spleef, TNT run, king of the hill, last alive...),
  (d) exam: rounds needed to play an unseen generated game well.

- **Teams of the same AI:** several bodies running the same AI, no special channel between
  them: they only see what a player in their place would see; with the mind on they may use
  normal chat like players do, without it they rely on judgement (no friendly fire, focus the
  same target when it makes sense, spread out).
- **Spectator learning** (done in 1.12.0: Watch tab, ratings, action-only demos, counted health,
  game/server tags, once-only conversion; 1.13.0: watches when a human is in control or as a real
  spectator (AI too), per-game networks trained from watched demos, ratings list). Spec: watch real players
  around you (e.g. random games of random PvP kits on a server) and learn from them as
  efficiently as possible. While learning it may use more than the player's eyes (exact
  positions and actions of everyone nearby, behind walls too), but **not exact health** (some
  servers spoof it): health is counted like in fair mode. As it advances it should learn
  anything useful even from bad players but not copy their bad habits: rate each player from
  what it sees (wins, hits landed vs taken, combos), weight their moments by outcome and by
  that rating, and keep only what worked. Must be easy to understand: one switch, a live
  "learned from N fights, M players" readout. Builds on Watch & learn.
- **Long goals (manhunt, "kill many players"):** a strategist layer above the Manager that
  decides every 30-60 s from long-term memory (gear up, hunt the target, retreat and heal,
  ambush, pick winnable fights); rules first, later learned in sped-up whole-session games
  (instant modes such as "Manhunt vs bots" and free-for-all survival with bot players). The mind
  may suggest plans.
- **Memory** (done in 1.14.0: remembered moments with feelings and forgetting, person profiles,
  recall by cue into the brain, joke vs real attacks, betrayal and insignia trust, Memory screen).
  Next for memory: places with feelings, sleep consolidation.
- **Insignia trust** (done in 1.14.0): trust per shield/banner insignia that drops on real attacks by its wearers
  (more for several different betrayers), suspicious wearers with a bad history, trusted ->
  careful -> enemy, slow recovery, shown in the Memory screen with the reason and a reset.
- Auto curriculum, per-skill rating graphs, thought overlay, replay with thoughts, global
  "never do" list and rules of conduct, server profiles, sharing skills/AIs, performance
  guard, menu-minigame skill type, more arena types (bridge, void, parkour, mining, farm,
  build) with kits, AI vs AI tournaments, privileged training critic, restyle older screens.

## Human-like brain (1.15.0 done: place memory, fast/slow thinking, 8-bit CPU play, never-worse
check per skill, why-it-lost explanations, rule carry-over, rules told in words). Left for the
very end: **sleep** (needs special handling: minimise/background the game while sleeping, etc.).
Not done: fast weights per opponent, human-floor scores.

## Reaching further (agreed, all fit this PC; docs/MIND_DESIGN.md section 12)

Sleep with daily reflection -> cause-and-effect rules -> rules told in words -> more generated
games for faster learning -> learned strategist (after headless worlds).

## Done from earlier reports (for reference)

Spectating a training world; per-skill restore from backups; locks shown clearly and held
for a whole coached session; Practice spelling; block/creature/item picker; recording that
restarted itself (Watch & learn); freezes from backups on the game thread; aim command spam;
Memory zoom buttons.

## 1.18.0: headless training worlds
In singleplayer the Training Grounds server hands each tick's world views straight to the
brain through a shared-JVM hook (System properties `minigamebots.worldStep` / `worldStop`), so
the player's own client is no longer the relay and stays in the human's hands. LAN and dedicated
servers keep the old relay. "Fast & light" (Worlds tab, on by default) also runs the built-in
server at 40 TPS and drops the client to 10 FPS / 2-chunk view; everything is restored on stop
or when leaving the world.

## 1.19.0: teams of the same AI
Training worlds take a team size (1v1-4v4; `/tg multitrain here <n> <style> <team>`). Each learner
body is steered on its own (own recurrent state and rollout, key (world, slot) in the brain);
teammates appear in each other's observation as `sameTeam`/`relation: 1`, nothing else is shared.
Round result is shared (knocked-out teammates still get the win/loss). `team_fight` objective adds
team_kill, friendly_fire, team_focus, team_help, team_clump. Real play: `TeamTalk` sends callouts
only via `say` (so only with chat replies on) and reads a teammate's "help"/"on me" chat to
prioritise enemies near them; scoreboard-teammate hits (<3 in 10 s) are accidents in memory.
Fixed: starting worlds no longer cancels Fast & light; Plans tab "Advanced..." no longer overlaps
the tabs (now "More...").

## 1.20.0: game-mode bots, dummy shields wear out
`training.dummy.ModeBots`: server-side bots for the Core minigames (registered per foe through
`DummyManager.setBrain`). BFS paths over the remaining floor (no holes, no crumbling blocks, no
corner-cutting, 2-block straight jumps over 1-block gaps). Spleef digs under/ahead of the target,
TNT Run seeks the largest intact patch and avoids recently visited cells, Sumo/King position on the
centre side and sprint-hit outwards, Snowball strafes, dodges and throws with lead and drop.
Skill from the dummy level. Baritone itself only drives a player's client, so it can't steer these.
Vanilla `BlocksAttacks.hurtBlockingItem` only wears players' shields: `afterDamage` now wears and
breaks a mannequin's shield the same way. TNT Run: 3 s grace before crumbling.

## 1.21.0: training-worlds customization, free-for-all minigames
`MultiTrainer.Options` (kit, size, type flat/pillars/edge, mix single/mixed/ladder, round seconds
with "draw", practice duel/crits/wtap/shields/mixed) from `/tg multitrain here <n> <style> <team>
<k=v ...>`; client keeps them in MgbConfig (world*) and sends them only when not default.
`Kits` gives both sides the same gear; the shield drill gives learner an axe and opponent a shield.
Learner hits now also emit `tg_damage_dealt` with charge/sprint_hit/crit/blocked/shield_disabled
(from `DummyManager.lastSwing`), so the drill templates work in training worlds; the brain picks
a world's objective from its `objective` field. Speed 1-3x via FastTraining (light mode separate).
Core minigames: `/tg coregame <kind> ffa` (Core screen toggle, off by default) makes the mode bots
target the nearest contestant instead of only the player. Spleef bots wait 3 s before digging.

## 1.22.0: the learned strategist
`policies/strategy.py`: plans gear_up/hunt/heal/ambush/pick_fights/roam/guard, chosen every 30-60 s
or on surprise (death or `tg_death`, health < 8, player kill). 15 situation features; rule prior
per plan blended with a per-plan ridge regression (A, b; trust n/(n+25)) learned from the plan's
outcome (kills incl. training mannequins, deaths, damage, gear, target kill), normalised per 45 s;
10% exploration after the first plan while learning. Saved in <profile>/strategist.json.
Manager: on for free play and long goals (`manhunt` {player}, `kill_players` {count}), off for a
plain task list; plan -> sub-goal (roam = normal free play; guard at night = free play's "go home"),
courage added to the flee threshold, `min_odds` for which prey to take; `ambush` goal = sneak and
look about. Status adds `strategy` and `long_goal`; Goals screen shows the plan.
Training Grounds `StrategyArena` (`/tg ffa start [bots]|stop`): 37x37 arena with cover, bots of
mixed styles each on its own team (all fight all), own respawns, player deaths caught (reported as
`tg_death`, respawn in the arena with kit). PvP plan: free-for-all step before the exam.

## 1.23.0: creativity, part 1 (inventing traps and shelters)
`brain/invent.py`: designs are small block plans (palette + [dx,dy,dz,i], path/inside, dispenser
items) built from "genes" over a part library (traps: pit/lava pit, pressure plate + TNT or arrow
dispensers, cobweb, magma, floor material; shelters: walls/roof/door/torch, sizes, materials).
`propose`: 40% new random genes, 60% mutations/crossings of the hall of fame; skips known ids
(failed list + kept designs). `record`: scores (traps: harm, caught, visibility, cost; shelters:
enclosed, light >= 8, room, cost), hall of fame per quality (3 each), failures remembered;
<profile>/inventions.json. Training Grounds `Sandbox`: TgRequestPayload "sandbox_test" (new
serverbound payload, up to 32 KB), 4 slots 80 blocks above the player on a stone/obsidian slab,
victim dummy walks the path for 10 s (walker brain), shelters flood-filled; result as
`sandbox_result`; the space is cleared to air with entities removed. Client: InventSession loop,
Inventions screen (main menu) with top-down pictures. Next: its own building skill, then the
strategist using inventions.

## 1.24.0: creativity, part 2 (its own building skill)
`policies/build.py` ("build"): places a design by real input: digs first (top-down, and makes
room for blocks set into the ground), then places bottom-up against filled neighbours; walks
within reach (4.3), selects the hotbar slot, aims at the support face, `use`; verifies in the
block sense cube; 4 tries per block, missing items reported. Turns account for ordered-but-not-
yet-seen rotation (humanizer smoothing) like FollowPolicy. Client `BuildRunner`: anchor 6 blocks
ahead, overlay progress line, records the session as a teacher demo "Build: <name>" for a
building skill; `design_kit` request gives the blocks (Training Grounds managers). Not yet:
block orientation (dispensers face the builder), filling dispensers, Baritone build demos.
Placement is planned like a player: a standing spot on a ring 2 outside the design (walking around
it via ring corners), a face that points at the eye, an exact look-ray check that the block lands
in the right cell (re-checked right before clicking, once the view has settled), jump-placing for
the top of 2-high walls, and for shelters a "gate" (the door column) left open until last so blocks
reachable only from inside (middle of a roof) are placed from inside. A clicked block is never
clicked again (that stacked towers in-game). Tests: a fake world with ray-cast placement and jumps.

## 1.25.0: creativity, part 3 (inventions in play)
Strategist plans `set_trap` and `build_shelter` with features trap_ready / shelter_ready (the
Manager has the items for a kept design and is allowed: profile `useInventions` off|shelters|all,
default shelters; traps only with "all"). Not ready = score -9 (never picked, not even to explore).
The plan's goal `invent_build` runs an embedded BuildPolicy 5 blocks ahead; finishing a shelter
marks it "base"; finishing a trap switches to ambush by it; fights/fleeing interrupt (restart
later; placed blocks count as done). strategist.json from older versions is reset (new features).

## 1.26.0: sleep
`client/sleep/SleepMode` + `SleepScreen` (main menu > Sleep...). Steps: backup; memory
consolidation (`Mind.consolidate`: forgetting curve, then drop weak >1-day episodes about people
with a profile); replay recordings since `MgbConfig.lastSleep` (first time: last 24 h) of sources
human/player into the main network and "Build: ..." teacher demos into the building skill, plan
QUICK, resources QUIET; optional inventing (traps 5 min, shelters 5 min) which keeps the world
running. Low load: 10 FPS, render distance 2, master volume 0, SDL_MinimizeWindow (26.x uses SDL3,
not GLFW); the SleepScreen is a pause screen in singleplayer unless inventing. Wake (any key, Wake
up, or when done) restores everything and shows a morning report. Not yet: dreaming with the world
model, daily lessons from the language model, automatic sleep when idle.

## 1.27.0: fast weights per opponent, human floor
`policies/fastweights.py`: per-opponent additive biases on the learned policy's key / attack /
use logits (opponent = player or mannequin in the latest exchange of hits within 100 ticks),
updated each tick from events (dealt +a/20, taken -a/20, kill +1, death -1) by a policy-gradient
step over a 30-tick trace (decay 0.85, lr 0.08), clipped to +-2, faded between fights (half per
day), saved in <skill>/fast_weights.json (200 most recent opponents). LearnedPolicy status shows
`adapting_to` and `adapted`. Human floor: `HumanBench` (Progress.human: "duel" win rate over 10
rounds of the running match, "aim" best 60 s flick score), only while the human plays; Exams
screen shows AI vs you.

## 1.28.0: facing, filling, lessons, dreaming, sleep when minimised, Baritone building demos
- Build: `placement_ok` encodes vanilla getStateForPlacement facing rules (toward: dispenser,
  dropper, piston, barrel; away: observer; horizontal: door, stairs; horizontal opposite: chest,
  furnace family; trapdoor clicked face / opposite horizontal; hopper opposite of clicked face).
  Planning requires the look to clearly pick that direction (margin 1.4) and re-checks with the
  actual look before clicking; inner standing ring (1 out) is a fallback. Thin blocks (plates,
  torches...) are looked over and never used as supports. Fill steps (design "items"): find a spot
  with a clear view, right-click, move the cursor to its own stack (learning px/degree), shift-click,
  close with the inventory key. Observer `gui.slotPos` = every slot centre + mine flag (accessor
  mixin on AbstractContainerScreen leftPos/topPos). Sandbox/kit fill any Container.
- Lessons: Mind.Lesson (careful_with / can_beat / avoid_place / idea); facts from Person/Place
  counters; brain `lessons` message (mind/lessons.py phrases; templates without the mind); trust
  0.3 + 0.2*confirm - 0.3*contradict since made; trusted (>= 0.6) adjust recalled danger/place
  feeling. LessonsScreen from the Memory screen.
- Dreaming: dream.py tiny MLP (situation + action -> next-second dealt-taken) on own recordings;
  skip if held-out correlation < 0.3; hard moments get the best alternative if clearly better ->
  "dream" recording (weight 0.3) included in the sleep replay.
- Sleep when minimised: MgbConfig.sleepWhenMinimised (off); Window.isIconified for 5 s starts,
  restore wakes. Never time-based.
- Baritone BUILD teacher: kept invention as a Proxy ISchematic (cells outside the design are
  "don't care"), kit requested, demos named "Baritone: build" feed the building skill in sleep.
  Only designs that fit (nothing below the world bottom / unbreakable in the way); liquid cells are
  dug instead (Baritone can't pour); `allowInventory` on so a kit outside the hotbar is used.
- Builder deep pits (build.py shaft mode): a dig or place it can't do from the standing rings
  starts a shaft in a column next to the cell (no design cells below the floor layer; way in
  straight out from the design). Phases approach -> enter (centred, sneaking) -> down (dig the
  block underfoot, never into liquid or a drop) -> work (deep digs with clear sight, places from
  the shaft; liquids only below its feet) -> climb (jump + place underfoot, design block if the
  cell is one, else a filler). Also: digs need a clear sight line with margin (centre + 4 face
  points) and a settled aim, never the block it stands on; no hops near the build; turn before
  walking; re-aim from the real eye when off-centre; non-jump spots before jump ones; corner
  routing prefers corners that see the goal; climb out of a hole if done while down in one.
  MGB_BUILD_TRACE=<file> logs every change of what it's doing (debugging).
- Opponent reading (policies/opponent.py): per-opponent traits (range, aggression = closing speed,
  strafe = lateral speed in my view, block-after-my-hit, crit = airborne when hitting me,
  low_retreat). Each trait: slow estimate (prior from <profile>/styles.json, capped at 15 obs) and
  fast EMA; if they differ by more than SPREAD after 8 fast obs -> style switch, slow = fast.
  Counters are key/attack logit nudges added to the fast-weight bias in learned.py (circle against
  strafe, spacing vs rushers, close in vs kiters, no swing into a block, back off from air crits,
  chase when low) + random strafe mixing spells (harder to read). Saved on opponent change and
  every 400 obs; MemoryScreen shows the summary per person.
- Bluff resistance (opponent.py): counters scored per player by exchange outcome within 20 ticks
  of use, relative to the player's baseline (r.base) -> counter_scale 0..1.3. Baiter = (deviation
  rate at their hits - base deviation rate)/0.4 -> habits trusted less at high stakes. Hedge: counters
  on in random 1-3 s spells (75%). Consistency (max trait gap vs start of fight) scales the prior.
  Judgement: believed health (shown, else 4/hit dealt if the server always shows full; reset on a
  fresh life) vs behaviour, only against established habit (n>=10), sustained 5..60 ticks, never in
  the 12 ticks after either side's hit (knockback, S-tap). fake_retreat -> don't chase; bluff_strength
  -> press. Judged ticks don't feed the habit.
- Progress watchdog (coach/Watchdog.java): exams.json grouped by checkpointStep for the most-run
  scenario; score = 100*(0.6*winRate + 0.4*damageShare); last 3 versions vs the 3 before:
  > +4 improving, < -4 getting worse, else flat. Reason order: practice learning error (from brain
  status policy_status.last_error, also toasted once by BrainManager), < 2 recordings since the stall
  began, objectives/ changed since, else plateau/drop advice. ProgressScreen: status card, chart
  (series chips, highlighted compare stretch, least-squares trend, "you" line for win rate, hover
  card), tiles. Main menu warning button when flat/dropping; Exams screen "Progress" button.

## Next update plan (do in parts; commit+push main and coach after each part; one release zip at the end)
S = small, M = medium, (check) = needs an in-game check (batch these).
- A1 persistent brain process (swap policies, no restart per play) M (check)
- A2 automatic short exam after every training, in a fast training world M
- A3 everything this mod draws (popups, REC, AI-control line, toasts) drawn after FrameCapture; other mods' HUD (AppleSkin) stays visible M (check)
- B1 damage_taken gets cause (explosion/fall/fire/lava/melee/arrow/drown/void) + self flag; picker: "Hurts itself", "Takes <cause> damage" S
- B2 options: going down; standing on block (item picker); inside zone; near/away from edge; holding item; close to teammate/protectee; combo hits; lands in water; falls into void M
- B3 rule settings: "only if safe for N s" (hold reward, cancel on damage/death; mirror of forgive) and "only beyond its best this round" S
- B4 item wasted (budget cost, scaled by scarcity = how many left): projectiles that hit nothing; food by hunger+saturation overflow; golden apples by absorption soaked + regen healed before effects end M
- B5 item wasted: healing by % actually used (setting, default 50%, self + allies); splash/lingering by type (healing used / harmful reached enemies / buffs reached self-ally + used where measurable); pearls (closed in + damage, escaped, reached height/gap); wind charges (damage/knockback, escape, real height/distance toward something). Waste only ever costs, never pays. No blocks. M
- B6 optional popups (off / points / points+knowledge; side; small stuff), per-second aggregation, held ⏳ -> ✓/✗, knowledge lines (needs A3) S
- C1 learn from who beats it: opponent's play from lost/heavy-damage fights -> player-source clips weighted by damage dealt, tagged by mode/kit; "what hurt it" tally -> lessons/strategist M
- C2 skill card "Learns from" row: [Your play] [Watching others] [Who beats it] with weights (on for PvP); reward entry "Uses what beat it"; "Learned from N defeats" S
- C3 opponent style traits as network inputs; one combined adaptation layer instead of stacked nudges (fast weights, counters, bluff judgement, mixing) M
- C4 dreaming via the main network instead of the tiny MLP S
- D1 UI kit: switches, sliders, tabs, list rows, dialogs, empty states, "?" help M
- D2-D5 old screens rebuilt in batches: main menu -> dashboard; Brain+Profiles; Training+Practice+Exams; rest incl. Settings (custom, internal values hidden) M each (check)
- E1 reward weights learned from good clips vs marked mistakes (preference learning) M
- E2 automatic difficulty by success rate; watchdog acts on its diagnosis M
- E3 overnight population-based evolution of settings + hand-picked constants, judged by auto exams M
- E4 mind as middleman (goal, scope, explanation) + playbook notes per minigame; form fallback without the mind M
- E5 program card UI (timeline, editable, Accept creates skills/rules, follow-up on stalls) M (check)
- F1 guard orders: "Takes orders from" list (empty default), only signed player chat/whispers by sender UUID; guard <player> <dist> <ring|line|front|wedge|square> with <names>; standby/follow/stop; cooldown, max distance, allowed protectees M
- F2 slots = sorted names index + simple math, rotate with protectee facing, optional close gaps from visible guards; nearest guard engages, others hold; safety first; scripted (no training, no Baritone) M (check)

## Code map (for the next update; read this instead of exploring)
Client: client/src/main/java/io/github/mudklip/minigamebots/client/
- brain/BrainManager (talks to Python: play(), playLearned(), ask(); status handler "status"), brain/BrainRuntime (code unpack, venv)
- events/EventDetector + EventTypes (damage_taken emits only "amount" today -> add cause/self in B1)
- observe/Observer + Observation (Self has saturation; EntityInfo has dx..vz, health, blocking, onGround, hitMe/hitByMe, protectedTarget), observe/FrameCapture (AI snapshot; MinecraftMixin injects after GameRenderer.render -> A3 draws after this)
- practice/ExamManager (exams.json, Result) + Objective (rules), coach/Watchdog (progress verdict), coach/Coach, coach/Progress
- gui/Ui (BG, CARD, ACCENT, GOOD, WARN, panel/card/bar/header/chip/tile pieces), gui/ProgressScreen (chart/line/dashed/hover card), gui/PickRuleScreen (rule picker: skills.json behaviours + events), gui/SkillEditorScreen ("Rewarded for" card), gui/ExamsScreen, MainScreen (LinearLayout menu)
- config/MgbConfig (all settings; YACL screen), invent/BuildRunner, train/BaritoneTeacher, sleep/SleepMode
Brain: brain/minigamebots_brain/
- policies/learned.py (net + FastWeights + OpponentModel biases), policies/opponent.py (style, bluffs), policies/fastweights.py (who_key), policies/build.py, policies/manager.py, policies/strategy.py
- rl/objective.py (rules, tiers, forgive, budget; AVOIDANCE_EVENTS), rl/ppo.py, rl/skills.json (behaviours/events catalog)
- players.py (writes player-source clips), dream.py, invent.py (evolution loop to reuse for E3), learn/recording.py (read_chunk)
Dev: build /tmp/claude-0/g.sh <tasks>; brain tests: cd brain && ../run/minigamebots/brain/runtime/venv/bin/python -m unittest discover -s tests;
relaunch script lives in the session scratchpad (recreate: Xvfb :99, ./gradlew :client:runClient --offline --args="--quickPlaySingleplayer test" with MINIGAMEBOTS_BRAIN_DIR=brain).
