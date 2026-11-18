package io.github.mudklip.minigamebots.client.debug;

import io.github.mudklip.minigamebots.client.control.ActionSource;
import io.github.mudklip.minigamebots.client.control.AgentAction;
import net.minecraft.client.Minecraft;

/** Plays a {@link Script} one tick at a time, then hands control back. */
public final class ScriptSource implements ActionSource {
	/** Extra idle ticks at the end so delayed actions and turns can finish. */
	private static final int TAIL = 20;

	private final Script script;
	private int index;

	public ScriptSource(Script script) {
		this.script = script;
	}

	@Override
	public AgentAction next(Minecraft client) {
		return index < script.ticks().size() ? script.ticks().get(index++) : idle();
	}

	private AgentAction idle() {
		index++;
		return AgentAction.NOOP;
	}

	@Override
	public boolean finished() {
		return index >= script.ticks().size() + TAIL;
	}

	@Override
	public String name() {
		return "test script";
	}
}
