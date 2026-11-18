package io.github.mudklip.minigamebots.client.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class HumanizerTest {
	private static final HumanizerSettings NO_DELAY_NO_NOISE =
			new HumanizerSettings(true, 0, 0, 1000f, 15000f, 0f, 12);

	@Test
	void reactionDelayPostponesAction() {
		Humanizer h = new Humanizer(new HumanizerSettings(true, 200, 0, 1000f, 15000f, 0f, 12), new Random(1));
		h.submit(AgentAction.builder().hold(VirtualKey.FORWARD).build(), 0);
		for (long t = 0; t < 4; t++) {
			assertFalse(h.tick(t).holds(VirtualKey.FORWARD), "too early at tick " + t);
		}
		assertTrue(h.tick(4).holds(VirtualKey.FORWARD));
	}

	@Test
	void heldKeysPersistUntilNextDecision() {
		Humanizer h = new Humanizer(NO_DELAY_NO_NOISE, new Random(1));
		h.submit(AgentAction.builder().hold(VirtualKey.JUMP).build(), 0);
		assertTrue(h.tick(0).holds(VirtualKey.JUMP));
		assertTrue(h.tick(1).holds(VirtualKey.JUMP));
		h.submit(AgentAction.NOOP, 2);
		assertFalse(h.tick(2).holds(VirtualKey.JUMP));
	}

	@Test
	void turnIsSpreadOverTimeButCompletes() {
		Humanizer h = new Humanizer(NO_DELAY_NO_NOISE, new Random(1));
		h.submit(AgentAction.builder().camera(180, 0).build(), 0);
		float total = 0;
		float maxPerTick = 0;
		for (long t = 0; t < 60; t++) {
			float yaw = h.tick(t).yaw();
			total += yaw;
			maxPerTick = Math.max(maxPerTick, Math.abs(yaw));
		}
		assertEquals(180f, total, 0.01f);
		// 1000 deg/s is at most 50 degrees per tick.
		assertTrue(maxPerTick <= 50.001f, "too fast: " + maxPerTick);
		// A 180 degree flick must take more than one tick.
		assertTrue(maxPerTick < 180f);
	}

	@Test
	void turnAccelerationIsLimited() {
		Humanizer h = new Humanizer(NO_DELAY_NO_NOISE, new Random(1));
		h.submit(AgentAction.builder().camera(90, 0).build(), 0);
		float first = h.tick(0).yaw();
		// 15000 deg/s^2 allows at most 750 deg/s after one tick, i.e. 37.5 degrees.
		assertTrue(first <= 37.501f, "first tick too fast: " + first);
	}

	@Test
	void cpsIsCapped() {
		Humanizer h = new Humanizer(NO_DELAY_NO_NOISE, new Random(1));
		int clicks = 0;
		for (long t = 0; t < 20; t++) {
			h.submit(AgentAction.builder().extraAttacks(3).build(), t);
			clicks += h.tick(t).attackClicks();
		}
		assertEquals(12, clicks);
	}

	@Test
	void pressCountsAsOneClick() {
		Humanizer h = new Humanizer(NO_DELAY_NO_NOISE, new Random(1));
		h.submit(AgentAction.builder().hold(VirtualKey.ATTACK).build(), 0);
		assertEquals(1, h.tick(0).attackClicks());
		assertEquals(0, h.tick(1).attackClicks(), "holding is not clicking again");
	}

	@Test
	void aimNoiseStaysSmall() {
		Humanizer h = new Humanizer(new HumanizerSettings(true, 0, 0, 1000f, 15000f, 0.12f, 12), new Random(7));
		float yaw = 0;
		float worst = 0;
		for (long t = 0; t < 2000; t++) {
			yaw += h.tick(t).yaw();
			worst = Math.max(worst, Math.abs(yaw));
		}
		assertTrue(worst < 1.0f, "aim drifted " + worst + " degrees");
	}

	@Test
	void disabledPassesThrough() {
		Humanizer h = new Humanizer(HumanizerSettings.OFF, new Random(1));
		h.submit(AgentAction.builder().hold(VirtualKey.ATTACK).extraAttacks(5).camera(170, -20).build(), 0);
		GameInput in = h.tick(0);
		assertEquals(170f, in.yaw());
		assertEquals(-20f, in.pitch());
		assertEquals(6, in.attackClicks());
	}

	@Test
	void quickTapsAreNeverLost() {
		for (int seed = 0; seed < 50; seed++) {
			Humanizer h = new Humanizer(new HumanizerSettings(true, 180, 80, 1000f, 15000f, 0f, 12), new Random(seed));
			int taps = 0;
			int pressedTicks = 0;
			boolean wasHeld = false;
			for (long t = 0; t < 440; t++) {
				// Tap inventory every 10 ticks: pressed for one tick, then released.
				boolean press = t < 400 && t % 10 == 0;
				h.submit(press ? AgentAction.builder().hold(VirtualKey.INVENTORY).build() : AgentAction.NOOP, t);
				if (press) {
					taps++;
				}
				boolean held = h.tick(t).holds(VirtualKey.INVENTORY);
				if (held && !wasHeld) {
					pressedTicks++;
				}
				wasHeld = held;
			}
			assertEquals(taps, pressedTicks, "seed " + seed);
		}
	}

	@Test
	void attackTapsBecomeClicksWhenMerged() {
		AgentAction tap = AgentAction.builder().hold(VirtualKey.ATTACK).build();
		AgentAction merged = tap.mergeLater(AgentAction.NOOP);
		assertEquals(1, merged.extraAttacks());
		assertFalse(merged.holds(VirtualKey.ATTACK));
	}

	@Test
	void ordersAreKeptWithRandomDelays() {
		Humanizer h = new Humanizer(new HumanizerSettings(true, 150, 80, 1000f, 15000f, 0f, 12), new Random(3));
		h.submit(AgentAction.builder().hold(VirtualKey.LEFT).build(), 0);
		h.submit(AgentAction.builder().hold(VirtualKey.RIGHT).build(), 1);
		boolean sawRight = false;
		for (long t = 0; t < 40; t++) {
			GameInput in = h.tick(t);
			if (in.holds(VirtualKey.RIGHT)) {
				sawRight = true;
			}
			if (sawRight) {
				assertFalse(in.holds(VirtualKey.LEFT), "older decision overtook newer one");
			}
		}
		assertTrue(sawRight);
	}
}
