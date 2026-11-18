package io.github.mudklip.minigamebots.training.scenario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * The combat league: for teams whose dummies are set to "league", each round's opponent style is
 * picked for the player, mostly among the styles it loses to (prioritized matchmaking). That's
 * also how practice leans on its weak spots: an opponent it always beats is rarely picked.
 * Results per player and style are kept in the world (league.json).
 */
public final class League {
	public static final String LEAGUE = "league";
	/** Styles the league picks from: everything but the punching bags. */
	public static final List<String> POOL = DummySettings.STYLES.stream().filter(s -> !s.equals("L1") && !s.equals("L2")).toList();

	/** Games and wins of one player against one style. */
	public static final class Record {
		public int games;
		public int wins;

		/** Estimated win rate, starting from 50% before any games. */
		public double winRate() {
			return (wins + 1.0) / (games + 2.0);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Map<String, Map<String, Record>> records = new LinkedHashMap<>();
	private @Nullable Path file;

	public void load(Path file) {
		this.file = file;
		records.clear();
		if (Files.isRegularFile(file)) {
			try {
				Map<String, Map<String, Record>> read = GSON.fromJson(Files.readString(file),
						new TypeToken<Map<String, Map<String, Record>>>() { }.getType());
				if (read != null) {
					records.putAll(read);
				}
			} catch (IOException | RuntimeException e) {
				// Start over: the league only steers matchmaking.
			}
		}
	}

	private void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(records));
		} catch (IOException ignored) {
			// Next result tries again.
		}
	}

	public Map<String, Record> of(UUID player) {
		return records.computeIfAbsent(player.toString(), k -> new LinkedHashMap<>());
	}

	/**
	 * Picks an opponent style for the player. Weight = (1 - win rate)^2, so styles it loses to
	 * come up most, and ones it beats every time still come up now and then.
	 */
	public String pick(UUID player, RandomSource random, boolean selfPlay) {
		Map<String, Record> mine = of(player);
		List<String> pool = POOL.stream().filter(s -> selfPlay || !s.equals("self")).toList();
		List<Double> weights = new ArrayList<>();
		double total = 0;
		for (String style : pool) {
			Record r = mine.get(style);
			double loss = 1 - (r == null ? 0.5 : r.winRate());
			double w = 0.02 + loss * loss;
			weights.add(w);
			total += w;
		}
		double x = random.nextDouble() * total;
		for (int i = 0; i < pool.size(); i++) {
			x -= weights.get(i);
			if (x <= 0) {
				return pool.get(i);
			}
		}
		return pool.get(pool.size() - 1);
	}

	public void result(UUID player, String style, boolean won) {
		Record r = of(player).computeIfAbsent(style, k -> new Record());
		r.games++;
		if (won) {
			r.wins++;
		}
		save();
	}
}
