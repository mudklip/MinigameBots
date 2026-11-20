package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.TrainingLink;
import io.github.mudklip.minigamebots.client.practice.ExamManager;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Exams: the AI plays the next N rounds of a Training Grounds match the way it normally plays
 * (no learning), and the results are kept so you can see whether training is helping.
 */
public final class ExamsScreen extends Screen {
	private static final int[] ROUNDS = {5, 10, 20, 50};

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private int rounds = 1;
	private @Nullable String error;

	public ExamsScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.translatable("minigamebots.exams.screen", profile.name));
		this.parent = parent;
		this.profile = profile;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(roundsLabel(), b -> {
			rounds = (rounds + 1) % ROUNDS.length;
			b.setMessage(roundsLabel());
		}).pos(10, 28).width(90).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.exams.start"), b -> start())
				.pos(104, 28).width(110).tooltip(Tooltip.create(Component.translatable("minigamebots.exams.start_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> MinigameBotsClient.instance().exams().cancel())
				.pos(218, 28).width(60).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 28).width(60).build());
		// The human floor: measure yourself on the same tests.
		addRenderableWidget(Button.builder(Component.literal("Measure me: duel"), b -> {
			if (!TrainingLink.active()) {
				error = "Needs Training Grounds with a match going (the same one the AI's exams use)";
				return;
			}
			io.github.mudklip.minigamebots.client.coach.HumanBench.startDuel();
			minecraft.gui.setScreen(null);
		}).pos(10, height - 28).width(110).tooltip(Tooltip.create(Component.literal("You play the next "
				+ io.github.mudklip.minigamebots.client.coach.HumanBench.ROUNDS + " rounds of the match yourself; your win rate "
				+ "becomes the human floor the AI's exams are compared with."))).build());
		addRenderableWidget(Button.builder(Component.literal("Measure me: aim"), b -> {
			io.github.mudklip.minigamebots.client.coach.HumanBench.startAim(minecraft);
			minecraft.gui.setScreen(null);
		}).pos(124, height - 28).width(100).tooltip(Tooltip.create(Component.literal("A 60-second aim trainer test, played by you."))).build());
	}

	private Component roundsLabel() {
		return Component.literal(ROUNDS[rounds] + " rounds");
	}

	private void start() {
		Path dir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		if (!TrainingLink.active()) {
			error = "Exams need Training Grounds: play on a world or server that has it, with a match set to keep going.";
			return;
		}
		if (!Files.exists(dir.resolve("core/model.json"))) {
			error = "Train this AI first";
			return;
		}
		if (minecraft.player == null) {
			error = "Join a world first";
			return;
		}
		error = null;
		var info = readInfo(dir);
		MinigameBotsClient.instance().exams().start(profile, ROUNDS[rounds], info.step(), info.phase());
		MinigameBotsClient.instance().profiles().setActive(profile.id);
		MinigameBotsClient.instance().brain().playLearned(minecraft, profile);
		minecraft.gui.setScreen(null);
	}

	private record Info(long step, String phase) {
	}

	private static Info readInfo(Path dir) {
		try {
			var o = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("core/model.json"))).getAsJsonObject();
			long step = o.has("step") ? o.get("step").getAsLong() : 0;
			String phase = o.has("phase") ? o.get("phase").getAsString() : "imitation";
			long upd = o.has("practice_updates") ? o.get("practice_updates").getAsLong() : 0;
			return new Info(step, upd > 0 ? phase + " " + upd : phase);
		} catch (Exception e) {
			return new Info(0, "");
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		int y = 54;
		ExamManager em = MinigameBotsClient.instance().exams();
		if (error != null) {
			g.textWithWordWrap(font, Component.literal(error), 10, y, width - 20, 0xFFFF8080);
			y += 22;
		}
		if (em.running()) {
			g.text(font, String.format("Exam running: %d / %d rounds", em.done(), em.target()), 10, y, 0xFF80E080, false);
			y += 14;
		} else {
			g.textWithWordWrap(font, Component.translatable("minigamebots.exams.help"), 10, y, width - 20, 0xFFA0A0A0);
			y += 34;
		}
		Path dir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		List<ExamManager.Result> results = ExamManager.load(dir);
		// AI vs you (the human floor).
		var pr = io.github.mudklip.minigamebots.client.coach.Progress.active();
		String hb = io.github.mudklip.minigamebots.client.coach.HumanBench.status();
		if (!hb.isEmpty()) {
			g.text(font, Component.literal("§e" + hb), 10, height - 42, 0xFFFFFFFF, false);
		} else if (pr != null) {
			String aiDuel = results.isEmpty() ? "-" : Math.round(results.get(results.size() - 1).winRate() * 100) + "%";
			String aiAim = pr.rating.containsKey("aim") ? String.valueOf(Math.round(pr.rating.get("aim"))) : "-";
			String youDuel = pr.human.containsKey("duel") ? Math.round(pr.human.get("duel") * 100) + "%" : "not measured";
			String youAim = pr.human.containsKey("aim") ? String.valueOf(Math.round(pr.human.get("aim"))) : "not measured";
			g.text(font, Component.literal("§fAI§7 duel " + aiDuel + ", aim " + aiAim + "   §fYou§7 duel " + youDuel + ", aim " + youAim),
					10, height - 42, 0xFFFFFFFF, false);
		}
		if (results.isEmpty()) {
			g.text(font, "No exams yet.", 10, y, 0xFF909090, false);
			return;
		}
		int[] cols = {10, 104, 160, 236, 300, 350};
		String[] heads = {"When", "Match", "Trained", "Won", "Kills", "Dealt / taken"};
		for (int c = 0; c < cols.length; c++) {
			g.text(font, heads[c], cols[c], y, 0xFFB0B0B0, false);
		}
		y += 12;
		int rowsFit = Math.max(1, (height - 56 - y) / 11);
		int from = Math.max(0, results.size() - rowsFit);
		for (int i = results.size() - 1; i >= from; i--) {
			ExamManager.Result r = results.get(i);
			double n = Math.max(1, r.rounds);
			String[] cells = {r.date.length() > 5 ? r.date.substring(5) : r.date, font.plainSubstrByWidth(r.scenario, 52),
					"step " + r.checkpointStep + (r.phase.startsWith("practice") ? " +RL" : ""),
					"", String.format("%.1f", r.kills / n), String.format("%.1f / %.1f", r.damageDealt / n, r.damageTaken / n)};
			for (int c = 0; c < cols.length; c++) {
				g.text(font, cells[c], cols[c], y, 0xFFE0E0E0, false);
			}
			// Win rate: a bar and the percentage.
			int bx = cols[3];
			g.fill(bx, y + 1, bx + 30, y + 8, 0xFF303030);
			g.fill(bx, y + 1, bx + (int) (30 * r.winRate()), y + 8, 0xFF43A047);
			g.text(font, String.format("%.0f%%", r.winRate() * 100), bx + 33, y, 0xFFE0E0E0, false);
			y += 11;
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
