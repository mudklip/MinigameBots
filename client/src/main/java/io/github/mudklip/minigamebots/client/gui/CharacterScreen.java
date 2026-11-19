package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.Skills;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The AI's character for chat (optional O1): write your own, or let the mind make one to edit. */
public final class CharacterScreen extends Screen {
	private static final String[][] FIELDS = {
			{"name", "Name in chat", "e.g. Blaze"},
			{"personality", "Personality", "e.g. cocky PvP sweat, secretly kind"},
			{"style", "How it talks", "e.g. short, lowercase, says gg a lot"},
			{"background", "About it", "e.g. lives in a mountain base, loves cats"},
			{"avoid", "Never talk about", "comma-separated, e.g. politics, real life"}};

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private final EditBox[] boxes = new EditBox[FIELDS.length];
	private @Nullable String message;
	private boolean busy;

	public CharacterScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.translatable("minigamebots.character.screen", profile.name));
		this.parent = parent;
		this.profile = profile;
	}

	private String get(String k) {
		var c = profile.character;
		return switch (k) {
			case "name" -> c.name;
			case "personality" -> c.personality;
			case "style" -> c.style;
			case "background" -> c.background;
			default -> c.avoid;
		};
	}

	private void set(String k, String v) {
		var c = profile.character;
		switch (k) {
			case "name" -> c.name = v;
			case "personality" -> c.personality = v;
			case "style" -> c.style = v;
			case "background" -> c.background = v;
			default -> c.avoid = v;
		}
	}

	@Override
	protected void init() {
		int x = 110;
		int w = Math.min(260, width - x - 10);
		addRenderableWidget(Button.builder(Component.literal("Chat: " + (profile.character.chat ? "on" : "off")), b -> {
			profile.character.chat = !profile.character.chat;
			b.setMessage(Component.literal("Chat: " + (profile.character.chat ? "on" : "off")));
		}).pos(10, 26).width(90).tooltip(Tooltip.create(Component.translatable("minigamebots.character.chat_help"))).build());
		for (int i = 0; i < FIELDS.length; i++) {
			String key = FIELDS[i][0];
			EditBox e = new EditBox(font, x, 50 + i * 22, w, 18, Component.literal(FIELDS[i][1]));
			e.setMaxLength(key.equals("name") ? 16 : 120);
			e.setValue(get(key));
			e.setHint(Component.literal(FIELDS[i][2]));
			e.setResponder(v -> set(key, v.trim()));
			boxes[i] = addRenderableWidget(e);
		}
		int by = 50 + FIELDS.length * 22 + 4;
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.character.make"), b -> make())
				.pos(x, by).width(130).tooltip(Tooltip.create(Component.translatable("minigamebots.character.make_help"))).build()).active = !busy;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 28).width(60).build());
	}

	private void make() {
		busy = true;
		message = "Thinking up a character...";
		rebuildWidgets();
		JsonObject req = new JsonObject();
		req.addProperty("type", "make_character");
		req.addProperty("ai_name", profile.name);
		JsonArray skills = new JsonArray();
		Skills.trainedOnes(MinigameBotsClient.instance().profiles().store().dir(profile.id)).forEach(skills::add);
		req.add("skills", skills);
		MinigameBotsClient.instance().brain().ask(req, r -> {
			busy = false;
			if (r.has("error")) {
				message = r.get("error").getAsString();
			} else {
				JsonObject c = r.getAsJsonObject("character");
				for (String k : new String[] {"name", "personality", "style", "background"}) {
					if (c.has(k) && !c.get(k).getAsString().isBlank()) {
						set(k, c.get(k).getAsString());
					}
				}
				message = "Here's one. Edit anything you like, or make another.";
			}
			if (minecraft.gui.screen() == this) {
				rebuildWidgets();
			}
		});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		for (int i = 0; i < FIELDS.length; i++) {
			g.text(font, FIELDS[i][1], 10, 55 + i * 22, 0xFFB0B0B0, false);
		}
		int by = 50 + FIELDS.length * 22 + 30;
		if (message != null) {
			g.text(font, font.plainSubstrByWidth(message, width - 20), 10, by, 0xFF80E080, false);
		}
		g.textWithWordWrap(font, Component.translatable("minigamebots.character.help"), 10, by + 12, width - 90, 0xFF909090);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
