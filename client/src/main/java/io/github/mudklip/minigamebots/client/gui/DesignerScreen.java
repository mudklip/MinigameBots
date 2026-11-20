package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.practice.Objective;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.skills.SkillDef;
import io.github.mudklip.minigamebots.client.skills.SkillLibrary;
import io.github.mudklip.minigamebots.client.skills.SkillStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The training designer: say what you want it to get better at, get a checked program built from
 * parts that exist (designer.py), tick what you want, Accept. The mind only helps understand the
 * request; the steps come from fixed rules and the playbook.
 */
public final class DesignerScreen extends Screen {
	private static final String[] QUICK = {"Sumo", "Spleef", "Bridging", "Crystal PvP", "Sword", "Bow", "Dropper", "Survival"};

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private final String initial;
	private EditBox request;
	private @Nullable JsonObject program;
	private final List<Boolean> include = new ArrayList<>();
	private String message = "";
	private boolean busy;
	private boolean useMind = true;
	private final int[] quickX = new int[QUICK.length];
	private final int[] quickW = new int[QUICK.length];
	private int stepsTop;

	public DesignerScreen(@Nullable Screen parent, ProfileManifest profile, String initial) {
		super(Component.literal("Training designer"));
		this.parent = parent;
		this.profile = profile;
		this.initial = initial;
	}

	private Path profileDir() {
		return MinigameBotsClient.instance().profiles().store().dir(profile.id);
	}

