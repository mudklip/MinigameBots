package io.github.mudklip.minigamebots.client.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EvolveSessionTest {
	@Test
	void variantsStartWithTheCurrentSettingsAndAreDistinct() {
		for (int seed = 0; seed < 20; seed++) {
			var v = EvolveSession.variants(new Random(seed));
			assertEquals(4, v.size());
			assertEquals(new EvolveSession.Variant(1, 1, 1), v.get(0));
			assertEquals(4, new HashSet<>(v).size());
		}
	}

	@Test
	void bestExamWinsAndTiesKeepTheCurrentSettings() {
		assertEquals(2, EvolveSession.best(List.of(new double[] {40, 0}, new double[] {35, 1}, new double[] {52, 2})));
		assertEquals(0, EvolveSession.best(List.of(new double[] {50, 1}, new double[] {50, 0})));
		assertEquals(-1, EvolveSession.best(List.of()));
		assertTrue(EvolveSession.best(List.of(new double[] {Double.NEGATIVE_INFINITY, 1}, new double[] {10, 3})) == 3);
	}
}
