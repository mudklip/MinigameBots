package io.github.mudklip.minigamebots.client.control;

import java.util.Set;

/**
 * The final input for one tick, after the {@link Humanizer}: exactly what gets pressed.
 *
 * @param held         keys held down during this tick
 * @param attackClicks total attack clicks this tick (a fresh press included)
 * @param useClicks    total use clicks this tick (a fresh press included)
 * @param yaw          camera turn in degrees for this tick, positive = right
 * @param pitch        camera turn in degrees for this tick, positive = down
 */
public record GameInput(Set<VirtualKey> held, int attackClicks, int useClicks, float yaw, float pitch) {
	public static final GameInput NONE = new GameInput(Set.of(), 0, 0, 0, 0);

	public GameInput {
		held = Set.copyOf(held);
	}

	public boolean holds(VirtualKey key) {
		return held.contains(key);
	}
}
