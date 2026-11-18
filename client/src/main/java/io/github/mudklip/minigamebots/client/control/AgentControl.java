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
		humanizer.setSettings(profiles.humanizer());
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
		input.releaseAll(client);
		humanizer.reset();
		camera.reset();
		lastInput = GameInput.NONE;
		source = ActionSource.IDLE;
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
		lastInput = humanizer.tick(tick);
		input.apply(client, lastInput);
		camera.beginTick(client, lastInput.yaw(), lastInput.pitch());
		tick++;
	}
}
