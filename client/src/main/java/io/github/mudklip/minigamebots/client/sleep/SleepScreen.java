package io.github.mudklip.minigamebots.client.sleep;

import io.github.mudklip.minigamebots.client.gui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Sleep: choose how, start it, watch it (or minimise the game), wake it, read the morning report.
 * While it sleeps this screen pauses a singleplayer world, so nothing can happen to you meanwhile
 * (unless inventing is on, which needs the world running).
 */
public final class SleepScreen extends Screen {
	private static boolean minimise = true;
	private static boolean invent;

	private final @Nullable Screen parent;
	private @Nullable String message;
	private boolean builtSleeping;

	public SleepScreen(@Nullable Screen parent) {
		super(Component.literal("Sleep"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		builtSleeping = SleepMode.sleeping();
		int x = width / 2 - 110;
		if (!builtSleeping) {
			addRenderableWidget(Button.builder(Component.literal("Minimise the game: " + (minimise ? "§aon" : "off")), b -> {
				minimise = !minimise;
				rebuildWidgets();
			}).pos(x, 92).width(220).tooltip(Tooltip.create(Component.literal("Minimises Minecraft while it sleeps, so your PC "
					+ "is free for other things. Click it in the taskbar to come back."))).build());
			addRenderableWidget(Button.builder(Component.literal("Also invent: " + (invent ? "§aon" : "off")), b -> {
				invent = !invent;
				rebuildWidgets();
			}).pos(x, 116).width(220).tooltip(Tooltip.create(Component.literal("Keeps inventing traps and shelters (needs "
					+ "Training Grounds). The world keeps running for that, so only in a safe place."))).build());
			var cfg = io.github.mudklip.minigamebots.client.config.MgbConfig.get();
			addRenderableWidget(Button.builder(Component.literal("Sleep when I minimise the game: " + (cfg.sleepWhenMinimised ? "§aon" : "off")), b -> {
				cfg.sleepWhenMinimised = !cfg.sleepWhenMinimised;
				io.github.mudklip.minigamebots.client.config.MgbConfig.HANDLER.save();
				rebuildWidgets();
			}).pos(x, 68).width(220).tooltip(Tooltip.create(Component.literal("When on: minimising Minecraft puts it to sleep "
					+ "(after a few seconds), and bringing the window back wakes it. It only ever starts because you minimised; "
					+ "nothing happens on a timer."))).build());
			addRenderableWidget(Button.builder(Component.literal("§a☾ Go to sleep"), b -> {
				message = SleepMode.start(minecraft, minimise, invent);
				rebuildWidgets();
			}).pos(x, 146).width(220).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("☀ Wake up"), b -> {
				SleepMode.wake(minecraft, false);
				rebuildWidgets();
			}).pos(x, 146).width(220).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build())
				.active = !SleepMode.sleeping();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (SleepMode.sleeping() != builtSleeping) {
			rebuildWidgets();
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, Component.literal("§f§l" + (SleepMode.sleeping() ? "☾ Asleep" : "Sleep")), 8, 8, Ui.TEXT, false);
		int y = 24;
		for (var line : font.split(Component.literal("§7It uses idle time to get better: replays the day (trains on what was "
				+ "recorded since it last slept), tidies its memories, and if you like keeps inventing. Your PC stays free: "
				+ "10 FPS, short view, no sound, quiet training, and in singleplayer the world is paused."), width - 16)) {
			g.text(font, line, 8, y, Ui.MUTED, false);
			y += 10;
		}
		if (SleepMode.sleeping()) {
			g.text(font, Component.literal("§a" + SleepMode.status()), 8, 172, Ui.TEXT, false);
			g.text(font, Component.literal("§7Press any key or Wake up to stop it."), 8, 184, Ui.MUTED, false);
		} else if (!SleepMode.report().isEmpty()) {
			int ry = 172;
			g.text(font, Component.literal("§fMorning report"), 8, ry, Ui.TEXT, false);
			for (String r : SleepMode.report()) {
				ry += 11;
				g.text(font, Component.literal("§7• " + font.plainSubstrByWidth(r, width - 30)), 8, ry, Ui.MUTED, false);
			}
		} else if (message != null) {
			g.text(font, Component.literal("§e" + message), 8, 172, Ui.TEXT, false);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (SleepMode.sleeping()) {
			SleepMode.wake(minecraft, false); // Any key wakes it.
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	/** Pauses a singleplayer world while it sleeps (unless inventing needs the world). */
	@Override
	public boolean isPauseScreen() {
		return !SleepMode.worldRuns();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !SleepMode.sleeping();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
