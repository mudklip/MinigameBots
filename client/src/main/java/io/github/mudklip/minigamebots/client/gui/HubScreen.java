package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.coach.Coach;
import io.github.mudklip.minigamebots.client.coach.Progress;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The Training Hub: one place to make an AI better. Pick what to improve and how long you have;
 * the Coach combines teacher demos, training, practice and exams by itself. Levels show effort,
 * ratings show measured skill, achievements mark milestones. The detailed screens are under
 * "Advanced".
 */
public final class HubScreen extends Screen {
	private static final int[] BUDGETS = {30, 120, 480};
	private static final String[] BUDGET_LABELS = {"30 min", "2 hours", "Overnight"};
	private static final Map<String, Item> BADGES = Map.of("first_training", Items.BOOK, "first_hit", Items.WOODEN_SWORD,
			"first_crit", Items.NETHER_STAR, "first_kill", Items.SKELETON_SKULL, "beat_l5", Items.IRON_SWORD,
			"beat_godly", Items.NETHERITE_SWORD, "wins_100", Items.GOLD_BLOCK, "aim_80", Items.TARGET,
			"coach_plan", Items.WRITABLE_BOOK, "overnight", Items.CLOCK);

	private final @Nullable Screen parent;
	private static Coach.Goal selected = Coach.Goal.PVP;
	private static int budget = 1;
	private @Nullable String message;
	private int cardsX = 8;
	private int cardsY = 30;
	private int cardW = 150;
	private static final int CARD_H = 30;

