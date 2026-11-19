package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.video.VideoLab;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Video Lab (DESIGN 3.10): turn gameplay videos into demos, and answer what it can't recognise. */
public final class VideoLabScreen extends Screen {
	private final @Nullable Screen parent;
	private List<Path> videos = List.of();
	private int selected;
	private static String tags = "";
	private EditBox tagBox;
	private volatile @Nullable String status;
	private volatile boolean busy;

	public VideoLabScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.video.screen"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		videos = VideoLab.videos();
		selected = Math.min(selected, Math.max(0, videos.size() - 1));
		int x = 10;
		int w = Math.min(300, width - 20);
		addRenderableWidget(Button.builder(Component.literal("Video: " + (videos.isEmpty() ? "(none: open the folder)"
				: videos.get(selected).getFileName())), b -> {
			if (!videos.isEmpty()) {
				selected = (selected + 1) % videos.size();
				rebuildWidgets();
			}
		}).pos(x, 50).width(w).build());
		tagBox = addRenderableWidget(new EditBox(font, x, 74, w, 18, Component.translatable("minigamebots.video.tags")));
		tagBox.setHint(Component.translatable("minigamebots.video.tags_hint"));
		tagBox.setValue(tags);
		tagBox.setResponder(v -> tags = v);
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.video.import"), b -> importVideo())
				.pos(x, 98).width(100).tooltip(Tooltip.create(Component.translatable("minigamebots.video.import_help"))).build()).active =
				!busy && !videos.isEmpty();
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.video.idm"), b -> trainIdm())
				.pos(x + 104, 98).width(130).tooltip(Tooltip.create(Component.translatable("minigamebots.video.idm_help"))).build()).active = !busy;
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.video.atlas"), b -> {
			try {
				status = "Atlas: " + VideoLab.buildAtlas(minecraft) + " item icons saved";
			} catch (Exception e) {
				status = "Atlas failed: " + e.getMessage();
			}
		}).pos(x, 122).width(100).tooltip(Tooltip.create(Component.translatable("minigamebots.video.atlas_help"))).build());
		addRenderableWidget(Button.builder(Component.literal("Questions (" + VideoLab.questions().size() + ")"),
				b -> minecraft.gui.setScreen(new QuestionScreen(this))).pos(x + 104, 122).width(130).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.video.share"), b -> {
			try {
				status = VideoLab.shareBooks();
			} catch (Exception e) {
				status = "Couldn't: " + e.getMessage();
			}
		}).pos(x, 146).width(100).tooltip(Tooltip.create(Component.translatable("minigamebots.video.share_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.profiles.folder"),
				b -> com.mojang.blaze3d.Blaze3D.openPath(VideoLab.root().resolve("videos"))).pos(x + 104, 146).width(130).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 28).width(60).build());
	}

	private void background(String what, List<String> args) {
		busy = true;
		status = what + "...";
		rebuildWidgets();
		Thread t = new Thread(() -> {
			try {
				JsonObject r = VideoLab.run(args, m -> status = what + ": " + m);
				status = summary(r);
			} catch (Exception e) {
				status = "Failed: " + e.getMessage();
			}
			busy = false;
			minecraft.execute(this::rebuildWidgets);
		}, "MinigameBots video lab");
		t.setDaemon(true);
		t.start();
	}

	private static String summary(JsonObject r) {
		if ("error".equals(r.has("type") ? r.get("type").getAsString() : "")) {
			return "Failed: " + r.get("message").getAsString();
		}
		if (r.has("key_accuracy")) {
			return String.format("Input guesser trained on %s moments: keys %.0f%% right, camera off by %s deg on average",
					r.get("examples").getAsString(), r.get("key_accuracy").getAsDouble() * 100, r.get("yaw_error_deg").getAsString());
		}
		if (r.has("kept")) {
			return String.format("Kept %s of %s shots (%s s of gameplay) as recordings; %s new question(s)",
					r.get("kept").getAsString(), r.get("shots").getAsString(), r.get("kept_seconds").getAsString(),
					r.get("questions").getAsString());
		}
		return "Done";
	}

	private void importVideo() {
		if (videos.isEmpty()) {
			return;
		}
		if (!java.nio.file.Files.isRegularFile(VideoLab.root().resolve("idm/idm.pt"))) {
			status = "Train the input guesser first (it learns from your recordings)";
			return;
		}
		Path recordings = io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().recorder().store().root();
		background("Importing", List.of("import", "--video", videos.get(selected).toString(), "--recordings", recordings.toString(),
				"--lab", VideoLab.root().toString(), "--tags", tags.trim()));
	}

	private void trainIdm() {
		Path recordings = io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().recorder().store().root();
		background("Training the input guesser", List.of("idm", "--recordings", recordings.toString(), "--lab",
				VideoLab.root().toString()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		g.textWithWordWrap(font, Component.translatable("minigamebots.video.help"), 10, 24, width - 20, 0xFFB0B0B0);
		if (status != null) {
			g.textWithWordWrap(font, Component.literal(status), 10, 172, width - 20, 0xFF80E080);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
