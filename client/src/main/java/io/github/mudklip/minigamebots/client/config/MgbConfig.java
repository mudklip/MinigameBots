package io.github.mudklip.minigamebots.client.config;

import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.Boolean;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleSlider;
import dev.isxander.yacl3.config.v2.api.autogen.FloatSlider;
import dev.isxander.yacl3.config.v2.api.autogen.IntSlider;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import io.github.mudklip.minigamebots.client.control.HumanizerSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/** MinigameBots settings, stored in config/minigamebots.json5 and editable in-game. */
public final class MgbConfig {
	public static final ConfigClassHandler<MgbConfig> HANDLER = ConfigClassHandler.createBuilder(MgbConfig.class)
			.id(Identifier.fromNamespaceAndPath("minigamebots", "config"))
			.serializer(config -> GsonConfigSerializerBuilder.create(config)
					.setPath(FabricLoader.getInstance().getConfigDir().resolve("minigamebots.json5"))
					.setJson5(true)
					.build())
			.build();

	// Humanizer
	@AutoGen(category = "humanizer")
	@Boolean(colored = true)
	@SerialEntry
	public boolean humanizerEnabled = true;

	@AutoGen(category = "humanizer")
	@IntSlider(min = 0, max = 500, step = 10, format = "%d ms")
	@SerialEntry
	public int reactionMs = HumanizerSettings.DEFAULT.reactionMs();

	@AutoGen(category = "humanizer")
	@IntSlider(min = 0, max = 150, step = 5, format = "%d ms")
	@SerialEntry
	public int reactionJitterMs = HumanizerSettings.DEFAULT.reactionJitterMs();

	@AutoGen(category = "humanizer")
	@IntSlider(min = 100, max = 3000, step = 50, format = "%d°/s")
	@SerialEntry
	public int maxTurnSpeed = (int) HumanizerSettings.DEFAULT.maxTurnSpeed();

	@AutoGen(category = "humanizer")
	@IntSlider(min = 1000, max = 60000, step = 1000, format = "%d°/s²")
	@SerialEntry
	public int maxTurnAccel = (int) HumanizerSettings.DEFAULT.maxTurnAccel();

	@AutoGen(category = "humanizer")
	@FloatSlider(min = 0f, max = 1f, step = 0.01f, format = "%.2f°")
	@SerialEntry
	public float aimNoise = HumanizerSettings.DEFAULT.aimNoise();

	@AutoGen(category = "humanizer")
	@IntSlider(min = 1, max = 20, step = 1, format = "%d CPS")
	@SerialEntry
	public int maxCps = HumanizerSettings.DEFAULT.maxCps();

	// Background
	@AutoGen(category = "background")
	@Boolean(colored = true)
	@SerialEntry
	public boolean keepRunningUnfocused = true;

	@AutoGen(category = "background")
	@IntSlider(min = 10, max = 120, step = 5, format = "%d FPS")
	@SerialEntry
	public int aiFrameRate = 30;

	@AutoGen(category = "background")
	@DoubleSlider(min = 0.5, max = 10.0, step = 0.5, format = "%.1f px/°")
	@SerialEntry
	public double guiCursorPixelsPerDegree = 2.0;

	// Perception
	@AutoGen(category = "perception")
	@Boolean(colored = true)
	@SerialEntry
	public boolean fairPerception = true;

	@AutoGen(category = "perception")
	@IntSlider(min = 64, max = 256, step = 32, format = "%d px")
	@SerialEntry
	public int frameSize = 128;

	@AutoGen(category = "humanizer")
	@FloatSlider(min = 0f, max = 1f, step = 0.05f, format = "%.2f")
	@SerialEntry
	public float predictability = 0.3f;

	// Recording
	@AutoGen(category = "recording")
	@IntSlider(min = 50, max = 100, step = 5, format = "%d%%")
	@SerialEntry
	public int recordingJpegQuality = 90;

	@AutoGen(category = "recording")
	@IntSlider(min = 5, max = 480, step = 5, format = "%d min")
	@SerialEntry
	public int maxRecordingMinutes = 120;

	@AutoGen(category = "recording")
	@Boolean(colored = true)
	@SerialEntry
	public boolean showRecordingIndicator = true;

	// Brain (set from the Brain screen, not shown in this settings screen)
	/** Your own Python to build the brain's environment from; empty = download a standalone Python. */
	@SerialEntry
	public String customPythonPath = "";

	/** Id of the AI profile in use; empty = none chosen yet. Set from the Profiles screen. */
	@SerialEntry
	public String activeProfile = "";

	public static MgbConfig get() {
		return HANDLER.instance();
	}

	public HumanizerSettings humanizer() {
		if (!humanizerEnabled) {
			return HumanizerSettings.OFF;
		}
		return new HumanizerSettings(true, reactionMs, reactionJitterMs, maxTurnSpeed, maxTurnAccel, aimNoise, maxCps);
	}

	public static Screen screen(Screen parent) {
		return HANDLER.generateGui().generateScreen(parent);
	}
}
