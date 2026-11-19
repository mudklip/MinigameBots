package io.github.mudklip.minigamebots.client.coach;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.practice.ObjectiveStore;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.train.BaritoneTeacher;
import io.github.mudklip.minigamebots.client.train.TeacherDemos;
import io.github.mudklip.minigamebots.client.train.TrainingManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The Coach: pick a goal and a time budget, and it chains the existing tools into a plan and
 * runs it: builds a practice arena, records teacher demos, trains, practices, takes exams, and
 * repeats the practice/exam part until the time is up. Steps that can't run (e.g. practice on
 * a VPT-based AI, Baritone not installed) are skipped with a note, never silently.
 * Needs singleplayer (or operator rights) with Training Grounds for PvP and aim.
 */
public final class Coach {
	public enum Goal {
		PVP("PvP", Items.DIAMOND_SWORD, "general", "Fight like an expert: crits, W-taps, shields, then beat the league."),
		AIM("Aim", Items.BOW, "general", "Turn onto targets and track them fast and steadily."),
		MOVEMENT("Movement", Items.LEATHER_BOOTS, "travel", "Walk, jump, bridge and path across terrain (Baritone teacher)."),
		MINING("Mining", Items.IRON_PICKAXE, "mining", "Find and mine ores (Baritone teacher)."),
		SURVIVAL("Survival", Items.COOKED_BEEF, "general", "Stay alive: fight back, flee, eat, keep going.");

		public final String label;
		public final Item icon;
		public final String skill;
		public final String help;

		Goal(String label, Item icon, String skill, String help) {
			this.label = label;
			this.icon = icon;
			this.skill = skill;
			this.help = help;
		}

		public String key() {
			return name().toLowerCase();
		}
	}

	/** One step of a plan. */
	public static final class Step {
		public final String label;
		final Runnable start;
		final BooleanSupplier done;
		final boolean repeat;
		public String state = "waiting"; // waiting, running, done, skipped

		Step(String label, boolean repeat, Runnable start, BooleanSupplier done) {
			this.label = label;
			this.repeat = repeat;
			this.start = start;
			this.done = done;
		}
	}

	private static @Nullable Goal goal;
	private static final List<Step> steps = new ArrayList<>();
	private static int index;
	private static long endAt;
	private static long stepStarted;
	private static int minutes;
	private static int cycles;
	public static final List<String> log = new ArrayList<>();

	private Coach() {
	}

	private static @Nullable String skillId;
	private static @Nullable String skillName;

	public static boolean running() {
		return goal != null || skillId != null;
	}

	/** The skill being coached (from the Skills screen), or null. */
	public static @Nullable String skillId() {
		return skillId;
	}

	public static @Nullable String skillName() {
		return skillName;
	}

	private static String xpKey() {
		return goal != null ? goal.key() : skillId != null ? skillId : "general";
	}

