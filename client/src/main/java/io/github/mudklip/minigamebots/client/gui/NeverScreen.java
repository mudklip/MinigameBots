package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The AI's "never do" list: actions it never uses, whatever it's doing (free play, goals, plans, practice). */
public final class NeverScreen extends Screen {
	private final @Nullable Screen parent;
	private final List<int[]> chips = new ArrayList<>();
	private @Nullable String message;

	public NeverScreen(@Nullable Screen parent) {
		super(Component.literal("Never do"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, height - 26).width(52).build());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		for (int[] c : chips) {
			if (p != null && Ui.inside(event.x(), event.y(), c[0], c[1], c[2], 13)) {
				String key = new ArrayList<>(SkillLibrary.get().locks.keySet()).get(c[3]);
				if (!p.never.remove(key)) {
					p.never.add(key);
				}
				try {
					MinigameBotsClient.instance().profiles().store().save(p);
					message = null;
				} catch (Exception e) {
					message = "Couldn't save: " + e.getMessage();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int w = Math.min(320, width - 16);
		int x0 = (width - w) / 2;
		Ui.panel(g, x0 - 6, 24, w + 12, 120);
		super.extractRenderState(g, mouseX, mouseY, a);
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		g.text(font, Component.literal("§lNever do§r  " + (p == null ? "§cno AI selected" : "§7" + p.name)), x0, 10, Ui.TEXT, false);
		if (p == null) {
			return;
		}
		int y = 30;
		for (var line : font.split(Component.literal("§7What it may never use, whatever it's doing: free play, goals, plans, "
				+ "practice and training worlds. Each skill can block more on its own card."), w)) {
			g.text(font, line, x0, y, Ui.MUTED, false);
			y += 10;
		}
		y += 6;
		chips.clear();
		int cx = x0;
		int i = 0;
		String hover = null;
		for (var e : SkillLibrary.get().locks.entrySet()) {
			boolean blocked = p.never.contains(e.getKey());
			String text = blocked ? "§c✖ §7§m" + e.getValue().label : "§a✔ §f" + e.getValue().label;
			int cw = font.width("✔ " + e.getValue().label) + 10;
			if (cx + cw > x0 + w) {
				cx = x0;
				y += 16;
			}
			boolean h = Ui.inside(mouseX, mouseY, cx, y, cw, 13);
			Ui.chip(g, font, text, cx, y, cw, true, h, blocked ? 0xFFB04040 : 0xFF4FAF5A);
			if (h) {
				hover = blocked ? "✖ Never: click to allow " + e.getValue().label.toLowerCase() + " again"
						: "✔ Allowed: click to block " + e.getValue().label.toLowerCase() + " everywhere";
			}
			chips.add(new int[] {cx, y, cw, i++});
			cx += cw + 3;
		}
		String bottom = hover != null ? hover : message;
		if (bottom != null) {
			g.text(font, Component.literal(font.plainSubstrByWidth(bottom, w)), x0, 150, hover != null ? Ui.MUTED : Ui.WARN, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
