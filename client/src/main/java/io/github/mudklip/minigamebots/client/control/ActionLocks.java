package io.github.mudklip.minigamebots.client.control;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Actions a skill may not use while it practices (e.g. aim training: no walking, no inventory).
 * Applied to the final input, so nothing locked is ever pressed, whatever the brain asks for.
 * Cleared when the AI stops.
 */
public final class ActionLocks {
	private static volatile Set<VirtualKey> keys = Set.of();
	private static volatile boolean camera;

	private ActionLocks() {
	}

	/** Sets the locks from key names as the brain spells them ("forward", "hotbar_1", ...). */
	public static void set(Iterable<String> names, boolean lockCamera) {
		EnumSet<VirtualKey> out = EnumSet.noneOf(VirtualKey.class);
		for (String n : names) {
			try {
				out.add(VirtualKey.valueOf(n.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {
				// Not a key.
			}
		}
		keys = out;
		camera = lockCamera;
	}

	public static void clear() {
		keys = Set.of();
		camera = false;
	}

	public static boolean any() {
		return !keys.isEmpty() || camera;
	}

	public static GameInput filter(GameInput in) {
		Set<VirtualKey> k = keys;
		if (k.isEmpty() && !camera) {
			return in;
		}
		EnumSet<VirtualKey> held = in.held().isEmpty() ? EnumSet.noneOf(VirtualKey.class) : EnumSet.copyOf(in.held());
		held.removeAll(k);
		return new GameInput(held, k.contains(VirtualKey.ATTACK) ? 0 : in.attackClicks(), k.contains(VirtualKey.USE) ? 0 : in.useClicks(),
				camera ? 0 : in.yaw(), camera ? 0 : in.pitch());
	}
}
