package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.practice.Objective;
import io.github.mudklip.minigamebots.client.practice.ObjectiveStore;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Edits an Objective: each rule says which event is worth how much, with optional conditions,
 * caps, cooldowns, forgiveness, budgets and priority. Saved in the AI profile.
 */
public final class ObjectiveEditorScreen extends Screen {
	private static final int ROW = 48;
	private static final String[] PER = {"event", "amount", "count", "accuracy", "combo", "dps", "kills"};
	private static final String[] FORGIVE = {"no forgiving", "forgive: kill", "forgive: win", "forgive: kill or win"};

	/** Widgets of one rule row. */
	private final class Row {
		final Objective.Rule rule;
		EditBox event;
		EditBox where;
		EditBox value;
		EditBox cap;
		EditBox cooldown;
		EditBox budget;
		EditBox note;

		Row(Objective.Rule rule) {
			this.rule = rule;
		}

		void commit() {
			rule.event = event.getValue().trim();
			rule.where = Objective.parseWhere(where.getValue());
			rule.value = parse(value.getValue(), rule.value);
			rule.cap = Math.max(0, parse(cap.getValue(), 0));
			rule.cooldown = Math.max(0, parse(cooldown.getValue(), 0));
			double limit = parse(budget.getValue(), -1);
			if (limit >= 0 && !budget.getValue().isBlank()) {
				if (rule.budget == null) {
					rule.budget = new Objective.Budget();
				}
				rule.budget.limit = limit;
			} else {
				rule.budget = null;
			}
			rule.note = note.getValue().trim();
		}
	}

	private final @Nullable Screen parent;
	private final Path dir;
	private final String id;
	private final Objective objective;
	private final Consumer<String> onSaved;
	private final List<Row> rows = new ArrayList<>();
	private int page;
	private EditBox name;
	private @Nullable String error;

	public ObjectiveEditorScreen(@Nullable Screen parent, ProfileManifest profile, String id, Objective objective,
			Consumer<String> onSaved) {
		super(Component.translatable("minigamebots.objective.screen"));
		this.parent = parent;
		this.dir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		this.id = id;
		this.objective = ObjectiveStore.GSON.fromJson(ObjectiveStore.GSON.toJson(objective), Objective.class); // Edit a copy.
		this.objective.params = null;
		this.onSaved = onSaved;
	}

	private int perPage() {
		return Math.max(1, (height - 34 - 60) / ROW);
	}

