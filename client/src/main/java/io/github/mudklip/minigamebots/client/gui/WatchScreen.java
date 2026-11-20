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
				.tooltip(Tooltip.create(Component.literal("Trains the main network on all watched demos, then each game "
						+ "(Bedwars, duels...) its own network on its own demos, one after another in the background. Best after "
						+ "an hour or more of watching."))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
	}

	private void train() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		message = p == null ? "Pick an AI first" : WatchLearn.trainAll(p);
	}

	/** Players rated so far (best first): "name rating% (minutes seen)". */
	private List<String> ratings() {
		List<String> out = new java.util.ArrayList<>();
		try {
			var root = MinigameBotsClient.instance().recorder().store().root().resolve("player_ratings.json");
			if (!java.nio.file.Files.isRegularFile(root)) {
				return out;
			}
			var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root)).getAsJsonObject();
			record R(String name, double r, double min) {
			}
			List<R> rs = new java.util.ArrayList<>();
			for (var e : o.entrySet()) {
				var v = e.getValue().getAsJsonObject();
				double landed = v.get("landed").getAsDouble() + 2;
				double taken = v.get("taken").getAsDouble() + v.get("deaths").getAsDouble() * 3 + 2;
				double seen = v.has("seen_ticks") ? v.get("seen_ticks").getAsDouble() / 1200 : 0;
				if (seen > 0.15) {
					rs.add(new R(e.getKey(), landed / (landed + taken), seen));
				}
			}
			rs.sort((a, b) -> Double.compare(b.r, a.r));
			for (R r : rs.subList(0, Math.min(8, rs.size()))) {
				out.add(String.format("%s §e%d§7 (%.0f min)", r.name, Math.round(r.r * 100), r.min));
			}
		} catch (Exception ignored) {
			// No ratings yet.
		}
		return out;
	}

	private List<String> ratingCache = List.of();
	private long ratingRead;

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
		for (var line : font.split(Component.literal("§7Play near others, or be a spectator (then even the AI may watch by itself): "
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
		if (System.currentTimeMillis() - ratingRead > 5000) {
			ratingCache = ratings();
			ratingRead = System.currentTimeMillis();
		}
		if (!ratingCache.isEmpty()) {
			g.text(font, Component.literal("§bPlayers rated so far (best first):"), x, y + 3, Ui.TEXT, false);
			y += 14;
			for (String r : ratingCache) {
				if (y > height - 30) {
					break;
				}
				g.text(font, Component.literal("§f• " + r), x + 4, y, Ui.TEXT, false);
				y += 10;
			}
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
