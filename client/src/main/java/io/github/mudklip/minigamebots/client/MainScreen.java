package io.github.mudklip.minigamebots.client;

import io.github.mudklip.minigamebots.client.brain.BrainManager;
import io.github.mudklip.minigamebots.client.coach.Progress;
import io.github.mudklip.minigamebots.client.coach.Watchdog;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.gui.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard: your AI at a glance (name, level, brain, progress), the two main actions, and a
 * tile for everything else with a line saying what it's for.
 */
public final class MainScreen extends Screen {
	private record Tile(String icon, String title, String help, boolean needsWorld, Supplier<Screen> open) {
	}

	private final @Nullable Screen parent;
	private final List<Tile> tiles = new ArrayList<>();
	private final List<int[]> tileBoxes = new ArrayList<>();
	private Watchdog.@Nullable Verdict verdict;

	public MainScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.menu.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		tiles.clear();
		tiles.add(new Tile("minecraft:player_head", "AI Profiles", "Edit your AIs", false,
				() -> new io.github.mudklip.minigamebots.client.gui.ProfilesScreen(this)));
		tiles.add(new Tile("minecraft:redstone", "Brain", "Set up, start", false,
				() -> new io.github.mudklip.minigamebots.client.gui.BrainScreen(this)));
		tiles.add(new Tile("minecraft:compass", "Goals", "Its plan in play", true,
				() -> new io.github.mudklip.minigamebots.client.gui.GoalsScreen(this)));
		tiles.add(new Tile("minecraft:book", "Memory", "Places, people", true,
				() -> new io.github.mudklip.minigamebots.client.gui.MemoryScreen(this)));
		tiles.add(new Tile("minecraft:spyglass", "Recordings", "Record, review", false,
				() -> new io.github.mudklip.minigamebots.client.gui.RecorderScreen(this)));
		tiles.add(new Tile("minecraft:red_bed", "Sleep", "Learns while away", false,
				() -> new io.github.mudklip.minigamebots.client.sleep.SleepScreen(this)));
		tiles.add(new Tile("minecraft:painting", "Video Lab", "Learn from video", false,
				() -> new io.github.mudklip.minigamebots.client.gui.VideoLabScreen(this)));
		tiles.add(new Tile("minecraft:tnt", "Inventions", "Traps, shelters", false,
				() -> new io.github.mudklip.minigamebots.client.gui.InventionsScreen(this)));
		tiles.add(new Tile("minecraft:written_book", "Guide", "How it works", false,
				() -> new io.github.mudklip.minigamebots.client.gui.GuideScreen(this, "start")));
		tiles.add(new Tile("minecraft:comparator", "Settings", "Play, safety", false,
				() -> new io.github.mudklip.minigamebots.client.gui.SettingsScreen(this)));

		var mgb = MinigameBotsClient.instance();
		var active = mgb.profiles().active();
		verdict = active == null ? null : Watchdog.check(mgb.profiles().store().dir(active.id));
		boolean inWorld = minecraft.player != null;
		int lw = leftWidth();
		int y = 112;
		addRenderableWidget(Button.builder(Component.literal("§e★ Training Hub"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.HubScreen(this))).pos(10, y).size(lw, 20)
				.tooltip(Tooltip.create(Component.literal("Plans, skills, training worlds and backups: everything for making your AI better.")))
				.build());
		Button control = addRenderableWidget(Button.builder(Component.literal("§a▶ Give control to AI"), b -> {
			onClose();
			AgentControl.get().start(minecraft, io.github.mudklip.minigamebots.client.control.ActionSource.IDLE);
		}).pos(10, y + 24).size(lw, 20).tooltip(Tooltip.create(Component.literal(inWorld
				? "Let it play. F8 takes control back, F9 stops it." : "Join a world first."))).build());
		control.active = inWorld;
		if (verdict != null && (verdict.state() == Watchdog.State.FLAT || verdict.state() == Watchdog.State.DROPPING)) {
			addRenderableWidget(Button.builder(Component.literal("§6⚠ " + verdict.state().label + ": see why"),
					b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.ProgressScreen(this, active))).pos(10, y + 48)
					.size(lw, 20).tooltip(Tooltip.create(Component.literal(verdict.why()))).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
		if (!MgbConfig.get().guideHintShown) {
			// Once: point new players at the guide, then never again.
			MgbConfig.get().guideHintShown = true;
			MgbConfig.HANDLER.save();
			io.github.mudklip.minigamebots.client.overlay.Overlay.toast(new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(),
					Component.translatable("minigamebots.guide.hint_title"), Component.translatable("minigamebots.guide.hint"));
		}
	}

