package io.github.mudklip.minigamebots.training.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import java.util.List;
import java.util.Random;

/**
 * Minigame templates and the random minigame generator (DESIGN 5.4, 4.8): ready-made matches
 * for any arena, and randomized ones (teams, opponents, kits, win rules, time) so the AI learns
 * to "figure it out" rather than one fixed game. Pair with the Figure It Out objective.
 */
public final class Minigames {
	public static final List<String> TEMPLATES = List.of("duel", "sumo", "ffa", "team_deathmatch", "survival", "random");
	private static final String[] COLORS = {"red", "blue", "green", "yellow"};
	/** Kits: hotbar items, then armour (head, chest, legs, feet) and the off hand. */
	private static final List<String[]> KITS = List.of(
			new String[] {"iron_sword", "", "iron_helmet,iron_chestplate,iron_leggings,iron_boots", ""},
			new String[] {"diamond_sword,diamond_axe", "", "diamond_helmet,diamond_chestplate,diamond_leggings,diamond_boots", "shield"},
			new String[] {"bow,stone_sword", "arrow:32", "leather_helmet,leather_chestplate,leather_leggings,leather_boots", ""},
			new String[] {"crossbow,iron_axe", "arrow:16", "chainmail_helmet,chainmail_chestplate,chainmail_leggings,chainmail_boots", "shield"},
			new String[] {"mace,iron_sword", "wind_charge:8", "iron_helmet,iron_chestplate,iron_leggings,iron_boots", ""},
			new String[] {"trident,stone_axe", "", "golden_helmet,golden_chestplate,golden_leggings,golden_boots", ""},
			new String[] {"", "", "", ""});

	private Minigames() {
	}

	private static JsonObject slot(int slot, String id, int count) {
		JsonObject item = new JsonObject();
		item.addProperty("id", id.contains(":") ? id : "minecraft:" + id);
		item.addProperty("count", count);
		JsonObject o = new JsonObject();
		o.addProperty("slot", slot);
		o.add("item", item);
		return o;
	}

	public static JsonArray kit(String[] k) {
		return kit(k, true);
	}

	/** A kit from its parts; food adds the usual stack of steak in the last hotbar slot. */
	public static JsonArray kit(String[] k, boolean food) {
		JsonArray out = new JsonArray();
		int s = 0;
		for (String id : k[0].split(",")) {
			if (!id.isBlank()) {
				out.add(slot(s++, id, 1));
			}
		}
		for (String extra : k[1].split(",")) {
			if (!extra.isBlank()) {
				String[] p = extra.split(":");
				out.add(slot(s++, p[0], p.length > 1 ? Integer.parseInt(p[1]) : 1));
			}
		}
		String[] armor = k[2].isBlank() ? new String[0] : k[2].split(",");
		int[] armorSlots = {39, 38, 37, 36};
		for (int i = 0; i < armor.length && i < 4; i++) {
			out.add(slot(armorSlots[i], armor[i], 1));
		}
		if (!k[3].isBlank()) {
			out.add(slot(40, k[3], 1));
		}
		if (food) {
			out.add(slot(8, "cooked_beef", 16));
		}
		return out;
	}

	private static Scenario.Team team(int i, int dummies, String style, JsonArray kit) {
		Scenario.Team t = new Scenario.Team();
		t.color = COLORS[i];
		t.dummies = dummies;
		t.dummyStyle = style;
		t.kit = kit.deepCopy();
		return t;
	}

	/** Re-rolls a random minigame in place for its next round, keeping its arena and spawns. */
	public static void reroll(Scenario s, Random rnd) {
		List<double[]> spawns = new java.util.ArrayList<>();
		s.teams.forEach(t -> spawns.addAll(t.spawns));
		Scenario fresh = make("random", s.name, s.arena, rnd);
		s.teams = fresh.teams;
		for (int i = 0; i < spawns.size(); i++) {
			s.teams.get(i % s.teams.size()).spawns.add(spawns.get(i));
		}
		s.rule = fresh.rule;
		s.killTarget = fresh.killTarget;
		s.timeLimit = fresh.timeLimit;
	}

	/** A new scenario from a template ({@link #TEMPLATES}); "random" rolls everything. */
	public static Scenario make(String template, String name, String arena, Random rnd) {
		Scenario s = new Scenario();
		s.name = name;
		s.arena = arena;
		s.loop = true;
		JsonArray std = kit(KITS.get(0));
		switch (template) {
			case "duel" -> {
				s.teams.add(team(0, 0, "", std));
				s.teams.add(team(1, 1, League.LEAGUE, std));
				s.rule = WinRule.LAST_TEAM_STANDING;
				s.timeLimit = 120;
			}
			case "sumo" -> {
				// No weapons: knock them off the platform (build the arena over the void or water).
				JsonArray none = kit(KITS.get(KITS.size() - 1));
				s.teams.add(team(0, 0, "", none));
				s.teams.add(team(1, 1, "L3", none));
				s.rule = WinRule.LAST_TEAM_STANDING;
				s.timeLimit = 90;
			}
			case "ffa" -> {
				for (int i = 0; i < 4; i++) {
					s.teams.add(team(i, i == 0 ? 0 : 1, i == 0 ? "" : League.LEAGUE, std));
				}
				s.rule = WinRule.LAST_TEAM_STANDING;
				s.timeLimit = 180;
			}
			case "team_deathmatch" -> {
				s.teams.add(team(0, 1, "L3", std));
				s.teams.add(team(1, 2, League.LEAGUE, std));
				s.rule = WinRule.FIRST_TO_KILLS;
				s.killTarget = 8;
				s.timeLimit = 240;
			}
			case "survival" -> {
				s.teams.add(team(0, 0, "", kit(KITS.get(1))));
				s.teams.add(team(1, 3, "rusher", std));
				s.rule = WinRule.SURVIVE;
				s.timeLimit = 60;
			}
			default -> {
				int teams = 2 + rnd.nextInt(3);
				// Only kits the dummies can use as well as you can (no mace or wind charges yet).
				List<String[]> pool = KITS.stream().filter(io.github.mudklip.minigamebots.training.dummy.Loadout::dummyCanUse).toList();
				JsonArray mine = kit(pool.get(rnd.nextInt(pool.size())));
				boolean sameKits = rnd.nextBoolean();
				List<String> styles = new java.util.ArrayList<>(DummySettings.STYLES);
				styles.remove("self");
				styles.add(League.LEAGUE);
				for (int i = 0; i < teams; i++) {
					JsonArray k = sameKits ? mine : kit(pool.get(rnd.nextInt(pool.size())));
					int dummies = i == 0 ? rnd.nextInt(2) : 1 + rnd.nextInt(teams == 2 ? 3 : 2);
					s.teams.add(team(i, dummies, styles.get(rnd.nextInt(styles.size())), i == 0 ? mine : k));
				}
				WinRule[] rules = WinRule.values();
				s.rule = rules[rnd.nextInt(rules.length)];
				s.killTarget = 3 + rnd.nextInt(8);
				s.timeLimit = 60 + 30 * rnd.nextInt(5);
				s.randomize = true;
			}
		}
		return s;
	}
}
