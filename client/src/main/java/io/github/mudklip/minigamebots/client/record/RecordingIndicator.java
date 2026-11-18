package io.github.mudklip.minigamebots.client.record;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small "REC 1:23" badge in the top-left corner while recording. It's part of the HUD, so it
 * also appears in the recorded frames; that's intended, the AI learns to ignore it.
 */
public final class RecordingIndicator implements HudElement {
	private static final int RED = 0xFFE53935;
	private static final int BACKGROUND = 0x88000000;
	private static final int WHITE = 0xFFFFFFFF;

	private final Recorder recorder;

	public RecordingIndicator(Recorder recorder) {
		this.recorder = recorder;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!recorder.recording() || !MgbConfig.get().showRecordingIndicator) {
			return;
		}
		var font = Minecraft.getInstance().font;
		String text = "REC " + Recorder.time(recorder.ticks());
		int width = font.width(text) + 16;
		graphics.fill(2, 2, 2 + width, 16, BACKGROUND);
		// Blink the dot once a second.
		if ((recorder.ticks() / 10) % 2 == 0) {
			graphics.fill(6, 6, 12, 12, RED);
		}
		graphics.text(font, text, 15, 5, WHITE, false);
	}
}
