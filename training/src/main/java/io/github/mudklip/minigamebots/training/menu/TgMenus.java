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

	static void msg(ServerPlayer p, String text, boolean ok) {
		p.sendSystemMessage(Component.literal(text).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
	}
}
