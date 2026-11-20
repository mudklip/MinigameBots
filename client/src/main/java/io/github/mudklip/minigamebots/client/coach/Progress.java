package io.github.mudklip.minigamebots.client.coach;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * An AI's progress (progress.json in its profile): XP per skill (time and effort put in),
 * measured ratings where a real test exists (PvP exam win rate, aim trainer score), and
 * achievements. XP shows effort; ratings only move when the AI actually got better.
 */
public final class Progress {
	public static final Map<String, String> ACHIEVEMENTS = new LinkedHashMap<>();

	static {
		ACHIEVEMENTS.put("first_training", "Finished its first training");
		ACHIEVEMENTS.put("first_hit", "Landed its first hit");
		ACHIEVEMENTS.put("first_crit", "First critical hit");
		ACHIEVEMENTS.put("first_kill", "First kill");
		ACHIEVEMENTS.put("beat_l5", "Beat a sweaty (L5) dummy");
		ACHIEVEMENTS.put("beat_godly", "Beat the godly teacher");
		ACHIEVEMENTS.put("wins_100", "Won 100 rounds");
		ACHIEVEMENTS.put("aim_80", "Aim trainer score 80+");
		ACHIEVEMENTS.put("coach_plan", "Completed a Coach plan");
		ACHIEVEMENTS.put("overnight", "Trained overnight");
	}

	public Map<String, Integer> xp = new LinkedHashMap<>();
	public Map<String, Double> rating = new LinkedHashMap<>();
	/** Your own scores on the same tests (the human floor): "duel" win rate, "aim" score. */
	public Map<String, Double> human = new LinkedHashMap<>();
	public List<String> achievements = new ArrayList<>();
	public List<String> log = new ArrayList<>();
	public int roundsWon;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static @Nullable Progress current;
	private static String currentId = "";

	public static int level(int xp) {
		return (int) Math.floor(Math.sqrt(Math.max(0, xp) / 20.0));
	}

	/** Fraction of the way to the next level. */
	public static double toNext(int xp) {
		int l = level(xp);
		double a = 20.0 * l * l;
		double b = 20.0 * (l + 1) * (l + 1);
		return (xp - a) / (b - a);
	}

	private static Path file(ProfileManifest p) {
		return MinigameBotsClient.instance().profiles().store().dir(p.id).resolve("progress.json");
	}

	/** The active AI's progress (loaded on demand), or null without an active AI. */
	/** Reads the progress file again next time (it was restored from a backup). */
	public static void forget() {
		current = null;
		currentId = "";
	}

	public static @Nullable Progress active() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		if (p == null) {
			return null;
		}
		if (current == null || !currentId.equals(p.id)) {
			try {
				current = Files.isRegularFile(file(p)) ? GSON.fromJson(Files.readString(file(p)), Progress.class) : new Progress();
			} catch (Exception e) {
				current = new Progress();
			}
			if (current == null) {
				current = new Progress();
			}
			currentId = p.id;
		}
		return current;
	}

	public static void save() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		if (p != null && current != null && currentId.equals(p.id)) {
			try {
				Files.writeString(file(p), GSON.toJson(current));
			} catch (Exception e) {
				MinigameBotsClient.LOGGER.warn("Couldn't save progress", e);
			}
		}
	}

	public static void addXp(String skill, int amount, String note) {
		Progress pr = active();
		if (pr == null || amount <= 0) {
			return;
		}
		int before = level(pr.xp.getOrDefault(skill, 0));
		pr.xp.merge(skill, amount, Integer::sum);
		pr.note("+" + amount + " " + skill + " XP: " + note);
		int after = level(pr.xp.get(skill));
		if (after > before) {
			toast("Level up!", skill + " is now level " + after);
		}
		save();
	}

	public static void unlock(String id) {
		Progress pr = active();
		if (pr == null || pr.achievements.contains(id) || !ACHIEVEMENTS.containsKey(id)) {
			return;
		}
		pr.achievements.add(id);
		pr.note("Achievement: " + ACHIEVEMENTS.get(id));
		toast("Achievement unlocked", ACHIEVEMENTS.get(id));
		save();
	}

	public static void setRating(String skill, double value, String note) {
		Progress pr = active();
		if (pr == null) {
			return;
		}
		Double old = pr.rating.put(skill, value);
		pr.note(String.format("%s rating %.0f%s (%s)", skill, value,
				old == null ? "" : String.format(", %+.0f", value - old), note));
		save();
	}

	private void note(String text) {
		log.add(0, LocalDate.now() + "  " + text);
		while (log.size() > 30) {
			log.remove(log.size() - 1);
		}
	}

	private static void toast(String title, String body) {
		Minecraft c = Minecraft.getInstance();
		c.execute(() -> SystemToast.addOrUpdate(c.gui.toastManager(), new SystemToast.SystemToastId(),
				Component.literal("§6★ " + title), Component.literal(body)));
	}

	/** Event listener: achievements and ratings from what the AI does. */
	public static void onEvent(GameEvent e) {
		if (!AgentControl.aiActive()) {
			return;
		}
		Object d;
		switch (e.type()) {
			case "tg_damage_dealt" -> {
				unlock("first_hit");
				if (Boolean.TRUE.equals(e.data().get("crit"))) {
					unlock("first_crit");
				}
			}
			case "tg_kill" -> unlock("first_kill");
			case "tg_episode_end" -> {
				if ("win".equals(e.data().get("result"))) {
					Progress pr = active();
					if (pr != null) {
						pr.roundsWon++;
						if (pr.roundsWon >= 100) {
							unlock("wins_100");
						}
						save();
					}
					String opp = String.valueOf(e.data().getOrDefault("opponents", ""));
					if (opp.contains("L5")) {
						unlock("beat_l5");
					}
					if (opp.contains("godly")) {
						unlock("beat_godly");
					}
				}
			}
			case "tg_aim_result" -> {
				d = e.data().get("score");
				if (d instanceof Number n) {
					Progress pr = active();
					if (pr != null && n.doubleValue() > pr.rating.getOrDefault("aim", 0.0)) {
						setRating("aim", n.doubleValue(), "aim trainer best");
					}
					if (n.doubleValue() >= 80) {
						unlock("aim_80");
					}
				}
			}
			default -> {
			}
		}
	}
}
