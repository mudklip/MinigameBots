package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.brain.BrainProcess;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Realism (M12): how human the AI's input looks to a critic trained on your recordings, your
 * style picks ("which was better?"), and its human limits tuned to match your own play.
 */
public final class RealismScreen extends Screen {
	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private final Path dir;
	private @Nullable JsonObject data;
	private volatile @Nullable String message;
	private volatile boolean busy;

	public RealismScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.translatable("minigamebots.realism.screen", profile.name));
		this.parent = parent;
		this.profile = profile;
		this.dir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		load();
	}

	private void load() {
		try {
			data = Files.isRegularFile(dir.resolve("realism.json"))
					? JsonParser.parseString(Files.readString(dir.resolve("realism.json"))).getAsJsonObject() : null;
		} catch (Exception e) {
			data = null;
		}
	}

	@Override
	protected void init() {
		int x = 10;
		int y = height - 52;
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.realism.analyze"), b -> analyze())
				.pos(x, y).width(120).tooltip(Tooltip.create(Component.translatable("minigamebots.realism.analyze_help"))).build()).active = !busy;
		Button apply = addRenderableWidget(Button.builder(Component.translatable("minigamebots.realism.apply"), b -> applyHumanizer())
				.pos(x + 124, y).width(150).tooltip(Tooltip.create(Component.translatable("minigamebots.realism.apply_help"))).build());
		apply.active = data != null && data.has("humanizer");
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.realism.compare"),
				b -> minecraft.gui.setScreen(new CompareScreen(this, profile))).pos(x, y + 24).width(120)
				.tooltip(Tooltip.create(Component.translatable("minigamebots.realism.compare_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, y + 24).width(60).build());
	}

	private void analyze() {
		var runtime = MinigameBotsClient.instance().brain().runtime();
		if (!runtime.installed()) {
			message = "Set up the brain first (Brain > Install)";
			return;
		}
		busy = true;
		message = "Analyzing recordings...";
		rebuildWidgets();
		Thread t = new Thread(() -> {
			try {
				var code = runtime.prepareCode();
				ArrayDeque<String> out = new ArrayDeque<>();
				int exit = BrainProcess.run(List.of(runtime.venvPython().toString(), "-m", "minigamebots_brain", "realism",
						"--profile", dir.toString(), "--recordings", MinigameBotsClient.instance().recorder().store().root().toString()),
						runtime.root(), Map.of("PYTHONPATH", code.toString()), runtime.logsDir().resolve("realism.log"), out, l -> { });
				String last = out.isEmpty() ? "" : out.peekLast();
				JsonObject r = last.startsWith("{") ? JsonParser.parseString(last).getAsJsonObject() : new JsonObject();
				message = exit == 0 ? "Done." : "Failed: " + (r.has("message") ? r.get("message").getAsString() : "see logs/realism.log");
			} catch (Exception e) {
				message = "Failed: " + e.getMessage();
			}
			busy = false;
			minecraft.execute(() -> {
				load();
				rebuildWidgets();
			});
		}, "MinigameBots realism");
		t.setDaemon(true);
		t.start();
	}

	private void applyHumanizer() {
		if (data == null || !data.has("humanizer")) {
			return;
		}
		JsonObject h = data.getAsJsonObject("humanizer");
		profile.humanizer.maxTurnSpeed = h.get("maxTurnSpeed").getAsFloat();
		profile.humanizer.maxTurnAccel = h.get("maxTurnAccel").getAsFloat();
		profile.humanizer.aimNoise = h.get("aimNoise").getAsFloat();
		try {
			MinigameBotsClient.instance().profiles().store().save(profile);
			message = "Applied: the AI now turns and wobbles like you.";
		} catch (Exception e) {
			message = "Couldn't save: " + e.getMessage();
		}
	}

	private String num(String k) {
		return data != null && data.has(k) ? data.get(k).getAsString() : "-";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		g.textWithWordWrap(font, Component.translatable("minigamebots.realism.help"), 10, 26, width - 20, 0xFFB0B0B0);
		int y = 70;
		if (data == null) {
			g.text(font, "Not analyzed yet. Record some of your own play, then press Analyze.", 10, y, 0xFF909090, false);
		} else {
			String acc = data.has("critic_accuracy") ? Math.round(data.get("critic_accuracy").getAsDouble() * 100) + "%"
					: "needs both your recordings and AI play";
			g.text(font, "Realism critic: " + acc + " (" + num("human_windows") + " of your windows, "
					+ num("machine_windows") + " AI/teacher)", 10, y, 0xFFFFFFFF, false);
			g.text(font, "Style model: " + (data.has("style_pairs") ? num("style_pairs") + " picks" : "make at least 2 picks"),
					10, y + 12, 0xFFFFFFFF, false);
			if (data.has("humanizer")) {
				JsonObject h = data.getAsJsonObject("humanizer");
				g.text(font, String.format("Your turning: up to %s deg/s, accel %s, wobble %s (AI now: %.0f, %.0f, %.3f)",
						h.get("maxTurnSpeed").getAsString(), h.get("maxTurnAccel").getAsString(), h.get("aimNoise").getAsString(),
						profile.humanizer.maxTurnSpeed, profile.humanizer.maxTurnAccel, profile.humanizer.aimNoise), 10, y + 24, 0xFFFFFFFF, false);
			}
		}
		if (message != null) {
			g.text(font, font.plainSubstrByWidth(message, width - 20), 10, height - 66, 0xFF80E080, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