	public HubScreen(@Nullable Screen parent) {
		super(Component.literal("Training Hub"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		cardW = Math.min(160, width / 3);
		int rx = cardsX + cardW + 8;
		int rw = width - rx - 8;
		addRenderableWidget(Button.builder(Component.literal("Advanced..."), b -> {
			ProfileManifest p = MinigameBotsClient.instance().profiles().active();
			if (p != null) {
				minecraft.gui.setScreen(new TrainingScreen(this, p));
			}
		}).pos(width - 140, 6).width(70).tooltip(Tooltip.create(Component.literal("The detailed screens: training, practice, "
				+ "exams, versions, realism, teacher demos"))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 66, 6).width(58).build());
		int by = cardsY + 40;
		for (int i = 0; i < BUDGETS.length; i++) {
			int idx = i;
			addRenderableWidget(Button.builder(Component.literal((budget == i ? "§e" : "") + BUDGET_LABELS[i]), b -> {
				budget = idx;
				rebuildWidgets();
			}).pos(rx + 6 + i * 64, by).width(60).build()).active = !Coach.running();
		}
		addRenderableWidget(Button.builder(Component.literal(Coach.running() ? "§cStop coaching" : "§a▶ Start coaching"), b -> {
			if (Coach.running()) {
				Coach.stop("Stopped by you");
				message = null;
			} else {
				message = Coach.start(selected, BUDGETS[budget]);
				if (message == null) {
					minecraft.gui.setScreen(null); // Back to the game: the AI can't play behind a menu.
					return;
				}
			}
			rebuildWidgets();
		}).pos(rx + 6, by + 24).width(Math.min(196, rw - 12)).build());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < Coach.Goal.values().length; i++) {
			if (!Coach.running() && Ui.inside(event.x(), event.y(), cardsX, cardsY + i * (CARD_H + 4), cardW, CARD_H)) {
				selected = Coach.Goal.values()[i];
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// Panels first, so the buttons draw on top of them.
		int prx = cardsX + cardW + 8;
		Ui.panel(g, prx, cardsY, width - prx - 8, height - cardsY - 44);
		super.extractRenderState(g, mouseX, mouseY, a);
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		Progress pr = Progress.active();
		g.text(font, Component.literal("§lTraining Hub§r  " + (p == null ? "§cno AI selected" : "§7" + p.name)), 8, 10, Ui.TEXT, false);
		if (p == null || pr == null) {
			g.text(font, "Pick an AI first: F10 > AI Profiles > Use this AI.", 8, 40, Ui.WARN, false);
			return;
		}
		// Goal cards.
		Coach.Goal active = Coach.running() ? Coach.goal() : selected;
		for (int i = 0; i < Coach.Goal.values().length; i++) {
			Coach.Goal goal = Coach.Goal.values()[i];
			int y = cardsY + i * (CARD_H + 4);
			boolean hover = Ui.inside(mouseX, mouseY, cardsX, y, cardW, CARD_H);
			Ui.card(g, cardsX, y, cardW, CARD_H, goal == active ? Ui.CARD_SELECTED : hover ? Ui.CARD_HOVER : Ui.CARD,
					goal == active ? Ui.ACCENT : Ui.BORDER);
			g.item(new ItemStack(goal.icon), cardsX + 6, y + 7);
			int xp = pr.xp.getOrDefault(goal.key(), 0);
			g.text(font, goal.label, cardsX + 28, y + 4, Ui.TEXT, false);
			String lv = "Lv " + Progress.level(xp);
			g.text(font, lv, cardsX + cardW - 6 - font.width(lv), y + 4, Ui.GOOD, false);
			Ui.bar(g, cardsX + 28, y + 16, cardW - 36, 6, Progress.toNext(xp), Ui.ACCENT);
			Double r = pr.rating.get(goal == Coach.Goal.PVP ? "pvp" : goal == Coach.Goal.AIM ? "aim" : "");
			if (r != null) {
				g.text(font, Component.literal(String.format("§7%s %.0f", goal == Coach.Goal.PVP ? "win%" : "score", r)),
						cardsX + 28, y + 23, Ui.MUTED, false);
			}
		}
		// Right panel: the goal and its plan.
		int rx = cardsX + cardW + 8;
		int rw = width - rx - 8;
		Ui.header(g, font, active.label, rx + 6, cardsY + 6);
		g.text(font, font.plainSubstrByWidth(active.help, rw - 12), rx + 6, cardsY + 22, Ui.MUTED, false);
		int y = cardsY + 90;
		if (Coach.running()) {
			g.text(font, Component.literal("§aCoaching§r: " + Coach.minutesLeft() + " min left, cycle " + (Coach.cycles() + 1)
					+ "  §7(F9 stops the AI; this screen stops the plan)"), rx + 6, y, Ui.TEXT, false);
			y += 12;
		} else {
			g.text(font, font.plainSubstrByWidth("Pick a goal and a time, then Start: it runs everything by itself.", rw - 12),
					rx + 6, y, Ui.MUTED, false);
			y += 12;
		}
		// Show from the step before the current one, so what's happening now is always visible.
		var all = Coach.steps();
		int first = 0;
		for (int k = 0; k < all.size(); k++) {
			if (all.get(k).state.equals("running") || all.get(k).state.equals("waiting")) {
				first = Math.max(0, k - 1);
				break;
			}
		}
		if (first > 0) {
			g.text(font, Component.literal("§8  ... " + first + " step(s) done"), rx + 8, y, Ui.MUTED, false);
			y += 10;
		}
		for (Coach.Step s : all.subList(first, all.size())) {
			if (y > height - 70) {
				break;
			}
			String icon = switch (s.state) {
				case "done" -> "§a✔";
				case "running" -> "§e▶";
				case "skipped" -> "§8–";
				default -> "§7○";
			};
			g.text(font, Component.literal(icon + " §r" + font.plainSubstrByWidth(s.label, rw - 30)), rx + 8, y,
					s.state.equals("skipped") ? Ui.MUTED : Ui.TEXT, false);
			y += 11;
		}
		List<String> log = Coach.running() ? List.of() : !Coach.log.isEmpty() ? Coach.log : pr.log;
		for (int i = 0; i < log.size() && y < height - 58; i++, y += 10) {
			g.text(font, font.plainSubstrByWidth(log.get(i), rw - 16), rx + 8, y, Ui.MUTED, false);
		}
		if (message != null) {
			g.text(font, font.plainSubstrByWidth(message, rw - 12), rx + 6, height - 56, Ui.WARN, false);
		}
		// Achievements.
		int ax = 8;
		int ay = height - 30;
		g.text(font, Component.literal("§7Achievements " + pr.achievements.size() + "/" + Progress.ACHIEVEMENTS.size()), ax, ay - 10,
				Ui.MUTED, false);
		String hovered = null;
		int i = 0;
		for (var e : Progress.ACHIEVEMENTS.entrySet()) {
			int x = ax + i * 22;
			boolean got = pr.achievements.contains(e.getKey());
			Ui.card(g, x, ay, 20, 20, got ? 0xE0303A28 : Ui.CARD, got ? Ui.GOOD : Ui.BORDER);
			g.item(new ItemStack(BADGES.getOrDefault(e.getKey(), Items.PAPER)), x + 2, ay + 2);
			if (!got) {
				g.fill(x + 1, ay + 1, x + 19, ay + 19, 0xA0101418);
			}
			if (Ui.inside(mouseX, mouseY, x, ay, 20, 20)) {
				hovered = (got ? "§a" : "§7Locked: ") + e.getValue();
			}
			i++;
		}
		if (hovered != null) {
			g.text(font, Component.literal(hovered), ax + i * 22 + 6, ay + 6, Ui.TEXT, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
