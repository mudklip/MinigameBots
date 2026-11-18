package io.github.mudklip.minigamebots.client.practice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An Objective: rules that turn game events into rewards for practice. Same JSON as the brain
 * reads (brain/minigamebots_brain/rl/objective.py, docs/formats/objective.md).
 */
public final class Objective {
	public static final class Forgive {
		/** Event types (or {"event", "where"} objects) that cancel a held penalty. */
		public List<Object> by = new ArrayList<>();
		public double within = 20;
	}

	public static final class Budget {
		public double limit = 10;
	}

	public static final class Rule {
		public String event = "kill";
		public Map<String, Object> where = new LinkedHashMap<>();
		public double value = 1;
		public String per = "event";
		public double cap;
		public double cooldown;
		public int tier = 2;
		public @Nullable Forgive forgive;
		public @Nullable Budget budget;
		public String note = "";
	}

	public String name = "";
	public String description = "";
	public List<Rule> rules = new ArrayList<>();
	public Map<String, Object> params;

	/** Plain-language warnings about Objectives that tend to teach the wrong thing (mirrors the brain). */
	public List<String> warnings() {
		List<String> out = new ArrayList<>();
		if (rules.isEmpty()) {
			out.add("No rules yet, so nothing will be learned.");
			return out;
		}
		boolean positive = rules.stream().anyMatch(r -> r.value > 0 && r.budget == null);
		if (!positive) {
			out.add("Nothing is rewarded, only punished: it will learn that doing nothing is safest.");
		}
		List<String> avoid = List.of("damage_taken", "tg_damage_taken", "hit_by");
		List<String> win = List.of("kill", "tg_kill", "tg_episode_end", "damage_dealt", "tg_damage_dealt", "tg_dummy_hit");
		boolean harsh = rules.stream().anyMatch(r -> avoid.contains(r.event) && r.value < 0 && r.forgive == null && r.budget == null);
		boolean rewardsWinning = rules.stream().anyMatch(r -> win.contains(r.event) && r.value > 0);
		if (harsh && !rewardsWinning) {
			out.add("Taking damage is punished but winning or hitting back isn't rewarded: this will likely teach it to avoid fights.");
		} else if (harsh) {
			out.add("Damage penalties can't be forgiven: consider \"forgive: kill or win\" so it still takes hits when that's how it wins.");
		}
		if (rules.stream().anyMatch(r -> r.value > 0 && "second".equals(r.event) && r.cap <= 0)) {
			out.add("A reward every second without a cap rewards stalling; add a cap.");
		}
		return out;
	}

	/** "target=minecraft:pig, amount>=2" -> where map (numbers stay numbers). */
	public static Map<String, Object> parseWhere(String text) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String part : text.split(",")) {
			String p = part.trim();
			if (p.isEmpty()) {
				continue;
			}
			String[] ops = {">=", "<=", "~", "="};
			for (String op : ops) {
				int i = p.indexOf(op);
				if (i <= 0) {
					continue;
				}
				String key = p.substring(0, i).trim();
				String val = p.substring(i + op.length()).trim();
				switch (op) {
					case ">=" -> out.put(key, Map.of("min", number(val)));
					case "<=" -> out.put(key, Map.of("max", number(val)));
					case "~" -> out.put(key, Map.of("contains", val));
					default -> out.put(key, literal(val));
				}
				break;
			}
		}
		return out;
	}

	public static String formatWhere(Map<String, Object> where) {
		List<String> parts = new ArrayList<>();
		for (var e : where.entrySet()) {
			if (e.getValue() instanceof Map<?, ?> m) {
				if (m.containsKey("min")) {
					parts.add(e.getKey() + ">=" + num(m.get("min")));
				}
				if (m.containsKey("max")) {
					parts.add(e.getKey() + "<=" + num(m.get("max")));
				}
				if (m.containsKey("contains")) {
					parts.add(e.getKey() + "~" + m.get("contains"));
				}
			} else {
				parts.add(e.getKey() + "=" + num(e.getValue()));
			}
		}
		return String.join(", ", parts);
	}

	private static String num(Object o) {
		if (o instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
			return Long.toString(d.longValue());
		}
		return String.valueOf(o);
	}

	private static Object literal(String v) {
		if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
			return Boolean.parseBoolean(v);
		}
		try {
			return Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return v;
		}
	}

	private static Object number(String v) {
		try {
			return Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return v;
		}
	}
}
