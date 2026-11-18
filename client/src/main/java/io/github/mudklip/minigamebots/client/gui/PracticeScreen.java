package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.brain.BrainManager;
import io.github.mudklip.minigamebots.client.practice.Objective;
import io.github.mudklip.minigamebots.client.practice.ObjectiveStore;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Practice: the AI plays and improves itself from an Objective's rewards (reinforcement
 * learning), staying close to how it learned to play from recordings.
 */
public final class PracticeScreen extends Screen {
	private static final String[] RESOURCES = {"quiet", "balanced", "max"};

	/** A choice in the Objective list: a template (with parameters) or one of the profile's own. */
	private record Choice(String label, @Nullable String template, @Nullable String saved) {
	}

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private final Path dir;
	private final List<Choice> choices = new ArrayList<>();
	private int choice;
	private final Map<String, String> params = new HashMap<>();
	private double stayHuman = 0.05;
	private int resources = 1;
	private String skill = "general";
	private @Nullable String error;
	private @Nullable EditBox param;

	public PracticeScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.translatable("minigamebots.practice.screen", profile.name));
		this.parent = parent;
		this.profile = profile;
		this.dir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		reloadChoices(null);
		selectRunning();
	}

	/** While practice runs, show the Objective it's using. */
	private void selectRunning() {
		var brain = MinigameBotsClient.instance().brain();
		JsonObject st = brain.lastStatus();
		if (!"practice".equals(brain.policy()) || st == null || !st.has("policy_status") || !st.get("policy_status").isJsonObject()) {
			return;
		}
		String running = str(st.getAsJsonObject("policy_status"), "objective");
		for (int i = 0; i < choices.size(); i++) {
			Choice c = choices.get(i);
			Objective o = c.template() != null ? ObjectiveStore.templates().get(c.template()) : ObjectiveStore.load(dir, c.saved());
			if (o != null && (running.equals(o.name) || o.name.contains("{") && running.startsWith(o.name.substring(0, o.name.indexOf('{'))))) {
				choice = i;
				return;
			}
		}
	}

	private void reloadChoices(@Nullable String select) {
		choices.clear();
		ObjectiveStore.templates().forEach((id, o) -> choices.add(new Choice(o.name.replace("{target}", "...") , id, null)));
		for (String id : ObjectiveStore.list(dir)) {
			Objective o = ObjectiveStore.load(dir, id);
			choices.add(new Choice((o == null || o.name.isBlank() ? id : o.name) + " (yours)", null, id));
		}
		choice = 0;
		for (int i = 0; i < choices.size(); i++) {
			if (select != null && select.equals(choices.get(i).saved())) {
				choice = i;
			}
		}
	}

	/** The chosen Objective, parameters filled in. */
	private @Nullable Objective objective() {
		if (choices.isEmpty()) {
			return null;
		}
		Choice c = choices.get(choice);
		if (c.template() != null) {
			Objective t = ObjectiveStore.templates().get(c.template());
			return t == null ? null : ObjectiveStore.fill(t, params);
		}
		return ObjectiveStore.load(dir, c.saved());
	}

	private @Nullable String paramName() {
		if (choices.isEmpty() || choices.get(choice).template() == null) {
			return null;
		}
		Objective t = ObjectiveStore.templates().get(choices.get(choice).template());
		return t == null || t.params == null || t.params.isEmpty() ? null : t.params.keySet().iterator().next();
	}

	@Override
	protected void init() {
		int x = 10;
		int w = Math.min(200, width / 2 - 20);
		int y = 30;
		addRenderableWidget(Button.builder(objectiveLabel(), b -> {
			choice = choices.isEmpty() ? 0 : (choice + 1) % choices.size();
			rebuildWidgets();
		}).pos(x, y).width(w).tooltip(Tooltip.create(Component.translatable("minigamebots.practice.objective_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.practice.edit"), b -> {
			Objective o = objective();
			if (o == null) {
				return;
			}
			Choice c = choices.get(choice);
			String id = c.saved() != null ? c.saved() : ObjectiveStore.newId(dir, o.name);
			minecraft.gui.setScreen(new ObjectiveEditorScreen(this, profile, id, o, saved -> reloadChoices(saved)));
		}).pos(x + w + 4, y).width(80).tooltip(Tooltip.create(Component.translatable("minigamebots.practice.edit_help"))).build());
		y += 24;
		String pn = paramName();
		param = null;
		if (pn != null) {
			Objective t = ObjectiveStore.templates().get(choices.get(choice).template());
			EditBox box = addRenderableWidget(new EditBox(font, x + 60, y, w - 60, 18, Component.literal(pn)));
			box.setMaxLength(80);
			box.setValue(params.getOrDefault(pn, String.valueOf(t.params.get(pn))));
			box.setResponder(v -> params.put(pn, v.trim()));
			param = box;
			y += 24;
		}
		ValueSlider human = new ValueSlider(w, "Stay human", 0, 0.3, 0.01, stayHuman, v -> v == 0 ? "off" : String.format("%.2f", v),
				v -> stayHuman = v);
		human.setPosition(x, y);
		human.setTooltip(Tooltip.create(Component.translatable("minigamebots.practice.human_help")));
		addRenderableWidget(human);
		addRenderableWidget(Button.builder(Component.literal("Skill: " + skill), b -> {
			var all = io.github.mudklip.minigamebots.client.profile.Skills.all(dir);
			skill = all.get((all.indexOf(skill) + 1) % all.size());
			b.setMessage(Component.literal("Skill: " + skill));
		}).pos(x + w + 88, y).width(90).tooltip(Tooltip.create(Component.translatable("minigamebots.training.skill_help"))).build());
		addRenderableWidget(Button.builder(Component.literal("PC: " + RESOURCES[resources]), b -> {
			resources = (resources + 1) % RESOURCES.length;
			b.setMessage(Component.literal("PC: " + RESOURCES[resources]));
		}).pos(x + w + 4, y).width(80).tooltip(Tooltip.create(Component.translatable("minigamebots.training.resources_help"))).build());
		y += 28;
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.practice.start"), b -> start(true))
				.pos(x, y).width(w / 2 - 2).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.practice.stop"), b -> stop())
				.pos(x + w / 2 + 2, y).width(w / 2 - 2).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.practice.exams"),
				b -> minecraft.gui.setScreen(new ExamsScreen(this, profile))).pos(width - 140, height - 28).width(64).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 28).width(60).build());
	}

	private Component objectiveLabel() {
		return Component.literal("Objective: " + (choices.isEmpty() ? "none" : choices.get(choice).label()));
	}

	private void start(boolean learn) {
		Objective o = objective();
		if (o == null) {
			error = "Choose an Objective first";
			return;
		}
		if (minecraft.player == null) {
			error = "Join a world first";
			return;
		}
		if (!MinigameBotsClient.instance().brain().runtime().installed()) {
			error = Component.translatable("minigamebots.training.needs_brain").getString();
			return;
		}
		if (MinigameBotsClient.instance().training().busy()) {
			error = "Wait for the imitation training to finish (they share the PC)";
			return;
		}
		error = null;
		MinigameBotsClient.instance().profiles().setActive(profile.id);
		MinigameBotsClient.instance().brain().practice(minecraft, profile, o, learn, stayHuman, RESOURCES[resources], skill);
		minecraft.gui.setScreen(null);
	}

	private void stop() {
		var brain = MinigameBotsClient.instance().brain();
		if ("practice".equals(brain.policy())) {
			io.github.mudklip.minigamebots.client.control.AgentControl.get().stop(minecraft,
					Component.translatable("minigamebots.control.human_on"));
			brain.stop();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		int w = Math.min(200, width / 2 - 20);
		if (param != null) {
			g.text(font, paramName() + ":", 10, param.getY() + 5, 0xFFA0A0A0, false);
		}
		int x = 10;
		int y = (param != null ? 54 + 24 : 54) + 28 + 26;
		Objective o = objective();
		int tw = width - 20;
		if (error != null) {
			y = wrap(g, error, x, y, tw, 0xFFFF8080);
		}
		if (o != null) {
			y = wrap(g, o.description == null || o.description.isBlank() ? o.rules.size() + " rules" : o.description.replace("{target}",
					params.getOrDefault("target", "")), x, y, tw, 0xFFB0B0B0);
			for (String warn : o.warnings()) {
				y = wrap(g, "⚠ " + warn, x, y, tw, 0xFFFFC060);
			}
		}
		y += 4;
		BrainManager brain = MinigameBotsClient.instance().brain();
		JsonObject status = brain.lastStatus();
		if ("practice".equals(brain.policy()) && status != null && status.has("policy_status")
				&& status.get("policy_status").isJsonObject()) {
			JsonObject ps = status.getAsJsonObject("policy_status");
			y = wrap(g, String.format("Practicing \"%s\": %s rounds, average reward %s (last %s)", str(ps, "objective"),
					str(ps, "episodes"), num(ps, "avg_return"), num(ps, "last_return")), x, y, tw, 0xFF80E080);
			if (ps.has("updates")) {
				y = wrap(g, String.format("%s improvements made; %s", str(ps, "updates"),
						bool(ps, "learning") ? "learning from the last " + str(ps, "rollout") + " ticks now..."
								: "collecting experience " + str(ps, "collected") + " / " + str(ps, "rollout")), x, y, tw, 0xFFE0E0E0);
			}
			if (ps.has("last_kl_prior")) {
				y = wrap(g, String.format("Distance from its human style: %s", num(ps, "last_kl_prior")), x, y, tw, 0xFFB0B0B0);
			}
			if (ps.has("last_error")) {
				y = wrap(g, "Learning error: " + str(ps, "last_error"), x, y, tw, 0xFFFF8080);
			}
		} else {
			y = wrap(g, Component.translatable("minigamebots.practice.help").getString(), x, y, tw, 0xFFA0A0A0);
		}
	}

	private int wrap(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
		for (var line : font.split(Component.literal(text), w)) {
			if (y + 9 > height - 32) {
				return y;
			}
			g.text(font, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "-" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	private static boolean bool(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsBoolean();
	}

	private static String num(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "-" : String.format("%.2f", e.getAsDouble());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