	private int leftWidth() {
		return Math.min(160, Math.max(120, width / 3));
	}

	private boolean available(Tile t) {
		if (!t.needsWorld()) {
			return true;
		}
		return minecraft.player != null && (!t.title().equals("Memory") || MinigameBotsClient.instance().profiles().active() != null);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lMinigameBots"), 10, 10, Ui.TEXT, false);
		var mgb = MinigameBotsClient.instance();
		var active = mgb.profiles().active();
		int lw = leftWidth();
		// The AI card.
		Ui.card(g, 10, 26, lw, 80, Ui.CARD, Ui.ACCENT);
		g.item(Ui.stack("minecraft:player_head"), 16, 32);
		g.text(font, Component.literal("§l" + font.plainSubstrByWidth(active == null ? "No AI chosen" : active.name, lw - 34)), 36, 32,
				Ui.TEXT, false);
		var pr = Progress.active();
		int xp = pr == null ? 0 : pr.xp.values().stream().mapToInt(Integer::intValue).sum();
		g.text(font, active == null ? "AI Profiles: make or pick one" : "Level " + Progress.level(xp) + "  ·  " + xp + " XP", 36, 43,
				Ui.MUTED, false);
		Ui.bar(g, 16, 56, lw - 12, 5, active == null ? 0 : Progress.toNext(xp), Ui.ACCENT);
		// Status chips: brain, progress.
		BrainManager.Status st = mgb.brain().status();
		boolean installed = mgb.brain().runtime().installed();
		String brain = !installed ? "Brain: not set up" : st == BrainManager.Status.RUNNING ? "Brain: running"
				: st == BrainManager.Status.STARTING ? "Brain: starting" : "Brain: ready";
		int bc = !installed ? 0xFFFF6E6E : st == BrainManager.Status.RUNNING ? Ui.GOOD : Ui.ACCENT;
		chip(g, 16, 68, brain, bc);
		Watchdog.Verdict v = verdict;
		String prog = v == null ? "-" : switch (v.state()) {
			case NO_DATA -> "no exams yet";
			case IMPROVING -> "improving";
			case FLAT -> "not improving";
			case DROPPING -> "getting worse";
		};
		chip(g, 16, 84, font.plainSubstrByWidth("Progress: " + prog, lw - 24),
				v == null ? Ui.MUTED : v.state().color);
		// Tiles.
		tileBoxes.clear();
		int gx = 10 + lw + 10, gw = width - gx - 10;
		int cols = gw >= 330 ? 3 : 2;
		int tw = (gw - (cols - 1) * 6) / cols, th = 34;
		for (int i = 0; i < tiles.size(); i++) {
			Tile t = tiles.get(i);
			int tx = gx + (i % cols) * (tw + 6), ty = 26 + (i / cols) * (th + 6);
			boolean ok = available(t);
			boolean hover = ok && Ui.inside(mouseX, mouseY, tx, ty, tw, th);
			Ui.card(g, tx, ty, tw, th, hover ? Ui.CARD_HOVER : Ui.CARD, ok ? (hover ? Ui.ACCENT : Ui.BORDER) : 0xFF2A3038);
			g.item(Ui.stack(t.icon()), tx + 6, ty + 9);
			g.text(font, Component.literal("§l" + t.title()), tx + 28, ty + 6, ok ? Ui.TEXT : 0xFF6A7380, false);
			g.text(font, font.plainSubstrByWidth(ok ? t.help() : "Join a world first", tw - 32), tx + 28, ty + 19, Ui.MUTED, false);
			tileBoxes.add(new int[] {tx, ty, tw, th});
		}
	}

	private void chip(GuiGraphicsExtractor g, int x, int y, String text, int color) {
		int w = font.width(text) + 12;
		g.fill(x, y, x + w, y + 12, 0x40000000 | (color & 0xFFFFFF));
		g.outline(x, y, w, 12, color);
		g.fill(x + 3, y + 4, x + 6, y + 7, color);
		g.text(font, text, x + 8, y + 2, Ui.TEXT, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < tileBoxes.size() && i < tiles.size(); i++) {
			int[] b = tileBoxes.get(i);
			if (Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3]) && available(tiles.get(i))) {
				minecraft.gui.setScreen(tiles.get(i).open().get());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
