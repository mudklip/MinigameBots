package io.github.mudklip.minigamebots.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Before going back to an older version: suggests a backup first, but never blocks.
 * [Back up first, then go back] [Go back] [Cancel].
 */
public final class DowngradeScreen extends Screen {
	private final Screen parent;
	private final String what;
	private final Runnable backup;
	private final Runnable go;

	/** {@code backup} makes a backup (may throw nothing); {@code go} does the going back and opens the next screen. */
	public DowngradeScreen(Screen parent, String what, Runnable backup, Runnable go) {
		super(Component.literal("Go back to an older version?"));
		this.parent = parent;
		this.what = what;
		this.backup = backup;
		this.go = go;
	}

	@Override
	protected void init() {
		int w = Math.min(320, width - 20);
		int x = (width - w) / 2;
		int y = height / 2 + 18;
		addRenderableWidget(Button.builder(Component.literal("§a✔ Back up first, then go back"), b -> {
			backup.run();
			go.run();
		}).pos(x, y).width(w).build());
		addRenderableWidget(Button.builder(Component.literal("Go back without a backup"), b -> go.run()).pos(x, y + 24).width(w / 2 - 2).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).pos(x + w / 2 + 2, y + 24).width(w / 2 - 2).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int w = Math.min(340, width - 10);
		int x = (width - w) / 2;
		int top = height / 2 - 62;
		Ui.panel(g, x, top, w, 132);
		g.fill(x, top, x + w, top + 2, Ui.WARN);
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§l⚠ Go back to an older version?"), x + 10, top + 10, Ui.WARN, false);
		int y = top + 26;
		for (FormattedCharSequence line : font.split(Component.literal(what + " Anything it learned since then will be replaced. "
				+ "Make a backup first, so you can come back to how it is now."), w - 20)) {
			g.text(font, line, x + 10, y, Ui.TEXT, false);
			y += 10;
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
