package io.github.mudklip.minigamebots.client.practice;

import com.google.gson.reflect.TypeToken;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Exams: score the AI over the next N rounds of a Training Grounds match, without learning,
 * and keep the result in the profile ({@code exams.json}) so you can see whether training helps.
 */
public final class ExamManager {
	/** One finished exam. */
	public static final class Result {
		public String date = "";
		public String scenario = "";
		public long checkpointStep;
		public String phase = "";
		public int rounds;
		public int wins;
		public int losses;
		public int draws;
		public double kills;
		public double deaths;
		public double damageDealt;
		public double damageTaken;
		public double seconds;

		public double winRate() {
			return rounds == 0 ? 0 : (double) wins / rounds;
		}
	}

	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private @Nullable ProfileManifest profile;
	private @Nullable Result running;
	private int target;

	public boolean running() {
		return running != null;
	}

	public int done() {
		Result r = running;
		return r == null ? 0 : r.rounds;
	}

	public int target() {
		return target;
	}

	public @Nullable ProfileManifest profile() {
		return profile;
	}

	/** Starts counting rounds; the AI should be playing (the caller hands it control). */
	public void start(ProfileManifest p, int rounds, long checkpointStep, String phase) {
		profile = p;
		target = rounds;
		Result r = new Result();
		r.date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		r.checkpointStep = checkpointStep;
		r.phase = phase;
		running = r;
	}

	public void cancel() {
		running = null;
	}

	/** Event listener (registered once by the client). */
	public void onEvent(GameEvent e) {
		Result r = running;
		if (r == null || !"tg_episode_end".equals(e.type())) {
			return;
		}
		String result = String.valueOf(e.data().get("result"));
		if ("stopped".equals(e.data().get("reason"))) {
			return;
		}
		r.scenario = String.valueOf(e.data().getOrDefault("scenario", ""));
		r.rounds++;
		switch (result) {
			case "win" -> r.wins++;
			case "loss" -> r.losses++;
			default -> r.draws++;
		}
		r.kills += num(e, "kills");
		r.deaths += num(e, "deaths");
		r.damageDealt += num(e, "damage_dealt");
		r.damageTaken += num(e, "damage_taken");
		r.seconds += num(e, "duration_s");
		if (r.rounds >= target) {
			finish(r);
		}
	}

	private void finish(Result r) {
		running = null;
		io.github.mudklip.minigamebots.client.coach.Progress.setRating("pvp", r.winRate() * 100,
				"exam: won " + r.wins + " of " + r.rounds);
		io.github.mudklip.minigamebots.client.coach.Progress.addXp("pvp", 5 + r.wins * 2, "exam");
		ProfileManifest p = profile;
		if (p == null) {
			return;
		}
		save(MinigameBotsClient.instance().profiles().store().dir(p.id), r);
		Minecraft c = Minecraft.getInstance();
		String text = String.format("%s: won %d of %d rounds", p.name, r.wins, r.rounds);
		c.execute(() -> SystemToast.addOrUpdate(c.gui.toastManager(), TOAST, Component.literal("Exam finished"),
				Component.literal(text)));
	}

	/** Adds a finished exam to the profile's {@code exams.json}. */
	public static void save(Path profileDir, Result r) {
		List<Result> all = load(profileDir);
		all.add(r);
		try {
			Files.writeString(profileDir.resolve("exams.json"), ObjectiveStore.GSON.toJson(all));
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.warn("Couldn't save exam result", e);
		}
	}

	public static List<Result> load(Path profileDir) {
		Path f = profileDir.resolve("exams.json");
		if (!Files.isRegularFile(f)) {
			return new ArrayList<>();
		}
		try {
			List<Result> list = ObjectiveStore.GSON.fromJson(Files.readString(f), new TypeToken<List<Result>>() { }.getType());
			return list == null ? new ArrayList<>() : new ArrayList<>(list);
		} catch (IOException | RuntimeException e) {
			return new ArrayList<>();
		}
	}

	private static double num(GameEvent e, String key) {
		return e.data().get(key) instanceof Number n ? n.doubleValue() : 0;
	}
}
