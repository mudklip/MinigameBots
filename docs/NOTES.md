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
