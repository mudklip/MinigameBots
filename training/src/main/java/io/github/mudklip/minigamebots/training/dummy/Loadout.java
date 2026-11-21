package io.github.mudklip.minigamebots.training.dummy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.mudklip.minigamebots.training.TgStorage;
import io.github.mudklip.minigamebots.training.scenario.Minigames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.jspecify.annotations.Nullable;

/**
 * Kits: one list of items per game, given the same way to the player (or AI) and to every dummy.
 * A dummy's style only decides how it plays (movement, clicks, aim, raising a shield it has);
 * its gear always comes from the kit. Kits use the arena format ({slot, item}; 36-39 armour,
 * 40 off hand). Built-in ones are below; your own are saved with /tg kit save.
 */
public final class Loadout {
	private static final String IRON = "iron_helmet,iron_chestplate,iron_leggings,iron_boots";
	private static final String DIAMOND = "diamond_helmet,diamond_chestplate,diamond_leggings,diamond_boots";
	/** Hotbar items, extras (id:count), armour (head to feet), off hand: as Minigames' kits. */
	public static final Map<String, String[]> BUILT_IN = new LinkedHashMap<>();
	/** Items dummies can't use properly yet: kits with these stay out of random games. */
	public static final Set<String> DUMMY_CANNOT = Set.of("mace", "wind_charge", "ender_pearl", "fishing_rod", "splash_potion",
			"lingering_potion", "potion", "golden_apple", "enchanted_golden_apple", "ender_chest", "water_bucket", "lava_bucket");
	private static final Deque<String> REPORT = new ArrayDeque<>();

	static {
		BUILT_IN.put("sword", new String[] {"iron_sword", "", IRON, ""});
		BUILT_IN.put("shield", new String[] {"iron_sword", "", IRON, "shield"});
		BUILT_IN.put("axe", new String[] {"iron_axe", "", IRON, ""});
		BUILT_IN.put("diamond", new String[] {"diamond_sword", "", DIAMOND, ""});
		BUILT_IN.put("bow", new String[] {"bow,iron_sword", "arrow:32", IRON, ""});
		BUILT_IN.put("bare", new String[] {"", "", "", ""});
		// Practice minigames: no armour on anyone, the same tools for all.
		BUILT_IN.put("core_snowball", new String[] {"", "snowball:16", "", ""});
		BUILT_IN.put("core_one_hit", new String[] {"wooden_sword", "", "", ""});
		BUILT_IN.put("core_sumo", new String[] {"", "", "", ""});
		BUILT_IN.put("core_king", new String[] {"", "", "", ""});
		BUILT_IN.put("core_spleef", new String[] {"diamond_shovel", "", "", ""});
		BUILT_IN.put("core_tnt_run", new String[] {"", "", "", ""});
	}

	private Loadout() {
	}

	/** Kit names you can pick for training worlds (built-in, minus the minigame ones). */
	public static List<String> pickable() {
		return BUILT_IN.keySet().stream().filter(k -> !k.startsWith("core_")).toList();
	}

	public static boolean dummyCanUse(String[] kit) {
		for (String part : (kit[0] + "," + kit[1] + "," + kit[3]).split(",")) {
			if (DUMMY_CANNOT.contains(part.split(":")[0].trim())) {
				return false;
			}
		}
		return true;
	}

	public static JsonArray builtIn(String name) {
		String[] k = BUILT_IN.get(name);
		return k == null ? new JsonArray() : Minigames.kit(k, !name.startsWith("core_"));
	}

	// --- Your own kits ---

	public static boolean validName(String n) {
		return n.matches("[a-z0-9_-]{1,32}") && !n.startsWith("core_") && !"default".equals(n);
	}

	private static Path file(MinecraftServer s, String name) {
		return TgStorage.dir(s).resolve("kits").resolve(name + ".json");
	}

	/** A built-in kit or one of yours; null if there's none by that name. */
	public static @Nullable JsonArray find(MinecraftServer s, String name) {
		if (BUILT_IN.containsKey(name)) {
			return builtIn(name);
		}
		if (!validName(name)) {
			return null;
		}
		try {
			Path f = file(s, name);
			return Files.exists(f) ? TgStorage.GSON.fromJson(Files.readString(f), JsonArray.class) : null;
		} catch (Exception e) {
			return null;
		}
	}

	public static void save(MinecraftServer s, String name, JsonArray kit) throws java.io.IOException {
		TgStorage.writeAtomic(file(s, name), TgStorage.GSON.toJson(kit));
	}

	public static boolean delete(MinecraftServer s, String name) {
		try {
			return validName(name) && Files.deleteIfExists(file(s, name));
		} catch (java.io.IOException e) {
			return false;
		}
	}

	public static List<String> custom(MinecraftServer s) {
		List<String> out = new ArrayList<>();
		try (var files = Files.list(TgStorage.dir(s).resolve("kits"))) {
			files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json") && !n.startsWith("_"))
					.map(n -> n.substring(0, n.length() - 5)).sorted().forEach(out::add);
		} catch (java.io.IOException e) {
			// No kits yet.
		}
		return out;
	}

	/** The gear a dummy style used to carry, as a kit (training worlds' "default": both sides get it). */
	public static JsonArray ofStyle(DummySettings s) {
		String weapon = !s.fightsBack ? "" : s.cheats ? "netherite_sword" : s.level >= 4 ? "diamond_sword" : "iron_sword";
		String armour = switch (s.armor) {
			case NONE -> "";
			case LEATHER -> "leather_helmet,leather_chestplate,leather_leggings,leather_boots";
			case IRON -> IRON;
			case DIAMOND -> DIAMOND;
			case NETHERITE -> "netherite_helmet,netherite_chestplate,netherite_leggings,netherite_boots";
		};
		boolean shield = s.defense != DummySettings.Defense.NONE || s.smartShield;
		return Minigames.kit(new String[] {weapon, "", armour, shield ? "shield" : ""}, true);
	}

