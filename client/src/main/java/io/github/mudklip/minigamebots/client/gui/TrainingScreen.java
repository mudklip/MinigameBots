package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileFaces;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.record.Recorder;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.train.TrainingManager;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Train an AI by imitation: pick recordings, a plan and how hard the PC may work, then watch the
 * graphs. Training keeps running if you close this screen.
 */
public final class TrainingScreen extends Screen {
	private static final int ROW = 22;

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private final TrainingManager training;
	private final ProfileFaces faces = new ProfileFaces();
	private final List<RecordingMeta> recordings;
	private final Set<String> chosen = new HashSet<>();
	private TrainingManager.Plan plan = TrainingManager.Plan.STANDARD;
	private String skill = "general";
	private TrainingManager.Resources resources = TrainingManager.Resources.QUIET;
	private boolean fresh;
	/** null = Lite, else an installed foundation id. */
	private @Nullable String base;
	private final List<@Nullable String> bases = new ArrayList<>();
	private @Nullable String error;
	private int scroll;

	private Button start;
	private Button stop;
	private Button play;

	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public TrainingScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.translatable("minigamebots.training.screen", profile.name));
		this.parent = parent;
		this.profile = profile;
		this.training = MinigameBotsClient.instance().training();
		var rec = MinigameBotsClient.instance().recorder();
		this.recordings = new ArrayList<>(rec.store().list(rec.currentId()));
		this.recordings.removeIf(r -> !r.complete || r.ticks < 100);
		for (RecordingMeta r : recordings) {
			chosen.add(r.id);
		}
		bases.add(null);
		for (var f : MinigameBotsClient.instance().profiles().foundations().list()) {
			bases.add(f.id);
		}
		// Keep training on whatever this AI was trained on before, if it's still installed.
		base = bases.contains(profile.foundation) ? profile.foundation : null;
	}

	@Override
	protected void init() {
		listX = 10;
		listY = 48;
		listW = Math.min(220, width / 2 - 20);
		listH = height - listY - 40;
		int rx = listX + listW + 14;
		int bw = Math.min(170, width - rx - 10);

		addRenderableWidget(Button.builder(baseLabel(), b -> {
			base = bases.get((bases.indexOf(base) + 1) % bases.size());
			b.setMessage(baseLabel());
		}).pos(rx, 48).width(bw / 2 - 2).tooltip(Tooltip.create(Component.translatable("minigamebots.training.base_help"))).build());
		addRenderableWidget(Button.builder(planLabel(), b -> {
			plan = TrainingManager.Plan.values()[(plan.ordinal() + 1) % TrainingManager.Plan.values().length];
			b.setMessage(planLabel());
		}).pos(rx, 72).width(bw / 2 - 2).tooltip(Tooltip.create(Component.translatable("minigamebots.training.plan_help"))).build());
		addRenderableWidget(Button.builder(Component.literal("Skill: " + skill), b -> {
			var all = io.github.mudklip.minigamebots.client.profile.Skills.all(MinigameBotsClient.instance().profiles().store().dir(profile.id));
			skill = all.get((all.indexOf(skill) + 1) % all.size());
			rebuildWidgets();
		}).pos(rx + bw / 2 + 2, 72).width(bw / 2 - 2).tooltip(Tooltip.create(Component.translatable("minigamebots.training.skill_help"))).build());
		addRenderableWidget(Button.builder(resourcesLabel(), b -> {
			resources = TrainingManager.Resources.values()[(resources.ordinal() + 1) % TrainingManager.Resources.values().length];
			b.setMessage(resourcesLabel());
		}).pos(rx + bw / 2 + 2, 48).width(bw / 2 - 2).tooltip(Tooltip.create(Component.translatable("minigamebots.training.resources_help"))).build());
		boolean trainedBefore = io.github.mudklip.minigamebots.client.profile.Skills.trained(
				MinigameBotsClient.instance().profiles().store().dir(profile.id), skill);
		addRenderableWidget(Checkbox.builder(Component.translatable("minigamebots.training.fresh"), font)
				.pos(rx, 98).selected(!trainedBefore).onValueChange((c, on) -> fresh = on)
				.tooltip(Tooltip.create(Component.translatable("minigamebots.training.fresh_help"))).build());
		fresh = !trainedBefore;
		addRenderableWidget(Checkbox.builder(Component.translatable("minigamebots.training.aim_drill"), font)
				.pos(rx + bw / 2 + 2, 98).selected(training.aimDrill()).onValueChange((c, on) -> training.setAimDrill(on))
				.tooltip(Tooltip.create(Component.translatable("minigamebots.training.aim_drill_help"))).build());

		start = addRenderableWidget(Button.builder(Component.translatable("minigamebots.training.start"), b -> startTraining())
				.pos(rx, 124).width(bw / 2 - 2).build());
		stop = addRenderableWidget(Button.builder(Component.translatable("minigamebots.training.stop"), b -> training.stop())
				.pos(rx + bw / 2 + 2, 124).width(bw / 2 - 2).build());

		play = addRenderableWidget(Button.builder(Component.translatable("minigamebots.training.play"), b -> {
			MinigameBotsClient.instance().profiles().setActive(profile.id);
			MinigameBotsClient.instance().brain().playLearned(minecraft, profile);
			minecraft.gui.setScreen(null);
		}).pos(width - 190, height - 28).width(110).tooltip(Tooltip.create(Component.translatable("minigamebots.training.play_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 74, height - 28).width(64).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.versions.button"),
				b -> minecraft.gui.setScreen(new VersionsScreen(this, profile))).pos(width - 228, 8).width(70)
				.tooltip(Tooltip.create(Component.translatable("minigamebots.versions.button_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.practice.button"),
				b -> minecraft.gui.setScreen(new PracticeScreen(this, profile))).pos(width - 154, 8).width(70)
				.tooltip(Tooltip.create(Component.translatable("minigamebots.practice.button_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.practice.exams"),
				b -> minecraft.gui.setScreen(new ExamsScreen(this, profile))).pos(width - 80, 8).width(70).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.training.all"), b -> {
			recordings.forEach(r -> chosen.add(r.id));
		}).pos(listX, height - 28).width(50).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.training.none"), b -> chosen.clear())
				.pos(listX + 54, height - 28).width(50).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.teacher.button"),
				b -> minecraft.gui.setScreen(new TeacherScreen(this))).pos(listX + 108, height - 28).width(80)
				.tooltip(Tooltip.create(Component.translatable("minigamebots.teacher.button_help"))).build());
		updateButtons();
	}

	private Component baseLabel() {
		if (base == null) {
			return Component.translatable("minigamebots.training.base", "Lite");
		}
		if (base.startsWith("vpt-")) {
			return Component.translatable("minigamebots.training.base", "VPT " + base.substring(4));
		}
		return Component.translatable("minigamebots.training.base", base);
	}

	private Component planLabel() {
		return Component.literal("Plan: " + plan.label);
	}

	private Component resourcesLabel() {
		return Component.translatable("minigamebots.training.resources", resources.label);
	}

	private void startTraining() {
		List<RecordingMeta> picked = recordings.stream().filter(r -> chosen.contains(r.id)).toList();
		try {
			training.start(profile, picked, plan, resources, fresh, base, skill);
			error = null;
		} catch (IOException e) {
			error = e.getMessage();
		}
		updateButtons();
	}

	@Override
	public void tick() {
		updateButtons();
	}

	private void updateButtons() {
		boolean busy = training.busy();
		start.active = !busy && (!chosen.isEmpty() || training.aimDrill() && base == null)
				&& MinigameBotsClient.instance().brain().runtime().installed();
		stop.active = busy && training.status() == TrainingManager.Status.RUNNING && profile.id.equals(training.profileId());
		play.active = !busy && Files.exists(MinigameBotsClient.instance().profiles().store().dir(profile.id)
				.resolve("core/model.safetensors")) && minecraft.player != null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.fill(10, 6, 42, 38, 0xFF6A6A6A);
		faces.draw(g, MinigameBotsClient.instance().profiles().store(), profile, 12, 8, 28);
		g.text(font, title, 48, 10, 0xFFFFFFFF, false);
		String sub = profile.stats.trainingHours > 0 ? String.format("%.1f h trained so far", profile.stats.trainingHours)
				: Component.translatable("minigamebots.profiles.untrained").getString();
		g.text(font, sub, 48, 22, 0xFFA0A0A0, false);

		drawRecordings(g, mouseX, mouseY);
		drawStatus(g, listX + listW + 14, 150);
	}

	private void drawRecordings(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		long chosenTicks = 0;
		for (RecordingMeta r : recordings) {
			if (chosen.contains(r.id)) {
				chosenTicks += r.ticks;
			}
		}
		g.text(font, Component.translatable("minigamebots.training.learn_from", Recorder.time((int) chosenTicks)),
				listX, listY - 11, 0xFFA0A0A0, false);
		g.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, 0xFF404040);
		g.fill(listX, listY, listX + listW, listY + listH, 0xC0101010);
		if (recordings.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("minigamebots.training.no_recordings"), listX + 4, listY + 4,
					listW - 8, 0xFFFFC060);
			return;
		}
		int visible = listH / ROW;
		scroll = Math.max(0, Math.min(scroll, recordings.size() - visible));
		for (int i = 0; i < visible && scroll + i < recordings.size(); i++) {
			RecordingMeta r = recordings.get(scroll + i);
			int y = listY + i * ROW;
			boolean on = chosen.contains(r.id);
			boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW;
			if (hover) {
				g.fill(listX, y, listX + listW, y + ROW, 0x30FFFFFF);
			}
			g.fill(listX + 3, y + 6, listX + 13, y + 16, 0xFF8B8B8B);
			g.fill(listX + 4, y + 7, listX + 12, y + 15, on ? 0xFF43A047 : 0xFF202020);
			g.text(font, font.plainSubstrByWidth(r.name, listW - 22), listX + 18, y + 3, 0xFFFFFFFF, false);
			String info = Recorder.time(r.ticks) + (r.tags.isEmpty() ? "" : "  #" + String.join(" #", r.tags));
			g.text(font, font.plainSubstrByWidth(info, listW - 22), listX + 18, y + 12, 0xFF909090, false);
		}
	}

	private void drawStatus(GuiGraphicsExtractor g, int x, int y) {
		int w = width - x - 10;
		boolean ours = profile.id.equals(training.profileId());
		if (error != null) {
			y = line(g, x, y, error, 0xFFFF8080, w);
		}
		if (!MinigameBotsClient.instance().brain().runtime().installed()) {
			line(g, x, y, Component.translatable("minigamebots.training.needs_brain").getString(), 0xFFFFC060, w);
			return;
		}
		if (training.status() == TrainingManager.Status.IDLE || !ours) {
			if (training.busy()) {
				line(g, x, y, "Another AI is training right now.", 0xFFFFC060, w);
			} else {
				// Only as many lines as fit above the buttons at the bottom.
				for (var l : font.split(Component.translatable("minigamebots.training.help"), w)) {
					if (y + 9 > height - 34) {
						break;
					}
					g.text(font, l, x, y, 0xFFB0B0B0, false);
					y += 9;
				}
			}
			return;
		}
		int color = switch (training.status()) {
			case DONE -> 0xFF80E080;
			case FAILED -> 0xFFFF8080;
			default -> 0xFFFFFFFF;
		};
		y = line(g, x, y, training.message(), color, w);
		int total = Math.max(1, training.totalSteps());
		g.fill(x, y, x + w, y + 6, 0xFF303030);
		g.fill(x, y, x + (int) ((long) w * Math.min(training.step(), total) / total), y + 6, 0xFF43A047);
		y += 10;
		y = line(g, x, y, String.format("Step %,d / %,d%s", training.step(), total,
				Double.isNaN(training.etaSeconds()) ? "" : "   about " + eta(training.etaSeconds()) + " left"), 0xFFE0E0E0, w);
		y = line(g, x, y, String.format("Copying keys right: %s   aim right: %s", pct(training.keyAcc()), pct(training.cameraAcc())),
				0xFFE0E0E0, w);
		y = line(g, x, y, String.format("Error: training %s, on unseen moments %s", num(training.loss()), num(training.valLoss())),
				0xFFB0B0B0, w);
		// The graph uses whatever room is left above the bottom buttons (none on very small windows).
		int graphH = height - 36 - (y + 4);
		if (graphH >= 16) {
			drawGraph(g, x, y + 4, w, graphH);
		}
	}

	/** Loss over time: white = training, green = recordings it hasn't trained on (the honest score). */
	private void drawGraph(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		List<TrainingManager.Point> pts = training.points();
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF404040);
		g.fill(x, y, x + w, y + h, 0xC0101010);
		if (pts.size() < 2) {
			return;
		}
		double max = 0;
		for (TrainingManager.Point p : pts) {
			max = Math.max(max, p.loss());
			if (!Double.isNaN(p.valLoss())) {
				max = Math.max(max, p.valLoss());
			}
		}
		int lastStep = Math.max(1, pts.get(pts.size() - 1).step());
		for (TrainingManager.Point p : pts) {
			int px = x + (int) ((long) (w - 2) * p.step() / lastStep);
			int py = y + h - 2 - (int) ((h - 4) * (p.loss() / max));
			g.fill(px, py, px + 2, py + 2, 0xFFE0E0E0);
			if (!Double.isNaN(p.valLoss())) {
				int vy = y + h - 2 - (int) ((h - 4) * (p.valLoss() / max));
				g.fill(px, vy, px + 2, vy + 2, 0xFF43A047);
			}
		}
	}

	private int line(GuiGraphicsExtractor g, int x, int y, String text, int color, int w) {
		if (y + 9 <= height - 34) { // Never draw over the buttons at the bottom.
			g.text(font, font.plainSubstrByWidth(text, w), x, y, color, false);
		}
		return y + 11;
	}

	private static String pct(double v) {
		return Double.isNaN(v) ? "-" : Math.round(v * 100) + "%";
	}

	private static String num(double v) {
		return Double.isNaN(v) ? "-" : String.format("%.3f", v);
	}

	private static String eta(double seconds) {
		long s = (long) seconds;
		return s >= 3600 ? String.format("%dh %02dm", s / 3600, s / 60 % 60) : String.format("%dm %02ds", s / 60, s % 60);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH && !training.busy()) {
			int i = scroll + (int) ((my - listY) / ROW);
			if (i < recordings.size()) {
				String id = recordings.get(i).id;
				if (!chosen.remove(id)) {
					chosen.add(id);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll -= (int) Math.signum(dy);
		return true;
	}

	@Override
	public void removed() {
		faces.close();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
