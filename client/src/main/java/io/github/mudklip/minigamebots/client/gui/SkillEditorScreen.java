package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.coach.Coach;
import io.github.mudklip.minigamebots.client.practice.Objective;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.skills.SkillDef;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import io.github.mudklip.minigamebots.client.skills.SkillStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * One skill, the simple way: icon and name, where it practices, what it's rewarded for, what
 * it may not do, and its stages (with hints and learning speed). Everything else is under
 * Advanced. Ready-made skills can be edited and reset; your own can be deleted.
 */
public final class SkillEditorScreen extends Screen {
	static final int[] TRIES = {5, 10, 15, 25, 40, 60, 100, 0};
	private static final int[] BUDGETS = {15, 30, 120, 480};
	private static final String[] BUDGET_LABELS = {"15 min", "30 min", "2 h", "Overnight"};
	private static int budget = 1;

	private final Screen parent;
	private @Nullable String id;
	final SkillDef def;
	private final boolean preset;
	private boolean edited;
	private final boolean fresh;
	private boolean dirty;
	private EditBox name;
	private EditBox help;
	private @Nullable String message;
	private final List<int[]> lockChips = new ArrayList<>();
	private int lx;
	private int rx;
	private int colW;
	private int[] prog = {0, 0};

	public SkillEditorScreen(Screen parent, @Nullable String id, SkillDef def, boolean preset, boolean edited, boolean fresh) {
		super(Component.literal("Skill"));
		this.parent = parent;
		this.id = id;
		this.def = def;
		this.preset = preset;
		this.edited = edited;
		this.fresh = fresh;
		this.dirty = fresh;
	}

