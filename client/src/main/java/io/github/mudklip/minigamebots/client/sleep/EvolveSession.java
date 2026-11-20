package io.github.mudklip.minigamebots.client.sleep;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.coach.Watchdog;
import io.github.mudklip.minigamebots.client.practice.ExamManager;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.VersionHistory;
import io.github.mudklip.minigamebots.client.skills.SkillDef;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import io.github.mudklip.minigamebots.client.skills.SkillStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Overnight evolution of a skill's learning settings (a small population-based search): from the
 * same saved version every time, each variant of learning speed, curiosity and how close it stays
 * to your style gets a short practice, then the automatic exam scores it. The best one is kept
 * (its network and its settings); the others are thrown away. Judged only by exams, never by guess.
 */
public final class EvolveSession {
	static final long PRACTICE_MS = 6 * 60_000L;
	static final long EXAM_WAIT_MS = 6 * 60_000L;

	/** Multipliers on (learning speed, curiosity, stay-human). */
	record Variant(double lr, double entropy, double stay) {
	}

	private static @Nullable EvolveSession current;

	private final ProfileManifest profile;
	private final String skill;
	private final Path profileDir;
	private final Path core;
	private final String baseId;
	private final SkillDef.Advanced base;
	private final List<Variant> variants;
	private final List<double[]> results = new ArrayList<>(); // {score, variant index}
	private final List<String> snapshots = new ArrayList<>();
	private int index = -1;
	private long phaseStart;
	private int examsBefore;
	private boolean examining;
	private String status = "";
	private String outcome = "";

	private EvolveSession(ProfileManifest profile, String skill, Path profileDir, String baseId, SkillDef.Advanced base) {
		this.profile = profile;
		this.skill = skill;
		this.profileDir = profileDir;
		this.core = io.github.mudklip.minigamebots.client.profile.Skills.dir(profileDir, skill).resolve("core");
		this.baseId = baseId;
		this.base = base;
		this.variants = variants(new Random());
	}

	/** The current settings plus 3 random neighbours (one or two settings halved or doubled). */
	static List<Variant> variants(Random rng) {
		List<Variant> out = new ArrayList<>();
		out.add(new Variant(1, 1, 1));
		while (out.size() < 4) {
			double[] m = {1, 1, 1};
			int changes = 1 + rng.nextInt(2);
			for (int c = 0; c < changes; c++) {
				m[rng.nextInt(3)] = rng.nextBoolean() ? 0.5 : 2.0;
			}
			Variant v = new Variant(m[0], m[1], m[2]);
			if (!out.contains(v)) {
				out.add(v);
			}
		}
		return out;
	}

	/** Which variant won: the best exam score (ties: the current settings). */
	static int best(List<double[]> results) {
		int bestI = -1;
		double best = Double.NEGATIVE_INFINITY;
		for (double[] r : results) {
			if (r[0] > best + 1e-9 || (Math.abs(r[0] - best) < 1e-9 && (int) r[1] == 0)) {
				best = r[0];
				bestI = (int) r[1];
			}
		}
		return bestI;
	}

	public static boolean running() {
		return current != null;
	}

	public static String status() {
		EvolveSession s = current;
		return s == null ? "" : s.status;
	}

	public static String lastOutcome() {
		return lastOutcome;
	}

	private static String lastOutcome = "";

	/** Starts evolving the AI's main fighting skill; false (with a reason in lastOutcome) if it can't. */
	public static boolean start(ProfileManifest p) {
		var mgb = MinigameBotsClient.instance();
		Path dir = mgb.profiles().store().dir(p.id);
		String skill = SkillStore.list(dir).stream().map(SkillStore.Entry::id).filter(id -> id.contains("pvp") || id.contains("fight"))
				.findFirst().orElse(null);
		if (skill == null) {
			lastOutcome = "Evolving: no fighting skill to evolve yet.";
			return false;
		}
		Path core = io.github.mudklip.minigamebots.client.profile.Skills.dir(dir, skill).resolve("core");
		try {
			var snap = VersionHistory.snapshot(core, "Before evolving its settings", "");
			if (snap == null) {
				lastOutcome = "Evolving: train this skill first.";
				return false;
			}
			SkillDef def = SkillStore.read(dir, skill);
			if (def == null) {
				SkillDef preset = SkillLibrary.get().preset(skill);
				def = preset == null ? SkillDef.blank() : preset.copy();
			}
			SkillDef.Advanced adv = def.advanced == null ? new SkillDef.Advanced() : def.advanced;
			current = new EvolveSession(p, skill, dir, snap.id, adv);
			return true;
		} catch (Exception e) {
			lastOutcome = "Evolving: couldn't save a starting point (" + e.getMessage() + ")";
			return false;
		}
	}