	@Override
	protected void init() {
		commitAll();
		rows.clear();
		name = addRenderableWidget(new EditBox(font, 60, 8, Math.min(200, width - 200), 18, Component.literal("Name")));
		name.setMaxLength(40);
		name.setValue(objective.name);
		int pages = Math.max(1, (objective.rules.size() + perPage() - 1) / perPage());
		page = Math.min(page, pages - 1);
		int y = 34;
		int from = page * perPage();
		for (int i = from; i < Math.min(objective.rules.size(), from + perPage()); i++) {
			rows.add(row(objective.rules.get(i), y));
			y += ROW;
		}
		int by = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.objective.add"), b -> {
			commitAll();
			objective.rules.add(new Objective.Rule());
			page = (objective.rules.size() - 1) / perPage();
			rebuildWidgets();
		}).pos(10, by).width(70).build());
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> {
				page = Math.max(0, page - 1);
				rebuildWidgets();
			}).pos(84, by).width(20).build());
			addRenderableWidget(Button.builder(Component.literal(">"), b -> {
				page = Math.min(pages - 1, page + 1);
				rebuildWidgets();
			}).pos(108, by).width(20).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.objective.save"), b -> save()).pos(width - 214, by).width(70).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.objective.delete"), b -> {
			try {
				ObjectiveStore.delete(dir, id);
				onSaved.accept(null);
				minecraft.gui.setScreen(parent);
			} catch (IOException e) {
				error = e.getMessage();
			}
		}).pos(width - 140, by).width(64).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> minecraft.gui.setScreen(parent))
				.pos(width - 72, by).width(62).build());
	}

	private Row row(Objective.Rule r, int y) {
		Row row = new Row(r);
		int x = 10;
		row.event = box(x, y, 92, r.event, "event (e.g. kill)", 40);
		row.where = box(x + 96, y, 118, Objective.formatWhere(r.where), "only when, e.g. target=minecraft:pig", 120);
		row.value = box(x + 218, y, 40, fmt(r.value), "value", 12);
		addRenderableWidget(Button.builder(Component.literal(perLabel(r.per)), b -> {
			int i = indexOf(PER, r.per);
			r.per = PER[(i + 1) % PER.length];
			b.setMessage(Component.literal(perLabel(r.per)));
		}).pos(x + 262, y).size(66, 18).tooltip(Tooltip.create(Component.translatable("minigamebots.objective.per_help"))).build());
		addRenderableWidget(Button.builder(Component.literal("T" + r.tier), b -> {
			r.tier = r.tier % 4 + 1;
			b.setMessage(Component.literal("T" + r.tier));
		}).size(22, 18).pos(x + 332, y).tooltip(Tooltip.create(Component.translatable("minigamebots.objective.tier_help"))).build());
		addRenderableWidget(Button.builder(Component.literal("x"), b -> {
			commitAll();
			objective.rules.remove(r);
			rebuildWidgets();
		}).size(16, 18).pos(x + 358, y).tooltip(Tooltip.create(Component.translatable("minigamebots.objective.remove_help"))).build());
		int y2 = y + 22;
		addRenderableWidget(Button.builder(Component.literal(forgiveLabel(r)), b -> {
			int next = (forgiveIndex(r) + 1) % FORGIVE.length;
			r.forgive = switch (next) {
				case 1 -> forgive(List.of("kill"));
				case 2 -> forgive(List.of(winTrigger()));
				case 3 -> forgive(List.of("kill", winTrigger()));
				default -> null;
			};
			b.setMessage(Component.literal(forgiveLabel(r)));
		}).size(118, 18).pos(x, y2).tooltip(Tooltip.create(Component.translatable("minigamebots.objective.forgive_help"))).build());
		row.cap = box(x + 122, y2, 40, r.cap > 0 ? fmt(r.cap) : "", "cap", 12);
		row.cap.setTooltip(Tooltip.create(Component.translatable("minigamebots.objective.cap_help")));
		row.cooldown = box(x + 166, y2, 40, r.cooldown > 0 ? fmt(r.cooldown) : "", "wait s", 12);
		row.cooldown.setTooltip(Tooltip.create(Component.translatable("minigamebots.objective.cooldown_help")));
		row.budget = box(x + 210, y2, 48, r.budget != null ? fmt(r.budget.limit) : "", "budget", 12);
		row.budget.setTooltip(Tooltip.create(Component.translatable("minigamebots.objective.budget_help")));
		row.note = box(x + 262, y2, 112, r.note, "label", 40);
		return row;
	}

	private EditBox box(int x, int y, int w, String value, String hint, int max) {
		EditBox b = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal(hint)));
		b.setMaxLength(max);
		b.setValue(value);
		b.setHint(Component.literal(hint).withColor(0xFF707070));
		return b;
	}

	private static Objective.Forgive forgive(List<Object> by) {
		Objective.Forgive f = new Objective.Forgive();
		f.by = new ArrayList<>(by);
		f.within = 20;
		return f;
	}

	private static Object winTrigger() {
		return java.util.Map.of("event", "tg_episode_end", "where", java.util.Map.of("result", "win"));
	}

	private static int forgiveIndex(Objective.Rule r) {
		if (r.forgive == null || r.forgive.by.isEmpty()) {
			return 0;
		}
		boolean kill = r.forgive.by.stream().anyMatch("kill"::equals);
		boolean win = r.forgive.by.stream().anyMatch(o -> o instanceof java.util.Map<?, ?>);
		return kill && win ? 3 : win ? 2 : 1;
	}

	private static String forgiveLabel(Objective.Rule r) {
		int i = forgiveIndex(r);
		return i == 0 ? FORGIVE[0] : FORGIVE[i] + ", " + fmt(r.forgive.within) + " s";
	}

	private static String perLabel(String per) {
		return "event".equals(per) ? "each time" : "x " + per;
	}

	private static int indexOf(String[] a, String v) {
		for (int i = 0; i < a.length; i++) {
			if (a[i].equals(v)) {
				return i;
			}
		}
		return 0;
	}

	private void commitAll() {
		if (name != null) {
			objective.name = name.getValue().trim();
		}
		rows.forEach(Row::commit);
	}

	private void save() {
		commitAll();
		if (objective.name.isBlank()) {
			error = "Give it a name";
			return;
		}
		objective.rules.removeIf(r -> r.event.isBlank());
		try {
			ObjectiveStore.save(dir, id, objective);
			onSaved.accept(id);
			minecraft.gui.setScreen(parent);
		} catch (IOException e) {
			error = "Couldn't save: " + e.getMessage();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, "Name:", 10, 13, 0xFFA0A0A0, false);
		for (int i = 0; i < rows.size(); i++) {
			int y = 34 + i * ROW;
			g.fill(6, y - 2, Math.min(width - 6, 390), y + ROW - 6, 0x40000000);
		}
		commitAll();
		List<String> warnings = new ArrayList<>(objective.warnings());
		if (error != null) {
			warnings.add(0, error);
		}
		int y = height - 34 - 10 * Math.min(3, warnings.size());
		for (int i = 0; i < Math.min(3, warnings.size()); i++) {
			g.text(font, font.plainSubstrByWidth((i == 0 && error != null ? "" : "⚠ ") + warnings.get(i), width - 20), 10, y,
					i == 0 && error != null ? 0xFFFF8080 : 0xFFFFC060, false);
			y += 10;
		}
		if (width - 406 >= 120) {
			g.textWithWordWrap(font, Component.translatable("minigamebots.objective.help"), 398, 34, width - 406, 0xFF909090);
		}
	}

	private static double parse(String s, double fallback) {
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
