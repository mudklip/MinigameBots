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
	public static final HumanizerSettings DEFAULT = new HumanizerSettings(true, 180, 35, 1000f, 15000f, 0.12f, 12);
	public static final HumanizerSettings OFF = new HumanizerSettings(false, 0, 0, Float.MAX_VALUE, Float.MAX_VALUE, 0f,
			Integer.MAX_VALUE);

	public HumanizerSettings {
		if (reactionMs < 0 || reactionJitterMs < 0 || maxTurnSpeed <= 0 || maxTurnAccel <= 0 || aimNoise < 0
				|| maxCps < 1) {
			throw new IllegalArgumentException("invalid humanizer settings");
		}
	}
}