	/**
	 * Coaches one skill: sets up where it practices (arena, dummy, duel or aim trainer), records
	 * teacher demos and trains first if it has a teacher and nothing learned yet, then practices
	 * with the skill's rewards, hints and locks until the time is up.
	 */
	public static @Nullable String startSkill(String id, io.github.mudklip.minigamebots.client.skills.SkillDef def, int budgetMinutes) {
		var mgb = MinigameBotsClient.instance();
		ProfileManifest p = mgb.profiles().active();
		Minecraft mc = Minecraft.getInstance();
		if (p == null) {
			return "Pick an AI first (AI Profiles > Use this AI)";
		}
		if (!mgb.brain().runtime().installed()) {
			return "Set up the brain first (Brain > Install)";
		}
		if (mc.player == null) {
			return "Join a world first";
		}
		if (!lite(p)) {
			return "Skill practice needs a Lite-based AI (this one is built on a VPT foundation)";
		}
		String setup = def.setup == null ? "none" : def.setup;
		if (!setup.equals("none") && io.github.mudklip.minigamebots.client.TrainingLink.get().isEmpty()) {
			return "This skill practices in Training Grounds (the training mod): add it to this world, or set Where to \"Nothing\"";
		}
		stop("");
		io.github.mudklip.minigamebots.client.profile.Backups.auto(mgb.profiles().store().dir(p.id), "Before practicing " + def.name);
		skillId = id;
		skillName = def.name;
		io.github.mudklip.minigamebots.client.control.ActionLocks.session(
				io.github.mudklip.minigamebots.client.brain.BrainManager.lockedKeys(def), def.locks.contains("camera"));
		minutes = budgetMinutes;
		endAt = System.currentTimeMillis() + budgetMinutes * 60_000L;
		index = 0;
		cycles = 0;
		log.clear();
		steps.clear();
		int chunk = Math.max(5, Math.min(30, budgetMinutes / 3));
		if (setup.equals("dummy")) {
			arena();
			steps.add(new Step("Spawn a practice dummy", false, () -> cmd("tg dummy spawn L3"), () -> elapsed() > 1500));
		} else if (setup.startsWith("duel:")) {
			arena();
			match(setup.substring(5));
		}
		String network = io.github.mudklip.minigamebots.client.skills.SkillStore.network(id, def);
		boolean trained = io.github.mudklip.minigamebots.client.profile.Skills.trained(mgb.profiles().store().dir(p.id), network);
		if (!trained && "pvp".equals(def.teacher)) {
			teacher(TeacherDemos.TEACHERS.get(0), Math.max(3, Math.min(10, budgetMinutes / 6)));
			train(p, TrainingManager.Plan.QUICK, network, true, "teacher", "human", "player");
		} else if (!trained && def.teacher.startsWith("baritone:") && BaritoneTeacher.installed()) {
			String task = def.teacher.substring(9);
			TeacherDemos.available().stream().filter(x -> x.policy().equals("baritone:" + task)).findFirst().ifPresent(t -> {
				teacher(t, Math.max(3, Math.min(10, budgetMinutes / 6)));
				train(p, TrainingManager.Plan.QUICK, network, false, "teacher", "human");
			});
		}
		String aimMode = setup.startsWith("aim:") ? setup.substring(4) : null;
		if (aimMode != null) {
			arena();
		}
		long[] lastAim = {0};
		steps.add(new Step("Practice " + def.name + " (" + chunk + " min)", true, () -> {
			if (aimMode != null) {
				cmd("tg aim " + aimMode + " 300");
				lastAim[0] = System.currentTimeMillis();
			}
			mgb.brain().practiceSkill(Minecraft.getInstance(), p, id, def, true);
		}, () -> {
			if (aimMode != null && System.currentTimeMillis() - lastAim[0] > 302_000) {
				cmd("tg aim " + aimMode + " 300");
				lastAim[0] = System.currentTimeMillis();
			}
			return elapsed() > chunk * 60_000L;
		}));
		note("Plan for " + def.name + ", " + budgetMinutes + " min: " + steps.size() + " steps");
		return null;
	}

	public static @Nullable Goal goal() {
		return goal;
	}

	public static List<Step> steps() {
		return steps;
	}

	public static int cycles() {
		return cycles;
	}

	public static long minutesLeft() {
		return Math.max(0, (endAt - System.currentTimeMillis()) / 60000);
	}

	private static void note(String s) {
		log.add(0, s);
		while (log.size() > 12) {
			log.remove(log.size() - 1);
		}
	}

