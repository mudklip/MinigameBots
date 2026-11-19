package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.mudklip.minigamebots.client.video.VideoLab;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The Question Queue: things in imported videos the atlas couldn't recognise, most useful
 * first. Answers go into the Knowledge Books of the video's tags, so the next video with the
 * same tags doesn't ask again. Skip is always fine.
 */
public final class QuestionScreen extends Screen {
	private static final Identifier TEX = Identifier.fromNamespaceAndPath("minigamebots", "question");

	private final @Nullable Screen parent;
	private @Nullable JsonObject q;
	private @Nullable DynamicTexture texture;
	private int behavior;
	private int skip;
	private EditBox name;

	public QuestionScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.question.screen"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		var all = VideoLab.questions();
		q = all.isEmpty() ? null : all.get(Math.min(skip, all.size() - 1)).getAsJsonObject();
		loadImage();
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 28).width(60).build());
		if (q == null) {
			return;
		}
		int x = 90;
		int y = 40;
		var sugg = q.getAsJsonArray("suggestions");
		for (int i = 0; i < Math.min(3, sugg.size()); i++) {
			String id = sugg.get(i).getAsString();
			addRenderableWidget(Button.builder(Component.literal("It's " + id), b -> answer(id, "vanilla"))
					.pos(x, y + i * 22).width(Math.min(220, width - x - 10)).build());
		}
		int cy = y + 78;
		name = addRenderableWidget(new EditBox(font, x, cy, 140, 18, Component.literal("name")));
		name.setHint(Component.translatable("minigamebots.question.name_hint"));
		addRenderableWidget(Button.builder(Component.literal("Does: " + VideoLab.BEHAVIORS.get(behavior)), b -> {
			behavior = (behavior + 1) % VideoLab.BEHAVIORS.size();
			b.setMessage(Component.literal("Does: " + VideoLab.BEHAVIORS.get(behavior)));
		}).pos(x + 144, cy).width(90).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.question.save"), b -> {
			if (!name.getValue().isBlank()) {
				answer(name.getValue().trim(), "custom");
			}
		}).pos(x, cy + 22).width(140).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.question.ignore"), b -> answer(null, ""))
				.pos(x, cy + 50).width(68).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.compare.skip"), b -> {
			skip++;
			rebuildWidgets();
		}).pos(x + 72, cy + 50).width(68).build());
	}

	private void answer(@Nullable String answer, String kind) {
		try {
			VideoLab.answer(q, answer, kind, VideoLab.BEHAVIORS.get(behavior));
		} catch (Exception e) {
			io.github.mudklip.minigamebots.client.MinigameBotsClient.LOGGER.warn("Couldn't save answer", e);
		}
		rebuildWidgets();
	}

	private void loadImage() {
		release();
		if (q == null) {
			return;
		}
		Path p = VideoLab.root().resolve("questions").resolve(q.get("id").getAsString() + ".png");
		try (InputStream in = Files.newInputStream(p)) {
			NativeImage img = NativeImage.read(in);
			texture = new DynamicTexture(() -> "MinigameBots question", img);
			minecraft.getTextureManager().register(TEX, texture);
		} catch (Exception e) {
			texture = null;
		}
	}

	private void release() {
		if (texture != null) {
			minecraft.getTextureManager().release(TEX);
			texture = null;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		if (q == null) {
			g.text(font, "No questions. Import a video to get some.", 10, 30, 0xFF909090, false);
			return;
		}
		g.text(font, "What is this item? (from " + q.get("video").getAsString() + ")", 10, 24, 0xFFB0B0B0, false);
		if (texture != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, TEX, 10, 40, 0, 0, 64, 64, 64, 64, 64, 64);
		}
		g.text(font, "Custom or modded: name it and say what it does", 90, 107, 0xFFB0B0B0, false);
	}

	@Override
	public void onClose() {
		release();
		minecraft.gui.setScreen(parent);
	}
}
