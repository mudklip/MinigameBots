package io.github.mudklip.minigamebots.training.menu;

import io.github.mudklip.minigamebots.training.TrainingGrounds;
import io.github.mudklip.minigamebots.training.aim.AimTrainer;
import io.github.mudklip.minigamebots.training.arena.Arena;
import io.github.mudklip.minigamebots.training.arena.ArenaManager;
import io.github.mudklip.minigamebots.training.dummy.DummyManager;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The Training Grounds menus (open with /tg). Everything here can also be done with commands. */
public final class TgMenus {
	private final TrainingGrounds tg;

	public TgMenus(TrainingGrounds tg) {
		this.tg = tg;
	}

	public void main(ServerPlayer p) {
		boolean manage = tg.canManage(p.createCommandSourceStack());
		ButtonMenu.open(p, "Training Grounds", 3, b -> {
			if (manage) {
				b.button(10, Items.FILLED_MAP, "Arenas", List.of("Save an area and reset it instantly:", "blocks, items, health, kit, spawns"),
						(pl, r, s) -> arenas(pl));
				b.button(11, Items.IRON_SWORD, "Matches", List.of("Team fights with win rules and", "automatic rounds (red vs blue...)"),
						(pl, r, s) -> matches(pl));
				b.button(12, Items.ARMOR_STAND, "Training dummies", List.of("Practice targets from punching bag (L1)", "to sweaty (L5)"),
						(pl, r, s) -> dummies(pl));
			}
			b.button(14, Items.TARGET, "Aim trainer", List.of("Flick, track and switch drills", "with scores and personal bests"),
					(pl, r, s) -> aim(pl));
			boolean linked = tg.truth().wants(p);
			b.button(16, linked ? Items.DYE.pick(DyeColor.LIME) : Items.DYE.pick(DyeColor.GRAY), linked ? "MinigameBots linked" : "No MinigameBots link",
					linked ? List.of("Exact damage, kills and drill results", "are sent to your MinigameBots AI.")
							: List.of("Join with the MinigameBots client mod", "to send exact events to your AI."), null);
		});
	}

	// --- Arenas ---

	public void arenas(ServerPlayer p) {
		ArenaManager am = tg.arenas();
		BlockPos[] c = am.corners(p);
		String c1 = c != null && c[0] != null ? c[0].toShortString() : "not set";
		String c2 = c != null && c[1] != null ? c[1].toShortString() : "not set";
		ButtonMenu.open(p, "Arenas", 6, b -> {
			b.button(0, Items.BANNER.pick(DyeColor.LIME), "Corner 1: " + c1, List.of("Click: set to where you stand"), (pl, r, s) -> {
				am.setCorner(pl, 0, pl.blockPosition());
				arenas(pl);
			});
			b.button(1, Items.BANNER.pick(DyeColor.RED), "Corner 2: " + c2, List.of("Click: set to where you stand"), (pl, r, s) -> {
				am.setCorner(pl, 1, pl.blockPosition());
				arenas(pl);
			});
			String next = am.nextName();
			b.button(2, Items.WRITABLE_BOOK, "Save as " + next, List.of("Snapshots everything between the corners.",
					"Rename or delete with /tg arena"), (pl, r, s) -> {
						try {
							Arena a = am.create(pl, next);
							msg(pl, "Saved " + a.name + String.format(" (%d x %d x %d)", a.sizeX, a.sizeY, a.sizeZ), true);
						} catch (ArenaManager.ArenaException e) {
							msg(pl, e.getMessage(), false);
						}
						arenas(pl);
					});
			b.button(8, Items.ARROW, "Back", List.of(), (pl, r, s) -> main(pl));
			int slot = 9;
			for (Arena a : am.list()) {
				if (slot >= 54) {
					break;
				}
				List<String> lore = new ArrayList<>();
				lore.add(String.format("%d x %d x %d in %s", a.sizeX, a.sizeY, a.sizeZ, a.dimension.replace("minecraft:", "")));
				lore.add(a.spawns.size() + " spawn(s), kit: " + (a.kit.isEmpty() ? "players keep their items" : a.kit.size() + " items"));
				lore.add("");
				lore.add("Left click: reset now");
				lore.add("Right click: use my inventory as the kit");
				lore.add("Shift + left: add a spawn where I stand");
				lore.add("Shift + right: go to its first spawn");
				b.button(slot++, Items.GRASS_BLOCK, a.name, lore, (pl, right, shift) -> arenaAction(pl, a, right, shift));
			}
		});
	}

