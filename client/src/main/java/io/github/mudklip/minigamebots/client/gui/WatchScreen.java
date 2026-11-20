package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.train.TrainingManager;
import io.github.mudklip.minigamebots.client.train.WatchLearn;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Learn by watching: one switch. While you play or spectate (never while the AI plays), it
 * watches the players around you and turns their fights into demos: rated players, the moments
 * with action, weighted by how they turned out. Then train on it with one button.
 */
public final class WatchScreen extends Screen {
	private final @Nullable Screen parent;
	private @Nullable String message;

	public WatchScreen(@Nullable Screen parent) {
		super(Component.literal("Watch"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = 8;
		addRenderableWidget(Button.builder(Component.literal(WatchLearn.on() ? "§a● Watching (click to stop)" : "▶ Start watching"), b -> {
			WatchLearn.set(minecraft, !WatchLearn.on());
			rebuildWidgets();
		}).pos(x, 98).width(150).tooltip(Tooltip.create(Component.literal("Records the players around you while you play or "
				+ "spectate, and turns their fights into demos every 10 minutes. Never while the AI is playing."))).build());
		addRenderableWidget(Button.builder(Component.literal("Train on what it watched"), b -> train()).pos(x + 154, 98).width(140)
				.tooltip(Tooltip.create(Component.literal("Trains the AI on the watched players' demos (quick plan). Best "
						+ "after an hour or more of watching."))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
	}

	private void train() {
		var mgb = MinigameBotsClient.instance();
		ProfileManifest p = mgb.profiles().active();
		if (p == null) {
			message = "Pick an AI first";
			return;
		}
		List<RecordingMeta> recs = mgb.recorder().store().list().stream().filter(m -> "player".equals(m.source)).toList();
		if (recs.isEmpty()) {
			message = "Nothing watched yet: turn watching on, then spectate or play near others for a while";
			return;
		}
		try {
			boolean lite = p.foundation == null || p.foundation.isBlank() || p.foundation.startsWith("lite");
			mgb.training().start(p, recs, TrainingManager.Plan.QUICK, TrainingManager.Resources.BALANCED,
					!io.github.mudklip.minigamebots.client.profile.Skills.trained(mgb.profiles().store().dir(p.id), "general"),
					lite ? null : p.foundation, "general");
			message = "§aTraining on " + recs.size() + " watched demo(s)... (progress in Advanced > Train)";
		} catch (Exception e) {
			message = "Couldn't start: " + e.getMessage();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (HubTabs.click(minecraft, HubTabs.WATCH, event.x(), event.y(), parent)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false; // A live readout.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int w = width - 16;
		Ui.panel(g, 6, 124, w + 4, height - 124 - 8);
		super.extractRenderState(g, mouseX, mouseY, a);
		HubTabs.draw(g, font, HubTabs.WATCH, mouseX, mouseY, width - 66);
		int y = 30;
		for (var line : font.split(Component.literal("§7Spectate games or play near others: it learns from every player it sees, "
				+ "from their point of view. It tracks exact positions and actions while watching, but counts health like a "
				+ "player (servers may fake it), rates every player, keeps only the moments with action, and copies strong "
				+ "players more and nobody's mistakes. It never watches while the AI itself is playing."), w)) {
			g.text(font, line, 8, y, Ui.MUTED, false);
			y += 10;
		}
		g.text(font, Component.literal("§8Only where recording others is allowed."), 8, y + 2, Ui.MUTED, false);
		double[] st = WatchLearn.stats();
		int x = 12;
		y = 130;
		Ui.header(g, font, WatchLearn.on() ? "Watching now" : "Watching is off", x, y);
		y += 16;
		String[] lines = {
				String.format("§fPlayers seen: §e%.0f§f   hits seen: §e%.0f§f   watched: §e%d:%02d", st[0], st[1], (int) st[2] / 60, (int) st[2] % 60),
				String.format("§fDemos made: §e%.0f§f  (§e%.0f s§f of action)%s", st[3], st[4], WatchLearn.converting() ? "  §7converting..." : ""),
				"§7" + WatchLearn.status()};
		for (String l : lines) {
			g.text(font, Component.literal(font.plainSubstrByWidth(l, w - 10)), x, y, Ui.TEXT, false);
			y += 11;
		}
		if (!WatchLearn.top().isEmpty()) {
			g.text(font, Component.literal("§bBest players seen: §f" + String.join(", ", WatchLearn.top())), x, y + 3, Ui.TEXT, false);
			y += 14;
		}
		if (message != null) {
			g.text(font, Component.literal(font.plainSubstrByWidth(message, w - 10)), x, Math.min(y + 4, height - 20), Ui.WARN, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
