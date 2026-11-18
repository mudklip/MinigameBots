package io.github.mudklip.minigamebots.client.control;

import java.util.EnumSet;
import java.util.Set;

/**
 * What the AI wants to do during one game tick (1/20 s).
 *
 * @param held          keys held down during this tick
 * @param extraAttacks  attack clicks on top of the one a fresh press gives (fast clicking)
 * @param extraUses     use clicks on top of the one a fresh press gives
 * @param yaw           camera turn in degrees, positive = right. In a GUI it moves the cursor.
 * @param pitch         camera turn in degrees, positive = down. In a GUI it moves the cursor.
 */
public record AgentAction(Set<VirtualKey> held, int extraAttacks, int extraUses, float yaw, float pitch) {
	public static final AgentAction NOOP = new AgentAction(Set.of(), 0, 0, 0, 0);

	public AgentAction {
		held = held.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(held));
		if (extraAttacks < 0 || extraUses < 0) {
			throw new IllegalArgumentException("negative clicks");
		}
		if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			throw new IllegalArgumentException("camera delta must be finite");
		}
	}

	public boolean holds(VirtualKey key) {
		return held.contains(key);
	}

	public AgentAction withCamera(float newYaw, float newPitch) {
		return new AgentAction(held, extraAttacks, extraUses, newYaw, newPitch);
	}

	public AgentAction withClicks(int attacks, int uses) {
		return new AgentAction(held, attacks, uses, yaw, pitch);
	}

	/**
	 * Combines two actions that land in the same tick: the later one's held keys win, but nothing
	 * the earlier one pressed is lost. Its one-shot key presses (hotbar, inventory...) still
	 * happen, its attack/use presses become clicks, and camera movement adds up.
	 */
	public AgentAction mergeLater(AgentAction later) {
		EnumSet<VirtualKey> keys = later.held.isEmpty() ? EnumSet.noneOf(VirtualKey.class) : EnumSet.copyOf(later.held);
		for (VirtualKey key : held) {
			if (key.kind() == VirtualKey.Kind.CLICK) {
				keys.add(key);
			}
		}
		int attacks = extraAttacks + later.extraAttacks
				+ (holds(VirtualKey.ATTACK) && !later.holds(VirtualKey.ATTACK) ? 1 : 0);
		int uses = extraUses + later.extraUses + (holds(VirtualKey.USE) && !later.holds(VirtualKey.USE) ? 1 : 0);
		return new AgentAction(keys, attacks, uses, yaw + later.yaw, pitch + later.pitch);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private final EnumSet<VirtualKey> held = EnumSet.noneOf(VirtualKey.class);
		private int extraAttacks;
		private int extraUses;
		private float yaw;
		private float pitch;

		public Builder hold(VirtualKey... keys) {
			held.addAll(Set.of(keys));
			return this;
		}

		public Builder extraAttacks(int n) {
			extraAttacks = n;
			return this;
		}

		public Builder extraUses(int n) {
			extraUses = n;
			return this;
		}

		public Builder camera(float yawDeg, float pitchDeg) {
			yaw = yawDeg;
			pitch = pitchDeg;
			return this;
		}

		public AgentAction build() {
			return new AgentAction(held, extraAttacks, extraUses, yaw, pitch);
		}
	}
}
