package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;

/** Live state of every training world, for the Training worlds screen (from Training Grounds' reports). */
public final class MultiWorldStats {
	public static final class World {
		public int index;
		public float hp = 20;
		public float maxHp = 20;
		public float oppHp = 20;
		public float oppMaxHp = 20;
		public double oppDx;
		public double oppDz;
		public float yaw;
		public float oppYaw;
		public int wins;
		public int losses;
		public double dealt;
		public double taken;
		public long lastHit;
		public long lastHurt;
		/** +1 won, -1 lost, 0 none; with the time, for a short flash. */
		public int lastResult;
		public long lastResultAt;
		public long updated;
	}

	private static final Map<Integer, World> worlds = new TreeMap<>();
	private static final Deque<Integer> recent = new ArrayDeque<>();
	private static long startedAt;
	private static int rounds;

	private MultiWorldStats() {
	}

	public static synchronized void reset() {
		worlds.clear();
		recent.clear();
		rounds = 0;
		startedAt = 0;
	}

	public static synchronized void update(JsonArray copies) {
		long now = System.currentTimeMillis();
		if (startedAt == 0) {
			startedAt = now;
		}
		for (JsonElement el : copies) {
			JsonObject c = el.getAsJsonObject();
			if (c.has("slot") && c.get("slot").getAsInt() > 0) {
				continue; // Team worlds: the first learner's view stands for the world (the result is shared).
			}
			int i = c.get("copy").getAsInt();
			World w = worlds.computeIfAbsent(i, k -> {
				World n = new World();
				n.index = k;
				return n;
			});
			w.updated = now;
			if (c.has("obs")) {
				JsonObject obs = c.getAsJsonObject("obs");
				JsonObject self = obs.getAsJsonObject("self");
				if (self != null) {
					w.hp = self.get("health").getAsFloat();
					w.maxHp = self.get("maxHealth").getAsFloat();
					w.yaw = self.get("yaw").getAsFloat();
				}
				JsonArray ents = obs.getAsJsonArray("entities");
				if (ents != null && !ents.isEmpty()) {
					JsonObject o = ents.get(0).getAsJsonObject();
					w.oppHp = o.get("health").getAsFloat();
					w.oppMaxHp = o.get("maxHealth").getAsFloat();
					w.oppDx = o.get("dx").getAsDouble();
					w.oppDz = o.get("dz").getAsDouble();
					w.oppYaw = o.has("yaw") ? o.get("yaw").getAsFloat() : 0;
				}
			}
			JsonArray evs = c.getAsJsonArray("events");
			if (evs == null) {
				continue;
			}
			for (JsonElement e : evs) {
				JsonObject ev = e.getAsJsonObject();
				String t = ev.get("type").getAsString();
				JsonObject d = ev.has("data") ? ev.getAsJsonObject("data") : new JsonObject();
				switch (t) {
					case "damage_dealt" -> {
						w.dealt += d.has("amount") ? d.get("amount").getAsDouble() : 0;
						w.lastHit = now;
					}
					case "damage_taken" -> {
						w.taken += d.has("amount") ? d.get("amount").getAsDouble() : 0;
						w.lastHurt = now;
					}
					case "tg_episode_end" -> {
						boolean won = d.has("result") && "win".equals(d.get("result").getAsString());
						if (d.has("result") && "draw".equals(d.get("result").getAsString())) {
							rounds++; // Time ran out: a round, but neither a win nor a loss.
							continue;
						}
						if (won) {
							w.wins++;
						} else {
							w.losses++;
						}
						w.lastResult = won ? 1 : -1;
						w.lastResultAt = now;
						rounds++;
						recent.addLast(won ? 1 : 0);
						while (recent.size() > 40) {
							recent.removeFirst();
						}
					}
					default -> {
					}
				}
			}
		}
	}

	public static synchronized java.util.List<World> worlds() {
		return java.util.List.copyOf(worlds.values());
	}

	public static synchronized int rounds() {
		return rounds;
	}

	/** Win rate over the last 40 rounds, or -1. */
	public static synchronized double recentWinRate() {
		return recent.isEmpty() ? -1 : recent.stream().mapToInt(Integer::intValue).average().orElse(0);
	}

	public static synchronized double roundsPerMinute() {
		long ms = System.currentTimeMillis() - startedAt;
		return startedAt == 0 || ms < 10_000 ? 0 : rounds / (ms / 60_000.0);
	}

	public static synchronized long runningMs() {
		return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
	}
}