	private void arenaAction(ServerPlayer p, Arena a, boolean right, boolean shift) {
		ArenaManager am = tg.arenas();
		try {
			if (!right && !shift) {
				int n = tg.resetArena(a).size();
				msg(p, "Reset " + a.name + (n > 0 ? " (" + n + " player(s))" : ""), true);
				p.closeContainer();
				return;
			}
			if (right && !shift) {
				msg(p, "Kit for " + a.name + ": " + am.setKit(a, p) + " items", true);
			} else if (!right) {
				am.addSpawn(a, p);
				msg(p, "Added spawn " + a.spawns.size() + " to " + a.name, true);
			} else if (!a.spawns.isEmpty()) {
				double[] sp = a.spawns.get(0);
				p.closeContainer();
				p.teleportTo(p.level(), sp[0], sp[1], sp[2], Set.of(), (float) sp[3], (float) sp[4], true);
				return;
			} else {
				msg(p, a.name + " has no spawns yet", false);
			}
		} catch (ArenaManager.ArenaException e) {
			msg(p, e.getMessage(), false);
		}
		arenas(p);
	}

	// --- Dummies ---

	private static final Item[] LEVEL_ICONS = {Items.HAY_BLOCK, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE,
			Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE};
	private static final String[] LEVEL_TEXT = {
			"Punching bag: stands still, never dies",
			"Strafes side to side",
			"Strafes and jumps, random shield, hits back weakly",
			"Circles you, timed shield, iron armour, hits back",
			"Sweaty: rushes, w-taps, diamond armour, can die"};

	public void dummies(ServerPlayer p) {
		ButtonMenu.open(p, "Training dummies", 3, b -> {
			for (int i = 0; i < 5; i++) {
				int level = i + 1;
				b.button(10 + i, LEVEL_ICONS[i], "Spawn L" + level + " dummy", List.of(LEVEL_TEXT[i], "",
						"Sneak + right-click a dummy to change it"), (pl, r, s) -> {
							tg.dummies().spawn(pl, DummySettings.preset(level));
							pl.closeContainer();
						});
			}
			b.button(16, Items.BARRIER, "Remove nearby dummies", List.of("Within 10 blocks"), (pl, r, s) -> {
				msg(pl, "Removed " + tg.dummies().removeNear(pl, 10) + " dummies", true);
			});
			b.button(22, Items.ARROW, "Back", List.of(), (pl, r, s) -> main(pl));
		});
	}

