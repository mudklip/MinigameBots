package io.github.mudklip.minigamebots.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mudklip.minigamebots.training.aim.AimScore;
import io.github.mudklip.minigamebots.training.aim.AimTrainer;
import io.github.mudklip.minigamebots.training.arena.Arena;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import io.github.mudklip.minigamebots.training.dummy.HitStats;
import org.junit.jupiter.api.Test;

class TrainingLogicTest {
	@Test
	void comboCountsQuickHitsAndBreaksOnGaps() {
		HitStats s = new HitStats();
		s.hit(0, 2);
		s.hit(10, 2);
		s.hit(20, 2);
		assertEquals(3, s.combo(20));
		s.hit(20 + HitStats.COMBO_GAP_TICKS + 1, 2);
		assertEquals(1, s.combo(51));
		assertEquals(0, s.combo(51 + HitStats.COMBO_GAP_TICKS + 1));
		assertEquals(4, s.count());
		assertEquals(8f, s.total());
	}

	@Test
	void dpsCoversTheLastFiveSeconds() {
		HitStats s = new HitStats();
		for (int t = 0; t < 40; t += 10) {
			s.hit(t, 5); // 20 damage over 31 ticks.
		}
		assertEquals(20 / (31 / 20.0), s.dps(30), 1e-6);
		assertEquals(0, s.dps(30 + HitStats.WINDOW_TICKS + 1), 1e-9);
		HitStats one = new HitStats();
		one.hit(100, 4);
		assertEquals(4, one.dps(100), 1e-9); // A single hit counts over at least one second.
	}

	@Test
	void aimScore() {
		AimScore a = new AimScore();
		for (int i = 0; i < 4; i++) {
			a.click();
		}
		a.hit(10);
		a.hit(6);
		assertEquals(0.5, a.accuracy(), 1e-9);
		assertEquals(400, a.avgMillis(), 1e-9);
		a.trackTicks = 100;
		a.onTargetTicks = 25;
		assertEquals(0.25, a.tracking(), 1e-9);
		assertTrue(a.score(AimTrainer.Mode.TRACK) > 250);
		AimScore none = new AimScore();
		assertEquals(0, none.accuracy());
		assertEquals(0, none.avgMillis());
	}

	@Test
	void presetsGetHarder() {
		DummySettings l1 = DummySettings.preset(1);
		DummySettings l5 = DummySettings.preset(5);
		assertFalse(l1.fightsBack);
		assertTrue(l1.infiniteHealth);
		assertTrue(l5.fightsBack);
		assertTrue(l5.cps > DummySettings.preset(3).cps);
		assertTrue(l5.accuracy > DummySettings.preset(3).accuracy);
		assertEquals(5, DummySettings.preset(9).level);
		DummySettings copy = l5.copy();
		copy.cps = 1;
		assertEquals(6, l5.cps);
	}

	@Test
	void arenaNamesAreSafeFileNames() {
		assertTrue(Arena.validName("duel_1"));
		assertTrue(Arena.validName("arena-2"));
		assertFalse(Arena.validName("../x"));
		assertFalse(Arena.validName(""));
		assertFalse(Arena.validName("a b"));
		assertFalse(Arena.validName("x".repeat(33)));
		Arena a = new Arena();
		a.sizeX = 10;
		a.sizeY = 5;
		a.sizeZ = 4;
		assertEquals(200, a.volume());
	}
}
