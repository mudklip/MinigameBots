package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.practice.Objective;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Choose what to reward: a behaviour (measured every moment) or a game event. */
public final class PickRuleScreen extends Screen {
	private static final int ROW = 12;
	private final Screen parent;
	private final Consumer<Objective.Rule> onPick;
	private EditBox search;
	private final List<String[]> rows = new ArrayList<>(); // {event, label, kind}
	private int scroll;

	public PickRuleScreen(Screen parent, Consumer<Objective.Rule> onPick) {
		super(Component.literal("Add"));
		this.parent = parent;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		search = addRenderableWidget(new EditBox(font, 8, 24, Math.min(220, width - 80), 18, Component.literal("Search")));
		search.setHint(Component.literal("§7Search..."));
		search.setResponder(t -> filter());
		setInitialFocus(search);
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).pos(width - 60, 6).width(52).build());
		filter();
	}

	private void filter() {
		String q = search.getValue().toLowerCase(Locale.ROOT);
		rows.clear();
		SkillLibrary lib = SkillLibrary.get();
		for (var e : lib.behaviours.entrySet()) {
			if (e.getValue().toLowerCase(Locale.ROOT).contains(q)) {
				rows.add(new String[] {"b:" + e.getKey(), e.getValue(), "behaviour"});
			}
		}
		for (var e : lib.events.entrySet()) {
			if (e.getValue().toLowerCase(Locale.ROOT).contains(q)) {
				rows.add(new String[] {e.getKey(), e.getValue(), "event"});
			}
		}
		scroll = 0;
	}

	private int visible() {
		return Math.max(1, (height - 60) / ROW);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible()), scroll - (int) Math.signum(dy) * 3));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int i = (int) ((event.y() - 48) / ROW) + scroll;
		if (event.y() >= 48 && event.x() < width - 8 && i >= scroll && i < Math.min(rows.size(), scroll + visible())) {
			String[] r = rows.get(i);
			Objective.Rule rule = new Objective.Rule();
			// "event?key=value&key2=value2": a rule with conditions; a value "[block]" or "[item]" is
			// chosen with the item picker first.
			String key = r[0];
			rule.where = new java.util.LinkedHashMap<>();
			String pick = null;
			if (key.contains("?")) {
				for (String kv : key.substring(key.indexOf('?') + 1).split("&")) {
					String[] p2 = kv.split("=", 2);
					if (p2.length < 2) {
						continue;
					}
					if (p2[1].startsWith("[")) {
						pick = p2[0];
					} else {
						rule.where.put(p2[0], p2[1].equals("true") ? Boolean.TRUE : p2[1].equals("false") ? Boolean.FALSE : p2[1]);
					}
				}
				key = key.substring(0, key.indexOf('?'));
			}
			rule.event = key;
			rule.note = r[1].toLowerCase(Locale.ROOT);
			boolean behaviour = r[2].equals("behaviour");
			rule.per = behaviour ? "units" : "event";
			rule.value = behaviour ? 0.01 : 1;
			if (pick != null) {
				String field = pick;
				minecraft.gui.setScreen(new ItemPickerScreen(parent, id -> {
					rule.where.put(field, id);
					rule.note = rule.note.replace("…", "") + " " + id.replace("minecraft:", "").replace('_', ' ');
					onPick.accept(rule);
				}));
				return true;
			}
			onPick.accept(rule);
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Ui.panel(g, 6, 46, width - 12, visible() * ROW + 4);
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lWhat should count?§r  §b◆§7 behaviour (every moment)  §e●§7 event"), 8, 10,
				Ui.TEXT, false);
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible()); i++) {
			String[] r = rows.get(i);
			int y = 48 + (i - scroll) * ROW;
			boolean hover = Ui.inside(mouseX, mouseY, 8, y, width - 16, ROW);
			if (hover) {
				g.fill(8, y, width - 8, y + ROW, Ui.CARD_HOVER);
			}
			g.text(font, Component.literal((r[2].equals("behaviour") ? "§b◆ " : "§e● ") + "§f" + r[1]), 12, y + 2, Ui.TEXT, false);
			g.text(font, Component.literal("§8" + r[2]), width - 14 - font.width(r[2]), y + 2, Ui.MUTED, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