	/** Settings of one dummy; every click changes one thing and reopens the menu. */
	public void dummy(ServerPlayer p, Mannequin m) {
		DummyManager dm = tg.dummies();
		DummyManager.Dummy d = dm.get(m);
		if (d == null) {
			return;
		}
		DummySettings s = d.settings;
		Runnable refresh = () -> {
			dm.apply(m, d);
			dummy(p, m);
		};
		ButtonMenu.open(p, "Dummy L" + s.level, 3, b -> {
			b.button(10, Items.LEATHER_BOOTS, "Movement: " + s.movement.label, List.of("Click to change"), (pl, r, sh) -> {
				s.movement = cycle(DummySettings.Movement.values(), s.movement, r);
				refresh.run();
			});
			b.button(11, Items.SHIELD, "Defense: " + s.defense.label, List.of("Click to change"), (pl, r, sh) -> {
				s.defense = cycle(DummySettings.Defense.values(), s.defense, r);
				refresh.run();
			});
			b.button(12, s.fightsBack ? Items.IRON_SWORD : Items.WOODEN_SWORD, "Fights back: " + (s.fightsBack ? "yes" : "no"),
					List.of(String.format("%.0f CPS, %.0f%% accuracy, %.1f reach, %.0f damage", s.cps, s.accuracy * 100, s.reach, s.damage),
							"Click to toggle"), (pl, r, sh) -> {
						s.fightsBack = !s.fightsBack;
						refresh.run();
					});
			b.button(13, Items.IRON_CHESTPLATE, "Armour: " + s.armor.name().toLowerCase(), List.of("Click to change"), (pl, r, sh) -> {
				s.armor = cycle(DummySettings.Armor.values(), s.armor, r);
				refresh.run();
			});
			b.button(14, s.infiniteHealth ? Items.TOTEM_OF_UNDYING : Items.SKELETON_SKULL,
					"Health: " + (s.infiniteHealth ? "never dies" : "can die (respawns)"), List.of("Click to toggle"), (pl, r, sh) -> {
						s.infiniteHealth = !s.infiniteHealth;
						refresh.run();
					});
			b.button(15, Items.EXPERIENCE_BOTTLE, "Preset: L" + s.level, List.of("Click: next preset (replaces all settings)"),
					(pl, r, sh) -> {
						DummySettings next = DummySettings.preset(s.level % 5 + 1);
						d.settings = next;
						refresh.run();
					});
			b.button(16, Items.BARRIER, "Remove this dummy", List.of(), (pl, r, sh) -> {
				dm.remove(m);
				pl.closeContainer();
			});
			b.button(22, Items.OAK_DOOR, "Close", List.of(), (pl, r, sh) -> pl.closeContainer());
		});
	}

	private static <E extends Enum<E>> E cycle(E[] values, E current, boolean back) {
		int n = values.length;
		return values[(current.ordinal() + (back ? n - 1 : 1)) % n];
	}

	// --- Aim trainer ---

	public void aim(ServerPlayer p) {
		ButtonMenu.open(p, "Aim trainer", 3, b -> {
			Item[] icons = {Items.ARROW, Items.ENDER_EYE, Items.SPECTRAL_ARROW};
			String[] help = {"One target at a time, gone after 2 s", "Keep your crosshair on a moving target",
					"Three targets at once"};
			AimTrainer.Mode[] modes = AimTrainer.Mode.values();
			for (int i = 0; i < modes.length; i++) {
				AimTrainer.Mode mode = modes[i];
				b.button(11 + i * 2, icons[i], mode.label, List.of(help[i], "", "Left click: 30 seconds", "Right click: 60 seconds"),
						(pl, r, s) -> {
							pl.closeContainer();
							tg.aim().start(pl, mode, r ? 60 : 30);
						});
			}
			b.button(22, Items.ARROW, "Back", List.of(), (pl, r, s) -> main(pl));
		});
	}

	// --- Matches (scenarios) ---

	private static final String[] TEAM_COLORS = {"red", "blue", "green", "yellow"};

	private static DyeColor dye(String color) {
		return switch (color) {
			case "blue" -> DyeColor.BLUE;
			case "green" -> DyeColor.LIME;
			case "yellow" -> DyeColor.YELLOW;
			default -> DyeColor.RED;
		};
	}

	public void matches(ServerPlayer p) {
		var sm = tg.scenarios();
		ButtonMenu.open(p, "Matches", 6, b -> {
			b.button(0, Items.WRITABLE_BOOK, "New red vs blue match", List.of("Pick the arena it's played in"), (pl, r, s) -> pickArena(pl));
			var running = sm.running();
			if (running != null) {
				b.button(4, Items.BARRIER, "Stop " + running.name, List.of("Ends the current round, no more rounds"), (pl, r, s) -> {
					sm.stop();
					matches(pl);
				});
			}
			b.button(8, Items.ARROW, "Back", List.of(), (pl, r, s) -> main(pl));
			int slot = 9;
			for (var sc : sm.list()) {
				if (slot >= 54) {
					break;
				}
				List<String> lore = new ArrayList<>();
				lore.add(sc.rule.label + " in " + sc.arena);
				lore.add(String.join(" vs ", sc.teams.stream().map(t -> t.label()).toList()));
				lore.add(sc.episodes + " rounds played" + (sc.wins.isEmpty() ? "" : ", wins: " + sc.wins));
				lore.add("");
				lore.add("Click to open");
				b.button(slot++, sc == running ? Items.DIAMOND_SWORD : Items.IRON_SWORD, sc.name, lore, (pl, r, s) -> match(pl, sc));
			}
		});
	}

