package io.github.mudklip.minigamebots.training.aim;

/** Running score of one aim-trainer session. Pure logic, tested. */
public final class AimScore {
	public int hits;
	public int clicks;
	public int expired;
	public long hitTicks;
	public int trackTicks;
	public int onTargetTicks;

	public void hit(int ticksToHit) {
		hits++;
		hitTicks += ticksToHit;
	}

	public void click() {
		clicks++;
	}

	/** Hits per click (a hit also sends a click). */
	public double accuracy() {
		return clicks == 0 ? 0 : Math.min(1.0, hits / (double) clicks);
	}

	public double avgMillis() {
		return hits == 0 ? 0 : hitTicks * 50.0 / hits;
	}

	/** Share of tracking time the crosshair was on a target. */
	public double tracking() {
		return trackTicks == 0 ? 0 : onTargetTicks / (double) trackTicks;
	}

	/** One number to compare sessions of the same mode (higher is better). */
	public double score(AimTrainer.Mode mode) {
		return switch (mode) {
			case TRACK -> tracking() * 1000 + hits;
			default -> hits * 100 * accuracy() - expired * 20;
		};
	}
}
