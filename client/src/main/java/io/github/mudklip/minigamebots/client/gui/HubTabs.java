package io.github.mudklip.minigamebots.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The Training Hub's tabs: Plans, Skills, Worlds and Backups are one place, switched along the top. */
final class HubTabs {
	static final int PLANS = 0;
	static final int SKILLS = 1;
	static final int WORLDS = 2;
	static final int BACKUPS = 3;
	static final int WATCH = 4;
	private static final String[] NAMES = {"★ Plans", "✦ Skills", "⚡ Worlds", "Backups", "👁 Watch"};
	private static final String[] HELP = {"Pick a goal and a time: the Coach runs a whole plan", "Every skill, one by one, and your own",
			"Practice in many worlds at once and watch them live", "Back up the AI, or go back to a backup",
			"Learn by watching other players (spectating, PvP servers, minigames)"};
	static final int Y = 6;

	private HubTabs() {
	}

	/** Draws the tabs; returns the x where they end. Shows the active AI's name after them if there's room. */
	static int draw(GuiGraphicsExtractor g, Font font, int active, int mouseX, int mouseY, int rightLimit) {
		int x = 8;
		String hover = null;
		for (int i = 0; i < NAMES.length; i++) {
			int w = font.width("§l" + NAMES[i]) + 12;
			boolean h = Ui.inside(mouseX, mouseY, x, Y, w, 16);
			boolean on = i == active;
			g.fill(x, Y, x + w, Y + 16, on ? Ui.CARD_SELECTED : h ? Ui.CARD_HOVER : Ui.CARD);
			g.outline(x, Y, w, 16, on ? Ui.ACCENT : Ui.BORDER);
			if (on) {
				g.fill(x, Y + 15, x + w, Y + 16, Ui.ACCENT);
			}
			g.text(font, Component.literal((on ? "§f§l" : "§7") + NAMES[i]), x + 6, Y + 4, Ui.TEXT, false);
			if (h && !on) {
				hover = HELP[i];
			}
			x += w + 2;
		}
		var p = io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().profiles().active();
		String name = p == null ? "§cno AI selected" : "§7" + p.name;
		if (x + 8 + font.width(name) < rightLimit) {
			g.text(font, Component.literal(name), x + 6, Y + 4, Ui.MUTED, false);
		}
		if (hover != null) {
			int w = font.width(hover) + 8;
			int hx = Math.max(4, Math.min(mouseX, rightLimit + 60 - w));
			g.nextStratum();
			g.fill(hx, Y + 18, hx + w, Y + 31, 0xF0101418);
			g.outline(hx, Y + 18, w, 13, Ui.BORDER);
			g.text(font, Component.literal(hover), hx + 4, Y + 21, Ui.TEXT, false);
		}
		return x;
	}

	/** Switches tab if one was clicked; true if handled. */
	static boolean click(Minecraft mc, int active, double mx, double my, @Nullable Screen hubParent) {
		Font font = mc.font;
		int x = 8;
		for (int i = 0; i < NAMES.length; i++) {
			int w = font.width("§l" + NAMES[i]) + 12;
			if (Ui.inside(mx, my, x, Y, w, 16)) {
				if (i != active) {
					mc.gui.setScreen(switch (i) {
						case SKILLS -> new SkillsScreen(hubParent);
						case WORLDS -> new WorldsScreen(hubParent);
						case BACKUPS -> new BackupsScreen(hubParent);
						case WATCH -> new WatchScreen(hubParent);
						default -> new HubScreen(hubParent);
					});
				}
				return true;
			}
			x += w + 2;
		}
		return false;
	}
}
