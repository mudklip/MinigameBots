package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.Skills;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Goals: tell the AI what to do (a queue, worked through in order), or let it choose (free
 * play). The Manager picks the AI's skills for each goal, and instincts can interrupt.
 */
public final class GoalsScreen extends Screen {
	/** Goal type -> what its parameter is ("" = none), and whether it has a count. */
	private record Kind(String type, String param, String hint, boolean count) {
	}

	private static final List<Kind> KINDS = List.of(
			new Kind("mine", "item", "e.g. diamond, iron, coal", true),
			new Kind("hunt", "target", "e.g. minecraft:cow", true),
			new Kind("go_to", "waypoint", "a place name or type, e.g. base", false),
			new Kind("follow", "player", "player name", false),
			new Kind("protect", "player", "player name", false),
			new Kind("explore", "minutes", "how long", false),
			new Kind("survive", "minutes", "how long", false));

	/** Kept while the game runs, so the queue survives closing the screen. */
	private static final List<JsonObject> QUEUE = new ArrayList<>();
	private static boolean freePlay;
	private static double likeExplore = 1;
	private static double likeMine = 1;
	private static double likeHunt = 0.5;

	private final @Nullable Screen parent;
	private int kind;
	private EditBox param;
	private EditBox count;
	private @Nullable String error;