	private @Nullable Path profileDir() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		return p == null ? null : MinigameBotsClient.instance().profiles().store().dir(p.id);
	}

	void changed() {
		dirty = true;
	}

	@Override
	protected void init() {
		SkillLibrary lib = SkillLibrary.get();
		prog = profileDir() == null || id == null ? new int[] {0, 0} : SkillStore.progress(profileDir(), id);
		colW = Math.min(230, (width - 24) / 2);
		lx = 8;
		rx = width - 8 - colW;
		// Identity row.
		addRenderableWidget(Button.builder(Component.empty(), b -> minecraft.gui.setScreen(new ItemPickerScreen(this, icon -> {
			def.icon = icon;
			changed();
		}))).pos(lx, 26).size(22, 20).tooltip(Tooltip.create(Component.literal("Change the icon (any item)"))).build());
		String keepName = name != null ? name.getValue() : def.name;
		String keepHelp = help != null ? help.getValue() : def.help;
		name = addRenderableWidget(new EditBox(font, lx + 26, 27, colW - 26, 18, Component.literal("Name")));
		name.setMaxLength(40);
		name.setValue(keepName);
		name.moveCursorToStart(false);
		name.setResponder(t -> changed());
		help = addRenderableWidget(new EditBox(font, lx, 50, colW, 18, Component.literal("Description")));
		help.setMaxLength(200);
		help.setValue(keepHelp);
		help.moveCursorToStart(false);
		help.setHint(Component.literal("§7What is this skill about?"));
		help.setResponder(t -> changed());
		int half = (colW - 4) / 2;
		addRenderableWidget(Button.builder(Component.literal(def.category), b -> {
			List<String> cats = lib.categories;
			def.category = cats.get((cats.indexOf(def.category) + 1) % cats.size());
			changed();
			rebuildWidgets();
		}).pos(lx, 72).width(half).tooltip(Tooltip.create(Component.literal("Category: which tab it is listed under"))).build());
		addRenderableWidget(Button.builder(Component.literal("⌂ " + shortSetup(def.setup)), b -> {
			List<String> keys = new ArrayList<>(lib.setups.keySet());
			def.setup = keys.get((keys.indexOf(def.setup) + 1) % keys.size());
			changed();
			rebuildWidgets();
		}).pos(lx + half + 4, 72).width(half).tooltip(Tooltip.create(Component.literal("Where it practices: "
				+ lib.setups.getOrDefault(def.setup, def.setup) + ". Anything but \"Nothing\" needs Training Grounds."))).build());
		// Rewards card buttons.
		addRenderableWidget(Button.builder(Component.literal("Edit..."), b -> minecraft.gui.setScreen(
				new RulesScreen(this, "What " + def.name + " is rewarded for", def.rules, false))).pos(lx + colW - 62, 98).size(58, 16).build());
		// Stages.
		int sy = stagesTop();
		for (int i = 0; i < def.stages.size(); i++) {
			SkillDef.Stage st = def.stages.get(i);
			int y = sy + 16 + i * 18;
			int bx = rx + 62;
			int w = (colW - 66 - 6) / 3;
			addRenderableWidget(Button.builder(Component.literal(lib.speeds.containsKey(st.speed) ? lib.speeds.get(st.speed).label : st.speed), b -> {
				List<String> keys = new ArrayList<>(lib.speeds.keySet());
				st.speed = keys.get((keys.indexOf(st.speed) + 1) % keys.size());
				changed();
				rebuildWidgets();
			}).pos(bx, y).size(w + 10, 16).tooltip(Tooltip.create(Component.literal("How fast it learns in this stage: "
					+ (lib.speeds.containsKey(st.speed) ? lib.speeds.get(st.speed).help : "")))).build());
			boolean last = i == def.stages.size() - 1;
			Button tries = addRenderableWidget(Button.builder(Component.literal(last ? "§8last" : st.advance.episodes == 0 ? "stay" : st.advance.episodes + "×"), b -> {
				int k = 0;
				for (int j = 0; j < TRIES.length; j++) {
					if (TRIES[j] == st.advance.episodes) {
						k = j;
					}
				}
				st.advance.episodes = TRIES[(k + 1) % TRIES.length];
				changed();
				rebuildWidgets();
			}).pos(bx + w + 12, y).size(w - 4, 16).tooltip(Tooltip.create(Component.literal("Moves to the next stage after this many tries"
					+ " (\"stay\" = never by itself)"))).build());
			tries.active = !last;
			addRenderableWidget(Button.builder(Component.literal("Hints " + st.shaping.stream().filter(r -> !r.off).count()), b ->
					minecraft.gui.setScreen(new RulesScreen(this, "Hints in " + st.name + " (early encouragement)", st.shaping, true)))
					.pos(bx + 2 * w + 10, y).size(w - 4, 16).tooltip(Tooltip.create(Component.literal("Extra rewards that only apply in this "
							+ "stage, to get it started: e.g. \"keep holding break until the block breaks\""))).build());
		}
		addRenderableWidget(Button.builder(Component.literal(def.stage == null ? "Auto ▸" : "Stay: " + stageName(def.stage)), b -> {
			def.stage = def.stage == null ? Integer.valueOf(0) : def.stage + 1 >= def.stages.size() ? null : def.stage + 1;
			changed();
			rebuildWidgets();
		}).pos(rx + colW - 76, sy - 3).size(72, 16).tooltip(Tooltip.create(Component.literal("Auto: moves to the next stage by itself "
				+ "after the number of tries shown (×). Or keep it in one stage."))).build()).active = !def.stages.isEmpty();
		// Bottom bar.
		int by = height - 26;
		addRenderableWidget(Button.builder(Component.literal(dirty ? "§eSave" : "Saved"), b -> save()).pos(lx, by).width(44).build()).active = dirty;
		addRenderableWidget(Button.builder(Component.literal(BUDGET_LABELS[budget]), b -> {
			budget = (budget + 1) % BUDGETS.length;
			rebuildWidgets();
		}).pos(lx + 48, by).width(52).tooltip(Tooltip.create(Component.literal("How long to practice"))).build());
		addRenderableWidget(Button.builder(Component.literal(Coach.running() ? "§cStop practicing" : "§a▶ Practice"), b -> practice())
				.pos(lx + 104, by).width(80).build());
		addRenderableWidget(Button.builder(Component.literal("Advanced..."), b -> minecraft.gui.setScreen(new SkillAdvancedScreen(this, def)))
				.pos(lx + 188, by).width(64).build());
		if (preset && edited) {
			addRenderableWidget(Button.builder(Component.literal("Reset"), b -> confirm("Reset " + def.name + " to the ready-made version?",
					"What it learned is kept.", () -> {
						SkillStore.reset(profileDir(), id);
						minecraft.gui.setScreen(parent);
					})).pos(lx + 256, by).width(44).tooltip(Tooltip.create(Component.literal("Back to the ready-made version"))).build());
		} else if (!preset && !fresh) {
			addRenderableWidget(Button.builder(Component.literal("§cDelete"), b -> confirm("Delete " + def.name + "?",
					"The skill and what it learned for it are removed.", () -> {
						SkillStore.delete(profileDir(), id);
						minecraft.gui.setScreen(parent);
					})).pos(lx + 256, by).width(44).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 58, by).width(50).build());
	}

	private interface Io {
		void run() throws Exception;
	}

	private void confirm(String title, String text, Io action) {
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				try {
					action.run();
					return;
				} catch (Exception e) {
					message = "Couldn't: " + e.getMessage();
				}
			}
			minecraft.gui.setScreen(this);
		}, Component.literal(title), Component.literal(text)));
	}

	private String stageName(int i) {
		return i < def.stages.size() ? def.stages.get(i).name : "stage " + (i + 1);
	}

	private int stagesTop() {
		return lockTop() + lockRowsHeight() + 30;
	}

	private int lockTop() {
		return 26;
	}

	private int lockRowsHeight() {
		int x = 0;
		int rows = 1;
		for (var l : SkillLibrary.get().locks.values()) {
			int w = font.width("✔ " + l.label) + 10;
			if (x + w > colW - 12) {
				rows++;
				x = 0;
			}
			x += w + 3;
		}
		return rows * 16;
	}

	private static String shortSetup(String s) {
		String v = SkillLibrary.get().setups.getOrDefault(s, s);
		return v.length() > 22 ? v.substring(0, 21) + "…" : v;
	}

	private boolean save() {
		Path dir = profileDir();
		if (dir == null) {
			message = "Pick an AI first";
			return false;
		}
		def.name = name.getValue().isBlank() ? "Unnamed skill" : name.getValue().trim();
		def.help = help.getValue().trim();
		try {
			if (id == null) {
				id = SkillStore.newId(dir, def.name);
			}
			SkillStore.save(dir, id, def);
			edited = true;
			dirty = false;
			message = "§aSaved";
			rebuildWidgets();
			return true;
		} catch (Exception e) {
			message = "Couldn't save: " + e.getMessage();
			return false;
		}
	}

	private void practice() {
		if (Coach.running()) {
			Coach.stop("Stopped by you");
			rebuildWidgets();
			return;
		}
		if (dirty && !save()) {
			return;
		}
		if (id == null && !save()) {
			return;
		}
		message = Coach.startSkill(id, def, BUDGETS[budget]);
		if (message == null) {
			minecraft.gui.setScreen(null);
		} else {
			rebuildWidgets();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int[] c : lockChips) {
			if (Ui.inside(event.x(), event.y(), c[0], c[1], c[2], 13)) {
				String key = new ArrayList<>(SkillLibrary.get().locks.keySet()).get(c[3]);
				if (!def.locks.remove(key)) {
					def.locks.add(key);
				}
				changed();
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		SkillLibrary lib = SkillLibrary.get();
		// Cards under the widgets.
		int rewardsTop = 96;
		Ui.panel(g, lx, rewardsTop, colW, height - 32 - rewardsTop - 12);
		Ui.panel(g, rx, lockTop() - 2, colW, lockRowsHeight() + 22);
		int sy = stagesTop();
		Ui.panel(g, rx, sy - 6, colW, Math.max(30, def.stages.size() * 18 + 24));
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§l" + (fresh && id == null ? "New skill" : "Skill") + "§r  §7" + (preset ? edited ? "ready-made, edited ✎"
				: "ready-made" : "yours ★")), 8, 10, Ui.TEXT, false);
		g.item(Ui.stack(def.icon), lx + 3, 28);
		// Rewards.
		Ui.header(g, font, "Rewarded for", lx + 6, rewardsTop + 5);
		int y = rewardsTop + 20;
		int shown = 0;
		for (Objective.Rule r : def.rules) {
			if (y > height - 58) {
				g.text(font, Component.literal("§8+ " + (def.rules.size() - shown) + " more"), lx + 8, y, Ui.MUTED, false);
				break;
			}
			String label = r.note == null || r.note.isBlank() ? lib.label(r.event) : r.note;
			String v = (r.value >= 0 ? "§a+" : "§c") + RulesScreen.fmt(r.value);
			g.text(font, Component.literal((r.off ? "§8✖ §m" : "§a✔ §f") + font.plainSubstrByWidth(label, colW - 60)), lx + 8, y, Ui.TEXT, false);
			g.text(font, Component.literal(r.off ? "§8off" : v), lx + colW - 8 - font.width(r.off ? "off" : RulesScreen.fmt(r.value) + "+"), y,
					Ui.TEXT, false);
			y += 11;
			shown++;
		}
		if (def.rules.isEmpty()) {
			g.text(font, Component.literal("§7Nothing yet: press Edit... to add some."), lx + 8, y, Ui.MUTED, false);
		}
		// Locks.
		Ui.header(g, font, "What it may use", rx + 6, lockTop() + 3);
		lockChips.clear();
		int cx = rx + 6;
		int cy = lockTop() + 18;
		int i = 0;
		String hover = null;
		for (var e : lib.locks.entrySet()) {
			boolean locked = def.locks.contains(e.getKey());
			String text = locked ? "§c✖ §7§m" + e.getValue().label : "§a✔ §f" + e.getValue().label;
			int w = font.width("✔ " + e.getValue().label) + 10;
			if (cx + w > rx + colW - 6) {
				cx = rx + 6;
				cy += 16;
			}
			boolean h = Ui.inside(mouseX, mouseY, cx, cy, w, 13);
			Ui.chip(g, font, text, cx, cy, w, true, h, locked ? 0xFFB04040 : 0xFF4FAF5A);
			if (h) {
				hover = locked ? "✖ Blocked: it never uses " + e.getValue().label.toLowerCase() + " while practicing this skill. Click to allow it."
						: "✔ Allowed: it may use " + e.getValue().label.toLowerCase() + ". Click to block it while practicing this skill.";
			}
			lockChips.add(new int[] {cx, cy, w, i++});
			cx += w + 3;
		}
		// Stages.
		Ui.header(g, font, "Stages", rx + 6, sy);
		int[] prog = this.prog;
		for (int k = 0; k < def.stages.size(); k++) {
			SkillDef.Stage st = def.stages.get(k);
			int yy = sy + 16 + k * 18;
			boolean now = k == (def.stage != null ? def.stage : Math.min(prog[0], def.stages.size() - 1));
			if (now) {
				g.fill(rx + 2, yy, rx + 4, yy + 16, Ui.GOOD);
			}
			g.text(font, Component.literal((now ? "§a" : "§f") + font.plainSubstrByWidth(st.name, 44) + (st.discover ? " §d✦" : "")),
					rx + 7, yy + 4, Ui.TEXT, false);
			if (Ui.inside(mouseX, mouseY, rx, yy, 60, 16)) {
				hover = (st.help == null || st.help.isBlank() ? st.name : st.help) + (st.discover ? ". ✦ = tries its own ideas (self-discovery)" : "")
						+ (now ? ". It is in this stage now (" + prog[1] + " tries)" : "");
			}
		}
		String bottom = hover != null ? hover : message;
		if (bottom != null) {
			g.text(font, Component.literal(font.plainSubstrByWidth(bottom, width - 16)), 8, height - 40, hover != null ? Ui.MUTED : Ui.WARN, false);
		}
	}

	@Override
	public void onClose() {
		if (dirty && !fresh) {
			confirm("Leave without saving?", "Your changes to " + def.name + " would be lost.", () -> minecraft.gui.setScreen(parent));
			return;
		}
		minecraft.gui.setScreen(parent);
	}
}
