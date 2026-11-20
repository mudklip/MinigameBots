package io.github.mudklip.minigamebots.client.gui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The mod's look for every plain button in its own screens (the old ones included): dark cards
 * that light up on hover, an accent stripe on main actions (green labels), and "Something: on/off"
 * buttons drawn as switches. Vanilla screens and other mods are left alone.
 */
public final class StyledButtons {
	private static final Pattern SWITCH = Pattern.compile("^(.*?):\\s*(?:§.)*(on|off)\\s*$", Pattern.CASE_INSENSITIVE);

	private StyledButtons() {
	}

	/** Only inside this mod's screens. */
	public static boolean active() {
		var s = Minecraft.getInstance().gui.screen();
		return s != null && s.getClass().getName().startsWith("io.github.mudklip.minigamebots.");
	}

	public static void draw(Button b, GuiGraphicsExtractor g) {
		var font = Minecraft.getInstance().font;
		int x = b.getX(), y = b.getY(), w = b.getWidth(), h = b.getHeight();
		boolean hover = b.isHoveredOrFocused() && b.active;
		String raw = b.getMessage().getString();
		boolean primary = raw.startsWith("§a") && !SWITCH.matcher(raw).matches();
		int fill = !b.active ? 0xB0181C22 : hover ? Ui.CARD_HOVER : Ui.CARD;
		g.fill(x, y, x + w, y + h, fill);
		g.outline(x, y, w, h, hover ? Ui.ACCENT : Ui.BORDER);
		if (primary) {
			g.fill(x, y, x + 2, y + h, Ui.GOOD);
		}
		int textColor = b.active ? Ui.TEXT : 0xFF6A7380;
		int ty = y + (h - 8) / 2;
		Matcher m = SWITCH.matcher(raw);
		if (m.matches() && w >= 60) {
			// A switch: the label on the left, the toggle on the right.
			boolean on = m.group(2).equalsIgnoreCase("on");
			String label = font.plainSubstrByWidth(m.group(1).replaceAll("§.", ""), w - 34);
			g.text(font, Component.literal(label), x + 6, ty, textColor, false);
			int sx = x + w - 26, sy = y + (h - 10) / 2;
			g.fill(sx, sy, sx + 20, sy + 10, on ? 0xFF2F7D3B : 0xFF3A414D);
			g.outline(sx, sy, 20, 10, on ? Ui.GOOD : Ui.BORDER);
			int kx = on ? sx + 11 : sx + 1;
			g.fill(kx, sy + 1, kx + 8, sy + 9, on ? 0xFFE8ECF2 : 0xFF98A2B3);
			return;
		}
		Component msg = b.getMessage();
		int tw = font.width(msg);
		if (tw > w - 6) {
			String cut = font.plainSubstrByWidth(raw.replaceAll("§.", ""), w - 8);
			g.text(font, Component.literal(cut), x + 4, ty, textColor, false);
		} else {
			g.text(font, msg, x + (w - tw) / 2, ty, textColor, false);
		}
	}
}
