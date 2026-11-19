package io.github.mudklip.minigamebots.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A tiny widget kit on Minecraft's own GUI (no library): panels, cards, bars, headers. */
public final class Ui {
	public static final int BG = 0xC0101418;
	public static final int CARD = 0xD0202630;
	public static final int CARD_HOVER = 0xE02C3442;
	public static final int CARD_SELECTED = 0xE0304A6A;
	public static final int BORDER = 0xFF3C4656;
	public static final int ACCENT = 0xFF5AB4FF;
	public static final int GOOD = 0xFF6EE07A;
	public static final int WARN = 0xFFFFC24A;
	public static final int TEXT = 0xFFE8ECF2;
	public static final int MUTED = 0xFF98A2B3;

	private Ui() {
	}

	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, BG);
		g.outline(x, y, w, h, BORDER);
	}

	public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int accent) {
		g.fill(x, y, x + w, y + h, fill);
		g.outline(x, y, w, h, BORDER);
		g.fill(x, y, x + 2, y + h, accent);
	}

	public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double frac, int color) {
		g.fill(x, y, x + w, y + h, 0xFF14181E);
		int fw = (int) Math.round(Math.max(0, Math.min(1, frac)) * (w - 2));
		if (fw > 0) {
			g.fillGradient(x + 1, y + 1, x + 1 + fw, y + h - 1, color, darker(color));
		}
		g.outline(x, y, w, h, BORDER);
	}

	public static void header(GuiGraphicsExtractor g, Font font, String text, int x, int y) {
		g.text(font, Component.literal("§l" + text), x, y, ACCENT, false);
		g.fill(x, y + 10, x + font.width("§l" + text), y + 11, 0x605AB4FF);
	}

	public static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	private static int darker(int c) {
		int r = (c >> 16 & 0xFF) * 7 / 10;
		int gr = (c >> 8 & 0xFF) * 7 / 10;
		int b = (c & 0xFF) * 7 / 10;
		return c & 0xFF000000 | r << 16 | gr << 8 | b;
	}
}
