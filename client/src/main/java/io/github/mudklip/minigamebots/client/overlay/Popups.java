package io.github.mudklip.minigamebots.client.overlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.gui.Ui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Optional popups of what the AI just gained, lost or learned. Drawn in the overlay layer, after
 * the AI's snapshot, so only the player sees them. Off by default (Settings > Popups).
 */
public final class Popups implements Overlay.Piece {
	private static final Popups INSTANCE = new Popups();
	private static final long LIFE_MS = 4000;
	private static final int MAX = 6;

	private record Line(String text, int color, long born) {
	}

	private final List<Line> lines = new ArrayList<>();
	private boolean added;

	/** A "feed" message from the brain. */
	public static void feed(JsonObject m) {
		var cfg = MgbConfig.get();
		if (!cfg.popupPoints && !cfg.popupKnowledge) {
			return;
		}
		JsonArray items = m.getAsJsonArray("items");
		if (items == null) {
			return;
		}
		for (var el : items) {
			var o = el.getAsJsonObject();
			String kind = o.get("kind").getAsString();
			String text = o.get("text").getAsString();
			double amount = o.has("amount") && !o.get("amount").isJsonNull() ? o.get("amount").getAsDouble() : 0;
			String line;
			int color;
			switch (kind) {
				case "know" -> {
					if (!cfg.popupKnowledge) {
						continue;
					}
					line = "💡 " + text;
					color = Ui.ACCENT;
				}
				case "held" -> {
					line = "⏳ " + fmt(amount) + " " + text;
					color = Ui.WARN;
				}
				case "paid" -> {
					line = "✓ " + fmt(amount) + " " + text;
					color = Ui.GOOD;
				}
				case "cancelled" -> {
					line = "✗ " + fmt(amount) + " " + text + " (got hurt)";
					color = 0xFFFF6E6E;
				}
				default -> {
					if (!cfg.popupSmall && Math.abs(amount) < 0.5) {
						continue; // Small per-second trickles only if wanted.
					}
					line = fmt(amount) + (Math.abs(amount) < 0.5 ? "/s " : " ") + text;
					color = amount >= 0 ? Ui.GOOD : 0xFFFF6E6E;
				}
			}
			if (!kind.equals("know") && !cfg.popupPoints) {
				continue;
			}
			INSTANCE.add(line, color);
		}
	}

	private static String fmt(double a) {
		return (a >= 0 ? "+" : "") + (Math.abs(a) >= 10 ? String.valueOf(Math.round(a)) : String.format("%.1f", a));
	}

	private synchronized void add(String text, int color) {
		lines.add(new Line(text, color, System.currentTimeMillis()));
		while (lines.size() > MAX) {
			lines.remove(0);
		}
		if (!added) {
			added = true;
			Overlay.add(this);
		}
	}

	@Override
	public synchronized boolean draw(GuiGraphicsExtractor g, Minecraft mc, int width, int height) {
		long now = System.currentTimeMillis();
		lines.removeIf(l -> now - l.born() > LIFE_MS);
		boolean left = MgbConfig.get().popupLeft;
		int y = height - 44 - lines.size() * 12; // Low on the side: clear of the scoreboard and the hotbar.
		for (Line l : lines) {
			long age = now - l.born();
			// Slide in for the first 200 ms, fade over the last second.
			int slide = age < 200 ? (int) ((200 - age) / 10) : 0;
			int alpha = age > LIFE_MS - 1000 ? (int) (255 * (LIFE_MS - age) / 1000) : 255;
			alpha = Math.max(16, Math.min(255, alpha));
			String t = mc.font.plainSubstrByWidth(l.text(), Math.min(220, width / 3));
			int w = mc.font.width(t) + 8;
			int x = left ? 4 - slide : width - w - 4 + slide;
			g.fill(x, y, x + w, y + 11, (alpha * 0xB0 / 255) << 24 | 0x101418);
			g.fill(left ? x + w - 2 : x, y, left ? x + w : x + 2, y + 11, (alpha << 24) | (l.color() & 0xFFFFFF));
			g.text(mc.font, Component.literal(t), x + 4, y + 2, (alpha << 24) | (l.color() & 0xFFFFFF), false);
			y += 12;
		}
		return !lines.isEmpty();
	}
}
