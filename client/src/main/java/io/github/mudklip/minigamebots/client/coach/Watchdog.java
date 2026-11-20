package io.github.mudklip.minigamebots.client.coach;

import io.github.mudklip.minigamebots.client.practice.ExamManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Is training still paying off? Reads the exam history (exams.json, the same results the Exams
 * screen shows) and compares the last few trained versions with the ones before. When the score
 * stops rising it says so, with the most likely reason first: a learning error, no new recordings,
 * a recent reward change, or a real plateau.
 */
public final class Watchdog {
	/** How many trained versions make "recent" (compared with the ones before). */
	static final int WINDOW = 3;
	/** Points (0..100) that count as real change, not noise. */
	static final double NOISE = 4.0;

	public enum State {
		NO_DATA("Not enough exams yet", 0xFF98A2B3),
		IMPROVING("Improving", 0xFF6EE07A),
		FLAT("Not improving", 0xFFFFC24A),
		DROPPING("Getting worse", 0xFFFF6E6E);

		public final String label;
		public final int color;

		State(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	/** One trained version's exam score (0..100): win rate and share of the damage, averaged. */
	public record Point(long step, double score, double winRate, double damageShare, int exams, String date) {
	}

	public record Verdict(State state, String scenario, List<Point> points, double change, String why, String fix) {
	}

	private static @Nullable String lastError;
	private static long lastErrorAt;

	private Watchdog() {
	}

	/** Called with every brain status: remembers a practice learning error so the verdict can name it. */
	public static void learningError(@Nullable String error) {
		if (error != null && !error.isBlank()) {
			lastError = error;
			lastErrorAt = System.currentTimeMillis();
		}
	}

	public static @Nullable String recentError() {
		return lastError != null && System.currentTimeMillis() - lastErrorAt < 24 * 3600_000L ? lastError : null;
	}

	public static double score(ExamManager.Result r) {
		double dmg = r.damageDealt + r.damageTaken;
		double share = dmg <= 0 ? 0.5 : r.damageDealt / dmg;
		return 100 * (0.6 * r.winRate() + 0.4 * share);
	}

	/** Exams grouped by trained version, for the match it was examined in most. */
	public static Verdict check(Path profileDir) {
		List<ExamManager.Result> all = ExamManager.load(profileDir);
		Map<String, Integer> count = new LinkedHashMap<>();
		for (var r : all) {
			count.merge(r.scenario, 1, Integer::sum);
		}
		String scenario = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
		Map<Long, List<ExamManager.Result>> byStep = new LinkedHashMap<>();
		for (var r : all) {
			if (r.scenario.equals(scenario)) {
				byStep.computeIfAbsent(r.checkpointStep, k -> new ArrayList<>()).add(r);
			}
		}
		List<Point> points = new ArrayList<>();
		for (var e : byStep.entrySet()) {
			double s = 0, w = 0, d = 0;
			for (var r : e.getValue()) {
				s += score(r);
				w += r.winRate();
				double dmg = r.damageDealt + r.damageTaken;
				d += dmg <= 0 ? 0.5 : r.damageDealt / dmg;
			}
			int n = e.getValue().size();
			points.add(new Point(e.getKey(), s / n, w / n, d / n, n, e.getValue().get(n - 1).date));
		}
		String error = recentError();
		if (points.size() < WINDOW + 1) {
			String why = error != null ? "Practice hit a learning error: " + error : "Run an exam after each training to see the trend.";
			String fix = error != null ? "Open Practice to see it; tell the developer if it keeps happening."
					: "Exams screen: Start exam (" + (WINDOW + 1 - points.size()) + " more trained version(s) needed).";
			return new Verdict(State.NO_DATA, scenario, points, 0, why, fix);
		}
		int n = points.size();
		double recent = 0, before = 0;
		for (int i = n - WINDOW; i < n; i++) {
			recent += points.get(i).score();
		}
		recent /= WINDOW;
		int from = Math.max(0, n - 2 * WINDOW);
		for (int i = from; i < n - WINDOW; i++) {
			before += points.get(i).score();
		}
		before /= (n - WINDOW - from);
		double change = recent - before;
		State state = change > NOISE ? State.IMPROVING : change < -NOISE ? State.DROPPING : State.FLAT;
		if (state == State.IMPROVING) {
			return new Verdict(state, scenario, points, change, "Its last " + WINDOW + " versions score "
					+ Math.round(change) + " points higher than the ones before.", "Keep going.");
		}
		// Not improving: the most likely reason first.
		long since = epoch(points.get(n - WINDOW).date());
		String why;
		String fix;
		if (error != null) {
			why = "Practice hit a learning error, so it may not be learning at all: " + error;
			fix = "Open Practice to see it; if it keeps happening, tell the developer.";
		} else if (newRecordings(since) < 2) {
			why = "Hardly any new recordings since it stopped improving: it keeps learning from the same play.";
			fix = "Record more of yourself in this mode (F7), mark your mistakes, then train again.";
		} else if (rewardsChangedSince(profileDir, since)) {
			why = "Its rewards were changed recently, and it has done no better since.";
			fix = "Look at the last reward change (Practice > objective), or go back a version (Hub > Versions).";
		} else if (state == State.DROPPING) {
			why = "It does worse than a few versions ago, with nothing obviously wrong.";
			fix = "Go back to its best version (Hub > Versions) and train with more varied opponents (league, free-for-all).";
		} else {
			why = "A real plateau: more of the same training isn't helping.";
			fix = "Try different practice (league, free-for-all, watching better players). It may also be near what this PC can train.";
		}
		return new Verdict(state, scenario, points, change, why, fix);
	}

	private static long epoch(String date) {
		try {
			return LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(ZoneId.systemDefault())
					.toInstant().toEpochMilli();
		} catch (Exception e) {
			return 0;
		}
	}

	private static int newRecordings(long since) {
		Path root = FabricLoader.getInstance().getGameDir().resolve("minigamebots/recordings");
		int n = 0;
		try (var dirs = Files.list(root)) {
			for (Path d : (Iterable<Path>) dirs::iterator) {
				Path meta = d.resolve("meta.json");
				if (Files.isRegularFile(meta) && Files.getLastModifiedTime(meta).toMillis() >= since) {
					n++;
				}
			}
		} catch (Exception e) {
			return 99; // Can't tell: don't blame the recordings.
		}
		return n;
	}

	private static boolean rewardsChangedSince(Path profileDir, long since) {
		Path dir = profileDir.resolve("objectives");
		try (var files = Files.walk(dir, 2)) {
			return files.filter(Files::isRegularFile).anyMatch(f -> {
				try {
					return Files.getLastModifiedTime(f).toMillis() >= since;
				} catch (Exception e) {
					return false;
				}
			});
		} catch (Exception e) {
			return false;
		}
	}
}
