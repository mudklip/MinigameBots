# Notes: known issues and ideas

Things noticed but not fixed yet, in rough order of importance.

## Known issues

- **Training worlds spawn too many arenas and mix with terrain.** Each start builds a fresh
  row of arena copies next to the arena, through whatever terrain is there, and old copies are
  never cleaned up. Fix idea: build the copies once in a reserved, flat, cleared area (or a
  void/superflat training dimension), reuse them on the next start, and remove them on stop.
- **Far training worlds go "dumb".** Copies far from the player are outside the client's entity
  tracking range, so their learners and opponents stop being simulated and observed properly
  and just stand there. Fix idea: keep all copies within tracking range (tighter spacing,
  rows/grid instead of one long line), cap the count by the server's view/simulation distance,
  and keep their chunks loaded (forced chunk tickets) while training runs.
- **UI clipping pass not finished.** Checked and fixed: Training Hub tabs, Skills, skill editor,
  rewards, Worlds, Backups, Memory, Training. Still to check at GUI scale 3 (427x240): Practice,
  Exams, Versions, Realism, Teacher demos, Recordings, Recording viewer, Character, Brain,
  Foundations, Video Lab question queue.
- **Trained networks only see the best-guess health** of other players, not its range,
  certainty, totems or golden apples (the Manager and its reflexes use those). Adding them
  needs a feature change and retraining.

## Ideas

- Spectate a training world: done (Training Hub > Worlds, click a world; F10 returns to the
  live view). Possible extra: a picture-in-picture view of a second world.
- Farming, building and stashing goals currently run their skills only if trained; built-in
  reflexes for them would make the Neutral attitude useful out of the box.
- Guard attitude: keep to a radius around the base or protected player.
