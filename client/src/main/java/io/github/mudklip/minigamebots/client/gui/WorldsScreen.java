package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.brain.MultiWorldLink;
import io.github.mudklip.minigamebots.client.brain.MultiWorldStats;
import io.github.mudklip.minigamebots.client.coach.Coach;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Training worlds: practice in many copies of an arena at once, and watch all of them live
 * (a mini-map, health and score per world). Click a world to follow its learner up close.
 */
public final class WorldsScreen extends Screen {
	private static final int[] COUNTS = {2, 4, 6, 8, 12};
	private static final String[] OPPONENTS = {"L2", "L3", "L4", "L5", "godly"};
	private static final int CARD_W = 100;
	private static final int CARD_H = 66;
	private static int count = 2;
	private static int opponent = 1;
	private static int team = 0;
	private static final String[] TEAMS = {"1v1", "2v2", "3v3", "4v4"};
	private static boolean fast = true;

	private final @Nullable Screen parent;
	private final List<int[]> chips = new ArrayList<>();
	private @Nullable String message;
	private int scroll;
	private boolean builtRunning;

	public WorldsScreen(@Nullable Screen parent) {
		super(Component.literal("Training worlds"));
		this.parent = parent;
	}

	private static void cmd(String c) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand(c);
		}
	}

	@Override
	protected void init() {
		boolean running = MultiWorldLink.running();
		builtRunning = running;
		boolean grounds = io.github.mudklip.minigamebots.client.TrainingLink.get().isPresent();
		if (!running) {
			addRenderableWidget(Button.builder(Component.literal("§a▶ Start " + COUNTS[count] + " worlds"), b -> {
				var mgb = MinigameBotsClient.instance();
				var p = mgb.profiles().active();
				if (p == null) {
					message = "Pick an AI first (AI Profiles > Use this AI)";
					return;
				}
				if (Coach.running()) {
					Coach.stop("Stopped for training worlds");
				}
				io.github.mudklip.minigamebots.client.profile.Backups.auto(mgb.profiles().store().dir(p.id), "Before training worlds");
				cmd("tg multitrain here " + COUNTS[count] + " " + OPPONENTS[opponent] + (team > 0 ? " " + (team + 1) : ""));
				if (fast && minecraft.getSingleplayerServer() != null) {
					io.github.mudklip.minigamebots.client.brain.FastTraining.start(minecraft);
				}
				message = "Starting... (the AI takes control; F9 stops it)";
				rebuildWidgets();
			}).pos(width / 2 - 70, 112).width(140).build()).active = grounds && minecraft.player != null;
			addRenderableWidget(Button.builder(Component.literal("Fast & light: " + (fast ? "§aon" : "off")), b -> {
				fast = !fast;
				rebuildWidgets();
			}).pos(width / 2 + 74, 112).width(96).tooltip(Tooltip.create(Component.literal("Singleplayer: the worlds run twice as "
					+ "fast, the brain steers them directly (your player stays yours), and the game draws as little as possible "
					+ "(10 FPS, short view) so you can minimise it. Everything goes back when you stop."))).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("§cStop"), b -> {
				cmd("tg multitrain stop");
				MultiWorldLink.watching = -1;
				rebuildWidgets();
			}).pos(width - 52, 26).size(44, 16).build());
		}
		if (MultiWorldLink.watching >= 0) {
			addRenderableWidget(Button.builder(Component.literal("Back to my view"), b -> {
				cmd("tg multitrain watch off");
				MultiWorldLink.watching = -1;
				rebuildWidgets();
			}).pos(width - 148, 26).size(92, 16).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
		if (!grounds) {
			message = "Training worlds need Training Grounds (the training mod) in this world.";
		}
	}

	private int cols() {
		return Math.max(1, (width - 16 + 4) / (CARD_W + 4));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int rows = (MultiWorldStats.worlds().size() + cols() - 1) / cols();
		scroll = Math.max(0, Math.min(Math.max(0, rows - 2), scroll - (int) Math.signum(dy)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (HubTabs.click(minecraft, HubTabs.WORLDS, event.x(), event.y(), parent)) {
			return true;
		}
		for (int[] c : chips) {
			if (Ui.inside(event.x(), event.y(), c[0], c[1], c[2], 13)) {
				if (c[3] == 0) {
					count = c[4];
				} else if (c[3] == 1) {
					opponent = c[4];
				} else {
					team = c[4];
				}
				rebuildWidgets();
				return true;
			}
		}
		if (MultiWorldLink.running()) {
			List<MultiWorldStats.World> ws = MultiWorldStats.worlds();
			for (int i = 0; i < ws.size(); i++) {
				int[] r = cell(i);
				if (r != null && Ui.inside(event.x(), event.y(), r[0], r[1], CARD_W, CARD_H)) {
					cmd("tg multitrain watch " + (ws.get(i).index + 1));
					MultiWorldLink.watching = ws.get(i).index;
					onClose();
					minecraft.gui.setScreen(null);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int @Nullable [] cell(int i) {
		int row = i / cols() - scroll;
		int y = 54 + row * (CARD_H + 4);
		if (row < 0 || y + CARD_H > height - 6) {
			return null;
		}
		return new int[] {8 + (i % cols()) * (CARD_W + 4), y};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (MultiWorldLink.running() != builtRunning) {
			rebuildWidgets(); // Started or stopped: switch between setup and the live view.
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		var p = MinigameBotsClient.instance().profiles().active();
		HubTabs.draw(g, font, HubTabs.WORLDS, mouseX, mouseY, width - 66);
		if (!MultiWorldLink.running()) {
			setupView(g, mouseX, mouseY);
			return;
		}
		chips.clear();
		long s = MultiWorldStats.runningMs() / 1000;
		if (io.github.mudklip.minigamebots.client.brain.FastTraining.on()) {
			g.text(font, Component.literal("§a⚡ fast & light: 2x speed, 10 FPS (minimise the game if you like)"), 8, height - 12, Ui.MUTED, false);
		}
		double wr = MultiWorldStats.recentWinRate();
		String stats = MultiWorldStats.worlds().size() + " worlds · " + MultiWorldStats.rounds() + " rounds · "
				+ String.format("%.1f", MultiWorldStats.roundsPerMinute()) + "/min · " + s / 60 + ":" + String.format("%02d", s % 60);
		g.text(font, Component.literal("§7" + stats), 8, 28, Ui.MUTED, false);
		g.text(font, Component.literal("§7" + (wr < 0 ? "no rounds yet" : "wins (last 40): " + Math.round(wr * 100) + "%")), 8, 39, Ui.MUTED, false);
		if (wr >= 0) {
			Ui.bar(g, 8 + font.width("wins (last 40): 100%") + 6, 41, Math.min(100, width - 150), 5, wr, wr >= 0.5 ? Ui.GOOD : Ui.WARN);
		}
		List<MultiWorldStats.World> ws = MultiWorldStats.worlds();
		long now = System.currentTimeMillis();
		for (int i = 0; i < ws.size(); i++) {
			int[] r = cell(i);
			if (r == null) {
				continue;
			}
			drawWorld(g, ws.get(i), r[0], r[1], Ui.inside(mouseX, mouseY, r[0], r[1], CARD_W, CARD_H), now);
		}
		if (ws.isEmpty()) {
			g.text(font, "Waiting for the worlds to report...", 8, 56, Ui.MUTED, false);
		}
	}

	private void drawWorld(GuiGraphicsExtractor g, MultiWorldStats.World w, int x, int y, boolean hover, long now) {
		boolean watched = w.index == MultiWorldLink.watching;
		int accent = now - w.lastResultAt < 1500 ? (w.lastResult > 0 ? Ui.GOOD : 0xFFE05A5A)
				: now - w.lastHit < 300 ? Ui.WARN : watched ? Ui.ACCENT : Ui.BORDER;
		Ui.card(g, x, y, CARD_W, CARD_H, watched ? Ui.CARD_SELECTED : hover ? Ui.CARD_HOVER : Ui.CARD, accent);
		if (now - w.lastResultAt < 1500) {
			g.outline(x, y, CARD_W, CARD_H, accent);
		}
		g.text(font, Component.literal("§f" + (w.index + 1) + (watched ? " §b👁" : "")), x + 5, y + 4, Ui.TEXT, false);
		String score = "§a" + w.wins + "§7-§c" + w.losses;
		g.text(font, Component.literal(score), x + CARD_W - 5 - font.width(w.wins + "-" + w.losses), y + 4, Ui.TEXT, false);
		// Mini-map: the learner in the middle facing up, the opponent where it is relative to it.
		int mx = x + 5;
		int my = y + 15;
		int ms = 34;
		g.fill(mx, my, mx + ms, my + ms, 0x60000000);
		g.outline(mx, my, ms, ms, 0x40FFFFFF);
		int cx = mx + ms / 2;
		int cy = my + ms / 2;
		double yr = Math.toRadians(w.yaw);
		double right = w.oppDx * -Math.cos(yr) + w.oppDz * -Math.sin(yr);
		double fwd = w.oppDx * -Math.sin(yr) + w.oppDz * Math.cos(yr);
		double scale = 1.8;
		int ox = (int) Math.round(Math.max(-15, Math.min(15, right * scale)));
		int oy = (int) Math.round(Math.max(-15, Math.min(15, -fwd * scale)));
		g.fill(cx, cy - 4, cx + 1, cy, 0x806EE07A); // Facing.
		g.fill(cx - 1, cy - 1, cx + 2, cy + 2, Ui.GOOD);
		g.fill(cx + ox - 1, cy + oy - 1, cx + ox + 2, cy + oy + 2, 0xFFE05A5A);
		double dist = Math.hypot(w.oppDx, w.oppDz);
		g.text(font, Component.literal(String.format("§8%.1fm", dist)), mx, my + ms + 2, Ui.MUTED, false);
		// Health bars.
		int bx = mx + ms + 6;
		int bw = CARD_W - (bx - x) - 5;
		g.text(font, Component.literal("§7AI"), bx, my, Ui.MUTED, false);
		Ui.bar(g, bx, my + 9, bw, 5, w.hp / Math.max(1, w.maxHp), now - w.lastHurt < 300 ? 0xFFE05A5A : Ui.GOOD);
		g.text(font, Component.literal("§7Foe"), bx, my + 17, Ui.MUTED, false);
		Ui.bar(g, bx, my + 26, bw, 5, w.oppHp / Math.max(1, w.oppMaxHp), 0xFFE05A5A);
		if (hover) {
			g.text(font, Component.literal("§bclick: watch"), bx, my + 35, Ui.ACCENT, false);
		}
	}

	private void setupView(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		chips.clear();
		int y = 32;
		for (var line : font.split(Component.literal("§7Practice in many copies of an arena at the same time: each copy has its own "
				+ "learner body steered by your AI and its own opponent. More worlds = more practice per minute. Watch them all here, "
				+ "or click one to follow it."), width - 16)) {
			g.text(font, line, 8, y, Ui.MUTED, false);
			y += 10;
		}
		y += 6;
		int[] rowY = {y, y + 18};
		g.text(font, Component.literal("§fWorlds:"), 8, rowY[0] + 3, Ui.TEXT, false);
		g.text(font, Component.literal("§fOpponent:"), 8, rowY[1] + 3, Ui.TEXT, false);
		for (int row = 0; row < 2; row++) {
			int x = 64;
			int n = row == 0 ? COUNTS.length : OPPONENTS.length;
			for (int i = 0; i < n; i++) {
				String t = row == 0 ? String.valueOf(COUNTS[i]) : OPPONENTS[i];
				boolean on = row == 0 ? i == count : i == opponent;
				int w = font.width(t) + 14;
				Ui.chip(g, font, t, x, rowY[row], w, on, Ui.inside(mouseX, mouseY, x, rowY[row], w, 13), Ui.ACCENT);
				chips.add(new int[] {x, rowY[row], w, row, i});
				x += w + 3;
			}
			if (row == 0) {
				// Teams: copies of the same AI side by side, each seeing only what it sees (no shared link).
				x += 14;
				g.text(font, Component.literal("§fTeams:"), x, rowY[0] + 3, Ui.TEXT, false);
				x += font.width("Teams:") + 6;
				for (int i = 0; i < TEAMS.length; i++) {
					int w = font.width(TEAMS[i]) + 14;
					Ui.chip(g, font, TEAMS[i], x, rowY[0], w, i == team, Ui.inside(mouseX, mouseY, x, rowY[0], w, 13), Ui.ACCENT);
					chips.add(new int[] {x, rowY[0], w, 2, i});
					x += w + 3;
				}
			}
		}
		g.text(font, Component.literal("§7Tip: 4-6 worlds suit most PCs. The arenas go when you stop."),
				8, 136, Ui.MUTED, false);
		if (message != null) {
			g.text(font, Component.literal(font.plainSubstrByWidth(message, width - 16)), 8, 150, Ui.WARN, false);
		}
	}

	/** A live view: the game (and the worlds) keep running behind it. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
