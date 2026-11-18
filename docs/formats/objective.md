# Objectives (practice rewards), version 1

An Objective tells practice (reinforcement learning) what counts as doing well. It's JSON,
saved in an AI profile as `objectives/<id>.json`; ready-made ones live in
`brain/minigamebots_brain/rl/templates.json` (Duel, Hunt {target}, Survive, Aim trainer).

```json
{
  "name": "Duel",
  "description": "...",
  "rules": [
    {"event": "tg_episode_end", "where": {"result": "win"}, "value": 10, "tier": 1, "note": "round won"},
    {"event": "damage_taken", "value": -0.05, "per": "amount", "tier": 3,
     "forgive": {"by": ["kill", {"event": "tg_episode_end", "where": {"result": "win"}}], "within": 20}}
  ]
}
```

| Field | Meaning |
|---|---|
| `event` | A game event type: the mod's own (`kill`, `damage_dealt`, `damage_taken`, `death`, `attack`, `block_broken`, `item_gained`, `chat`, ...), Training Grounds ground truth (`tg_kill`, `tg_damage_dealt`, `tg_episode_end`, `tg_aim_hit`, ...), or `second`, which happens once a second with the player's state (`health`, `food`, `dead`, ...). `/mgb events` lists recent ones with their fields. |
| `where` | Conditions on the event's fields: a value (`"target": "minecraft:pig"`, `"result": "win"`), or `{"min": x}`, `{"max": x}`, `{"contains": "text"}`. In the editor: `target=minecraft:pig, amount>=2, text~won`. |
| `value` | Reward per event, or per unit of the field named by `per`. Negative = penalty. |
| `per` | `event` (each time), or a field to multiply by (`amount`, `count`, `accuracy`, ...). |
| `cap` | Most the rule can add up to in one try, as a size (0 = no cap). |
| `cooldown` | Seconds before the rule can count again. |
| `tier` | 1 (most important) to 4. While a more important rule fired in the last 20 s, penalties from less important tiers are multiplied by 0.25 per tier of difference. |
| `forgive` | For penalties: held back; cancelled if one of `by` happens within `within` seconds; otherwise applied at the moment it happened (the learner rewrites that step). Still held when a try ends: applied. |
| `budget` | For costs (`value` = cost per unit, positive): free while the try's total stays under `limit`; above it the cost counts with a weight that grows x1.5 each try the budget was broken and shrinks x0.9 each try it was kept. |
| `note` | Label shown in the editor and status. |

A **try** (episode) is a Training Grounds match round, a death, or, in free play, one minute.
The editor and the brain warn about Objectives that tend to teach the wrong thing (only
penalties; damage punished without rewarding winning; uncapped per-second rewards).

## Practice (PPO)

The AI plays with its network and samples actions; every 1024 ticks (2048 on Quiet) a copy
of the network is improved in the background with PPO (clipped ratio 0.2, GAE γ 0.995 λ 0.95,
3 epochs, 32-tick chunks replaying the GRU memory, entropy bonus 0.003, value head on
normalised returns) while the AI keeps playing; then the new weights are swapped in.
"Stay human" adds `β · KL(current ‖ imitation network)`, so it keeps the style learned from
recordings unless deviating clearly pays off. Practice saves to `core/latest.safetensors`
every two improvements; `core/model.safetensors` (what plays normally) only changes when the
average score over the last tries beats the best so far. Practice works with Lite-base AIs;
VPT-base practice is planned.

## Exams (`exams.json`)

A list of results: `date`, `scenario`, `checkpointStep`, `phase`, `rounds`, `wins`, `losses`,
`draws`, totals of `kills`, `deaths`, `damageDealt`, `damageTaken`, `seconds`.
