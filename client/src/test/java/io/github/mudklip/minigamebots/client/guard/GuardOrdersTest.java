package io.github.mudklip.minigamebots.client.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuardOrdersTest {
	@Test
	void parsesAFormationOrder() {
		var o = GuardOrders.parse("guard Steve 3 wedge with Bob, Ann Cid", "Ann");
		assertNotNull(o);
		assertEquals("guard", o.kind());
		assertEquals("Steve", o.target());
		assertEquals(3, o.dist());
		assertEquals("wedge", o.shape());
		assertEquals(List.of("Bob", "Ann", "Cid"), o.names());
	}

	@Test
	void ignoresOrdersForOtherAisAndPlainChat() {
		assertNull(GuardOrders.parse("guard Steve 3 ring with Bob Cid", "Ann"));
		assertNull(GuardOrders.parse("hello everyone", "Ann"));
		assertNull(GuardOrders.parse("guard", "Ann"));
		var stopBob = GuardOrders.parse("stop Bob", "Ann");
		assertNull(stopBob);
		assertNotNull(GuardOrders.parse("stop", "Ann"));
	}

	@Test
	void defaultsAndLimits() {
		var o = GuardOrders.parse("guard Steve 50", "Ann");
		assertEquals(12, o.dist());
		assertEquals("ring", o.shape());
		assertEquals(List.of("Ann"), o.names());
		assertEquals("line", GuardOrders.parse("follow Steve", "Ann").shape());
	}

	@Test
	void onlyListedPlayersAndAllowedProtectees() {
		var cmd = List.of("Steve", "Alex");
		assertTrue(GuardOrders.allowed("Steve", "Steve", cmd, "Ann"));
		assertTrue(GuardOrders.allowed("Steve", "Alex", cmd, "Ann"));
		assertFalse(GuardOrders.allowed("Griefer", "Griefer", cmd, "Ann"));
		assertFalse(GuardOrders.allowed("Steve", "Griefer", cmd, "Ann")); // Can't make it guard someone else.
		assertFalse(GuardOrders.allowed("Ann", "Ann", List.of("Ann"), "Ann")); // Never its own messages.
		assertFalse(GuardOrders.allowed("Steve", "Steve", List.of(), "Ann")); // Empty list: nobody.
	}
}
