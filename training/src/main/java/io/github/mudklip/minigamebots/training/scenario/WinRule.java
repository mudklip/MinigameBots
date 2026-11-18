package io.github.mudklip.minigamebots.training.scenario;

/**
 * How an episode is won. Pure logic, tested: given who is still in, kills and time, decides
 * whether the episode is over and who won.
 */
public enum WinRule {
	LAST_TEAM_STANDING("Last team standing", "Knock everyone else out"),
	FIRST_TO_KILLS("First to N kills", "The first team to reach the kill target wins"),
	MOST_KILLS("Most kills in time", "When time runs out, the team with most kills wins"),
	SURVIVE("Survive", "The first team must stay alive until time runs out");

	public final String label;
	public final String help;

	WinRule(String label, String help) {
		this.label = label;
		this.help = help;
	}

	/** {@code winner} is a team index, or -1 for a draw. */
	public record Outcome(boolean over, int winner, String reason) {
		static final Outcome RUNNING = new Outcome(false, -1, "");
	}

	/**
	 * @param alive     members still in, per team
	 * @param started   members at the start, per team (teams with none don't take part)
	 * @param kills     knockouts scored, per team
	 * @param elapsed   seconds since the start
	 * @param timeLimit seconds, 0 = none
	 */
	public Outcome decide(int[] alive, int[] started, int[] kills, int elapsed, int timeLimit, int killTarget) {
		int teams = alive.length;
		int playing = 0;
		int stillIn = 0;
		int lastIn = -1;
		for (int t = 0; t < teams; t++) {
			if (started[t] > 0) {
				playing++;
				if (alive[t] > 0) {
					stillIn++;
					lastIn = t;
				}
			}
		}
		boolean timeUp = timeLimit > 0 && elapsed >= timeLimit;
		if (this == SURVIVE) {
			if (started[0] > 0 && alive[0] == 0) {
				return new Outcome(true, bestOther(alive, started, kills), "the defenders were knocked out");
			}
			if (timeUp) {
				return new Outcome(true, 0, "survived until the end");
			}
			if (playing > 1 && stillIn == 1 && lastIn == 0) {
				return new Outcome(true, 0, "every attacker was knocked out");
			}
			return Outcome.RUNNING;
		}
		if (playing > 1 && stillIn <= 1) {
			return new Outcome(true, lastIn, lastIn < 0 ? "everyone was knocked out" : "last team standing");
		}
		if (this == FIRST_TO_KILLS) {
			for (int t = 0; t < teams; t++) {
				if (kills[t] >= killTarget) {
					return new Outcome(true, t, "reached " + killTarget + " kills");
				}
			}
		}
		if (timeUp) {
			int best = mostKills(kills, started);
			if (this == LAST_TEAM_STANDING) {
				best = mostAlive(alive, started, kills);
			}
			return new Outcome(true, best, best < 0 ? "time ran out, tied" : "time ran out");
		}
		return Outcome.RUNNING;
	}

	private static int mostKills(int[] kills, int[] started) {
		int best = -1;
		boolean tie = false;
		for (int t = 0; t < kills.length; t++) {
			if (started[t] == 0) {
				continue;
			}
			if (best < 0 || kills[t] > kills[best]) {
				best = t;
				tie = false;
			} else if (kills[t] == kills[best]) {
				tie = true;
			}
		}
		return tie ? -1 : best;
	}

	/** Most members left; ties broken by kills; still tied = draw. */
	private static int mostAlive(int[] alive, int[] started, int[] kills) {
		int best = -1;
		boolean tie = false;
		for (int t = 0; t < alive.length; t++) {
			if (started[t] == 0) {
				continue;
			}
			if (best < 0 || alive[t] > alive[best] || alive[t] == alive[best] && kills[t] > kills[best]) {
				best = t;
				tie = false;
			} else if (alive[t] == alive[best] && kills[t] == kills[best]) {
				tie = true;
			}
		}
		return tie ? -1 : best;
	}

	private static int bestOther(int[] alive, int[] started, int[] kills) {
		int best = -1;
		for (int t = 1; t < alive.length; t++) {
			if (started[t] > 0 && (best < 0 || kills[t] > kills[best])) {
				best = t;
			}
		}
		return best;
	}
}
