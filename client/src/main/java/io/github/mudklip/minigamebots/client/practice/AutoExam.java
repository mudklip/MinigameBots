package io.github.mudklip.minigamebots.client.practice;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.brain.FastTraining;
import io.github.mudklip.minigamebots.client.brain.MultiWorldLink;
import io.github.mudklip.minigamebots.client.brain.MultiWorldStats;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.Skills;
import io.github.mudklip.minigamebots.client.skills.SkillStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A short exam after every training (A2): when practice, training worlds or imitation training of
 * a fighting network ends, the AI plays a few rounds in headless training worlds with learning off
 * (singleplayer, fast like the training worlds, your player stays yours). Always the same test
 * (4 worlds against L3, 60 s rounds) so the results line up in Exams and Progress.
 *
 * <p>No setup: it starts by itself a few seconds after the training ends; stopping the training
 * worlds (Training worlds > Stop) skips it.
 */
public final class AutoExam {
	public static final int WORLDS = 4;
	public static final String OPPONENT = "L3";
	public static final int ROUND_SECONDS = 60;
	/** Rounds to finish (all worlds together); a few more may end on the last tick. */
	public static final int ROUNDS = 8;
	/** Training shorter than this isn't worth an exam (a quick try, a misclick). */
	private static final long MIN_TRAINING_MS = 60_000;
	private static final long DELAY_MS = 5_000;
	/** Real-time cap; with fewer than {@link #MIN_ROUNDS} finished by then, nothing is saved. */
	private static final long MAX_MS = 5 * 60_000;
	private static final int MIN_ROUNDS = 3;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private enum State {
		IDLE,
		PENDING,
		STARTING,
		RUNNING,
		STOPPING
	}

	private static volatile State state = State.IDLE;
	private static @Nullable ProfileManifest profile;
	private static String network = "general";
	private static String trained = "";
	private static long at;
	private static long startedAt;
	private static long stoppingAt;

	private AutoExam() {
	}

	/** The scenario name results are filed under (one test, so they compare). */
	public static String scenario(String network) {
		return "Auto: " + WORLDS + " worlds vs " + OPPONENT + ("general".equals(network) ? "" : " (" + network + ")");
	}

	public static boolean active() {
		return state != State.IDLE;
	}

	/** One line for screens, or "". */
	public static String status() {
		return switch (state) {
			case PENDING -> "Exam after training starts in " + Math.max(0, (at - System.currentTimeMillis() + 999) / 1000) + " s";
			case STARTING, RUNNING -> String.format("Exam after training: %d / %d rounds", MultiWorldStats.rounds(), ROUNDS);
			case STOPPING -> "Exam after training: finishing";
			default -> "";
		};
	}

