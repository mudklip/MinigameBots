package io.github.mudklip.minigamebots.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mudklip.minigamebots.training.scenario.WinRule;
import org.junit.jupiter.api.Test;

class WinRuleTest {
	private static final int[] TWO_EACH = {2, 2};

	@Test
	void lastTeamStanding() {
		var r = WinRule.LAST_TEAM_STANDING;
		assertFalse(r.decide(new int[] {2, 1}, TWO_EACH, new int[] {1, 0}, 10, 120, 5).over());
		var o = r.decide(new int[] {1, 0}, TWO_EACH, new int[] {2, 1}, 30, 120, 5);
		assertTrue(o.over());
		assertEquals(0, o.winner());
		// Time up: more members left wins; equal members, more kills wins; else draw.
		assertEquals(1, r.decide(new int[] {1, 2}, TWO_EACH, new int[] {0, 0}, 120, 120, 5).winner());
		assertEquals(0, r.decide(new int[] {1, 1}, TWO_EACH, new int[] {1, 0}, 120, 120, 5).winner());
		assertEquals(-1, r.decide(new int[] {1, 1}, TWO_EACH, new int[] {1, 1}, 120, 120, 5).winner());
		// No time limit: keeps going.
		assertFalse(r.decide(new int[] {1, 1}, TWO_EACH, new int[] {0, 0}, 9999, 0, 5).over());
	}

	@Test
	void teamsWithoutMembersDontTakePart() {
		// Three teams set up, only two have anyone in them.
		var o = WinRule.LAST_TEAM_STANDING.decide(new int[] {1, 0, 0}, new int[] {1, 1, 0}, new int[] {1, 0, 0}, 5, 0, 5);
		assertTrue(o.over());
		assertEquals(0, o.winner());
		// A team alone from the start is not a win.
		assertFalse(WinRule.LAST_TEAM_STANDING.decide(new int[] {1, 0}, new int[] {1, 0}, new int[] {0, 0}, 5, 0, 5).over());
	}

	@Test
	void firstToKills() {
		var r = WinRule.FIRST_TO_KILLS;
		assertFalse(r.decide(TWO_EACH, TWO_EACH, new int[] {2, 1}, 10, 0, 3).over());
		var o = r.decide(TWO_EACH, TWO_EACH, new int[] {2, 3}, 10, 0, 3);
		assertTrue(o.over());
		assertEquals(1, o.winner());
	}

	@Test
	void mostKillsWhenTimeRunsOut() {
		var r = WinRule.MOST_KILLS;
		assertFalse(r.decide(TWO_EACH, TWO_EACH, new int[] {5, 1}, 59, 60, 3).over());
		assertEquals(0, r.decide(TWO_EACH, TWO_EACH, new int[] {5, 1}, 60, 60, 3).winner());
		assertEquals(-1, r.decide(TWO_EACH, TWO_EACH, new int[] {2, 2}, 60, 60, 3).winner());
	}

	@Test
	void survive() {
		var r = WinRule.SURVIVE;
		assertFalse(r.decide(new int[] {1, 3}, new int[] {1, 3}, new int[] {0, 0}, 30, 60, 3).over());
		var lost = r.decide(new int[] {0, 3}, new int[] {1, 3}, new int[] {0, 1}, 30, 60, 3);
		assertTrue(lost.over());
		assertEquals(1, lost.winner());
		assertEquals(0, r.decide(new int[] {1, 3}, new int[] {1, 3}, new int[] {0, 0}, 60, 60, 3).winner());
		assertEquals(0, r.decide(new int[] {1, 0}, new int[] {1, 3}, new int[] {3, 0}, 30, 60, 3).winner());
	}
}
