package io.github.mudklip.minigamebots.client.skills;

import io.github.mudklip.minigamebots.client.practice.Objective;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One skill: what it's rewarded for, hints per stage, locked actions (see brain rl/skills.py). */
public final class SkillDef {
	public static final class Advance {
		public int episodes;
		public @Nullable Double avg;
	}

	public static final class Stage {
		public String name = "Stage";
		public String speed = "normal";
		public List<Objective.Rule> shaping = new ArrayList<>();
		public Advance advance = new Advance();
		public boolean discover;
		public String help = "";
	}

	public static final class Advanced {
		/** Multipliers of the base values; null = from the stage's learning speed. */
		public @Nullable Double lr;
		public @Nullable Double entropy;
		public @Nullable Double stay_human;
		/** Train its own network instead of sharing the Manager's one for this area. */
		public boolean separate;
	}

	public String name = "New skill";
	public String icon = "minecraft:book";
	public String category = "Custom";
	public String help = "";
	public String setup = "none";
	public String manager = "general";
	public String teacher = "";
	public String target = "";
	public List<String> locks = new ArrayList<>();
	public List<Objective.Rule> rules = new ArrayList<>();
	public List<Stage> stages = new ArrayList<>();
	/** Fixed stage, or null to move on by itself. */
	public @Nullable Integer stage;
	public Advanced advanced = new Advanced();

	public SkillDef copy() {
		return SkillLibrary.GSON.fromJson(SkillLibrary.GSON.toJson(this), SkillDef.class);
	}

	/** A blank skill with the usual three stages. */
	public static SkillDef blank() {
		SkillDef d = new SkillDef();
		String[][] st = {{"Basics", "copy", "15"}, {"Practice", "normal", "60"}, {"Mastery", "slow", "0"}};
		for (String[] s : st) {
			Stage x = new Stage();
			x.name = s[0];
			x.speed = s[1];
			x.advance.episodes = Integer.parseInt(s[2]);
			x.discover = s[0].equals("Mastery");
			d.stages.add(x);
		}
		return d;
	}
}
