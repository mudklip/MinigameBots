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

	/** The item for an id like "minecraft:diamond_sword" (a barrier if unknown). */
	public static net.minecraft.world.item.ItemStack stack(String id) {
		var key = net.minecraft.resources.Identifier.tryParse(id == null ? "" : id);
		var item = key == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key);
		return new net.minecraft.world.item.ItemStack(item == null || item == net.minecraft.world.item.Items.AIR
				? net.minecraft.world.item.Items.BARRIER : item);
	}

	/** A small toggle pill; returns its width. */
	public static int chip(GuiGraphicsExtractor g, Font font, String text, int x, int y, boolean on, boolean hover, int onColor) {
		return chip(g, font, text, x, y, font.width(text) + 10, on, hover, onColor);
	}

	public static int chip(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, boolean on, boolean hover, int onColor) {
		g.fill(x, y, x + w, y + 13, on ? (onColor & 0x00FFFFFF | 0x60000000) : hover ? CARD_HOVER : CARD);
		g.outline(x, y, w, 13, on ? onColor : BORDER);
		g.text(font, Component.literal(text), x + 5, y + 3, on ? TEXT : MUTED, false);
		return w;
	}

	/** The "add" card: dashed outline and a big plus. */
	public static void plusCard(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, boolean hover, String label) {
		g.fill(x, y, x + w, y + h, hover ? 0x60304A6A : 0x40202630);
		int c = hover ? ACCENT : BORDER;
		for (int i = x; i < x + w; i += 6) {
			g.fill(i, y, Math.min(i + 3, x + w), y + 1, c);
			g.fill(i, y + h - 1, Math.min(i + 3, x + w), y + h, c);
		}
		for (int j = y; j < y + h; j += 6) {
			g.fill(x, j, x + 1, Math.min(j + 3, y + h), c);
			g.fill(x + w - 1, j, x + w, Math.min(j + 3, y + h), c);
		}
		g.text(font, Component.literal("§l+"), x + 10, y + h / 2 - 4, hover ? ACCENT : MUTED, false);
		g.text(font, Component.literal(label), x + 22, y + h / 2 - 4, hover ? TEXT : MUTED, false);
	}

	/** A skill-style card: icon, title, subtitle, optional progress bar. */
	public static void itemCard(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String itemId, String title,
			String sub, double bar, boolean hover, boolean selected) {
		card(g, x, y, w, h, selected ? CARD_SELECTED : hover ? CARD_HOVER : CARD, selected ? ACCENT : hover ? 0xFF6C7A90 : BORDER);
		g.fill(x + 4, y + h / 2 - 10, x + 24, y + h / 2 + 10, 0x40000000);
		g.item(stack(itemId), x + 6, y + h / 2 - 8);
		String t = font.width(title) > w - 32 ? font.plainSubstrByWidth(title, w - 32 - font.width("…")) + "…" : title;
		g.text(font, Component.literal(t), x + 28, y + 5, TEXT, false);
		g.text(font, Component.literal(font.plainSubstrByWidth(sub, w - 32)), x + 28, y + 16, MUTED, false);
		if (bar >= 0) {
			bar(g, x + 28, y + h - 8, w - 34, 4, bar, ACCENT);
		}
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
