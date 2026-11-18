package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.Blaze3D;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.brain.BrainManager;
import io.github.mudklip.minigamebots.client.brain.BrainRuntime;
import io.github.mudklip.minigamebots.client.brain.BrainSource;
import io.github.mudklip.minigamebots.client.brain.RuntimeInstaller;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Setup and control of the brain: install the Python runtime (once), start and stop the brain,
 * pick a test brain and hand it control. Shows status and the latest log lines.
 */
public final class BrainScreen extends Screen {
	private static final List<String> POLICIES = List.of("learned", "follow", "random", "spin", "idle", "torch_smoke");
	private static @Nullable RuntimeInstaller installer;
	private static @Nullable Thread installThread;

	private final @Nullable Screen parent;
	private final BrainManager brain;
	private int policyIndex;
	private Button installGpu;
	private Button installCpu;
	private Button ownPython;
	private Button cancel;
	private Button startStop;
	private Button policyButton;
	private Button play;
	private Button update;

	public BrainScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.brain.screen"));
		this.parent = parent;
		this.brain = MinigameBotsClient.instance().brain();
		policyIndex = Math.max(0, POLICIES.indexOf(brain.policy()));
	}

	@Override
	protected void init() {
		int left = 10;
		int y = height - 54;
		installGpu = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.install_gpu"),
				b -> install(RuntimeInstaller.Variant.CUDA)).pos(left, y).width(96).build());
		installCpu = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.install_cpu"),
				b -> install(RuntimeInstaller.Variant.CPU)).pos(left + 100, y).width(96).build());
		ownPython = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.own_python"),
				b -> minecraft.gui.setScreen(new PythonPathScreen(this))).pos(left + 200, y).width(110).build());
		cancel = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
			if (installer != null) {
				installer.cancel();
			}
		}).pos(left + 314, y).width(60).build());
		update = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.update"), b -> update())
				.pos(left + 314, y).width(60).tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.translatable("minigamebots.brain.update_help"))).build());

		int y2 = height - 28;
		startStop = addRenderableWidget(Button.builder(Component.empty(), b -> {
			if (brain.status() == BrainManager.Status.RUNNING || brain.status() == BrainManager.Status.STARTING) {
				if (AgentControl.aiActive() && AgentControl.get().source() instanceof BrainSource) {
					AgentControl.get().stop(minecraft, Component.translatable("minigamebots.control.human_on"));
				}
				brain.stop();
			} else {
				brain.start();
			}
		}).pos(left, y2).width(70).build());
		policyButton = addRenderableWidget(Button.builder(policyLabel(), b -> {
			policyIndex = (policyIndex + 1) % POLICIES.size();
			b.setMessage(policyLabel());
		}).pos(left + 74, y2).width(110).build());
		play = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.play"), b -> {
			String policy = POLICIES.get(policyIndex);
			var active = MinigameBotsClient.instance().profiles().active();
			if (policy.equals("learned")) {
				if (active == null) {
					return;
				}
				brain.playLearned(minecraft, active);
			} else {
				brain.play(minecraft, policy);
			}
			onClose();
		}).pos(left + 188, y2).width(110).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.logs"),
				b -> Blaze3D.openPath(brain.runtime().logsDir())).pos(left + 302, y2).width(40).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.pos(width - 70, y2).width(60).build());
		updateButtons();
	}

	private Component policyLabel() {
		return Component.translatable("minigamebots.brain.policy", POLICIES.get(policyIndex));
	}

	private void install(RuntimeInstaller.Variant variant) {
		if (installing()) {
			return;
		}
		if (brain.status() == BrainManager.Status.RUNNING) {
			brain.stop();
		}
		RuntimeInstaller i = new RuntimeInstaller(brain.runtime(), brain.outputSink());
		String custom = MgbConfig.get().customPythonPath;
		installer = i;
		installThread = new Thread(() -> i.install(variant, custom.isBlank() ? null : custom), "MinigameBots brain setup");
		installThread.setDaemon(true);
		installThread.start();
	}

	private void update() {
		if (installing()) {
			return;
		}
		RuntimeInstaller i = new RuntimeInstaller(brain.runtime(), brain.outputSink());
		installer = i;
		installThread = new Thread(i::updatePackages, "MinigameBots brain update");
		installThread.setDaemon(true);
		installThread.start();
	}

	private static boolean installing() {
		return installer != null && !installer.done();
	}

	@Override
	public void tick() {
		updateButtons();
	}

	private void updateButtons() {
		boolean busy = installing();
		boolean installed = brain.runtime().installed();
		installGpu.active = !busy;
		installCpu.active = !busy;
		ownPython.active = !busy;
		cancel.visible = busy;
		update.visible = !busy && installed;
		BrainManager.Status s = brain.status();
		boolean running = s == BrainManager.Status.RUNNING || s == BrainManager.Status.STARTING;
		startStop.setMessage(Component.translatable(running ? "minigamebots.brain.stop" : "minigamebots.brain.start"));
		startStop.active = !busy && installed;
		policyButton.active = !busy && installed;
		boolean learnedReady = !POLICIES.get(policyIndex).equals("learned")
				|| MinigameBotsClient.instance().profiles().active() != null;
		play.active = !busy && installed && minecraft.player != null && s != BrainManager.Status.STARTING && learnedReady;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		int x = 12;
		int y = 26;
		BrainRuntime runtime = brain.runtime();
		BrainRuntime.BrainState state = runtime.readStateCached();
		String custom = MgbConfig.get().customPythonPath;
		y = line(g, x, y, "Python: " + (custom.isBlank() ? "standalone (downloaded by Setup)" : custom), 0xFFB0B0B0);
		if (runtime.installed() && state != null) {
			y = line(g, x, y, RuntimeInstaller.summary(state.report), 0xFF80E080);
		} else {
			y = line(g, x, y, Component.translatable("minigamebots.brain.not_installed").getString(), 0xFFFFC060);
		}

		RuntimeInstaller i = installer;
		if (i != null) {
			if (!i.done()) {
				y = line(g, x, y + 4, "Setup: " + i.stage(), 0xFFFFFFFF);
				drawProgress(g, x, y, width - 24, i.progress());
				y += 10;
			} else if (i.error() != null) {
				y = line(g, x, y + 4, "Setup failed: " + i.error(), 0xFFFF8080);
			} else {
				y = line(g, x, y + 4, "Setup finished. " + i.stage(), 0xFF80E080);
			}
		}

		BrainManager.Status s = brain.status();
		int color = switch (s) {
			case RUNNING -> 0xFF80E080;
			case FAILED -> 0xFFFF8080;
			default -> 0xFFE0E0E0;
		};
		y = line(g, x, y + 4, "Brain: " + s.name().toLowerCase() + (brain.message().isEmpty() ? "" : " - " + brain.message()), color);
		JsonObject status = brain.lastStatus();
		if (s == BrainManager.Status.RUNNING && status != null) {
			y = line(g, x, y, "Policy " + status.get("policy").getAsString() + ", " + status.get("ticks").getAsLong()
					+ " decisions, thinking " + status.get("think_ms").getAsString() + " ms, "
					+ status.get("policy_status").toString(), 0xFFB0B0B0);
		}
		BrainSource src = brain.source();
		if (src != null) {
			y = line(g, x, y, String.format("Last session: round trip %.1f ms, no fresh answer on %.0f%% of ticks",
					src.latencyMs(), src.lateFraction() * 100), 0xFFB0B0B0);
		}

		// Latest output from setup or the brain.
		List<String> out = brain.output();
		int maxLines = Math.max(0, (height - 60 - y - 8) / 10);
		g.fill(x - 2, y + 4, width - 10, y + 6 + maxLines * 10, 0x66000000);
		int from = Math.max(0, out.size() - maxLines);
		int ly = y + 6;
		for (String l : out.subList(from, out.size())) {
			g.text(font, font.plainSubstrByWidth(l, width - 26), x, ly, 0xFFC0C0C0, false);
			ly += 10;
		}
	}

	private void drawProgress(GuiGraphicsExtractor g, int x, int y, int w, double p) {
		g.fill(x, y, x + w, y + 6, 0xFF303030);
		if (p >= 0) {
			g.fill(x, y, x + (int) (w * Math.min(1, p)), y + 6, 0xFF43A047);
		} else {
			// Unknown length: a moving block.
			int block = w / 6;
			int pos = (int) ((System.currentTimeMillis() / 8) % (w + block)) - block;
			g.fill(x + Math.max(0, pos), y, x + Math.min(w, pos + block), y + 6, 0xFF43A047);
		}
	}

	private int line(GuiGraphicsExtractor g, int x, int y, String text, int color) {
		g.text(font, font.plainSubstrByWidth(text, width - x - 10), x, y, color, false);
		return y + 10;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