	private static void cmd(String c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand(c);
		}
	}

	private static long elapsed() {
		return System.currentTimeMillis() - stepStarted;
	}

	/** Lite-based AI (foundation unset or "lite-..."), as opposed to one built on a VPT foundation. */
	private static boolean lite(ProfileManifest p) {
		return p.foundation == null || p.foundation.isBlank() || p.foundation.startsWith("lite");
	}

	/** Starts a plan; returns a problem to show, or null. */
	public static @Nullable String start(Goal g, int budgetMinutes) {
		var mgb = MinigameBotsClient.instance();
		ProfileManifest p = mgb.profiles().active();
		Minecraft mc = Minecraft.getInstance();
		if (p == null) {
			return "Pick an AI first (AI Profiles > Use this AI)";
		}
		if (!mgb.brain().runtime().installed()) {
			return "Set up the brain first (Brain > Install)";
		}
		if (mc.player == null) {
			return "Join a world first";
		}
		boolean grounds = io.github.mudklip.minigamebots.client.TrainingLink.get().isPresent();
		if ((g == Goal.PVP || g == Goal.AIM) && !grounds) {
			return "PvP and Aim need Training Grounds (the training mod) in this world";
		}
		stop("");
		io.github.mudklip.minigamebots.client.profile.Backups.auto(mgb.profiles().store().dir(p.id), "Before coaching " + g.label);
		goal = g;
		minutes = budgetMinutes;
		endAt = System.currentTimeMillis() + budgetMinutes * 60_000L;
		index = 0;
		cycles = 0;
		log.clear();
		steps.clear();
		build(g, p, budgetMinutes);
		note("Plan for " + g.label + ", " + budgetMinutes + " min: " + steps.size() + " steps");
		return null;
	}

	private static void build(Goal g, ProfileManifest p, int budget) {
		var mgb = MinigameBotsClient.instance();
		int chunk = Math.max(5, Math.min(60, budget / 4));
		TrainingManager.Plan plan = budget <= 30 ? TrainingManager.Plan.QUICK : budget <= 120 ? TrainingManager.Plan.STANDARD
				: TrainingManager.Plan.LONG;
		boolean lite = lite(p);
		switch (g) {
			case PVP -> {
				arena();
				match("godly");
				teacher(TeacherDemos.TEACHERS.get(0), Math.max(3, Math.min(15, budget / 6)));
				train(p, plan, g.skill, lite, "teacher", "human", "player");
				if (lite) {
					steps.add(new Step("Practice in 6 worlds at once (" + chunk + " min)", true, () -> {
						cmd("tg match stop");
						cmd("tg multitrain start coach 6 L4");
					}, () -> elapsed() > chunk * 60_000L));
					steps.add(new Step("Stop multi-world practice", true, () -> cmd("tg multitrain stop"), () -> elapsed() > 3000));
				} else {
					skip("Practice (needs a Lite-based AI): training on demos instead");
					teacher(TeacherDemos.TEACHERS.get(0), Math.min(15, chunk));
					train(p, TrainingManager.Plan.QUICK, g.skill, false, "teacher");
				}
				match("league");
				exam(p, 5);
			}
			case AIM -> {
				train(p, TrainingManager.Plan.QUICK, g.skill, lite, "teacher");
				arena();
				long[] lastAim = {0};
				steps.add(new Step("Aim trainer practice (" + chunk + " min)", true, () -> {
					cmd("tg aim flick 300");
					lastAim[0] = System.currentTimeMillis();
					practice(p, "aim", g.skill);
				}, () -> {
					// A new 5-minute aim session when the last one has ended (once, not every tick).
					if (System.currentTimeMillis() - lastAim[0] > 302_000) {
						cmd("tg aim flick 300");
						lastAim[0] = System.currentTimeMillis();
					}
					return elapsed() > chunk * 60_000L;
				}));
				steps.add(new Step("Aim test (60 s, no learning)", true, () -> {
					stopAi();
					cmd("tg aim flick 60");
					mgb.brain().playLearned(Minecraft.getInstance(), p);
				}, () -> elapsed() > 65_000));
			}
			case MOVEMENT, MINING -> {
				var task = g == Goal.MOVEMENT ? BaritoneTeacher.Task.TRAVEL : BaritoneTeacher.Task.MINE;
				if (!BaritoneTeacher.installed()) {
					skip("Baritone isn't installed: record yourself (F7) doing it instead, then this plan trains on it");
					train(p, plan, g.skill, false, "human");
					return;
				}
				var t = TeacherDemos.available().stream().filter(x -> x.policy().equals("baritone:" + task.name())).findFirst().orElseThrow();
				teacher(t, Math.min(20, chunk));
				train(p, plan, g.skill, false, "teacher", "human");
			}
			case SURVIVAL -> {
				if (!lite) {
					skip("Practice needs a Lite-based AI: training on your recordings instead");
					train(p, plan, g.skill, false, "human", "player");
					return;
				}
				steps.add(new Step("Practice surviving (" + chunk + " min)", true, () -> practice(p, "survive", g.skill),
						() -> elapsed() > chunk * 60_000L));
				steps.add(new Step("Practice hunting for food (" + Math.max(5, chunk / 2) + " min)", true,
						() -> practice(p, "hunt", g.skill), () -> elapsed() > Math.max(5, chunk / 2) * 60_000L));
			}
		}
	}

	private static void skip(String why) {
		Step s = new Step(why, false, () -> { }, () -> true);
		s.state = "skipped";
		steps.add(s);
	}

	private static void arena() {
		steps.add(new Step("Build a practice arena here", false, () -> cmd("tg arena quick coach"), () -> elapsed() > 2000));
	}

	private static void match(String style) {
		steps.add(new Step("Start a duel vs " + style, style.equals("league"), () -> {
			cmd("tg minigame duel coach coach_m");
			cmd("tg match opponent coach_m blue " + style);
			cmd("tg match join coach_m red");
			cmd("tg match start coach_m");
		}, () -> elapsed() > 4000));
	}

	private static void teacher(TeacherDemos.Teacher t, int mins) {
		long[] lastStart = {0};
		steps.add(new Step("Record " + t.label() + " demos (" + mins + " min)", false, () -> {
			lastStart[0] = System.currentTimeMillis();
			TeacherDemos.start(Minecraft.getInstance(), t, mins);
		}, () -> {
			if (TeacherDemos.active()) {
				return false;
			}
			long left = mins * 60_000L - elapsed();
			if (left > 20_000 && System.currentTimeMillis() - lastStart[0] > 8000) {
				// The demo ended early (e.g. knocked out of a round): record another for the time left.
				lastStart[0] = System.currentTimeMillis();
				TeacherDemos.start(Minecraft.getInstance(), t, (int) Math.max(1, left / 60_000));
				return false;
			}
			return left <= 20_000;
		}));
	}

	private static void train(ProfileManifest p, TrainingManager.Plan plan, String skill, boolean aimDrill, String... sources) {
		var mgb = MinigameBotsClient.instance();
		boolean[] failed = {false};
		steps.add(new Step("Train " + skill + " (" + plan.label + ")", false, () -> {
			stopAi();
			List<RecordingMeta> recs = mgb.recorder().store().list().stream()
					.filter(m -> java.util.Arrays.asList(sources).contains(m.source)).toList();
			try {
				mgb.training().setAimDrill(aimDrill);
				mgb.training().start(p, recs, plan, TrainingManager.Resources.BALANCED,
						!io.github.mudklip.minigamebots.client.profile.Skills.trained(mgb.profiles().store().dir(p.id), skill),
						lite(p) ? null : p.foundation, skill);
			} catch (Exception e) {
				note("Training skipped: " + e.getMessage());
				failed[0] = true;
			}
		}, () -> failed[0] || elapsed() > 5000 && !mgb.training().busy()));
	}

	private static void practice(ProfileManifest p, String objective, String skill) {
		var o = ObjectiveStore.templates().get(objective);
		if (o != null) {
			MinigameBotsClient.instance().brain().practice(Minecraft.getInstance(), p, o, true, 0.05, "balanced", skill);
		}
	}

	private static void exam(ProfileManifest p, int rounds) {
		var mgb = MinigameBotsClient.instance();
		steps.add(new Step("Exam: " + rounds + " rounds vs the league", true, () -> {
			stopAi();
			mgb.exams().start(p, rounds, 0, "coach");
			mgb.brain().playLearned(Minecraft.getInstance(), p);
		}, () -> elapsed() > 5000 && !mgb.exams().running() || elapsed() > 10 * 60_000L));
	}

	private static void stopAi() {
		Minecraft mc = Minecraft.getInstance();
		if (AgentControl.aiActive()) {
			AgentControl.get().stop(mc, net.minecraft.network.chat.Component.literal("Coach: next step"));
		}
	}

	public static void stop(String why) {
		if (!running()) {
			return;
		}
		stopAi();
		if (TeacherDemos.active()) {
			MinigameBotsClient.instance().recorder().stop(Minecraft.getInstance());
		}
		cmd("tg multitrain stop");
		cmd("tg match stop");
		if (!why.isEmpty()) {
			note(why);
		}
		goal = null;
		skillId = null;
		io.github.mudklip.minigamebots.client.control.ActionLocks.endSession();
		skillName = null;
	}

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		if (!running() || steps.isEmpty()) {
			return;
		}
		if (mc.player == null) {
			stop("Stopped: left the world");
			return;
		}
		if (mc.gui.screen() != null) {
			return; // A menu is open (maybe you're checking progress): start nothing new until it closes.
		}
		if (index >= steps.size()) {
			// One full pass done: repeat the repeatable part while time is left.
			cycles++;
			Progress.addXp(xpKey(), 10 + minutes / 6, "Coach cycle " + cycles);
			if (System.currentTimeMillis() >= endAt || steps.stream().noneMatch(s -> s.repeat)) {
				Progress.unlock("coach_plan");
				if (minutes >= 360) {
					Progress.unlock("overnight");
				}
				stop("Plan finished: " + cycles + " cycle(s). Check Exams for the results.");
				return;
			}
			index = 0;
			while (index < steps.size() && !steps.get(index).repeat) {
				index++;
			}
			steps.forEach(s -> {
				if (s.repeat) {
					s.state = "waiting";
				}
			});
		}
		Step s = steps.get(index);
		if (s.state.equals("skipped")) {
			index++;
			return;
		}
		if (s.state.equals("waiting")) {
			s.state = "running";
			stepStarted = System.currentTimeMillis();
			note("▶ " + s.label);
			s.start.run();
			return;
		}
		if (s.done.getAsBoolean()) {
			s.state = "done";
			index++;
		}
	}
}
