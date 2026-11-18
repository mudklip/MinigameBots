package io.github.mudklip.minigamebots.client.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mudklip.minigamebots.client.observe.RelationTracker;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelationTrackerTest {
	@Test
	void remembersWhoHitWhomAndForgets() {
		RelationTracker r = new RelationTracker();
		List<Integer> hits = new ArrayList<>();
		r.onHitMe(hits::add);
		r.setProtected(50);
		r.setTick(100);
		r.onDamage(1, 7, 9, 1);      // 7 hit me (with projectile 9)
		r.onDamage(8, 1, 1, 1);      // I hit 8
		r.onDamage(50, -1, 12, 1);   // 12 hit the person I protect, no "cause"
		assertEquals(List.of(7), hits);
		r.setTick(110);
		assertEquals(10, r.ticksSinceHitMe(7));
		assertEquals(10, r.ticksSinceHitByMe(8));
		assertEquals(10, r.ticksSinceHitProtected(12));
		assertEquals(RelationTracker.NEVER, r.ticksSinceHitMe(8));
		r.setTick(100 + RelationTracker.FORGET_TICKS + 1);
		assertEquals(RelationTracker.NEVER, r.ticksSinceHitMe(7));
	}

	@Test
	void attackersOutrankNearbyChickens() {
		double chicken = RelationTracker.importance(3, false, -1, -1, -1, false, false, false);
		double armedPlayer = RelationTracker.importance(25, false, -1, -1, -1, true, false, false);
		double attacker = RelationTracker.importance(40, false, 5, -1, -1, true, false, false);
		double vip = RelationTracker.importance(45, true, -1, -1, -1, true, false, false);
		assertTrue(armedPlayer < chicken);
		assertTrue(attacker < armedPlayer);
		assertTrue(vip < attacker);
		double pig = RelationTracker.importance(12, false, -1, -1, -1, false, false, false);
		double droppedPorkchop = RelationTracker.importance(1, false, -1, -1, -1, false, false, true);
		assertTrue(pig < droppedPorkchop, "creatures before loot lying around");
	}

	@Test
	void compassSectorsAndBands() {
		assertEquals(0, RelationTracker.sector(0, 5));
		assertEquals(2, RelationTracker.sector(-5, 0));
		assertEquals(4, RelationTracker.sector(0, -5));
		assertEquals(6, RelationTracker.sector(5, 0));
		assertEquals(1, RelationTracker.sector(-3, 3));
		assertEquals(0, RelationTracker.band(5));
		assertEquals(1, RelationTracker.band(20));
		assertEquals(2, RelationTracker.band(60));
	}
}
