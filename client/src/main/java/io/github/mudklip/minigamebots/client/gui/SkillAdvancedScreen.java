package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.profile.Skills;
import io.github.mudklip.minigamebots.client.skills.SkillDef;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The finer settings of a skill: network, learning numbers, teacher, target, stages. */
public final class SkillAdvancedScreen extends Screen {
	private static final List<String> TEACHERS = List.of("", "pvp", "baritone:TRAVEL", "baritone:MINE");
	private final SkillEditorScreen parent;
	private final SkillDef def;
	private EditBox target;

	public SkillAdvancedScreen(SkillEditorScreen parent, SkillDef def) {
		super(Component.literal("Advanced"));
		this.parent = parent;
		this.def = def;
	}

	@Override
	protected void init() {
		int w = Math.min(300, width - 16);
		int x = (width - w) / 2;
		int y = 30;
		addRenderableWidget(Button.builder(Component.literal("Network: " + (def.advanced.separate ? "§eits own" : "shared with " + def.manager)), b -> {
			def.advanced.separate = !def.advanced.separate;
			parent.changed();
			rebuildWidgets();
		}).pos(x, y).width(w - 104).tooltip(Tooltip.create(Component.literal("Shared: practicing this improves the AI's "
				+ def.manager + " skill, which the Manager uses when playing. Its own: a separate network only for this skill."))).build());
		addRenderableWidget(Button.builder(Component.literal("Area: " + def.manager), b -> {
			List<String> c = Skills.COMMON;
			def.manager = c.get((c.indexOf(def.manager) + 1) % c.size());
			parent.changed();
			rebuildWidgets();
		}).pos(x + w - 100, y).width(100).tooltip(Tooltip.create(Component.literal("Which of the Manager's skills this belongs to"))).build());
		y += 24;
		slider(x, y, w, "Learning speed", def.advanced.lr, 0.1, 5, v -> def.advanced.lr = v,
				"Multiplies how big each learning step is. Auto = from each stage's speed.");
		y += 24;
		slider(x, y, w, "Exploration", def.advanced.entropy, 0.5, 10, v -> def.advanced.entropy = v,
				"How much it tries new things. Auto = more in self-discovery stages.");
		y += 24;
		slider(x, y, w, "Stay like before", def.advanced.stay_human, 0, 0.5, v -> def.advanced.stay_human = v,
				"How strongly it keeps the behaviour it learned from demos (keeps it human-like). Auto = from each stage's speed.");
		y += 26;
		addRenderableWidget(Button.builder(Component.literal("Teacher: " + (def.teacher.isEmpty() ? "none" : def.teacher)), b -> {
			def.teacher = TEACHERS.get((TEACHERS.indexOf(def.teacher) + 1) % TEACHERS.size());
			parent.changed();
			rebuildWidgets();
		}).pos(x, y).width(w / 2 - 2).tooltip(Tooltip.create(Component.literal("Demos recorded and trained on first, while it "
				+ "knows nothing yet"))).build());
		target = addRenderableWidget(new EditBox(font, x + w / 2 + 2, y + 1, w / 2 - 2, 18, Component.literal("Target")));
		target.setValue(def.target);
		target.setHint(Component.literal("§7target, e.g. minecraft:pig"));
		target.setResponder(t -> {
			def.target = t.trim();
			parent.changed();
		});
		y += 26;
		addRenderableWidget(Button.builder(Component.literal("§a+ Stage"), b -> {
			SkillDef.Stage s = new SkillDef.Stage();
			s.name = "Stage " + (def.stages.size() + 1);
			if (!def.stages.isEmpty()) {
				def.stages.get(def.stages.size() - 1).advance.episodes = Math.max(15, def.stages.get(def.stages.size() - 1).advance.episodes);
			}
			def.stages.add(s);
			parent.changed();
			rebuildWidgets();
		}).pos(x, y).width(w / 2 - 2).build()).active = def.stages.size() < 5;
		addRenderableWidget(Button.builder(Component.literal("§c− Last stage"), b -> {
			def.stages.remove(def.stages.size() - 1);
			if (def.stage != null && def.stage >= def.stages.size()) {
				def.stage = null;
			}
			parent.changed();
			rebuildWidgets();
		}).pos(x + w / 2 + 2, y).width(w / 2 - 2).build()).active = def.stages.size() > 1;
		y += 24;
		for (SkillDef.Stage st : def.stages) {
			if (y > height - 56) {
				break;
			}
			addRenderableWidget(Button.builder(Component.literal(st.name + ": self-discovery " + (st.discover ? "§don ✦" : "off")), b -> {
				st.discover = !st.discover;
				parent.changed();
				rebuildWidgets();
			}).pos(x, y).width(w).tooltip(Tooltip.create(Component.literal("Self-discovery: explores more and keeps less to how it "
					+ "played, to find its own ways (e.g. which of several swords to use when)"))).build());
			y += 22;
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(x + w - 60, height - 26).width(60).build());
	}

	private void slider(int x, int y, int w, String label, Double value, double min, double max, java.util.function.DoubleConsumer set, String help) {
		boolean auto = value == null;
		addRenderableWidget(Button.builder(Component.literal(auto ? "§aAuto" : "Auto"), b -> {
			if (value == null) {
				set.accept(1.0 * Math.max(min, Math.min(max, label.startsWith("Stay") ? 0.05 : 1.0)));
			} else {
				switch (label) {
					case "Learning speed" -> def.advanced.lr = null;
					case "Exploration" -> def.advanced.entropy = null;
					default -> def.advanced.stay_human = null;
				}
			}
			parent.changed();
			rebuildWidgets();
		}).pos(x + w - 44, y).width(44).tooltip(Tooltip.create(Component.literal(help))).build());
		if (!auto) {
			ValueSlider s = addRenderableWidget(new ValueSlider(w - 48, label, min, max, 0.05, value, v -> String.format("%.2f", v)
					+ (label.startsWith("Stay") ? "" : "x"), v -> {
				set.accept(v);
				parent.changed();
			}));
			s.setPosition(x, y);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int w = Math.min(300, width - 16);
		int x = (width - w) / 2;
		g.text(font, Component.literal("§lAdvanced: " + def.name), x, 10, Ui.TEXT, false);
		int y = 30 + 24;
		for (String l : new String[] {"Learning speed", "Exploration", "Stay like before"}) {
			boolean auto = switch (l) {
				case "Learning speed" -> def.advanced.lr == null;
				case "Exploration" -> def.advanced.entropy == null;
				default -> def.advanced.stay_human == null;
			};
			if (auto) {
				g.text(font, Component.literal("§f" + l + "§7: from each stage"), x + 4, y + 6, Ui.TEXT, false);
			}
			y += 24;
		}
		g.text(font, Component.literal("§8Rewards and hints in detail: Edit rewards > Every detail."), x, height - 20, Ui.MUTED, false);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
