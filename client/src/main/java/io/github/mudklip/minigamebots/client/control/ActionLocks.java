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
	/** Locks of the skill being coached: they hold for the whole session (restarts, F8, teacher demos), until it ends. */
	private static volatile Set<VirtualKey> sessionKeys = Set.of();
	/** The AI's own "never do" list: applies to everything it does. */
	private static volatile Set<VirtualKey> globalKeys = Set.of();
	private static volatile boolean globalCamera;
	private static volatile boolean sessionCamera;

	private ActionLocks() {
	}

	/** Sets the locks from key names as the brain spells them ("forward", "hotbar_1", ...). */
	public static void set(Iterable<String> names, boolean lockCamera) {
		keys = parse(names);
		camera = lockCamera;
	}

	/** Locks for a whole coached session; cleared only by {@link #endSession()}. */
	public static void session(Iterable<String> names, boolean lockCamera) {
		sessionKeys = parse(names);
		sessionCamera = lockCamera;
	}

	public static void global(Iterable<String> names, boolean lockCamera) {
		globalKeys = parse(names);
		globalCamera = lockCamera;
	}

	public static void endSession() {
		sessionKeys = Set.of();
		sessionCamera = false;
	}

	private static Set<VirtualKey> parse(Iterable<String> names) {
		EnumSet<VirtualKey> out = EnumSet.noneOf(VirtualKey.class);
		for (String n : names) {
			try {
				out.add(VirtualKey.valueOf(n.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {
				// Not a key.
			}
		}
		return out;
	}

	public static void clear() {
		keys = Set.of();
		camera = false;
	}

	public static boolean any() {
		return !keys.isEmpty() || camera || !sessionKeys.isEmpty() || sessionCamera || !globalKeys.isEmpty() || globalCamera;
	}

	public static GameInput filter(GameInput in) {
		Set<VirtualKey> k = keys;
		Set<VirtualKey> sk = sessionKeys;
		Set<VirtualKey> gk = globalKeys;
		boolean camera = ActionLocks.camera || sessionCamera || globalCamera;
		if (!sk.isEmpty() || !gk.isEmpty()) {
			EnumSet<VirtualKey> all = EnumSet.noneOf(VirtualKey.class);
			all.addAll(k);
			all.addAll(sk);
			all.addAll(gk);
			k = all;
		}
		if (k.isEmpty() && !camera) {
			return in;
		}
		EnumSet<VirtualKey> held = in.held().isEmpty() ? EnumSet.noneOf(VirtualKey.class) : EnumSet.copyOf(in.held());
		held.removeAll(k);
		return new GameInput(held, k.contains(VirtualKey.ATTACK) ? 0 : in.attackClicks(), k.contains(VirtualKey.USE) ? 0 : in.useClicks(),
				camera ? 0 : in.yaw(), camera ? 0 : in.pitch());
	}
}