	// --- Putting a kit on a dummy ---

	private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

	/** Dresses a dummy exactly as the kit says; its hotbar is kept for switching (bow up close -> sword). */
	public static void apply(Mannequin m, DummyManager.@Nullable Dummy d, JsonArray kit) {
		if (d == null) {
			return;
		}
		RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, m.registryAccess());
		ItemStack[] slots = new ItemStack[41];
		java.util.Arrays.fill(slots, ItemStack.EMPTY);
		for (var el : kit) {
			JsonObject o = el.getAsJsonObject();
			int slot = o.get("slot").getAsInt();
			if (slot >= 0 && slot < slots.length) {
				ItemStack.CODEC.parse(ops, o.get("item")).result().ifPresent(s -> slots[slot] = s);
			}
		}
		for (int i = 0; i < 4; i++) {
			m.setItemSlot(ARMOUR[i], slots[36 + i].copy());
		}
		m.setItemSlot(EquipmentSlot.OFFHAND, slots[40].copy());
		List<ItemStack> hotbar = new ArrayList<>();
		for (int i = 0; i < 36; i++) {
			if (!slots[i].isEmpty()) {
				hotbar.add(slots[i].copy());
			}
		}
		d.kit = hotbar;
		d.kitJson = kit;
		d.food = hotbar.stream().anyMatch(s -> s.has(DataComponents.FOOD));
		d.arrows = hotbar.stream().filter(s -> s.is(Items.ARROW)).mapToInt(ItemStack::getCount).sum();
		ItemStack melee = melee(d);
		m.setItemSlot(EquipmentSlot.MAINHAND, (melee.isEmpty() && !hotbar.isEmpty() ? hotbar.get(0) : melee).copy());
		if (!m.getOffhandItem().is(Items.SHIELD)) {
			d.settings.defense = DummySettings.Defense.NONE;
			d.settings.smartShield = false;
			if (m.isUsingItem()) {
				m.stopUsingItem();
			}
		}
	}

	/** The kit's best close-range weapon (most damage), or empty for fists. */
	static ItemStack melee(DummyManager.Dummy d) {
		ItemStack best = ItemStack.EMPTY;
		double bestDmg = 1;
		for (ItemStack s : d.kit == null ? List.<ItemStack>of() : d.kit) {
			if (s.is(Items.BOW) || s.is(Items.CROSSBOW)) {
				continue;
			}
			double dmg = stats(s)[0];
			if (dmg > bestDmg) {
				best = s;
				bestDmg = dmg;
			}
		}
		return best;
	}

	/** {attack damage, attacks per second} of an item in the main hand, as for a player. */
	public static double[] stats(ItemStack s) {
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return new double[] {mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND),
				mods.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND)};
	}

	// --- Fairness check ---

	/**
	 * What a body has that its kit didn't give it, or lacks: armour, off hand, and (dummies) the main
	 * hand; for a player, every kit item should be in the inventory. Logged and kept for /tg fairness.
	 */
	public static List<String> check(String where, LivingEntity e, JsonArray kit) {
		List<String> problems = new ArrayList<>();
		Map<Integer, String> want = new java.util.HashMap<>();
		for (var el : kit) {
			JsonObject o = el.getAsJsonObject();
			JsonObject item = o.getAsJsonObject("item");
			if (item != null && item.has("id")) {
				want.put(o.get("slot").getAsInt(), item.get("id").getAsString());
			}
		}
		for (int i = 0; i < 4; i++) {
			compare(problems, ARMOUR[i].getName(), e.getItemBySlot(ARMOUR[i]), want.get(36 + i));
		}
		compare(problems, "off hand", e.getOffhandItem(), want.get(40));
		if (e instanceof ServerPlayer p) {
			for (var en : want.entrySet()) {
				if (en.getKey() < 36 && p.getInventory().countItem(net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getValue(net.minecraft.resources.Identifier.parse(en.getValue()))) == 0) {
					problems.add("missing " + en.getValue());
				}
			}
		} else {
			ItemStack main = e.getMainHandItem();
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(main.getItem()).toString();
			if (!main.isEmpty() && want.entrySet().stream().noneMatch(w -> w.getKey() < 36 && w.getValue().equals(id))) {
				problems.add("holds " + id + " (not in the kit)");
			}
		}
		if (!problems.isEmpty()) {
			String line = where + ": " + e.getName().getString() + " " + String.join(", ", problems);
			synchronized (REPORT) {
				REPORT.addFirst(line);
				while (REPORT.size() > 20) {
					REPORT.removeLast();
				}
			}
			io.github.mudklip.minigamebots.training.TrainingGrounds.LOGGER.warn("Unfair kit: {}", line);
		}
		return problems;
	}

	private static void compare(List<String> problems, String slot, ItemStack has, @Nullable String want) {
		String hasId = has.isEmpty() ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(has.getItem()).toString();
		if (want != null && !want.contains(":")) {
			want = "minecraft:" + want;
		}
		if (!java.util.Objects.equals(hasId, want)) {
			problems.add(slot + " has " + (hasId == null ? "nothing" : hasId) + ", kit has " + (want == null ? "nothing" : want));
		}
	}

	/** The latest problems the check found, newest first (empty = every round was fair). */
	public static List<String> report() {
		synchronized (REPORT) {
			return new ArrayList<>(REPORT);
		}
	}
}
