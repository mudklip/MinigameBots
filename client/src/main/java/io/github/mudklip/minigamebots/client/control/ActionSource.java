package io.github.mudklip.minigamebots.client.control;

import net.minecraft.client.Minecraft;

/** Something that decides what the AI does each tick: the brain, or a test script. */
public interface ActionSource {
	/** Called once per game tick while the AI is in control. */
	AgentAction next(Minecraft client);

	/**
	 * The tick the last returned action was decided for. Sources that think in the background
	 * (the brain) return an earlier tick, so their thinking time counts towards the reaction delay.
	 */
	default long decisionTick(long currentTick) {
		return currentTick;
	}

	/** When true, control goes back to the human. */
	default boolean finished() {
		return false;
	}

	/** Shown to the player when control starts. */
	String name();

	/** Does nothing: the AI holds control but stays still. */
	ActionSource IDLE = new ActionSource() {
		@Override
		public AgentAction next(Minecraft client) {
			return AgentAction.NOOP;
		}

		@Override
		public String name() {
			return "idle";
		}
	};
}
