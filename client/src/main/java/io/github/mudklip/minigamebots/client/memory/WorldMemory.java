package io.github.mudklip.minigamebots.client.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything one AI remembers about one world (saved as memory.json; maps as separate files). */
public final class WorldMemory {
	/** Which signal decides when they disagree. */
	public enum Trust {
		NAME_TEAM_INSIGNIA("Name > team > insignia"),
		NAME_INSIGNIA_TEAM("Name > insignia > team"),
		TEAM_NAME_INSIGNIA("Team > name > insignia"),
		INSIGNIA_NAME_TEAM("Insignia > name > team");

		public final String label;

		Trust(String label) {
			this.label = label;
		}
	}

	public static final int MAX_WAYPOINTS = 64;

	public String world = "";
	public List<Waypoint> waypoints = new ArrayList<>();
	/** Player name -> relation. */
	public Map<String, Relation> players = new LinkedHashMap<>();
	/** Insignia id -> relation. */
	public Map<String, Relation> insignias = new LinkedHashMap<>();
	/** Insignia id -> what it looks like, for the Memory screen. */
	public Map<String, String> insigniaLooks = new LinkedHashMap<>();
	/** Mark the insignia (and name) of whoever attacks the AI or the person it protects as enemies. */
	public boolean autoMarkAttackers = true;
	/** Players on a different scoreboard team count as enemies. */
	public boolean otherTeamsAreEnemies = true;
	public Trust trust = Trust.NAME_TEAM_INSIGNIA;

	/**
	 * Decides how to treat someone.
	 *
	 * @param team    1 same scoreboard team, -1 a different team, 0 no teams involved
	 * @param mine    the AI's own insignia (same insignia = same side)
	 */
	public Relation relation(String name, int team, String insignia, String mine) {
		Relation byName = name == null ? null : players.get(name);
		Relation byTeam = team > 0 ? Relation.FRIEND : team < 0 && otherTeamsAreEnemies ? Relation.ENEMY : null;
		Relation byInsignia = null;
		if (insignia != null && !insignia.isEmpty()) {
			byInsignia = insignias.get(insignia);
			if (byInsignia == null && insignia.equals(mine)) {
				byInsignia = Relation.FRIEND;
			}
		}
		Relation[] order = switch (trust) {
			case NAME_TEAM_INSIGNIA -> new Relation[] {byName, byTeam, byInsignia};
			case NAME_INSIGNIA_TEAM -> new Relation[] {byName, byInsignia, byTeam};
			case TEAM_NAME_INSIGNIA -> new Relation[] {byTeam, byName, byInsignia};
			case INSIGNIA_NAME_TEAM -> new Relation[] {byInsignia, byName, byTeam};
		};
		for (Relation r : order) {
			if (r != null) {
				return r;
			}
		}
		return Relation.NEUTRAL;
	}
}
