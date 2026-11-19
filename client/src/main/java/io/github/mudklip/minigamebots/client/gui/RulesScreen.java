package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.practice.Objective;
import io.github.mudklip.minigamebots.client.practice.ObjectiveStore;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A skill's rewards (or a stage's hints): switch each on/off, make it stronger or weaker, add or remove. */
public final class RulesScreen extends Screen {
	private static final int ROW = 22;
	private final SkillEditorScreen parent;
	private final String title;
	private final List<Objective.Rule> rules;
	private final boolean hints;
	private int scroll;

	public RulesScreen(SkillEditorScreen parent, String title, List<Objective.Rule> rules, boolean hints) {
		super(Component.literal(title));
		this.parent = parent;
		this.title = title;
		this.rules = rules;
		this.hints = hints;
	}

	/** The condition field a rule's target goes in for its event, and the picker for it (null = no target). */
	static String @org.jspecify.annotations.Nullable [] targetField(String event) {
		return switch (event == null ? "" : event) {
			case "block_broken", "block_placed" -> new String[] {"block", "BLOCK"};
			case "kill", "damage_dealt", "attack", "tg_kill" -> new String[] {"target", "ENTITY"};
			case "item_gained", "item_lost" -> new String[] {"item", "ITEM"};
			default -> null;
		};
	}

	static String targetLabel(Objective.Rule r) {
		String[] f = targetField(r.event);
		if (f == null) {
			return "";
		}
		Object v = r.where == null ? null : r.where.get(f[0]);
		if (v instanceof java.util.Map<?, ?> m && m.get("contains") != null) {
			return "any " + String.valueOf(m.get("contains")).replace("minecraft:", "").replace('_', ' ').trim();
		}
		return v == null ? (f[1].equals("BLOCK") ? "any block" : f[1].equals("ENTITY") ? "anything" : "any item")
				: String.valueOf(v).replace("minecraft:", "").replace('_', ' ');
	}

	/** Opens the right picker and puts the choice into the rule's condition. */
	static void pickTarget(net.minecraft.client.Minecraft mc, Screen back, Objective.Rule r, Runnable after) {
		String[] f = targetField(r.event);
		if (f == null) {
			return;
		}
		mc.gui.setScreen(new ItemPickerScreen(back, ItemPickerScreen.Mode.valueOf(f[1]), id -> {
			if (r.where == null) {
				r.where = new java.util.LinkedHashMap<>();
			}
			r.where.put(f[0], id);
			after.run();
		}));
	}

	static String fmt(double v) {
		double a = Math.abs(v);
		String s = a >= 10 ? String.format("%.0f", v) : a >= 1 ? String.format("%.1f", v) : a >= 0.01 ? String.format("%.2f", v)
				: String.format("%.3f", v);
		return s.replaceAll("\\.?0+$", "");
	}

	private int visible() {
		return Math.max(1, (height - 70) / ROW);
	}

