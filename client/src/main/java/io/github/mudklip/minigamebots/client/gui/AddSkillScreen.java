package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.skills.SkillDef;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Adding a skill: start from scratch, or from a copy of any ready-made skill. */
public final class AddSkillScreen extends Screen {
	private final Screen parent;
	private final String category;
	private final List<Map.Entry<String, SkillDef>> presets = new ArrayList<>();
	private int scroll;

	public AddSkillScreen(Screen parent, String category) {
		super(Component.literal("Add a skill"));
		this.parent = parent;
		this.category = category;
		var lib = SkillLibrary.get();
		// The chosen category's presets first.
		lib.skills.entrySet().stream().filter(e -> e.getValue().category.equals(category)).forEach(presets::add);
		lib.skills.entrySet().stream().filter(e -> !e.getValue().category.equals(category)).forEach(presets::add);
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).pos(width - 60, 6).width(52).build());
	}

	private int cols() {
		return Math.max(1, (width - 16 + 4) / (SkillsScreen.CARD_W + 4));
	}

	private int @org.jspecify.annotations.Nullable [] cell(int i) {
		int row = i / cols() - scroll;
		int y = 44 + row * (SkillsScreen.CARD_H + 4);
		if (row < 0 || y + SkillsScreen.CARD_H > height - 8) {
			return null;
		}
		return new int[] {8 + (i % cols()) * (SkillsScreen.CARD_W + 4), y};
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int rows = (presets.size() + 1 + cols() - 1) / cols();
		scroll = Math.max(0, Math.min(Math.max(0, rows - 3), scroll - (int) Math.signum(dy)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i <= presets.size(); i++) {
			int[] r = cell(i);
			if (r != null && Ui.inside(event.x(), event.y(), r[0], r[1], SkillsScreen.CARD_W, SkillsScreen.CARD_H)) {
				SkillDef d;
				if (i == 0) {
					d = SkillDef.blank();
					d.category = category;
				} else {
					d = presets.get(i - 1).getValue().copy();
					d.name = "My " + d.name.substring(0, 1).toLowerCase() + d.name.substring(1);
				}
				minecraft.gui.setScreen(new SkillEditorScreen(parent, null, d, false, false, true));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lAdd a skill"), 8, 10, Ui.TEXT, false);
		g.text(font, Component.literal("§7Start from scratch, or copy a ready-made skill and change it. Category: §f" + category), 8, 28, Ui.MUTED, false);
		for (int i = 0; i <= presets.size(); i++) {
			int[] r = cell(i);
			if (r == null) {
				continue;
			}
			boolean hover = Ui.inside(mouseX, mouseY, r[0], r[1], SkillsScreen.CARD_W, SkillsScreen.CARD_H);
			if (i == 0) {
				Ui.itemCard(g, font, r[0], r[1], SkillsScreen.CARD_W, SkillsScreen.CARD_H, "minecraft:writable_book", "§lFrom scratch",
						"Your own rewards and rules", -1, hover, true);
			} else {
				SkillDef d = presets.get(i - 1).getValue();
				Ui.itemCard(g, font, r[0], r[1], SkillsScreen.CARD_W, SkillsScreen.CARD_H, d.icon, d.name, "§7" + d.category, -1, hover, false);
			}
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