	public GoalsScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.goals.screen"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = 10;
		int y = 30;
		addRenderableWidget(Button.builder(Component.literal(KINDS.get(kind).type()), b -> {
			kind = (kind + 1) % KINDS.size();
			rebuildWidgets();
		}).pos(x, y).width(64).build());
		Kind k = KINDS.get(kind);
		param = addRenderableWidget(new EditBox(font, x + 68, y + 1, 130, 18, Component.literal(k.param())));
		param.setHint(Component.literal(k.hint()).withColor(0xFF707070));
		param.setMaxLength(64);
		count = addRenderableWidget(new EditBox(font, x + 202, y + 1, 30, 18, Component.literal("count")));
		count.setHint(Component.literal("1").withColor(0xFF707070));
		count.setMaxLength(4);
		count.visible = k.count();
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.goals.add"), b -> add()).pos(x + 236, y).width(50).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.goals.clear"), b -> {
			QUEUE.clear();
			rebuildWidgets();
		}).pos(x + 290, y).width(50).build());
		int ry = y + 28;
		for (int i = 0; i < Math.min(QUEUE.size(), 8); i++) {
			int idx = i;
			addRenderableWidget(Button.builder(Component.literal("x"), b -> {
				QUEUE.remove(idx);
				rebuildWidgets();
			}).pos(x, ry + i * 14 - 2).size(14, 12).build());
		}
		int by = height - 54;
		addRenderableWidget(Button.builder(Component.literal("Free play: " + (freePlay ? "on" : "off")), b -> {
			freePlay = !freePlay;
			b.setMessage(Component.literal("Free play: " + (freePlay ? "on" : "off")));
		}).pos(x, by).width(110).tooltip(Tooltip.create(Component.translatable("minigamebots.goals.free_help"))).build());
		// Three sliders share whatever room is right of the button, so narrow windows don't clip them.
		int sw = Math.max(60, Math.min(100, (width - x - 114 - 10) / 3 - 4));
		addRenderableWidget(slider(x + 114, by, sw, "Likes exploring", likeExplore, v -> likeExplore = v));
		addRenderableWidget(slider(x + 118 + sw, by, sw, "Likes mining", likeMine, v -> likeMine = v));
		addRenderableWidget(slider(x + 122 + 2 * sw, by, sw, "Likes hunting", likeHunt, v -> likeHunt = v));
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.goals.start"), b -> start())
				.pos(x, height - 28).width(110).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.practice.stop"), b -> {
			var brain = MinigameBotsClient.instance().brain();
			if ("manager".equals(brain.policy())) {
				io.github.mudklip.minigamebots.client.control.AgentControl.get().stop(minecraft,
						Component.translatable("minigamebots.control.human_on"));
			}
		}).pos(x + 114, height - 28).width(60).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 28).width(60).build());
	}

	private ValueSlider slider(int x, int y, int w, String label, double value, java.util.function.DoubleConsumer set) {
		ValueSlider s = new ValueSlider(w, label, 0, 2, 0.1, value, v -> String.format("%.1f", v), set);
		s.setPosition(x, y);
		return s;
	}

	private void add() {
		Kind k = KINDS.get(kind);
		String value = param.getValue().trim();
		if (value.isEmpty() && !k.param().equals("minutes")) {
			error = "Fill in the " + k.param();
			return;
		}
		JsonObject g = new JsonObject();
		g.addProperty("type", k.type());
		if (!value.isEmpty()) {
			if (k.param().equals("minutes")) {
				try {
					g.addProperty("minutes", Double.parseDouble(value));
				} catch (NumberFormatException e) {
					error = "Minutes must be a number";
					return;
				}
			} else {
				g.addProperty(k.param(), k.param().equals("target") && !value.contains(":") ? "minecraft:" + value : value);
			}
		}
		if (k.count()) {
			try {
				g.addProperty("count", count.getValue().isBlank() ? 1 : Integer.parseInt(count.getValue().trim()));
			} catch (NumberFormatException e) {
				error = "Count must be a whole number";
				return;
			}
		}
		error = null;
		QUEUE.add(g);
		rebuildWidgets();
	}

	private void start() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		if (p == null) {
			error = "Choose an AI first (AI Profiles > Use this AI)";
			return;
		}
		if (minecraft.player == null) {
			error = "Join a world first";
			return;
		}
		if (QUEUE.isEmpty() && !freePlay) {
			error = "Add a goal, or turn on free play";
			return;
		}
		JsonArray goals = new JsonArray();
		QUEUE.forEach(goals::add);
		JsonObject likes = new JsonObject();
		likes.addProperty("explore", likeExplore);
		likes.addProperty("mine", likeMine);
		likes.addProperty("hunt", likeHunt);
		MinigameBotsClient.instance().brain().playManager(minecraft, p, goals, freePlay, likes);
		minecraft.gui.setScreen(null);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		g.text(font, title.getString() + (p == null ? "" : ": " + p.name), 10, 12, 0xFFFFFFFF, false);
		int y = 58;
		if (QUEUE.isEmpty()) {
			g.text(font, freePlay ? "No goals: it will do what it feels like." : "No goals yet.", 10, y, 0xFF909090, false);
		}
		for (int i = 0; i < Math.min(QUEUE.size(), 8); i++) {
			g.text(font, (i + 1) + ". " + describe(QUEUE.get(i)), 28, y + i * 14, 0xFFFFFFFF, false);
		}
		int sy = y + Math.max(1, Math.min(QUEUE.size(), 8)) * 14 + 6;
		if (p != null) {
			var dir = MinigameBotsClient.instance().profiles().store().dir(p.id);
			var trained = Skills.trainedOnes(dir);
			g.text(font, font.plainSubstrByWidth("Trained skills: " + (trained.isEmpty() ? "none yet (it uses simple reflexes)"
					: String.join(", ", trained)), width - 20), 10, sy, 0xFFB0B0B0, false);
			sy += 12;
		}
		if (error != null) {
			g.text(font, error, 10, sy, 0xFFFF8080, false);
			sy += 12;
		}
		var brain = MinigameBotsClient.instance().brain();
		JsonObject st = brain.lastStatus();
		if ("manager".equals(brain.policy()) && st != null && st.has("policy_status") && st.get("policy_status").isJsonObject()) {
			JsonObject ps = st.getAsJsonObject("policy_status");
			String mode = str(ps, "mode");
			String line = "Now: " + switch (mode) {
				case "fight" -> "fighting";
				case "flee" -> "fleeing";
				case "eat" -> "eating";
				default -> str(ps, "goal");
			} + (str(ps, "why").isEmpty() || str(ps, "why").equals("-") ? "" : " (" + str(ps, "why") + ")") + " using " + str(ps, "skill");
			g.text(font, font.plainSubstrByWidth(line, width - 20), 10, sy, 0xFF80E080, false);
			sy += 12;
			if (ps.has("completed") && ps.get("completed").isJsonArray() && !ps.getAsJsonArray("completed").isEmpty()) {
				List<String> done = new ArrayList<>();
				ps.getAsJsonArray("completed").forEach(e -> done.add(e.getAsString()));
				g.text(font, font.plainSubstrByWidth("Done: " + String.join(", ", done), width - 20), 10, sy, 0xFFB0B0B0, false);
			}
		}
	}

	private static String describe(JsonObject g) {
		StringBuilder sb = new StringBuilder(g.get("type").getAsString().replace('_', ' '));
		for (String k : new String[] {"item", "target", "waypoint", "player"}) {
			if (g.has(k)) {
				sb.append(' ').append(g.get(k).getAsString().replace("minecraft:", ""));
			}
		}
		if (g.has("count")) {
			sb.append(" x").append(g.get("count").getAsInt());
		}
		if (g.has("minutes")) {
			sb.append(" for ").append(g.get("minutes").getAsDouble()).append(" min");
		}
		return sb.toString();
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "-" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