	private void pickArena(ServerPlayer p) {
		ButtonMenu.open(p, "Play it in which arena?", 6, b -> {
			b.button(8, Items.ARROW, "Back", List.of(), (pl, r, s) -> matches(pl));
			int slot = 9;
			for (Arena a : tg.arenas().list()) {
				if (slot >= 54) {
					break;
				}
				b.button(slot++, Items.GRASS_BLOCK, a.name, List.of(a.spawns.size() + " spawn(s)"), (pl, r, s) -> {
					try {
						var sc = tg.scenarios().create(tg.scenarios().nextName(), a);
						msg(pl, "Created " + sc.name + ". Join a team and press Start.", true);
						match(pl, sc);
					} catch (ArenaManager.ArenaException e) {
						msg(pl, e.getMessage(), false);
					}
				});
			}
			if (slot == 9) {
				b.button(22, Items.PAPER, "No arenas yet", List.of("Make one in Arenas first"), null);
			}
		});
	}

	/** One match: teams to join, team setup, rules and start/stop. */
	public void match(ServerPlayer p, io.github.mudklip.minigamebots.training.scenario.Scenario sc) {
		var sm = tg.scenarios();
		Runnable save = () -> sm.save(sc);
		ButtonMenu.open(p, "Match " + sc.name, 6, b -> {
			b.button(4, Items.BOOK, sc.name, List.of("Arena: " + sc.arena, sc.rule.help,
					sc.episodes + " rounds, wins " + sc.wins + ", draws " + sc.draws, "Delete with /tg match delete " + sc.name), null);
			b.button(8, Items.ARROW, "Back", List.of(), (pl, r, s) -> matches(pl));
			int mine = sm.teamOf(sc, p);
			for (int t = 0; t < sc.teams.size() && t < 4; t++) {
				int team = t;
				var def = sc.teams.get(t);
				b.button(9 + t * 2, Items.WOOL.pick(dye(def.color)), (mine == t ? "In " : "Join ") + def.label(),
						List.of(sm.joinedCount(sc, t) + " player(s), " + def.dummies + " L" + def.dummyLevel + " dummies",
								def.spawns.size() + " spawn(s), kit: " + (def.kit.isEmpty() ? "own items" : def.kit.size() + " items"),
								"", "Left click: join", "Right click: leave"), (pl, r, s) -> {
							if (r) {
								sm.leave(sc, pl);
							} else {
								sm.join(sc, pl, team);
							}
							match(pl, sc);
						});
				b.button(18 + t * 2, Items.ARMOR_STAND, def.label() + " dummies: " + def.dummies + " (L" + def.dummyLevel + ")",
						List.of("Left click: one more (0-4)", "Right click: next level"), (pl, r, s) -> {
							if (r) {
								def.dummyLevel = def.dummyLevel % 5 + 1;
							} else {
								def.dummies = (def.dummies + 1) % 5;
							}
							save.run();
							match(pl, sc);
						});
				b.button(19 + t * 2, Items.CHEST, def.label() + " kit and spawns", List.of("Left: my inventory becomes the kit",
						"Right: add a spawn where I stand", "Shift + left: no kit (own items)", "Shift + right: clear spawns"), (pl, r, s) -> {
							if (!r && !s) {
								def.kit = ArenaManager.captureKit(pl);
								msg(pl, def.label() + " kit: " + def.kit.size() + " items", true);
							} else if (r && !s) {
								def.spawns.add(new double[] {pl.getX(), pl.getY(), pl.getZ(), pl.getYRot(), pl.getXRot()});
								msg(pl, def.label() + " spawn " + def.spawns.size() + " added", true);
							} else if (!r) {
								def.kit = new com.google.gson.JsonArray();
							} else {
								def.spawns.clear();
							}
							save.run();
							match(pl, sc);
						});
			}
			b.button(28, Items.GOLDEN_SWORD, "Rule: " + sc.rule.label, List.of(sc.rule.help, "Click to change"), (pl, r, s) -> {
				sc.rule = cycle(io.github.mudklip.minigamebots.training.scenario.WinRule.values(), sc.rule, r);
				save.run();
				match(pl, sc);
			});
			int[] times = {0, 60, 120, 180, 300, 600};
			b.button(29, Items.CLOCK, "Time limit: " + (sc.timeLimit == 0 ? "none" : sc.timeLimit / 60 + ":" + String.format("%02d", sc.timeLimit % 60)),
					List.of("Click to change"), (pl, r, s) -> {
						int i = 0;
						while (i < times.length && times[i] != sc.timeLimit) {
							i++;
						}
						sc.timeLimit = times[(i + 1) % times.length];
						save.run();
						match(pl, sc);
					});
			int[] targets = {1, 3, 5, 10, 20};
			b.button(30, Items.SKELETON_SKULL, "Kill target: " + sc.killTarget, List.of("For \"First to N kills\"", "Click to change"),
					(pl, r, s) -> {
						int i = 0;
						while (i < targets.length && targets[i] != sc.killTarget) {
							i++;
						}
						sc.killTarget = targets[(i + 1) % targets.length];
						save.run();
						match(pl, sc);
					});
			b.button(31, sc.loop ? Items.REPEATER : Items.LEVER, "Rounds: " + (sc.loop ? "keep going" : "one at a time"),
					List.of("Keep going: a new round starts", "a few seconds after each one ends"), (pl, r, s) -> {
						sc.loop = !sc.loop;
						save.run();
						match(pl, sc);
					});
			b.button(32, sc.matchAiSpeed ? Items.CLOCK : Items.COMPASS, "Match AI speed: " + (sc.matchAiSpeed ? "on" : "off"),
					List.of("Slows the whole game down to the speed", "of the slowest AI playing, so it can",
							"decide every tick. Affects everyone", "on the server while a round runs."), (pl, r, s) -> {
						sc.matchAiSpeed = !sc.matchAiSpeed;
						save.run();
						match(pl, sc);
					});
			if (sc.teams.size() < 4) {
				b.button(33, Items.WOOL.pick(dye(TEAM_COLORS[sc.teams.size()])), "Add a team", List.of(), (pl, r, s) -> {
					var t = new io.github.mudklip.minigamebots.training.scenario.Scenario.Team();
					t.color = TEAM_COLORS[sc.teams.size()];
					sc.teams.add(t);
					save.run();
					match(pl, sc);
				});
			}
			if (sc.teams.size() > 2) {
				b.button(34, Items.SHEARS, "Remove the last team", List.of(), (pl, r, s) -> {
					sc.teams.remove(sc.teams.size() - 1);
					save.run();
					match(pl, sc);
				});
			}
			boolean running = sm.running() == sc;
			if (running) {
				b.button(49, Items.BARRIER, "Stop", List.of("Ends this round, no more rounds"), (pl, r, s) -> {
					sm.stop();
					pl.closeContainer();
				});
			} else {
				b.button(49, Items.EMERALD, "Start", List.of("Resets the arena and starts a round.",
						mine < 0 ? "You'll join " + sc.teams.get(0).label() + " if nobody joined." : ""), (pl, r, s) -> {
							try {
								sm.start(sc, pl);
								pl.closeContainer();
							} catch (ArenaManager.ArenaException e) {
								msg(pl, e.getMessage(), false);
							}
						});
			}
		});
	}

	static void msg(ServerPlayer p, String text, boolean ok) {
		p.sendSystemMessage(Component.literal(text).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
	}
}
