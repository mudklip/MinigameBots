package io.github.mudklip.minigamebots.client.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Spreads each tick's camera turn across the frames drawn during that tick, so the view moves
 * smoothly like a real mouse instead of jumping 20 times a second.
 */
public final class CameraDriver {
	/** Entity.turn() multiplies its input by this to get degrees. */
	private static final double TURN_UNITS_PER_DEGREE = 1 / 0.15;
	private static final long TICK_NANOS = 50_000_000L;

	private final GuiCursor cursor;
	private float tickYaw;
	private float tickPitch;
	private float appliedYaw;
	private float appliedPitch;
	private long tickStart;

	public CameraDriver(GuiCursor cursor) {
		this.cursor = cursor;
	}

	/** Starts a new tick's turn, finishing whatever is left of the previous one first. */
	public void beginTick(Minecraft client, float yaw, float pitch) {
		turn(client, tickYaw - appliedYaw, tickPitch - appliedPitch);
		tickYaw = yaw;
		tickPitch = pitch;
		appliedYaw = appliedPitch = 0;
		tickStart = System.nanoTime();
	}

	/** Called every frame while the AI is in control. */
	public void onFrame(Minecraft client) {
		float progress = Math.min(1f, (System.nanoTime() - tickStart) / (float) TICK_NANOS);
		float targetYaw = tickYaw * progress;
		float targetPitch = tickPitch * progress;
		turn(client, targetYaw - appliedYaw, targetPitch - appliedPitch);
		appliedYaw = targetYaw;
		appliedPitch = targetPitch;
	}

	public void reset() {
		tickYaw = tickPitch = appliedYaw = appliedPitch = 0;
	}

	private void turn(Minecraft client, float yaw, float pitch) {
		if (yaw == 0 && pitch == 0) {
			return;
		}
		Screen screen = client.gui.screen();
		if (screen != null) {
			cursor.move(client, screen, yaw, pitch);
			return;
		}
		LocalPlayer player = client.player;
		if (player != null) {
			player.turn(yaw * TURN_UNITS_PER_DEGREE, pitch * TURN_UNITS_PER_DEGREE);
		}
	}
}
