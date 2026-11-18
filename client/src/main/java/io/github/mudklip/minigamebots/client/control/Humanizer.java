package io.github.mudklip.minigamebots.client.control;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.random.RandomGenerator;

/**
 * Hard filter between the brain and the game that keeps input inside human limits:
 * reaction delay, turn speed and acceleration, aim wobble, and clicks per second.
 *
 * <p>Call {@link #submit} with each new decision and {@link #tick} once per game tick to get
 * the action to perform now. Not thread-safe; use from the client thread.
 */
public final class Humanizer {
	private static final float TICK_SECONDS = 0.05f;
	private static final int TICK_MS = 50;
	/** Pending camera movement beyond this is dropped, so a backlog can't build up forever. */
	private static final float MAX_PENDING_DEGREES = 360f;
	/** Aim wobble pull-back strength (per second). */
	private static final float NOISE_RETURN = 4f;
	private static final int CPS_WINDOW_TICKS = 20;
	/** How often a new reaction time is picked, and how often the delay may move one tick towards it. */
	private static final int RESAMPLE_TICKS = 20;
	private static final int LAG_STEP_TICKS = 4;

	private record Scheduled(long dueTick, AgentAction action) {
	}

	private final RandomGenerator random;
	private HumanizerSettings settings;

	private final Deque<Scheduled> queue = new ArrayDeque<>();
	private long lastDueTick = Long.MIN_VALUE;
	private int lagTicks = -1;
	private int targetLag;
	private long nextResample;
	private long nextLagStep;
	private AgentAction current = AgentAction.NOOP;

	private float pendingYaw;
	private float pendingPitch;
	private float velYaw;
	private float velPitch;
	private float noiseYaw;
	private float noisePitch;

	private final long[] clickTicks = new long[64];
	private int clickHead;
	private int clickCount;
	private boolean attackWasHeld;
	private boolean useWasHeld;

	public Humanizer(HumanizerSettings settings, RandomGenerator random) {
		this.settings = settings;
		this.random = random;
	}

	public HumanizerSettings settings() {
		return settings;
	}

	public void setSettings(HumanizerSettings settings) {
		this.settings = settings;
	}

	/** Forget everything in flight, e.g. when control is handed back to the human. */
	public void reset() {
		queue.clear();
		lastDueTick = Long.MIN_VALUE;
		lagTicks = -1;
		current = AgentAction.NOOP;
		pendingYaw = pendingPitch = velYaw = velPitch = noiseYaw = noisePitch = 0;
		clickHead = clickCount = 0;
		attackWasHeld = useWasHeld = false;
	}

	/**
	 * Schedules a decision made at {@code tick}; it takes effect after the reaction delay.
	 *
	 * <p>Like a person's, the delay drifts slowly rather than jumping every tick: a new target is
	 * picked now and then, and the actual delay moves towards it one tick at a time. Jumping
	 * would bunch decisions together and lose quick taps.
	 */
	public void submit(AgentAction action, long tick) {
		if (!settings.enabled()) {
			lagTicks = 0;
		} else {
			if (lagTicks < 0 || tick >= nextResample) {
				double ms = settings.reactionMs() + random.nextGaussian() * settings.reactionJitterMs();
				targetLag = (int) Math.max(0, Math.round(ms / TICK_MS));
				nextResample = tick + RESAMPLE_TICKS;
			}
			if (lagTicks < 0) {
				lagTicks = targetLag;
			} else if (lagTicks != targetLag && tick >= nextLagStep) {
				lagTicks += Integer.signum(targetLag - lagTicks);
				nextLagStep = tick + LAG_STEP_TICKS;
			}
		}
		// Never let a later decision overtake an earlier one.
		long due = Math.max(tick + lagTicks, lastDueTick);
		lastDueTick = due;
		Scheduled last = queue.peekLast();
		if (last != null && last.dueTick() == due) {
			queue.pollLast();
			queue.addLast(new Scheduled(due, last.action().mergeLater(action)));
		} else {
			queue.addLast(new Scheduled(due, action));
		}
	}