	public static void stop() {
		EvolveSession s = current;
		if (s != null) {
			MinigameBotsClient.instance().brain().release();
			s.finish();
		}
	}

	public static void tick(Minecraft mc) {
		EvolveSession s = current;
		if (s != null) {
			s.step(mc);
		}
	}

	private void step(Minecraft mc) {
		var mgb = MinigameBotsClient.instance();
		long now = System.currentTimeMillis();
		if (index < 0 || (examining && (ExamManager.load(profileDir).size() > examsBefore || now - phaseStart > EXAM_WAIT_MS))) {
			if (index >= 0) {
				var all = ExamManager.load(profileDir);
				double score = all.size() > examsBefore ? Watchdog.score(all.get(all.size() - 1)) : Double.NEGATIVE_INFINITY;
				results.add(new double[] {score, index});
				try {
					var snap = VersionHistory.snapshot(core, "Evolve variant " + (index + 1), String.valueOf(Math.round(score)));
					snapshots.add(snap == null ? "" : snap.id);
				} catch (Exception e) {
					snapshots.add("");
				}
			}
			index++;
			if (index >= variants.size()) {
				finish();
				return;
			}
			// The next variant, from the same starting point.
			try {
				VersionHistory.revert(core, baseId);
				apply(variants.get(index));
			} catch (Exception e) {
				outcome = "Evolving stopped: " + e.getMessage();
				finish();
				return;
			}
			var o = io.github.mudklip.minigamebots.client.practice.ObjectiveStore.templates().get("duel");
			if (o != null) {
				mgb.brain().practice(mc, profile, o, true, 0.05, "balanced", skill);
			}
			examining = false;
			phaseStart = now;
			status = "Evolving: variant " + (index + 1) + " of " + variants.size() + " practising";
			return;
		}
		if (!examining && now - phaseStart > PRACTICE_MS) {
			// Stopping practice runs the automatic exam (AutoExam) for this version.
			examsBefore = ExamManager.load(profileDir).size();
			mgb.brain().release();
			examining = true;
			phaseStart = now;
			status = "Evolving: variant " + (index + 1) + " of " + variants.size() + " taking its exam";
		}
	}

	private void apply(Variant v) throws java.io.IOException {
		SkillDef def = SkillStore.read(profileDir, skill);
		if (def == null) {
			SkillDef preset = SkillLibrary.get().preset(skill);
			def = preset == null ? SkillDef.blank() : preset.copy();
		}
		def.advanced.lr = (base.lr == null ? 1.0 : base.lr) * v.lr();
		def.advanced.entropy = (base.entropy == null ? 1.0 : base.entropy) * v.entropy();
		def.advanced.stay_human = (base.stay_human == null ? 0.05 : base.stay_human) * v.stay();
		SkillStore.save(profileDir, skill, def);
	}

	private void finish() {
		current = null;
		int win = best(results);
		try {
			if (win < 0) {
				VersionHistory.revert(core, baseId);
				apply(variants.get(0));
				lastOutcome = outcome.isEmpty() ? "Evolving: no exam results, kept everything as it was." : outcome;
				return;
			}
			VersionHistory.revert(core, snapshots.get(results.indexOf(results.stream().filter(r -> (int) r[1] == win).findFirst().get())));
			apply(variants.get(win));
			Variant v = variants.get(win);
			double baseScore = results.stream().filter(r -> (int) r[1] == 0).mapToDouble(r -> r[0]).findFirst().orElse(Double.NaN);
			double winScore = results.stream().filter(r -> (int) r[1] == win).mapToDouble(r -> r[0]).findFirst().orElse(Double.NaN);
			lastOutcome = win == 0 ? String.format("Evolving: its current settings did best (exam %.0f); kept them.", baseScore)
					: String.format("Evolving: kept new settings (learning x%.1f, curiosity x%.1f, stay-human x%.1f): exam %.0f vs %.0f before.",
							v.lr(), v.entropy(), v.stay(), winScore, baseScore);
		} catch (Exception e) {
			lastOutcome = "Evolving: couldn't restore the best version (" + e.getMessage() + ")";
		}
	}
}
