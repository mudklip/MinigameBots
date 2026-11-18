package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.control.ActionSource;
import io.github.mudklip.minigamebots.client.control.AgentAction;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

/**
 * Lets the brain drive the AI. Each tick the mod sends an observation; the brain's answer is
 * used as soon as it arrives. The action is tagged with the tick it was decided for, so the
 * Humanizer counts the brain's thinking time as part of the reaction delay instead of adding
 * it on top.
 *
 * <p>If an answer is late, the AI keeps holding the same keys for a few ticks (no new clicks or
 * turns), then lets go of everything, so a stalled brain never leaves it running off a cliff.
 */
public final class BrainSource implements ActionSource {
	/** How many ticks to keep holding keys while waiting for a late answer. */
	static final int HOLD_TICKS_WHEN_LATE = 3;
	/**
	 * How long a tick may wait for an answer that's on its way. When the game runs several ticks
	 * back to back (a slow frame), the next tick starts right after the observation was sent;
	 * a few milliseconds is enough for a quick brain to answer.
	 */
	static final long MAX_WAIT_NANOS = 4_000_000L;

	record Decision(long tick, AgentAction action, long receivedNanos) {
	}

	private final String policy;
	private final AtomicReference<Decision> latest = new AtomicReference<>();
	private long lastUsedTick = Long.MIN_VALUE;
	private long decisionTick;
	private AgentAction lastAction = AgentAction.NOOP;
	private int lateStreak;

	private long sentNanos;
	private long sentTick = Long.MIN_VALUE;
	private double latencyMs;
	private long answered;
	private long late;

	public BrainSource(String policy) {
		this.policy = policy;
	}

	/** Called from the brain reader thread with an {@code action} message. */
	public void offer(JsonObject message) {
		long tick = message.get("tick").getAsLong();
		metaActions(message);
		Decision d = new Decision(tick, parse(message), System.nanoTime());
		latest.accumulateAndGet(d, (old, now) -> old == null || now.tick() > old.tick() ? now : old);
		synchronized (this) {
			notifyAll();
		}
	}

	/** Records that an observation for {@code tick} was just sent. */
	public void sent(long tick) {
		sentTick = tick;
		sentNanos = System.nanoTime();
	}

	@Override
	public AgentAction next(Minecraft client) {
		Decision d = latest.get();
		if ((d == null || d.tick() < sentTick) && sentTick > lastUsedTick) {
			d = awaitAnswer();
		}
		if (d != null && d.tick() > lastUsedTick) {
			lastUsedTick = d.tick();
			decisionTick = d.tick();
			lateStreak = 0;
			answered++;
			if (d.tick() == sentTick) {
				double ms = (d.receivedNanos() - sentNanos) / 1e6;
				latencyMs = latencyMs == 0 ? ms : latencyMs * 0.9 + ms * 0.1;
			}
			lastAction = d.action();
			return lastAction;
		}
		late++;
		lateStreak++;
		decisionTick = Long.MIN_VALUE;
		if (lateStreak <= HOLD_TICKS_WHEN_LATE) {
			return new AgentAction(lastAction.held(), 0, 0, 0, 0);
		}
		return AgentAction.NOOP;
	}

	/** Waits briefly for the answer to the observation just sent. */
	private Decision awaitAnswer() {
		long deadline = System.nanoTime() + MAX_WAIT_NANOS;
		synchronized (this) {
			Decision d = latest.get();
			while ((d == null || d.tick() < sentTick) && System.nanoTime() < deadline) {
				long left = deadline - System.nanoTime();
				try {
					wait(Math.max(1, left / 1_000_000L), (int) (left % 1_000_000L));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
				d = latest.get();
			}
			return d;
		}
	}

	@Override
	public long decisionTick(long currentTick) {
		return decisionTick == Long.MIN_VALUE ? currentTick : Math.min(decisionTick, currentTick);
	}

	@Override
	public String name() {
		return "brain: " + policy;
	}

	/** Average time from sending an observation to receiving the answer, in ms. */
	public double latencyMs() {
		return latencyMs;
	}

	/** Fraction of ticks where no fresh answer was ready. */
	public double lateFraction() {
		long total = answered + late;
		return total == 0 ? 0 : (double) late / total;
	}

	/** Meta-actions ride along with an action: e.g. "mark": "base" remembers this place. */
	static void metaActions(JsonObject m) {
		if (m.has("puppet") && m.get("puppet").isJsonObject()) {
			SelfPlayLink.send(m.getAsJsonObject("puppet"));
		}
		if (m.has("protect") && m.get("protect").isJsonPrimitive()) {
			String name = m.get("protect").getAsString();
			net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
			c.execute(() -> {
				int id = -1;
				if (c.level != null && !name.isEmpty()) {
					for (var p : c.level.players()) {
						if (p.getGameProfile().name().equals(name)) {
							id = p.getId();
						}
					}
				}
				io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().relations().setProtected(id);
			});
		}
		if (m.has("mark") && m.get("mark").isJsonPrimitive()) {
			String type = m.get("mark").getAsString();
			net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
			c.execute(() -> io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().memory().mark(c, type, true));
		}
	}

	static AgentAction parse(JsonObject m) {
		EnumSet<VirtualKey> keys = EnumSet.noneOf(VirtualKey.class);
		if (m.has("keys")) {
			for (JsonElement k : m.getAsJsonArray("keys")) {
				try {
					keys.add(VirtualKey.valueOf(k.getAsString().toUpperCase(Locale.ROOT)));
				} catch (IllegalArgumentException ignored) {
					// Unknown key from a newer brain: skip it.
				}
			}
		}
		int attacks = clamp(m.has("attacks") ? m.get("attacks").getAsInt() : 0, 0, 5);
		int uses = clamp(m.has("uses") ? m.get("uses").getAsInt() : 0, 0, 5);
		float yaw = finite(m.has("yaw") ? m.get("yaw").getAsFloat() : 0);
		float pitch = finite(m.has("pitch") ? m.get("pitch").getAsFloat() : 0);
		// One decision can't ask for more than a full turn; the Humanizer limits speed anyway.
		return new AgentAction(keys, attacks, uses, Math.max(-180, Math.min(180, yaw)), Math.max(-90, Math.min(90, pitch)));
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float finite(float v) {
		return Float.isFinite(v) ? v : 0;
	}
}
