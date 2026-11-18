package io.github.mudklip.minigamebots.client.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mudklip.minigamebots.client.debug.Script;
import org.junit.jupiter.api.Test;

class ScriptTest {
	@Test
	void expandsSteps() {
		Script s = Script.parse("hold forward+sprint 3; tap jump; look 90 -10; click 2 2; wait 1; tap hotbar3");
		assertEquals(3 + 2 + 1 + 2 + 1 + 2, s.ticks().size());
		assertTrue(s.ticks().get(0).holds(VirtualKey.FORWARD));
		assertTrue(s.ticks().get(0).holds(VirtualKey.SPRINT));
		assertTrue(s.ticks().get(3).holds(VirtualKey.JUMP));
		assertEquals(90f, s.ticks().get(5).yaw());
		assertEquals(-10f, s.ticks().get(5).pitch());
		assertEquals(2, s.ticks().get(6).extraAttacks());
		assertTrue(s.ticks().get(9).holds(VirtualKey.HOTBAR_3));
	}

	@Test
	void rejectsBadInput() {
		assertThrows(IllegalArgumentException.class, () -> Script.parse("fly 10"));
		assertThrows(IllegalArgumentException.class, () -> Script.parse("hold forward"));
		assertThrows(IllegalArgumentException.class, () -> Script.parse("hold nope 5"));
		assertThrows(IllegalArgumentException.class, () -> Script.parse("wait -3"));
		assertThrows(IllegalArgumentException.class, () -> Script.parse(" ; "));
	}
}
