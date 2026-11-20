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

	// Training worlds (set on the Worlds tab > Customize; defaults = the classic setup)
	@SerialEntry
	public String worldKit = "default";
	@SerialEntry
	public String worldSize = "normal";
	@SerialEntry
	public String worldType = "flat";
	@SerialEntry
	public String worldMix = "single";
	@SerialEntry
	public int worldRound = 0;
	@SerialEntry
	public String worldPractice = "duel";
	@SerialEntry
	public int worldSpeed = 2;
	/** A short exam in training worlds after every training of a fighting network (singleplayer). */
	@SerialEntry
	public boolean autoExam = true;
	/** Core minigames: the bots also fight each other (off: they all play against the AI). */
	@SerialEntry
	public boolean coreFreeForAll = false;
	/** When it last slept (ISO time): the next sleep replays what was recorded since. */
	@SerialEntry
	public String lastSleep = "";
	/** Off unless you turn it on: minimising Minecraft (your action) puts it to sleep; restoring wakes it. */
	@SerialEntry
	public boolean sleepWhenMinimised = false;

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
	@Boolean(colored = true)
	@SerialEntry
	public boolean fairHealth = true;

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

	@AutoGen(category = "autonomy")
	@Boolean(colored = true)
	@SerialEntry
	public boolean autoRespawn = true;

	@AutoGen(category = "autonomy")
	@IntSlider(min = 0, max = 24, step = 1, format = "%d h")
	@SerialEntry
	public int sessionHours = 0;

	@AutoGen(category = "autonomy")
	@IntSlider(min = 0, max = 50, step = 1, format = "%d")
	@SerialEntry
	public int maxDeaths = 0;

	@AutoGen(category = "autonomy")
	@Boolean(colored = true)
	@SerialEntry
	public boolean stopWhenHealthCritical = false;

	@AutoGen(category = "autonomy")
	@Boolean(colored = true)
	@SerialEntry
	public boolean chatReplies = false;

	@AutoGen(category = "autonomy")
	@Boolean(colored = true)
	@SerialEntry
	public boolean tagChatAsAi = true;

	@AutoGen(category = "autonomy")
	@Boolean(colored = true)
	@SerialEntry
	public boolean speakReplies = false;

	@AutoGen(category = "autonomy")
	@IntSlider(min = 4, max = 64, step = 4, format = "%d blocks")
	@SerialEntry
	public int protectRadius = 16;

	// Brain (set from the Brain screen, not shown in this settings screen)
	/** Your own Python to build the brain's environment from; empty = download a standalone Python. */
	@SerialEntry
	public String customPythonPath = "";

	/** Id of the AI profile in use; empty = none chosen yet. Set from the Profiles screen. */
	@SerialEntry
	public String activeProfile = "";

	/** The one-time "there's a guide" hint was shown. */
	@SerialEntry
	public boolean guideHintShown = false;

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
