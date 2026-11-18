package io.github.mudklip.minigamebots.training.scenario;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A repeatable training match: an arena, teams (colour, spawns, kit, training dummies), a win
 * rule and timing. Stored as scenarios/<name>.json in the world.
 */
public final class Scenario {
	public static final class Team {
		/** Also the scoreboard colour: red, blue, green, yellow, ... */
		public String color = "red";
		public List<double[]> spawns = new ArrayList<>();
		public JsonArray kit = new JsonArray();
		/** Training dummies added to this team each episode. */
		public int dummies;
		public int dummyLevel = 3;
		/** Opponent style of its dummies (see DummySettings.STYLES), or "league" to let matchmaking pick. */
		public String dummyStyle = "";

		/** The style its dummies use: dummyStyle, or the level for scenarios saved before styles. */
		public String style() {
			return dummyStyle == null || dummyStyle.isEmpty() ? "L" + dummyLevel : dummyStyle;
		}

		/** Cycles through the styles, then "league". */
		public void nextStyle() {
			java.util.List<String> all = new java.util.ArrayList<>(io.github.mudklip.minigamebots.training.dummy.DummySettings.STYLES);
			all.add(League.LEAGUE);
			dummyStyle = all.get((all.indexOf(style()) + 1) % all.size());
		}

		public String label() {
			return Character.toUpperCase(color.charAt(0)) + color.substring(1).replace('_', ' ');
		}
	}

	public String name = "";
	public String arena = "";
	public List<Team> teams = new ArrayList<>();
	public WinRule rule = WinRule.LAST_TEAM_STANDING;
	public int killTarget = 5;
	/** Seconds; 0 = no limit. */
	public int timeLimit = 120;
	/** Start the next episode automatically. */
	public boolean loop = true;
	/** Slow the game down to the speed of the slowest AI playing, so it can decide every tick. */
	public boolean matchAiSpeed;
	public int episodes;
	public Map<String, Integer> wins = new LinkedHashMap<>();
	public int draws;

	public static Scenario redVsBlue(String name, String arena) {
		Scenario s = new Scenario();
		s.name = name;
		s.arena = arena;
		Team red = new Team();
		red.color = "red";
		Team blue = new Team();
		blue.color = "blue";
		blue.dummies = 1;
		s.teams.add(red);
		s.teams.add(blue);
		return s;
	}
}
