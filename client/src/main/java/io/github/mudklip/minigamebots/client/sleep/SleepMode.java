package io.github.mudklip.minigamebots.client.sleep;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.invent.InventSession;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.train.TrainingManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/**
 * Sleep: uses idle time to get better, with as little load on the PC as possible. It replays the
 * day (trains on what was recorded since it last slept: your play, players it watched, its own
 * builds), tidies its memory (lets trivial moments go, keeps what matters), and optionally keeps
 * inventing. Meanwhile the game draws almost nothing (10 FPS, short view, muted, optionally
 * minimised) and training runs on the "Quiet" setting; in singleplayer the world stays paused
 * unless inventing needs it. Training keeps the best version on held-out examples, so a night's
 * replay can't make it worse on what it has seen. Everything goes back when it wakes, with a
 * morning report of what it did.
 */
public final class SleepMode {
	private record Step(String label, Runnable start, BooleanSupplier done) {
	}

	private static final List<Step> steps = new ArrayList<>();
	private static final List<String> report = new ArrayList<>();
	private static int current = -1;
	private static boolean sleeping;
	private static boolean worldRuns;
	private static long startedAt;
	private static int fps;
	private static int view;
	private static double volume;
	private static String status = "";
	/** This sleep was started by minimising the game (restoring it wakes it). */
	private static boolean byMinimise;
	private static long minimisedSince = -1;

	private SleepMode() {
	}

	public static boolean sleeping() {
		return sleeping;
	}

	/** Whether the world keeps running while it sleeps (only when inventing). */
	public static boolean worldRuns() {
		return worldRuns;
	}

	public static String status() {
		return status;
	}

	public static List<String> report() {
		return List.copyOf(report);
	}

