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
	private static final List<String> POLICIES = List.of("learned", "vpt", "follow", "random", "spin", "idle", "torch_smoke");
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
		// Three cards: 1 Setup (left), 2 Run it (right), add-ons (below); the log under them.
		int cw = (width - 24) / 2;
		int c1 = 8, c2 = 16 + cw, cy = 40;
		int bw = (cw - 16) / 2;
		int y3 = cy + 98;
		int aw = (width - 16 - 30) / 4;
		installGpu = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.install_gpu"),
				b -> install(RuntimeInstaller.Variant.CUDA)).pos(c1 + 6, cy + 46).width(bw).build());
		installCpu = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.install_cpu"),
				b -> install(RuntimeInstaller.Variant.CPU)).pos(c1 + 10 + bw, cy + 46).width(bw).build());
		ownPython = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.own_python"),
				b -> minecraft.gui.setScreen(new PythonPathScreen(this))).pos(c1 + 6, cy + 68).width(bw).build());
		cancel = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
			if (installer != null) {
				installer.cancel();
			}
		}).pos(c1 + 10 + bw, cy + 68).width(bw).build());
		update = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.update"), b -> update())
				.pos(c1 + 10 + bw, cy + 68).width(bw).tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.translatable("minigamebots.brain.update_help"))).build());

		startStop = addRenderableWidget(Button.builder(Component.empty(), b -> {
			if (brain.status() == BrainManager.Status.RUNNING || brain.status() == BrainManager.Status.STARTING) {
				if (AgentControl.aiActive() && AgentControl.get().source() instanceof BrainSource) {
					AgentControl.get().stop(minecraft, Component.translatable("minigamebots.control.human_on"));
				}
				brain.stop();
			} else {
				brain.start();
			}
		}).pos(c2 + 6, cy + 46).width(bw).build());
		policyButton = addRenderableWidget(Button.builder(policyLabel(), b -> {
			policyIndex = (policyIndex + 1) % POLICIES.size();
			b.setMessage(policyLabel());
		}).pos(c2 + 10 + bw, cy + 46).width(bw).build());
		play = addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.play"), b -> {
			String policy = POLICIES.get(policyIndex);
			var active = MinigameBotsClient.instance().profiles().active();
			if (policy.equals("learned")) {
				if (active == null) {
					return;
				}
				brain.playLearned(minecraft, active);
			} else if (policy.equals("vpt")) {
				String id = firstFoundation();
				if (id == null) {
					return;
				}
				brain.playFoundation(minecraft, id);
			} else {
				brain.play(minecraft, policy);
			}
			onClose();
		}).pos(c2 + 6, cy + 68).width(bw).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.brain.logs"),
				b -> Blaze3D.openPath(brain.runtime().logsDir())).pos(c2 + 10 + bw, cy + 68).width(bw).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.foundations.button"),
				b -> minecraft.gui.setScreen(new FoundationsScreen(this))).pos(14, y3 + 6).width(aw)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.translatable("minigamebots.foundations.button_help"))).build());
		boolean mindInstalled = mindReady(brain.runtime().root().resolve("mind"));
		addRenderableWidget(Button.builder(Component.literal(mindBusy ? "Installing..." : mindInstalled ? "Mind: ready"
				: "Mind (3 GB)"), b -> installMind("standard")).pos(14 + 2 * (aw + 6), y3 + 6).width(aw)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("minigamebots.mind.help"))).build())
				.active = !mindBusy && !mindInstalled && brain.runtime().installed();
		boolean voiceReady = java.nio.file.Files.isRegularFile(brain.runtime().root().resolve("mind/voice/whisper/config.json"));
		addRenderableWidget(Button.builder(Component.literal(voiceReady ? "Voice: ready" : "Voice (350 MB)"),
				b -> installMind("voice")).pos(14 + aw + 6, y3 + 6).width(aw)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("minigamebots.voice.help"))).build())
				.active = !mindBusy && !voiceReady && brain.runtime().installed();
		addRenderableWidget(Button.builder(Component.literal("Lite mind (1 GB)"), b -> installMind("lite")).pos(14 + 3 * (aw + 6), y3 + 6).width(aw)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("minigamebots.mind.lite_help"))).build())
				.active = !mindBusy && !mindInstalled && brain.runtime().installed();
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.pos(width - 60, 6).width(52).build());
		updateButtons();
	}

	/** The foundation the plain "vpt" brain plays with: 1x if installed, else any. */
	private static @Nullable String firstFoundation() {
		var store = MinigameBotsClient.instance().profiles().foundations();
		if (store.has("vpt-1x")) {
			return "vpt-1x";
		}
		var all = store.list();
		return all.isEmpty() ? null : all.get(0).id;
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

	private static volatile boolean mindBusy;

	/** A mind model with its weights downloaded (an interrupted download doesn't count). */
	private static boolean mindReady(java.nio.file.Path root) {
		try (var dirs = java.nio.file.Files.list(root)) {
			return dirs.anyMatch(d -> {
				try (var files = java.nio.file.Files.list(d)) {
					return files.anyMatch(f -> f.toString().endsWith(".safetensors"));
				} catch (Exception e) {
					return false;
				}
			});
		} catch (Exception e) {
			return false;
		}
	}

	/** Downloads the optional language model for free-form /ai commands (one time, about 3 GB). */
	private void installMind(String size) {
		// The installer adds Python packages; on Windows a running brain keeps some of their files
		// locked, so stop it first (it starts again when needed).
		brain.stop();
		mindBusy = true;
		rebuildWidgets();
		var toast = new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId();
		var mc = minecraft;
		Thread t = new Thread(() -> {
			String end;
			try {
				var r = io.github.mudklip.minigamebots.client.video.VideoLab.run("voice".equals(size) ? java.util.List.of("mind", "install-voice", "--root",
						brain.runtime().root().resolve("mind").toString()) : java.util.List.of("mind", "install", "--size", size, "--root",
						brain.runtime().root().resolve("mind").toString()), m -> mc.execute(() ->
						io.github.mudklip.minigamebots.client.overlay.Overlay.toast(toast,
								Component.literal("Mind"), Component.literal(m))));
				end = "error".equals(r.has("type") ? r.get("type").getAsString() : "") ? "Failed: " + r.get("message").getAsString()
						: "Installed. Try /ai with anything.";
				if (end.startsWith("Failed")) {
					end += " (details: minigamebots/brain/logs/videolab.log)";
				}
			} catch (Exception e) {
				end = "Failed: " + e.getMessage();
			}
			String msg = end;
			mindBusy = false;
			mc.execute(() -> {
				io.github.mudklip.minigamebots.client.overlay.Overlay.toast(toast,
						Component.literal("Mind"), Component.literal(msg));
				if (mc.gui.screen() == this) {
					rebuildWidgets();
				}
			});
		}, "MinigameBots mind install");
		t.setDaemon(true);
		t.start();
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
		String chosen = POLICIES.get(policyIndex);
		boolean learnedReady = (!chosen.equals("learned") || MinigameBotsClient.instance().profiles().active() != null)
				&& (!chosen.equals("vpt") || firstFoundation() != null);
		play.active = !busy && installed && minecraft.player != null && s != BrainManager.Status.STARTING && learnedReady;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int cw = (width - 24) / 2;
		int c1 = 8, c2 = 16 + cw, cy = 40;
		int y3 = cy + 98;
		Ui.card(g, c1, cy, cw, 92, Ui.CARD, Ui.ACCENT);
		Ui.card(g, c2, cy, cw, 92, Ui.CARD, Ui.GOOD);
		Ui.card(g, 8, y3, width - 16, 32, Ui.CARD, Ui.BORDER);
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lBrain"), 8, 10, Ui.TEXT, false);
		g.text(font, font.plainSubstrByWidth("The AI's brain runs on your PC: set it up once, then start it and let it play.", width - 70), 8, 22,
				Ui.MUTED, false);
		g.text(font, Component.literal("§l1  Setup"), c1 + 6, cy + 5, Ui.ACCENT, false);
		g.text(font, Component.literal("§l2  Run it"), c2 + 6, cy + 5, Ui.GOOD, false);
		BrainRuntime runtime = brain.runtime();
		BrainRuntime.BrainState state = runtime.readStateCached();
		String custom = MgbConfig.get().customPythonPath;
		int x = c1 + 6;
		int y = cy + 17;
		g.text(font, font.plainSubstrByWidth("Python: " + (custom.isBlank() ? "standalone (downloaded)" : custom), cw - 12), x, y, Ui.MUTED, false);
		if (runtime.installed() && state != null) {
			g.text(font, font.plainSubstrByWidth(RuntimeInstaller.summary(state.report), cw - 12), x, y + 10, Ui.GOOD, false);
		} else {
			g.text(font, font.plainSubstrByWidth(Component.translatable("minigamebots.brain.not_installed").getString(), cw - 12), x, y + 10,
					Ui.WARN, false);
		}
		RuntimeInstaller i = installer;
		if (i != null) {
			if (!i.done()) {
				g.text(font, font.plainSubstrByWidth("Setup: " + i.stage(), cw - 12), x, y + 20, Ui.TEXT, false);
				drawProgress(g, x, y + 29, cw - 12, i.progress());
			} else if (i.error() != null) {
				g.text(font, font.plainSubstrByWidth("Setup failed: " + i.error(), cw - 12), x, y + 20, 0xFFFF8080, false);
			} else {
				g.text(font, font.plainSubstrByWidth("Setup finished. " + i.stage(), cw - 12), x, y + 20, Ui.GOOD, false);
			}
		}
		BrainManager.Status s = brain.status();
		int color = switch (s) {
			case RUNNING -> Ui.GOOD;
			case FAILED -> 0xFFFF8080;
			default -> Ui.TEXT;
		};
		x = c2 + 6;
		g.text(font, font.plainSubstrByWidth("Brain: " + s.name().toLowerCase() + (brain.message().isEmpty() ? "" : " - " + brain.message()),
				cw - 12), x, y, color, false);
		JsonObject status = brain.lastStatus();
		if (s == BrainManager.Status.RUNNING && status != null) {
			g.text(font, font.plainSubstrByWidth(status.get("policy").getAsString() + ", " + status.get("ticks").getAsLong()
					+ " decisions, " + status.get("think_ms").getAsString() + " ms each", cw - 12), x, y + 10, Ui.MUTED, false);
			if (status.has("swaps") && status.get("swaps").getAsInt() > 0) {
				g.text(font, font.plainSubstrByWidth(String.format("Kept loaded: %d plays, last switch %.0f ms",
						status.get("swaps").getAsInt(), Math.max(0, brain.lastSwapMs())), cw - 12), x, y + 20, Ui.MUTED, false);
			}
		} else {
			g.text(font, font.plainSubstrByWidth("Start it, pick what plays, then give it control.", cw - 12), x, y + 10, Ui.MUTED, false);
		}
		BrainSource src = brain.source();
		if (src != null) {
			g.text(font, font.plainSubstrByWidth(String.format("Last session: %.1f ms round trip, %.0f%% late",
					src.latencyMs(), src.lateFraction() * 100), cw - 12), x, y + 20, Ui.MUTED, false);
		}
		x = 12;
		y = y3 + 36;
		// Latest output from setup or the brain.
		List<String> out = brain.output();
		int maxLines = Math.max(0, (height - y - 12) / 10);
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
