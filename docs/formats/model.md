# Trained network inside a profile, version 1

Imitation training writes into the AI profile's `core/` folder:

| File | Content |
|---|---|
| `model.safetensors` | Best weights so far (lowest error on held-out moments) |
| `latest.safetensors` | Most recent weights, used to continue training |
| `optimizer.pt` | Optimizer state for continuing training |
| `model.json` | `arch` (`lite-v2`), `features` (input layout version, 2), `config`, `step`, `val_loss`, recordings used |

`optimizer.pt` and `latest.safetensors` stay on the PC that trains: exports leave them out and
imports skip them (the optimizer file is a pickle, which is never loaded from someone else's file).

An AI trained with an older `arch` or `features` version can't be loaded by a newer brain; the
Training screen then simply trains it again from its recordings.

## Network (`lite-v2`, about 2.9M parameters)

| Input | Encoder |
|---|---|
| 128x128 frame | IMPALA-style CNN (4 stages, 16/32/32/32 channels) → 256 |
| Player state + hotbar/armor/offhand items (hashed ids) | MLP → 128 |
| The 16 most important nearby entities, relative to facing, with who-hit-whom memory | Shared MLP, max and mean pooled → 2 x 96 |
| Crowd radar (everyone else: kind x distance x direction, turned to face forward) | MLP → 32 |
| 11³ block categories, turned to face forward | 3D CNN → 96 |
| Open screen (hashed) | Embedding → 16 |

These are fused (512), passed through a GRU memory (512), and decoded by five heads:
22 held keys (on/off), extra attack clicks (0-3), extra use clicks (0-3), camera yaw and pitch
(31 bins each, fine near zero, up to 50° per tick).

Item, entity and screen names are hashed into fixed vocabularies (4096 / 1024 / 256), so items
the network never saw still get a stable input of their own. Entity positions and the block cube
are expressed relative to where the player faces.

## Training (behaviour cloning)

Random 32-tick windows from the chosen recordings (the last 10% of each held out for the honest
score); the first 8 ticks of each window only warm up the memory. Loss: binary cross-entropy on
keys plus cross-entropy on clicks and camera bins; camera loss is skipped on teleport jumps.
Ticks within 40 of a "mistake" mark are not imitated; ticks near "good" marks count double.
AdamW, gradient clipping, fp16 on NVIDIA GPUs. Resource profiles cap CPU threads, batch size and
pause between steps.

## VPT base (`vpt-tune`)

With "Base: VPT" in the Training screen, the AI is trained on top of an installed foundation
model instead of the Lite network. `model.json` then holds `arch: "vpt-tune"`, `foundation`
(e.g. `vpt-1x`), `foundation_size` and `trainable` (the layer-name prefixes it trained), and
`model.safetensors` holds **only those layers** in float16: the last transformer block,
`net.lastlayer`, `net.final_ln` and both action heads, about 23M parameters (45 MB) for 1x. The
rest comes from the shared foundation, so the AI needs that foundation installed to play.

VPT sees only the 128x128 picture. Each tick it picks one of 8641 button combinations (hotbar,
forward/back, left/right, sprint/sneak, use, drop, attack, jump, camera on/off, or "inventory")
and, if the camera is on, one of 121 camera moves (11 mu-law bins per axis, up to 10° per tick).
Recorded inputs are mapped onto that numbering for training; pick-block and swap-offhand have
no VPT button and are dropped. Training: same windows, marks and resource profiles as Lite,
cross-entropy on the button combination and (on ticks where the camera moved) the camera move,
AdamW at 5e-5, smaller batches.

## Foundations (`.minecraft/minigamebots/foundations/<id>/`)

| File | Content |
|---|---|
| `foundation.json` | `id`, `name`, `description`, `kind` (`vpt`), `size` (`1x`/`2x`), `sha256` of the original download, `sizeBytes`, `installed`; written last, so its presence means "installed" |
| `model.safetensors` | All network weights in float16 |

Installing (Brain > Foundations) downloads OpenAI's published `.weights` file, checks its
SHA-256 against the pinned value, loads it with `torch.load(weights_only=True)` (tensors only, no
code), checks every tensor name and shape against the network, and converts it. Downloads retry
and resume on connection problems. The network code in `brain/minigamebots_brain/vpt/` is
OpenAI's Video-Pre-Training code (MIT, license file alongside) with gym/MineRL removed; its output
matches the original exactly.
