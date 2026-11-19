package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.coach.Coach;
import io.github.mudklip.minigamebots.client.coach.Progress;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.skills.SkillDef;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import io.github.mudklip.minigamebots.client.skills.SkillStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Every skill the AI can learn, by category: ready-made ones (editable) and your own. The "+"
 * card at the end of every list adds a skill, from a ready-made one or from scratch.
 */
public final class SkillsScreen extends Screen {
	static final int CARD_W = 132;
	static final int CARD_H = 34;
	private static String category = "All";

	private final @Nullable Screen parent;
	private List<SkillStore.Entry> entries = List.of();
	private int scroll;
	private final List<int[]> tabs = new ArrayList<>();
	/** Stage reached per skill, read once when the screen opens (not every frame). */
	private final java.util.Map<String, int[]> progress = new java.util.HashMap<>();

	public SkillsScreen(@Nullable Screen parent) {
		super(Component.literal("Skills"));
		this.parent = parent;
	}

	private @Nullable Path profileDir() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		return p == null ? null : MinigameBotsClient.instance().profiles().store().dir(p.id);
	}

	@Override
	protected void init() {
		Path dir = profileDir();
		entries = dir == null ? List.of() : SkillStore.list(dir);
		progress.clear();
		if (dir != null) {
			for (SkillStore.Entry e : entries) {
				progress.put(e.id(), SkillStore.progress(dir, e.id()));
			}
		}

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
	}

	private List<String> categories() {
		List<String> out = new ArrayList<>();
		out.add("All");
		out.addAll(SkillLibrary.get().categories);
		return out;
	}

	private List<SkillStore.Entry> shown() {
		return entries.stream().filter(e -> matches(e, category)).toList();
	}

	/** "Custom" also lists every skill you made, whatever its category. */
	private static boolean matches(SkillStore.Entry e, String c) {
		return c.equals("All") || e.def().category.equals(c) || c.equals("Custom") && !e.preset();
	}

	private int cols() {
		return Math.max(1, (width - 16 + 4) / (CARD_W + 4));
	}

	private int gridTop() {
		layoutTabs();
		int bottom = 32;
		for (int[] t : tabs) {
			bottom = Math.max(bottom, t[1]);
		}
		return bottom + 20;
	}

	/** Category pills, wrapping onto a second row when the screen is narrow. */
	private void layoutTabs() {
		tabs.clear();
		int x = 8;
		int y = 32;
		List<String> cats = categories();
		for (int i = 0; i < cats.size(); i++) {
			int w = font.width(tabLabel(cats.get(i))) + 10;
			if (x + w > width - 8) {
				x = 8;
				y += 16;
			}
			tabs.add(new int[] {x, y, w, i});
			x += w + 3;
		}
	}

	private String tabLabel(String c) {
		long n = entries.stream().filter(e -> matches(e, c)).count();
		return c + " §8" + n;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int rows = (shown().size() + 1 + cols() - 1) / cols();
		int visible = Math.max(1, (height - gridTop() - 22) / (CARD_H + 4));
		scroll = Math.max(0, Math.min(Math.max(0, rows - visible), scroll - (int) Math.signum(dy)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (HubTabs.click(minecraft, HubTabs.SKILLS, mx, my, parent)) {
			return true;
		}
		layoutTabs();
		for (int[] t : tabs) {
			if (Ui.inside(mx, my, t[0], t[1], t[2], 13)) {
				category = categories().get(t[3]);
				scroll = 0;
				return true;
			}
		}
		Path dir = profileDir();
		if (dir != null) {
			List<SkillStore.Entry> list = shown();
			for (int i = 0; i <= list.size(); i++) {
				int[] r = cell(i);
				if (r != null && Ui.inside(mx, my, r[0], r[1], CARD_W, CARD_H)) {
					if (i == list.size()) {
						minecraft.gui.setScreen(new AddSkillScreen(this, category.equals("All") ? "Custom" : category));
					} else {
						SkillStore.Entry e = list.get(i);
						minecraft.gui.setScreen(new SkillEditorScreen(this, e.id(), e.def().copy(), e.preset(), e.edited(), false));
					}
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** Position of grid cell i, or null if scrolled out of view. */
	private int @Nullable [] cell(int i) {
		int row = i / cols() - scroll;
		int y = gridTop() + row * (CARD_H + 4);
		if (row < 0 || y + CARD_H > height - 20) {
			return null;
		}
		return new int[] {8 + (i % cols()) * (CARD_W + 4), y};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		HubTabs.draw(g, font, HubTabs.SKILLS, mouseX, mouseY, width - 66);
		Path dir = profileDir();
		if (dir == null) {
			g.text(font, "Pick an AI first: F10 > AI Profiles > Use this AI.", 8, 40, Ui.WARN, false);
			return;
		}
		// Category tabs.
		layoutTabs();
		List<String> cats = categories();
		for (int[] t : tabs) {
			String c = cats.get(t[3]);
			Ui.chip(g, font, tabLabel(c), t[0], t[1], c.equals(category), Ui.inside(mouseX, mouseY, t[0], t[1], t[2], 13), Ui.ACCENT);
		}
		Progress pr = Progress.active();
		List<SkillStore.Entry> list = shown();
		String hoverHelp = null;
		for (int i = 0; i <= list.size(); i++) {
			int[] r = cell(i);
			if (r == null) {
				continue;
			}
			boolean hover = Ui.inside(mouseX, mouseY, r[0], r[1], CARD_W, CARD_H);
			if (i == list.size()) {
				Ui.plusCard(g, font, r[0], r[1], CARD_W, CARD_H, hover, "Add a skill");
				continue;
			}
			SkillStore.Entry e = list.get(i);
			SkillDef d = e.def();
			int[] prog = progress.getOrDefault(e.id(), new int[] {0, 0});
			String stage = d.stages.isEmpty() ? "" : d.stages.get(Math.min(prog[0], d.stages.size() - 1)).name;
			String sub = (prog[0] == 0 && prog[1] == 0 ? "§8new" : "§7" + stage) + (e.preset() ? e.edited() ? " §e✎" : "" : " §b★ yours");
			int xp = pr == null ? 0 : pr.xp.getOrDefault(e.id(), 0);
			boolean coaching = e.id().equals(Coach.skillId());
			Ui.itemCard(g, font, r[0], r[1], CARD_W, CARD_H, d.icon, (coaching ? "§a▶ " : "") + d.name, sub,
					xp > 0 ? Progress.toNext(xp) : 0, hover, coaching);
			if (xp > 0) {
				String lv = "Lv " + Progress.level(xp);
				g.text(font, lv, r[0] + CARD_W - 4 - font.width(lv), r[1] + 16, Ui.GOOD, false);
			}
			if (hover) {
				hoverHelp = d.help;
			}
		}
		int rows = (list.size() + 1 + cols() - 1) / cols();
		int visible = Math.max(1, (height - gridTop() - 22) / (CARD_H + 4));
		int room = width - 16;
		if (scroll + visible < rows) {
			String more = "▼ scroll for more";
			g.text(font, Component.literal("§7" + more), width - 8 - font.width(more), height - 14, Ui.MUTED, false);
			room -= font.width(more) + 8;
		}
		String help = hoverHelp != null && !hoverHelp.isBlank() ? hoverHelp : "Click a skill to change what counts, what it may use and how it learns.";
		g.text(font, Component.literal("§7" + font.plainSubstrByWidth(help, room)), 8, height - 14, Ui.MUTED, false);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
