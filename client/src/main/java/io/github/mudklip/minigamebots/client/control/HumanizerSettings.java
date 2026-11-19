package io.github.mudklip.minigamebots.client.control;

/**
 * Human limits applied to every AI action. All values are per AI and configurable.
 *
 * @param enabled          false passes actions through untouched (for debugging only)
 * @param reactionMs       average delay between deciding and acting
 * @param reactionJitterMs standard deviation of that delay
 * @param maxTurnSpeed     fastest camera turn, degrees per second
 * @param maxTurnAccel     how fast turning can speed up or slow down, degrees per second squared
 * @param aimNoise         size of the small, smooth aim wobble, in degrees
 * @param maxCps           clicks per second allowed over any one-second window
 */
public record HumanizerSettings(boolean enabled, int reactionMs, int reactionJitterMs, float maxTurnSpeed,
		float maxTurnAccel, float aimNoise, int maxCps) {
	/**
	 * Default: the limits of top-level human play, and nothing tighter. Reaction ~140 ms (elite
	 * players), flicks up to ~3500 deg/s, 20 clicks a second (top clickers; many anticheats flag
	 * more). No aim wobble: that's style, which training learns, not a capability limit.
	 */
	public static final HumanizerSettings DEFAULT = new HumanizerSettings(true, 140, 20, 3500f, 100000f, 0f, 20);
	/** A typical player's limits. */
	public static final HumanizerSettings AVERAGE = new HumanizerSettings(true, 220, 40, 900f, 12000f, 0f, 10);
	/** The previous default, still saved in older profiles (upgraded to {@link #DEFAULT} when used). */
	public static final HumanizerSettings OLD_DEFAULT = new HumanizerSettings(true, 180, 35, 1000f, 15000f, 0.12f, 12);
	/** Scripted players (teachers, reflexes) aim mathematically perfectly; a little wobble keeps their demos human. */
	public static final float SCRIPTED_WOBBLE = 0.08f;
	public static final HumanizerSettings OFF = new HumanizerSettings(false, 0, 0, Float.MAX_VALUE, Float.MAX_VALUE, 0f,
			Integer.MAX_VALUE);

	public HumanizerSettings {
		if (reactionMs < 0 || reactionJitterMs < 0 || maxTurnSpeed <= 0 || maxTurnAccel <= 0 || aimNoise < 0
				|| maxCps < 1) {
			throw new IllegalArgumentException("invalid humanizer settings");
		}
	}
}