	@Override
	protected void init() {
		String keep = request == null ? initial : request.getValue();
		request = addRenderableWidget(new EditBox(font, 8, 34, width - 112, 18, Component.literal("What should it get better at?")));
		request.setMaxLength(200);
		request.setHint(Component.literal("§7e.g. good at Sumo, or: it keeps wasting pearls"));
		request.setValue(keep);
		addRenderableWidget(Button.builder(Component.literal(busy ? "Designing..." : "§a✎ Design"), b -> design()).pos(width - 100, 33).width(92)
				.build()).active = !busy;
		boolean mindReady = Files.isDirectory(MinigameBotsClient.instance().brain().runtime().root().resolve("mind"));
		if (mindReady) {
			addRenderableWidget(Button.builder(Component.literal("Mind: " + (useMind ? "on" : "off")), b -> {
				useMind = !useMind;
				rebuildWidgets();
			}).pos(width - 152, 6).width(88).tooltip(Tooltip.create(Component.literal(
					"The mind helps understand free wording. The plan itself always comes from fixed rules."))).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
		if (program != null && program.getAsJsonArray("steps").size() > 0) {
			addRenderableWidget(Button.builder(Component.literal("§a✔ Accept"), b -> accept()).pos(width - 100, height - 26).width(92)
					.tooltip(Tooltip.create(Component.literal("Applies the ticked steps to this AI. Recording and plans stay as to-dos."))).build());
			addRenderableWidget(Button.builder(Component.literal("Training Hub..."), b -> minecraft.gui.setScreen(new HubScreen(this)))
					.pos(width - 196, height - 26).width(92).build());
		}
	}

	private void design() {
		if (request.getValue().isBlank()) {
			message = "Say what it should get better at first.";
			return;
		}
		busy = true;
		message = "";
		rebuildWidgets();
		JsonObject m = new JsonObject();
		m.addProperty("type", "design");
		m.addProperty("request", request.getValue());
		m.addProperty("use_mind", useMind);
		JsonObject ctx = new JsonObject();
		JsonArray skills = new JsonArray();
		for (var e : SkillStore.list(profileDir())) {
			skills.add(e.id());
		}
		ctx.add("skills", skills);
		var v = io.github.mudklip.minigamebots.client.coach.Watchdog.check(profileDir());
		JsonObject verdict = new JsonObject();
		verdict.addProperty("state", v.state().name());
		verdict.addProperty("why", v.why());
		ctx.add("verdict", verdict);
		m.add("context", ctx);
		MinigameBotsClient.instance().brain().ask(m, r -> {
			busy = false;
			if (r.has("error")) {
				message = "§c" + r.get("error").getAsString();
			} else {
				program = r;
				include.clear();
				for (int i = 0; i < r.getAsJsonArray("steps").size(); i++) {
					include.add(true);
				}
			}
			rebuildWidgets();
		});
	}

	/** Applies the ticked steps. */
	private void accept() {
		JsonObject p = program;
		if (p == null) {
			return;
		}
		int done = 0;
		List<String> todo = new ArrayList<>();
		var steps = p.getAsJsonArray("steps");
		for (int i = 0; i < steps.size(); i++) {
			if (!include.get(i)) {
				continue;
			}
			JsonObject st = steps.get(i).getAsJsonObject();
			String kind = st.get("kind").getAsString();
			try {
				switch (kind) {
					case "skill" -> {
						skill(st.get("skill").getAsString());
						done++;
					}
					case "rules" -> {
						String id = st.get("skill").getAsString();
						SkillDef def = skill(id);
						for (var r : st.getAsJsonArray("rules")) {
							def.rules.add(SkillLibrary.GSON.fromJson(r, Objective.Rule.class));
						}
						SkillStore.save(profileDir(), id, def);
						done++;
					}
					case "learn_from" -> {
						String id = st.get("skill").getAsString();
						SkillDef def = skill(id);
						def.learnFrom = SkillLibrary.GSON.fromJson(st.get("weights"), SkillDef.LearnFrom.class);
						SkillStore.save(profileDir(), id, def);
						done++;
					}
					case "setup" -> {
						String id = st.get("skill").getAsString();
						SkillDef def = skill(id);
						def.setup = st.get("setup").getAsString();
						SkillStore.save(profileDir(), id, def);
						done++;
					}
					case "fit" -> {
						fit(st.get("skill").getAsString());
						done++;
					}
					default -> todo.add(st.get("title").getAsString());
				}
			} catch (Exception e) {
				message = "§cCouldn't apply \"" + st.get("title").getAsString() + "\": " + e.getMessage();
				return;
			}
		}
		try {
			Files.writeString(profileDir().resolve("designer.json"), p.toString());
		} catch (Exception ignored) {
			// Only for the follow-up; not essential.
		}
		message = "§aApplied " + done + " step(s)." + (todo.isEmpty() ? "" : " To do: " + String.join(" · ", todo));
	}

	/** The AI's own copy of a skill, made from the ready-made one if it doesn't have it yet. */
	private SkillDef skill(String id) throws java.io.IOException {
		SkillDef def = SkillStore.read(profileDir(), id);
		if (def == null) {
			SkillDef preset = SkillLibrary.get().preset(id);
			def = preset != null ? preset.copy() : SkillDef.blank();
			SkillStore.save(profileDir(), id, def);
		}
		if (def.learnFrom == null) {
			def.learnFrom = new SkillDef.LearnFrom();
		}
		return def;
	}

	/** Reward weights fitted to your marked clips, applied if they clearly agree better. */
	private void fit(String id) throws java.io.IOException {
		SkillDef def = skill(id);
		JsonObject m = new JsonObject();
		m.addProperty("type", "fit_rewards");
		JsonObject sk = new JsonObject();
		sk.add("rules", SkillLibrary.GSON.toJsonTree(def.rules));
		m.add("skill", sk);
		m.addProperty("recordings", MinigameBotsClient.instance().recorder().store().root().toString());
		MinigameBotsClient.instance().brain().ask(m, r -> {
			if (r.has("rules")) {
				var live = def.rules.stream().filter(x -> !x.off).toList();
				for (var s : r.getAsJsonArray("rules")) {
					var o = s.getAsJsonObject();
					int i = o.get("index").getAsInt();
					if (i < live.size()) {
						live.get(i).value = o.get("suggested").getAsDouble();
					}
				}
				try {
					SkillStore.save(profileDir(), id, def);
				} catch (java.io.IOException ignored) {
					// Reported below.
				}
			}
			message = "§7Rewards check: " + (r.has("why") ? r.get("why").getAsString() : "done");
		});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lTraining designer"), 8, 10, Ui.TEXT, false);
		g.text(font, font.plainSubstrByWidth("Say what it should get better at; it builds a checked plan.", width - 170), 8, 21,
				Ui.MUTED, false);
		int x = 8;
		for (int i = 0; i < QUICK.length; i++) {
			quickX[i] = x;
			quickW[i] = Ui.chip(g, font, QUICK[i], x, 56, false, Ui.inside(mouseX, mouseY, x, 56, font.width(QUICK[i]) + 10, 13), Ui.ACCENT);
			x += quickW[i] + 3;
		}
		int y = 74;
		JsonObject p = program;
		if (p == null) {
			Ui.card(g, 8, y, width - 16, 40, Ui.CARD, Ui.BORDER);
			g.text(font, Component.literal("§lHow it works"), 14, y + 5, Ui.TEXT, false);
			g.text(font, font.plainSubstrByWidth("Name a minigame or a problem. It picks the smallest change that does it: one reward, one skill,",
					width - 28), 14, y + 17, Ui.MUTED, false);
			g.text(font, font.plainSubstrByWidth("or a few connected steps. Nothing changes until you Accept.", width - 28), 14, y + 27, Ui.MUTED, false);
		} else {
			String scope = p.get("scope").getAsString();
			Ui.card(g, 8, y, width - 16, 22, Ui.CARD, Ui.ACCENT);
			g.text(font, Component.literal("§l" + font.plainSubstrByWidth(p.get("title").getAsString(), width - 160)), 14, y + 3, Ui.TEXT, false);
			g.text(font, font.plainSubstrByWidth(p.get("summary").getAsString(), width - 40), 14, y + 13, Ui.MUTED, false);
			int sw = font.width(scope) + 10;
			Ui.chip(g, font, scope, width - 16 - sw, y + 4, true, false, scope.equals("rule") ? Ui.GOOD : Ui.ACCENT);
			y += 28;
			stepsTop = y;
			var steps = p.getAsJsonArray("steps");
			int rows = Math.max(1, (height - 46 - y) / 22);
			stepScroll = Math.max(0, Math.min(stepScroll, steps.size() - rows));
			rowsShown = rows;
			for (int i = stepScroll; i < Math.min(steps.size(), stepScroll + rows); i++) {
				JsonObject st = steps.get(i).getAsJsonObject();
				boolean on = include.get(i);
				int ry = y + (i - stepScroll) * 22;
				// The timeline: a numbered dot, joined to the next one.
				if (i + 1 < steps.size()) {
					g.fill(17, ry + 14, 18, ry + 22, Ui.BORDER);
				}
				g.fill(12, ry + 2, 24, ry + 14, on ? Ui.ACCENT : 0xFF3A414D);
				g.text(font, String.valueOf(i + 1), 18 - font.width(String.valueOf(i + 1)) / 2, ry + 4, 0xFF101418, false);
				g.text(font, Component.literal((on ? "§f" : "§8") + font.plainSubstrByWidth(st.get("title").getAsString(), width - 50)), 30, ry + 1,
						Ui.TEXT, false);
				g.text(font, font.plainSubstrByWidth(st.get("why").getAsString(), width - 50), 30, ry + 11, on ? Ui.MUTED : 0xFF4A525E, false);
			}
			if (steps.size() > stepScroll + rows) {
				g.text(font, "▼ " + (steps.size() - stepScroll - rows) + " more (scroll)", width - 120, y + rows * 22 - 4, Ui.ACCENT, false);
			}
			int cy = height - 42;
			for (var c : p.getAsJsonArray("checks")) {
				String t = c.getAsString();
				g.text(font, font.plainSubstrByWidth((t.startsWith("All") ? "§a✓ " : "§e! ") + t, width - 210), 8, cy, Ui.TEXT, false);
				cy += 10;
			}
		}
		if (!message.isEmpty()) {
			g.text(font, font.plainSubstrByWidth(message, width - 210), 8, height - 20, Ui.TEXT, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < QUICK.length; i++) {
			if (Ui.inside(event.x(), event.y(), quickX[i], 56, quickW[i], 13)) {
				String v = request.getValue().trim();
				request.setValue(v.isEmpty() ? "good at " + QUICK[i] : v + ", " + QUICK[i]);
				return true;
			}
		}
		JsonObject p = program;
		if (p != null && event.x() < width - 8 && event.y() >= stepsTop && event.y() < stepsTop + rowsShown * 22) {
			int i = (int) ((event.y() - stepsTop) / 22) + stepScroll;
			if (i >= 0 && i < include.size()) {
				include.set(i, !include.get(i));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int stepScroll;
	private int rowsShown = 1;

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		JsonObject p = program;
		if (p != null) {
			stepScroll = Math.max(0, Math.min(p.getAsJsonArray("steps").size() - rowsShown, stepScroll - (int) Math.signum(dy)));
		}
		return true;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