	public static String start(Minecraft mc, boolean minimise, boolean invent) {
		var mgb = MinigameBotsClient.instance();
		ProfileManifest p = mgb.profiles().active();
		if (p == null) {
			return "Pick an AI first (AI Profiles > Use this AI)";
		}
		if (sleeping) {
			return "Already asleep";
		}
		if (AgentControl.aiActive()) {
			AgentControl.get().stop(mc, Component.literal("Going to sleep"));
		}
		sleeping = true;
		worldRuns = invent && InventSession.available();
		startedAt = System.currentTimeMillis();
		report.clear();
		steps.clear();
		current = -1;
		// Low load: draw almost nothing, no sound.
		fps = mc.options.framerateLimit().get();
		view = mc.options.renderDistance().get();
		volume = mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).get();
		mc.options.framerateLimit().set(10);
		mc.options.renderDistance().set(2);
		mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
		var dir = mgb.profiles().store().dir(p.id);
		steps.add(new Step("Saving a backup first", () -> {
			try {
				io.github.mudklip.minigamebots.client.profile.Backups.auto(dir, "Before sleep");
			} catch (RuntimeException e) {
				report.add("Couldn't save a backup: " + e.getMessage());
			}
		}, () -> true));
		steps.add(new Step("Tidying its memories", () -> {
			var wm = mgb.memory().memory();
			if (wm == null) {
				report.add("Memories: none here to tidy (join a world first)");
				return;
			}
			int before = wm.mind.episodes.size();
			int gone = wm.mind.consolidate(System.currentTimeMillis());
			mgb.memory().save();
			report.add("Memories: let go of " + gone + " small moments, kept " + (before - gone) + " (and what it learned about "
					+ wm.mind.people.size() + " people)");
		}, () -> true));
		// Daily lessons: what the day's memories show, put into words (by the mind if installed).
		boolean[] lessonsDone = {false};
		long[] lessonsAsked = {0};
		steps.add(new Step("Thinking over the day", () -> {
			lessonsAsked[0] = System.currentTimeMillis();
			var wm = mgb.memory().memory();
			if (wm == null) {
				lessonsDone[0] = true;
				return;
			}
			long now = System.currentTimeMillis();
			int before = wm.mind.lessons.size();
			wm.mind.checkLessons(now);
			long trusted = wm.mind.lessons.stream().filter(io.github.mudklip.minigamebots.client.memory.Mind.Lesson::trusted).count();
			var facts = wm.mind.lessonFacts(key -> {
				String[] k = key.split(":");
				try {
					return "around " + (Integer.parseInt(k[k.length - 2]) * 16 + 8) + ", " + (Integer.parseInt(k[k.length - 1]) * 16 + 8);
				} catch (RuntimeException e) {
					return "in one place";
				}
			});
			if (facts.isEmpty()) {
				report.add("Lessons: nothing new to learn from today (" + wm.mind.lessons.size() + " kept, " + trusted + " trusted)");
				lessonsDone[0] = true;
				mgb.memory().save();
				return;
			}
			var req = new com.google.gson.JsonObject();
			req.addProperty("type", "lessons");
			var arr = new com.google.gson.JsonArray();
			for (String[] f : facts) {
				var o = new com.google.gson.JsonObject();
				o.addProperty("type", f[0]);
				o.addProperty("subject", f.length > 3 ? f[3] : f[1]);
				o.addProperty("facts", f[2]);
				arr.add(o);
			}
			req.add("facts", arr);
			mgb.brain().ask(req, r -> {
				var texts = r.has("lessons") ? r.getAsJsonArray("lessons") : null;
				for (int i = 0; i < facts.size(); i++) {
					var l = new io.github.mudklip.minigamebots.client.memory.Mind.Lesson();
					l.type = facts.get(i)[0];
					l.subject = facts.get(i)[1];
					l.text = texts != null && i < texts.size() ? texts.get(i).getAsString() : facts.get(i)[2];
					l.made = now;
					int[] e = wm.mind.evidence(l);
					l.confirm0 = e[0];
					l.contradict0 = e[1];
					wm.mind.lessons.add(l);
				}
				if (r.has("idea") && !r.get("idea").getAsString().isBlank()) {
					var l = new io.github.mudklip.minigamebots.client.memory.Mind.Lesson();
					l.type = "idea";
					l.text = r.get("idea").getAsString();
					l.made = now;
					wm.mind.lessons.add(l);
				}
				mgb.memory().save();
				report.add("Lessons: " + facts.size() + " new (" + (wm.mind.lessons.size() - before) + " written), " + trusted
						+ " trusted so far. See them on the Memory screen.");
				lessonsDone[0] = true;
			});
		}, () -> lessonsDone[0] || System.currentTimeMillis() - lessonsAsked[0] > 120_000));
		String sinceIso = MgbConfig.get().lastSleep;
		// Dreaming: the day's hard moments, replayed in its head with other choices (dream.py).
		boolean[] dreamt = {false};
		steps.add(new Step("Dreaming: going over hard moments in its head", () -> {
			Thread t = new Thread(() -> {
				try {
					double since;
					try {
						since = OffsetDateTime.parse(sinceIso).toEpochSecond();
					} catch (RuntimeException e) {
						since = System.currentTimeMillis() / 1000.0 - 86400;
					}
					List<String> dreamArgs = new java.util.ArrayList<>(List.of("dream", "--recordings",
							mgb.recorder().store().root().toString(), "--since", String.valueOf(since)));
					var dreamer = mgb.profiles().active();
					if (dreamer != null) {
						dreamArgs.addAll(List.of("--profile", mgb.profiles().store().dir(dreamer.id).toString()));
					}
					var r = io.github.mudklip.minigamebots.client.video.VideoLab.run(dreamArgs, m -> { });
					int better = r.has("better") ? r.get("better").getAsInt() : 0;
					int moments = r.has("moments") ? r.get("moments").getAsInt() : 0;
					report.add(better > 0 ? "Dreams: went over " + moments + " hard moments, found a better choice for " + better
							+ (r.has("tried") ? " " + r.get("tried") : "") + " (practised at low weight)"
							: "Dreams: " + (r.has("why") ? r.get("why").getAsString() : "nothing better found"));
				} catch (Exception e) {
					report.add("Dreams: couldn't (" + e.getMessage() + ")");
				}
				dreamt[0] = true;
			}, "MinigameBots dreaming");
			t.setDaemon(true);
			t.start();
		}, () -> dreamt[0]));
		steps.add(new Step("Replaying the day", () -> {
			List<RecordingMeta> play = since(sinceIso).stream()
					.filter(m -> "human".equals(m.source) || "player".equals(m.source) || "dream".equals(m.source)).toList();
			if (play.isEmpty()) {
				report.add("Replay: nothing new recorded since it last slept");
			} else {
				train(p, play, "general");
			}
		}, () -> !mgb.training().busy()));
		List<RecordingMeta> today = since(sinceIso);
		List<RecordingMeta> builds = today.stream().filter(m -> "teacher".equals(m.source)
				&& (m.name.startsWith("Build: ") || m.name.toLowerCase(java.util.Locale.ROOT).contains("baritone: build"))).toList();
		if (!builds.isEmpty()) {
			steps.add(new Step("Going over its own builds (" + builds.size() + ")", () -> train(p, builds, "building"),
					() -> !mgb.training().busy()));
		}
		if (worldRuns) {
			steps.add(new Step("Inventing traps (5 min)", () -> InventSession.start("trap", 5), () -> !InventSession.running()));
			steps.add(new Step("Inventing shelters (5 min)", () -> InventSession.start("shelter", 5), () -> !InventSession.running()));
		}
		if (minimise) {
			org.lwjgl.sdl.SDLVideo.SDL_MinimizeWindow(mc.getWindow().handle());
		}
		status = "Asleep";
		return "Asleep";
	}

	private static List<RecordingMeta> since(@Nullable String when) {
		var all = MinigameBotsClient.instance().recorder().store().list();
		OffsetDateTime t;
		try {
			t = OffsetDateTime.parse(when == null ? "" : when);
		} catch (RuntimeException e) {
			t = OffsetDateTime.now().minusDays(1); // First sleep: the last 24 hours.
		}
		OffsetDateTime from = t;
		return all.stream().filter(m -> {
			try {
				return m.complete && OffsetDateTime.parse(m.created).isAfter(from);
			} catch (RuntimeException e) {
				return false;
			}
		}).toList();
	}

	private static void train(ProfileManifest p, List<RecordingMeta> recs, String skill) {
		var mgb = MinigameBotsClient.instance();
		try {
			boolean lite = p.foundation == null || p.foundation.isBlank() || p.foundation.startsWith("lite");
			mgb.training().start(p, recs, TrainingManager.Plan.QUICK, TrainingManager.Resources.QUIET,
					!io.github.mudklip.minigamebots.client.profile.Skills.trained(mgb.profiles().store().dir(p.id), skill),
					lite ? null : p.foundation, skill);
			double minutes = recs.stream().mapToInt(m -> m.ticks).sum() / 20.0 / 60;
			report.add("Replayed " + recs.size() + " recording(s), " + String.format("%.0f", minutes) + " min of play, into "
					+ ("general".equals(skill) ? "its main network" : "its " + skill + " skill"));
		} catch (Exception e) {
			report.add("Couldn't replay into " + skill + ": " + e.getMessage());
		}
	}

	/**
	 * Sleep when minimised (only if you turned it on): you minimise Minecraft, it falls asleep a few
	 * seconds later (so a minimise-and-back doesn't count); restoring the window wakes it. Never
	 * anything automatic based on time: only your minimising starts it.
	 */
	private static void watchMinimise(Minecraft mc) {
		boolean minimised = mc.getWindow().isIconified();
		if (!minimised) {
			minimisedSince = -1;
			if (sleeping && byMinimise) {
				byMinimise = false;
				wake(mc, false);
			}
			return;
		}
		if (!MgbConfig.get().sleepWhenMinimised || sleeping || mc.player == null) {
			return;
		}
		if (minimisedSince < 0) {
			minimisedSince = System.currentTimeMillis();
		} else if (System.currentTimeMillis() - minimisedSince > 5000) {
			String r = start(mc, false, false);
			if (sleeping) {
				byMinimise = true;
				mc.gui.setScreen(new SleepScreen(null)); // Pauses a singleplayer world.
			} else {
				minimisedSince = Long.MAX_VALUE / 2; // Couldn't (e.g. no AI picked): don't keep trying.
				status = r;
			}
		}
	}

	/** Every client tick while asleep: one step after another. */
	public static void tick(Minecraft mc) {
		watchMinimise(mc);
		if (!sleeping) {
			return;
		}
		if (current >= 0 && current < steps.size() && !steps.get(current).done().getAsBoolean()) {
			var tr = MinigameBotsClient.instance().training();
			if (tr.busy() && tr.totalSteps() > 0) {
				status = steps.get(current).label() + ": " + tr.step() + "/" + tr.totalSteps() + " (about "
						+ Math.max(1, Math.round(tr.etaSeconds() / 60)) + " min left)";
			}
			return;
		}
		current++;
		if (current >= steps.size()) {
			status = "Done: it's had a good night's sleep";
			wake(mc, true);
			return;
		}
		status = steps.get(current).label() + "...";
		steps.get(current).start().run();
	}

	public static void wake(Minecraft mc, boolean finished) {
		if (!sleeping) {
			return;
		}
		sleeping = false;
		worldRuns = false;
		byMinimise = false;
		var mgb = MinigameBotsClient.instance();
		if (!finished) {
			if (mgb.training().busy()) {
				mgb.training().stop();
				report.add("Woken early: the replay in progress was stopped (what it had learned so far is kept only if better)");
			}
			InventSession.stop();
		}
		mc.options.framerateLimit().set(fps);
		mc.options.renderDistance().set(view);
		mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(volume);
		org.lwjgl.sdl.SDLVideo.SDL_RestoreWindow(mc.getWindow().handle());
		MgbConfig.get().lastSleep = OffsetDateTime.now().toString();
		MgbConfig.HANDLER.save();
		long min = (System.currentTimeMillis() - startedAt) / 60_000;
		report.add(0, (finished ? "Slept " : "Woke after ") + min + " min.");
		if (!(mc.gui.screen() instanceof SleepScreen)) {
			mc.execute(() -> mc.gui.setScreen(new SleepScreen(null)));
		}
	}
}
