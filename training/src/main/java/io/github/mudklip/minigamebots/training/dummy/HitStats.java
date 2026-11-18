package io.github.mudklip.minigamebots.training.dummy;

import java.util.ArrayDeque;
import java.util.Deque;

/** Damage per second (last 5 s) and combo (hits with under 1.5 s between them). Pure logic, tested. */
public final class HitStats {
	public static final int WINDOW_TICKS = 100;
	public static final int COMBO_GAP_TICKS = 30;

	private record Hit(long tick, float amount) {
	}

	private final Deque<Hit> hits = new ArrayDeque<>();
	private long lastHit = Long.MIN_VALUE;
	private int combo;
	private float total;
	private int count;

	public void hit(long tick, float amount) {
		combo = tick - lastHit <= COMBO_GAP_TICKS ? combo + 1 : 1;
		lastHit = tick;
		hits.addLast(new Hit(tick, amount));
		total += amount;
		count++;
		prune(tick);
	}

	private void prune(long now) {
		while (!hits.isEmpty() && now - hits.peekFirst().tick() > WINDOW_TICKS) {
			hits.pollFirst();
		}
	}

	/** Damage per second over the last 5 seconds (0 when idle). */
	public double dps(long now) {
		prune(now);
		if (hits.isEmpty()) {
			return 0;
		}
		double sum = 0;
		for (Hit h : hits) {
			sum += h.amount();
		}
		long span = Math.max(20, now - hits.peekFirst().tick() + 1);
		return sum / (span / 20.0);
	}

	public int combo(long now) {
		return now - lastHit <= COMBO_GAP_TICKS ? combo : 0;
	}

	public float total() {
		return total;
	}

	public int count() {
		return count;
	}

	public void reset() {
		hits.clear();
		lastHit = Long.MIN_VALUE;
		combo = 0;
		total = 0;
		count = 0;
	}
}
