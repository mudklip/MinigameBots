package io.github.mudklip.minigamebots.client.debug;

import io.github.mudklip.minigamebots.client.control.AgentAction;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A tiny test language for driving the AI's body by hand, before there's a brain. Steps are
 * separated by {@code ;}:
 *
 * <pre>
 *   hold forward+sprint 40   hold keys for 40 ticks (2 seconds)
 *   tap jump                 press a key for one tick
 *   look 90 -10              turn the camera 90 degrees right and 10 up
 *   click 3 20               3 attack clicks per tick for 20 ticks (the CPS cap still applies)
 *   wait 20                  do nothing for 20 ticks
 * </pre>
 *
 * Key names are the {@link VirtualKey} names in lower case; {@code hotbar1}..{@code hotbar9} also work.
 */
public final class Script {
	/** One tick's worth of action, already expanded. */
	private final List<AgentAction> ticks;

	private Script(List<AgentAction> ticks) {
		this.ticks = List.copyOf(ticks);
	}

	public List<AgentAction> ticks() {
		return ticks;
	}

	public static Script parse(String source) {
		List<AgentAction> out = new ArrayList<>();
		for (String raw : source.split(";")) {
			String step = raw.trim();
			if (step.isEmpty()) {
				continue;
			}
			String[] parts = step.split("\\s+");
			String cmd = parts[0].toLowerCase(Locale.ROOT);
			switch (cmd) {
				case "hold" -> {
					need(parts, 3, step);
					Set<VirtualKey> keys = keys(parts[1]);
					int n = positive(parts[2], step);
					for (int i = 0; i < n; i++) {
						out.add(new AgentAction(keys, 0, 0, 0, 0));
					}
				}
				case "tap" -> {
					need(parts, 2, step);
					out.add(new AgentAction(keys(parts[1]), 0, 0, 0, 0));
					out.add(AgentAction.NOOP);
				}
				case "look" -> {
					need(parts, 3, step);
					out.add(AgentAction.builder().camera(number(parts[1], step), number(parts[2], step)).build());
				}
				case "click" -> {
					need(parts, 3, step);
					int perTick = positive(parts[1], step);
					int n = positive(parts[2], step);
					for (int i = 0; i < n; i++) {
						out.add(AgentAction.builder().extraAttacks(perTick).build());
					}
				}
				case "wait" -> {
					need(parts, 2, step);
					int n = positive(parts[1], step);
					for (int i = 0; i < n; i++) {
						out.add(AgentAction.NOOP);
					}
				}
				default -> throw new IllegalArgumentException("Unknown step '" + cmd + "' in: " + step);
			}
		}
		if (out.isEmpty()) {
			throw new IllegalArgumentException("Empty script");
		}
		return new Script(out);
	}

	static Set<VirtualKey> keys(String spec) {
		EnumSet<VirtualKey> keys = EnumSet.noneOf(VirtualKey.class);
		for (String name : spec.split("\\+")) {
			keys.add(key(name));
		}
		return keys;
	}

	static VirtualKey key(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (n.matches("hotbar[1-9]")) {
			return VirtualKey.hotbar(n.charAt(6) - '1');
		}
		try {
			return VirtualKey.valueOf(n.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown key '" + name + "'");
		}
	}

	private static void need(String[] parts, int n, String step) {
		if (parts.length != n) {
			throw new IllegalArgumentException("Wrong number of arguments in: " + step);
		}
	}

	private static int positive(String s, String step) {
		try {
			int v = Integer.parseInt(s);
			if (v < 1 || v > 72000) {
				throw new NumberFormatException();
			}
			return v;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Expected a whole number from 1 to 72000 in: " + step);
		}
	}

	private static float number(String s, String step) {
		try {
			float v = Float.parseFloat(s);
			if (!Float.isFinite(v) || Math.abs(v) > 3600) {
				throw new NumberFormatException();
			}
			return v;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Expected a number in: " + step);
		}
	}
}