	/**
	 * A training of {@code network} for {@code profile} just ended after {@code trainedMs}; {@code what}
	 * says which (for the result). Queues the exam if it applies (fighting network, singleplayer with
	 * Training Grounds, the setting on).
	 */
	public static void afterTraining(ProfileManifest p, String network, String what, long trainedMs) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (!MgbConfig.get().autoExam || state != State.IDLE || trainedMs < MIN_TRAINING_MS || !fights(p, network)
					|| !Skills.trained(dir(p), network) || why(mc) != null) {
				return;
			}
			profile = p;
			AutoExam.network = network;
			trained = what;
			at = System.currentTimeMillis() + DELAY_MS;
			state = State.PENDING;
			toast(mc, "Exam after training", "In 5 s: " + ROUNDS + " quick rounds in training worlds, learning off");
		});
	}

	/** Why it can't run right now, or null. */
	private static @Nullable String why(Minecraft mc) {
		if (mc.player == null || mc.getSingleplayerServer() == null) {
			return "singleplayer only";
		}
		if (io.github.mudklip.minigamebots.client.TrainingLink.get().isEmpty()) {
			return "needs Training Grounds";
		}
		if (MultiWorldLink.running()) {
			return "training worlds are running";
		}
		if (io.github.mudklip.minigamebots.client.coach.Coach.running()) {
			return "the coach runs its own checks";
		}
		if (io.github.mudklip.minigamebots.client.sleep.SleepMode.sleeping()) {
			return "sleeping";
		}
		if (MinigameBotsClient.instance().training().busy()) {
			return "imitation training is running";
		}
		return null;
	}

	/** Only fighting networks: the exam is a duel. */
	static boolean fights(ProfileManifest p, String network) {
		if ("general".equals(network) || "combat".equals(network)) {
			return true;
		}
		for (SkillStore.Entry e : SkillStore.list(dir(p))) {
			if (network.equals(SkillStore.network(e.id(), e.def())) && "combat".equals(e.def().category.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private static Path dir(ProfileManifest p) {
		return MinigameBotsClient.instance().profiles().store().dir(p.id);
	}

	/** Client tick. */
	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		switch (state) {
			case PENDING -> {
				if (now < at) {
					return;
				}
				String why = why(mc);
				if (why != null || profile == null) {
					state = State.IDLE;
					return;
				}
				state = State.STARTING;
				startedAt = now;
				cmd(mc, "tg multitrain here " + WORLDS + " " + OPPONENT + " 1 round=" + ROUND_SECONDS);
				FastTraining.start(mc, MgbConfig.get().worldSpeed, io.github.mudklip.minigamebots.client.gui.WorldsScreen.fastLight());
			}
			case STARTING -> {
				if (MultiWorldLink.running()) {
					state = State.RUNNING;
				} else if (now - startedAt > 20_000) {
					state = State.IDLE; // The worlds never came up (no permission, no room...).
					FastTraining.stop(mc);
					toast(mc, "Exam after training", "Skipped: the training worlds didn't start");
				}
			}
			case RUNNING -> {
				if (MultiWorldStats.rounds() >= ROUNDS || now - startedAt > MAX_MS) {
					stoppingAt = now;
					finish(mc);
				}
			}
			case STOPPING -> {
				if (now - stoppingAt > 15_000) {
					state = State.IDLE; // The stop never came back; don't block the next exam.
				}
			}
			default -> {
			}
		}
	}

	/** Training worlds stopped (by anyone). Returns true if it was the exam's own worlds. */
	public static boolean worldsStopped(long ranMs) {
		State s = state;
		if (s == State.STOPPING) {
			state = State.IDLE;
			return true;
		}
		if (s == State.RUNNING || s == State.STARTING) {
			state = State.IDLE; // Stopped by you: skip the exam, save nothing.
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> toast(mc, "Exam after training", "Skipped (training worlds stopped)"));
			return true;
		}
		// Training worlds of the active AI just ended: the exam follows.
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		if (p != null) {
			afterTraining(p, "general", "training worlds", ranMs);
		}
		return false;
	}

	/**
	 * The brain's arguments for the exam's worlds (learning off, the trained network), or the
	 * training worlds' own when no exam runs. Called from the server thread.
	 */
	public static JsonObject worldArgs(JsonObject args) {
		State s = state;
		ProfileManifest p = profile;
		if ((s == State.STARTING || s == State.RUNNING) && p != null) {
			JsonObject a = new JsonObject();
			a.addProperty("profile", Skills.dir(dir(p), network).toString());
			a.addProperty("objective", "duel");
			a.addProperty("learn", false);
			return a;
		}
		return args;
	}

	private static void finish(Minecraft mc) {
		ProfileManifest p = profile;
		List<MultiWorldStats.World> worlds = MultiWorldStats.worlds();
		int rounds = MultiWorldStats.rounds();
		state = State.STOPPING;
		cmd(mc, "tg multitrain stop");
		if (p == null || rounds < MIN_ROUNDS) {
			toast(mc, "Exam after training", "Not enough rounds finished to score (" + rounds + ")");
			return;
		}
		ExamManager.Result r = new ExamManager.Result();
		r.date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		r.scenario = scenario(network);
		r.phase = "auto after " + trained + phase(p);
		r.checkpointStep = step(p);
		r.rounds = rounds;
		for (MultiWorldStats.World w : worlds) {
			r.wins += w.wins;
			r.losses += w.losses;
			r.damageDealt += w.dealt;
			r.damageTaken += w.taken;
		}
		r.draws = Math.max(0, rounds - r.wins - r.losses);
		r.kills = r.wins; // 1v1 duels: a won round is a kill, a lost one a death.
		r.deaths = r.losses;
		r.seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
		Path dir = dir(p);
		ExamManager.Result before = last(dir, r.scenario);
		ExamManager.save(dir, r);
		if ("general".equals(network)) {
			io.github.mudklip.minigamebots.client.coach.Progress.setRating("pvp", r.winRate() * 100,
					"exam after " + trained + ": won " + r.wins + " of " + r.rounds);
		}
		String text = String.format("Won %d of %d", r.wins, r.rounds)
				+ (before == null ? "" : String.format(" (before: %.0f%%, now %.0f%%)", before.winRate() * 100, r.winRate() * 100));
		toast(mc, "Exam after " + trained, text);
	}

	private static ExamManager.@Nullable Result last(Path dir, String scenario) {
		List<ExamManager.Result> all = ExamManager.load(dir);
		for (int i = all.size() - 1; i >= 0; i--) {
			if (scenario.equals(all.get(i).scenario)) {
				return all.get(i);
			}
		}
		return null;
	}

	private static JsonObject info(ProfileManifest p) {
		try {
			return com.google.gson.JsonParser.parseString(Files.readString(Skills.dir(dir(p), network).resolve("core/model.json")))
					.getAsJsonObject();
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	private static long step(ProfileManifest p) {
		JsonObject o = info(p);
		return o.has("step") ? o.get("step").getAsLong() : 0;
	}

	private static String phase(ProfileManifest p) {
		JsonObject o = info(p);
		long upd = o.has("practice_updates") ? o.get("practice_updates").getAsLong() : 0;
		return upd > 0 ? " (practice " + upd + ")" : "";
	}

	private static void cmd(Minecraft mc, String c) {
		if (mc.player != null) {
			mc.player.connection.sendCommand(c);
		}
	}

	private static void toast(Minecraft mc, String title, String text) {
		io.github.mudklip.minigamebots.client.overlay.Overlay.toast(TOAST, Component.literal(title), Component.literal(text));
	}
}