	/** The input to perform during {@code tick}. */
	public GameInput tick(long tick) {
		AgentAction arrived = null;
		while (!queue.isEmpty() && queue.peekFirst().dueTick() <= tick) {
			AgentAction next = queue.pollFirst().action();
			arrived = arrived == null ? next : arrived.mergeLater(next);
		}
		if (arrived != null) {
			// Held keys persist until the next decision arrives; clicks and turns happen once.
			current = arrived;
			pendingYaw += arrived.yaw();
			pendingPitch += arrived.pitch();
		} else {
			current = current.withClicks(0, 0).withCamera(0, 0);
		}

		float yaw;
		float pitch;
		if (settings.enabled()) {
			pendingYaw = clamp(pendingYaw, -MAX_PENDING_DEGREES, MAX_PENDING_DEGREES);
			pendingPitch = clamp(pendingPitch, -MAX_PENDING_DEGREES, MAX_PENDING_DEGREES);
			float[] turn = limitTurn();
			float[] wobble = wobble();
			yaw = turn[0] + wobble[0];
			pitch = turn[1] + wobble[1];
		} else {
			yaw = pendingYaw;
			pitch = pendingPitch;
			pendingYaw = pendingPitch = 0;
		}

		boolean attackHeld = current.holds(VirtualKey.ATTACK);
		boolean useHeld = current.holds(VirtualKey.USE);
		int attackClicks = (attackHeld && !attackWasHeld ? 1 : 0) + current.extraAttacks();
		int useClicks = (useHeld && !useWasHeld ? 1 : 0) + current.extraUses();
		attackWasHeld = attackHeld;
		useWasHeld = useHeld;
		if (settings.enabled()) {
			expireClicks(tick);
			attackClicks = takeClicks(attackClicks, tick);
			useClicks = takeClicks(useClicks, tick);
		}

		GameInput out = new GameInput(current.held(), attackClicks, useClicks, yaw, pitch);
		current = current.withClicks(0, 0);
		return out;
	}

	/** Moves towards the pending camera target no faster than human speed and acceleration. */
	private float[] limitTurn() {
		float maxSpeed = settings.maxTurnSpeed();
		float maxDv = settings.maxTurnAccel() * TICK_SECONDS;

		// Speed that would finish the turn this tick, capped to human limits.
		float wantYaw = pendingYaw / TICK_SECONDS;
		float wantPitch = pendingPitch / TICK_SECONDS;
		// Brake in time: no faster than what can still stop by the target (v^2 = 2ad).
		float stopYaw = (float) Math.sqrt(2 * settings.maxTurnAccel() * Math.abs(pendingYaw));
		float stopPitch = (float) Math.sqrt(2 * settings.maxTurnAccel() * Math.abs(pendingPitch));
		wantYaw = clamp(wantYaw, -Math.max(stopYaw, maxDv), Math.max(stopYaw, maxDv));
		wantPitch = clamp(wantPitch, -Math.max(stopPitch, maxDv), Math.max(stopPitch, maxDv));

		float dvYaw = clamp(wantYaw - velYaw, -maxDv, maxDv);
		float dvPitch = clamp(wantPitch - velPitch, -maxDv, maxDv);
		velYaw += dvYaw;
		velPitch += dvPitch;
		float speed = (float) Math.hypot(velYaw, velPitch);
		if (speed > maxSpeed) {
			velYaw *= maxSpeed / speed;
			velPitch *= maxSpeed / speed;
		}

		float yaw = velYaw * TICK_SECONDS;
		float pitch = velPitch * TICK_SECONDS;
		// Don't overshoot the target because of rounding.
		if (Math.abs(yaw) > Math.abs(pendingYaw)) {
			yaw = pendingYaw;
			velYaw = 0;
		}
		if (Math.abs(pitch) > Math.abs(pendingPitch)) {
			pitch = pendingPitch;
			velPitch = 0;
		}
		pendingYaw -= yaw;
		pendingPitch -= pitch;
		return new float[] {yaw, pitch};
	}

	/** Smooth random wobble around the aim point: it never drifts away, it just isn't perfect. */
	private float[] wobble() {
		float sigma = settings.aimNoise();
		if (sigma == 0) {
			return new float[] {0, 0};
		}
		float scale = sigma * (float) Math.sqrt(2 * NOISE_RETURN * TICK_SECONDS);
		float newYaw = noiseYaw - NOISE_RETURN * noiseYaw * TICK_SECONDS + scale * (float) random.nextGaussian();
		float newPitch = noisePitch - NOISE_RETURN * noisePitch * TICK_SECONDS + scale * (float) random.nextGaussian();
		float[] delta = {newYaw - noiseYaw, newPitch - noisePitch};
		noiseYaw = newYaw;
		noisePitch = newPitch;
		return delta;
	}

	private void expireClicks(long tick) {
		while (clickCount > 0 && clickTicks[(clickHead - clickCount + clickTicks.length) % clickTicks.length]
				<= tick - CPS_WINDOW_TICKS) {
			clickCount--;
		}
	}

	/** Allows as many of {@code wanted} clicks as the CPS cap permits right now. */
	private int takeClicks(int wanted, long tick) {
		int allowed = 0;
		while (allowed < wanted && clickCount < Math.min(settings.maxCps(), clickTicks.length)) {
			clickTicks[clickHead] = tick;
			clickHead = (clickHead + 1) % clickTicks.length;
			clickCount++;
			allowed++;
		}
		return allowed;
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