	@Override
	protected void init() {
		int w = Math.min(460, width - 16);
		int x0 = (width - w) / 2;
		scroll = Math.max(0, Math.min(scroll, rules.size() - visible()));
		for (int i = scroll; i < Math.min(rules.size(), scroll + visible()); i++) {
			Objective.Rule r = rules.get(i);
			int y = 34 + (i - scroll) * ROW;
			addRenderableWidget(Button.builder(Component.literal(r.off ? "§8✖" : "§a✔"), b -> {
				r.off = !r.off;
				parent.changed();
				rebuildWidgets();
			}).pos(x0, y).width(20).tooltip(Tooltip.create(Component.literal(r.off ? "Off: click to switch on" : "On: click to switch off"))).build());
			int bx = x0 + w - 104;
			if (targetField(r.event) != null) {
				addRenderableWidget(Button.builder(Component.literal("§b⌖ §f" + targetLabel(r)), b -> pickTarget(minecraft, this, r,
						parent::changed)).pos(bx - 96, y).width(92).tooltip(Tooltip.create(Component.literal("Which "
						+ (targetField(r.event)[1].equals("BLOCK") ? "block" : targetField(r.event)[1].equals("ENTITY") ? "creature" : "item")
						+ " counts. Click to pick one; right now: " + targetLabel(r)))).build());
			}
			addRenderableWidget(Button.builder(Component.literal("−"), b -> scale(r, 1 / 1.5)).pos(bx, y).width(16)
					.tooltip(Tooltip.create(Component.literal("Weaker"))).build());
			addRenderableWidget(Button.builder(Component.literal((r.value >= 0 ? "§a+" : "§c") + fmt(r.value)), b -> {
				r.value = -r.value;
				parent.changed();
				rebuildWidgets();
			}).pos(bx + 18, y).width(46).tooltip(Tooltip.create(Component.literal("Green = reward, red = penalty. Click to flip."
					+ ("units".equals(r.per) || !"event".equals(r.per) ? " Counted per " + r.per + "." : "")))).build());
			addRenderableWidget(Button.builder(Component.literal("+"), b -> scale(r, 1.5)).pos(bx + 66, y).width(16)
					.tooltip(Tooltip.create(Component.literal("Stronger"))).build());
			addRenderableWidget(Button.builder(Component.literal("§c✖"), b -> {
				rules.remove(r);
				parent.changed();
				rebuildWidgets();
			}).pos(bx + 86, y).width(18).tooltip(Tooltip.create(Component.literal("Remove"))).build());
		}
		int by = height - 26;
		addRenderableWidget(Button.builder(Component.literal(hints ? "§a+ Add hint" : "§a+ Add reward"), b -> minecraft.gui.setScreen(
				new PickRuleScreen(this, rule -> {
					rules.add(rule);
					parent.changed();
				}))).pos(x0, by).width(90).build());
		addRenderableWidget(Button.builder(Component.literal("Every detail..."), b -> details()).pos(x0 + 94, by).width(90)
				.tooltip(Tooltip.create(Component.literal("The full rule editor: conditions, caps, cooldowns, importance, forgiving..."))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(x0 + w - 60, by).width(60).build());
	}

	private void scale(Objective.Rule r, double f) {
		r.value = Math.round(r.value * f * 10000) / 10000.0;
		parent.changed();
		rebuildWidgets();
	}

	/** Opens the full Objective editor on these rules; what it saves comes back here. */
	private void details() {
		var mgb = MinigameBotsClient.instance();
		var p = mgb.profiles().active();
		if (p == null) {
			return;
		}
		Objective o = new Objective();
		o.name = title;
		o.rules = new java.util.ArrayList<>(rules);
		String tmp = "_skill_edit";
		minecraft.gui.setScreen(new ObjectiveEditorScreen(this, p, tmp, o, saved -> {
			var dir = mgb.profiles().store().dir(p.id);
			Objective back = saved == null ? null : ObjectiveStore.load(dir, tmp);
			if (back != null) {
				rules.clear();
				rules.addAll(back.rules);
				parent.changed();
			}
			try {
				ObjectiveStore.delete(dir, tmp);
			} catch (java.io.IOException ignored) {
				// A leftover file is harmless.
			}
		}));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll = Math.max(0, Math.min(Math.max(0, rules.size() - visible()), scroll - (int) Math.signum(dy)));
		rebuildWidgets();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int w = Math.min(460, width - 16);
		int x0 = (width - w) / 2;
		Ui.panel(g, x0 - 4, 30, w + 8, visible() * ROW + 6);
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§l" + title), x0, 10, Ui.TEXT, false);
		g.text(font, Component.literal(hints ? "§7Hints only count in their stage, then fade out as it improves."
				: "§7Rewards teach it what to do; penalties (red) what not to do."), x0, 20, Ui.MUTED, false);
		SkillLibrary lib = SkillLibrary.get();
		for (int i = scroll; i < Math.min(rules.size(), scroll + visible()); i++) {
			Objective.Rule r = rules.get(i);
			int y = 34 + (i - scroll) * ROW;
			String label = r.note == null || r.note.isBlank() ? lib.label(r.event) : r.note;
			String what = lib.label(r.event);
			int tw = w - 132 - (targetField(r.event) != null ? 96 : 0);
			g.text(font, Component.literal((r.off ? "§8§m" : "§f") + font.plainSubstrByWidth(label, tw)), x0 + 26, y + 2, Ui.TEXT, false);
			g.text(font, Component.literal("§8" + font.plainSubstrByWidth(what, tw)), x0 + 26, y + 11, Ui.MUTED, false);
		}
		if (rules.isEmpty()) {
			g.text(font, Component.literal("§7Nothing yet. Press " + (hints ? "+ Add hint" : "+ Add reward") + "."), x0 + 6, 40, Ui.MUTED, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
