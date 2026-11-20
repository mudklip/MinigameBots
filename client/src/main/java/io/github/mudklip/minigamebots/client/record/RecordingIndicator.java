package io.github.mudklip.minigamebots.client.record;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small "REC 1:23" badge in the top-left corner while recording. Drawn after the frame is
 * captured (see {@link io.github.mudklip.minigamebots.client.overlay.Overlay}), so recordings and
 * the AI's view don't contain it; as a normal HUD element only if that isn't possible.
 */
public final class RecordingIndicator implements HudElement {
	private static final int RED = 0xFFE53935;
	private static final int BACKGROUND = 0x88000000;
	private static final int WHITE = 0xFFFFFFFF;

	private final Recorder recorder;

	public RecordingIndicator(Recorder recorder) {
		this.recorder = recorder;
		io.github.mudklip.minigamebots.client.overlay.Overlay.add((g, mc, w, h) -> mc.gui.screen() == null && draw(g));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!io.github.mudklip.minigamebots.client.overlay.Overlay.deferred()) {
			draw(graphics);
		}
	}

	private boolean draw(GuiGraphicsExtractor graphics) {
		if (!recorder.recording() || !MgbConfig.get().showRecordingIndicator) {
			return false;
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
		return true;
	}
}
