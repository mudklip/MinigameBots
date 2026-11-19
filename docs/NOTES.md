# Notes: to-do list and ideas

## To fix (reported, not done yet)

1. **Training worlds pile up arenas in the terrain.** Every start builds a fresh row of arena
   copies through whatever terrain is there, and old ones are never removed. Build them once in
   a cleared, reserved area (or a flat/void training dimension), reuse them, remove them on stop.
2. **Far training worlds go "dumb".** Copies out of the client's tracking range stop being
   simulated/observed, so their fighters stand still. Lay copies out in a compact grid, cap the
   count by view/simulation distance, keep their chunks loaded while training runs.
3. **UI clipping, rest of the pass.** Still to check and fix at GUI scale 3 (427x240): Practice,
   Exams, Versions, Realism, Teacher demos, Recordings, Recording viewer, Edit recording,
   Character, Brain, Foundations, Video Lab question queue, Goals (slider labels clip:
   "ikes exploring"), Profiles editor, Merge, Guide.
4. **Disabled actions outside skill practice.** Locks only apply while a skill is practiced or
   coached. Free play, Goals and Let it play ignore them. Add a global "never do" list (per AI)
   that applies to everything the AI does.
5. **Flicker/stutter when the brain starts** (Python + PyTorch loading onto the GPU). Measure on
   an RTX 2060 6 GB, with the mind loaded too; load in the background at lower priority, and
   warm up the GPU before the AI takes control.
6. **Mind download (WinError 10054).** Retries and resume shipped in 1.1.1; not yet confirmed
   on a real Windows PC.
7. **Training is thin outside PvP and aim.** Most skills have rewards and hints but no teacher
   demos or Training Grounds setups (mining course, farm plot, bridge lane, parkour course,
   build area, inventory drill). Add setups and scripted teachers for them.
8. **Neutral/guard attitudes need real behaviours.** Farm, build and stash goals only run their
   skills if trained; give them built-in reflexes. Guard should stay within a radius.
9. **Trained networks only see best-guess health** of other players, not the range, certainty,
   totems or golden apples. Add as inputs (needs retraining).
10. **Plans and skills keep separate levels.** The Plans tab's PvP/Aim levels and the skill
    levels don't add up into each other; show one progress picture.

## Done from earlier reports (for reference)

Spectating a training world; per-skill restore from backups; locks shown clearly and held
for a whole coached session; Practice spelling; block/creature/item picker; recording that
restarted itself (Watch & learn); freezes from backups on the game thread; aim command spam;
Memory zoom buttons.
