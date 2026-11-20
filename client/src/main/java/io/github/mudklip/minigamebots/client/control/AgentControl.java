package io.github.mudklip.minigamebots.client.control;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import java.util.Random;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Decides who is in control of this Minecraft instance (the human or the AI) and runs the AI's
 * input pipeline every tick: action source, then Humanizer, then virtual input.
 */
public final class AgentControl {
	private static final AgentControl INSTANCE = new AgentControl();

	private final GuiCursor cursor = new GuiCursor();
	private final VirtualInput input = new VirtualInput(cursor);
	private final CameraDriver camera = new CameraDriver(cursor);
	private final Humanizer humanizer = new Humanizer(MgbConfig.get().humanizer(), new Random());

	private volatile boolean active;
	private ActionSource source = ActionSource.IDLE;
	private long tick;
	private GameInput lastInput = GameInput.NONE;

	public static AgentControl get() {
		return INSTANCE;
	}

	/** True while the AI controls this instance. Read from mixins on the render thread. */
	/**
	 * Whether your real mouse and keyboard are held back: while the AI plays, except on
	 * MinigameBots' own screens (e.g. watching training worlds), which you can always use.
	 */
	public static boolean blocksHuman(Minecraft mc) {
		if (!aiActive()) {
			return false;
		}
		var screen = mc.gui.screen();
		return screen == null || !screen.getClass().getName().startsWith("io.github.mudklip.minigamebots.");
	}

	private static final boolean DEBUG_INPUT = Boolean.getBoolean("minigamebots.debugInput");

	public static boolean aiActive() {
		return INSTANCE.active;
	}

	public boolean active() {
		return active;
	}

	public ActionSource source() {
		return source;
	}

	public GameInput lastInput() {
		return lastInput;
	}

	/** The tick number the next action will be performed in. */
	public long nextTick() {
		return tick;
	}

	public Humanizer humanizer() {
		return humanizer;
	}

	public CameraDriver camera() {
		return camera;
	}

	/** Hands control to the AI, using {@code newSource} to decide what to do. */
	public void start(Minecraft client, ActionSource newSource) {
		if (client.player == null) {
			return;
		}
		source = newSource;
		var profiles = io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().profiles();
		var me = profiles.active();
		java.util.List<String> never = me == null ? java.util.List.of() : me.never;
		ActionLocks.global(io.github.mudklip.minigamebots.client.brain.BrainManager.lockedKeys(never), never.contains("camera"));
		var limits = profiles.humanizer();
		if (newSource instanceof io.github.mudklip.minigamebots.client.brain.BrainSource b && b.scripted()
				&& limits.enabled() && limits.aimNoise() == 0f) {
			limits = new HumanizerSettings(true, limits.reactionMs(), limits.reactionJitterMs(), limits.maxTurnSpeed(),
					limits.maxTurnAccel(), HumanizerSettings.SCRIPTED_WOBBLE, limits.maxCps());
		}
		humanizer.setSettings(limits);
		humanizer.reset();
		camera.reset();
		KeyMapping.releaseAll();
		active = true;
		client.player.sendOverlayMessage(Component.translatable("minigamebots.control.ai_on",
				profiles.displayName() + " - " + newSource.name()));
	}

	/** Gives control back to the human. */
	public void stop(Minecraft client, Component reason) {
		if (!active) {
			return;
		}
		active = false;
		ActionLocks.clear();
		input.releaseAll(client);
		humanizer.reset();
		camera.reset();
		lastInput = GameInput.NONE;
		ActionSource ended = source;
		source = ActionSource.IDLE;
		if (ended instanceof io.github.mudklip.minigamebots.client.brain.BrainSource b) {
			// The brain keeps running; a practice that was learning saves and gets its exam (A1, A2).
			io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().brain().controlStopped(b);
		}
		// Pick up whatever the human is physically holding right now.
		KeyMapping.setAll();
		if (client.gui.screen() == null && client.isWindowActive()) {
			client.mouseHandler.grabMouse();
		}
		if (client.player != null) {
			client.player.sendOverlayMessage(reason);
		}
	}

	public void toggle(Minecraft client) {
		if (active) {
			stop(client, Component.translatable("minigamebots.control.human_on"));
		} else {
			start(client, source);
		}
	}

	/** F9: release everything immediately, whatever the AI was doing. */
	public void emergencyStop(Minecraft client) {
		if (active) {
			stop(client, Component.translatable("minigamebots.control.emergency_stop"));
		}
	}

	/** Called at the start of every client tick. */
	public void onStartTick(Minecraft client) {
		if (!active) {
			return;
		}
		if (client.player == null || client.level == null) {
			// Left the world: nothing to control.
			active = false;
			input.releaseAll(client);
			source = ActionSource.IDLE;
			return;
		}
		if (source.finished()) {
			stop(client, Component.translatable("minigamebots.control.finished", source.name()));
			return;
		}
		AgentAction action = source.next(client);
		humanizer.submit(action, source.decisionTick(tick));
		lastInput = ActionLocks.filter(Autonomy.filter(client, humanizer.tick(tick)));
		input.apply(client, lastInput);
		if (DEBUG_INPUT && tick % 10 == 0) {
			io.github.mudklip.minigamebots.client.MinigameBotsClient.LOGGER.info("[input] {} locks={} brain={}", lastInput.held(),
					ActionLocks.any(), action.held());
		}
		camera.beginTick(client, lastInput.yaw(), lastInput.pitch());
		tick++;
	}
}
