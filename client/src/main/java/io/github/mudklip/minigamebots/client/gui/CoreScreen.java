package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Core: common sense shared by every skill. "Learn this game" lets it play the game you're in,
 * work out the rules from what happens (what knocks players out, what scores, what wins), reward
 * itself by them, borrow skills that fit, and remember the game for next time.
 */
public final class CoreScreen extends Screen {
	private final @Nullable Screen parent;
	private List<String> games = List.of();
	private net.minecraft.client.gui.components.EditBox tell;
	private @Nullable String message;

	public CoreScreen(@Nullable Screen parent) {
		super(Component.literal("Core"));
		this.parent = parent;
	}

	private @Nullable Path dir() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		return p == null ? null : MinigameBotsClient.instance().profiles().store().dir(p.id).resolve("core-games").resolve("core").resolve("games");
	}

	private static boolean learning() {
		return "core".equals(MinigameBotsClient.instance().brain().policy())
				&& io.github.mudklip.minigamebots.client.control.AgentControl.aiActive();
	}

	@Override
	protected void init() {
		Path d = dir();
		games = new ArrayList<>();
		if (d != null && Files.isDirectory(d)) {
			try (Stream<Path> s = Files.list(d)) {
				s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.replace(".json", "")).sorted().forEach(games::add);
			} catch (Exception ignored) {
				// None listed.
			}
		}
		int w = Math.min(380, width - 16);
		int x = (width - w) / 2;
		addRenderableWidget(Button.builder(Component.literal(learning() ? "§cStop learning" : "§a▶ Learn this game"), b -> {
			var mgb = MinigameBotsClient.instance();
			ProfileManifest p = mgb.profiles().active();
			if (learning()) {
				io.github.mudklip.minigamebots.client.control.AgentControl.get().stop(minecraft, Component.literal("Core: stopped"));
				rebuildWidgets();
				return;
			}
			if (p == null || minecraft.player == null) {
				return;
			}
			mgb.brain().playCore(minecraft, p);
			minecraft.gui.setScreen(null);
		}).pos(x, 64).width(140).tooltip(Tooltip.create(Component.literal("It plays the game you're in now and works out the rules "
				+ "as it goes. Join the game first. F9 stops it."))).build()).active = MinigameBotsClient.instance().profiles().active() != null;
		tell = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, x + 146, 65, w - 146 - 48, 18,
				Component.literal("Tell it a rule")));
		tell.setHint(Component.literal("§7Tell it a rule, e.g. snowballs knock you out"));
		tell.setMaxLength(120);
		addRenderableWidget(Button.builder(Component.literal("Tell"), b -> tellRule()).pos(x + w - 44, 64).width(44)
				.tooltip(Tooltip.create(Component.literal("For the game you're in now (by its scoreboard title). It starts out "
						+ "believing it; play can still prove it wrong."))).build());
		games.removeIf(g -> g.endsWith(".told"));
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, height - 26).width(52).build());
	}

	private void tellRule() {
		String text = tell.getValue().trim();
		Path d = dir();
		var sb = minecraft.level == null ? null : minecraft.level.getScoreboard();
		var obj = sb == null ? null : sb.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
		if (text.isEmpty() || d == null) {
			return;
		}
		if (obj == null) {
			message = "Join the game first (it's recognised by its scoreboard title)";
			return;
		}
		String key = io.github.mudklip.minigamebots.client.train.WatchLearn.gameKey(obj.getDisplayName().getString());
		try {
			Files.createDirectories(d);
			Files.writeString(d.resolve(key + ".told.txt"), text + "\n", java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.APPEND);
			message = "§aTold it for " + key.replace('_', ' ') + ": \"" + text + "\"";
			tell.setValue("");
		} catch (Exception e) {
			message = "Couldn't save: " + e.getMessage();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int w = Math.min(380, width - 16);
		int x = (width - w) / 2;
		Ui.panel(g, x - 6, 88, w + 12, height - 88 - 32);
		super.extractRenderState(g, mouseX, mouseY, a);
		g.item(Ui.stack("minecraft:light"), x, 8);
		g.text(font, Component.literal("§lCore§r  §7common sense shared by every skill"), x + 22, 12, Ui.TEXT, false);
		int y = 30;
		for (var line : font.split(Component.literal("§7In a new minigame it works out the rules from what happens (what knocks you "
				+ "out, what scores, what wins), rewards itself by them, borrows skills that fit, and remembers the game."), w)) {
			g.text(font, line, x, y, Ui.MUTED, false);
			y += 10;
		}
		if (message != null) {
			g.text(font, Component.literal(font.plainSubstrByWidth(message, w - 70)), x, height - 20, Ui.WARN, false);
		}
		y = 94;
		JsonObject st = MinigameBotsClient.instance().brain().lastStatus();
		boolean live = learning() && st != null && st.has("rules");
		Ui.header(g, font, live ? "Rules found in " + (st.has("game") ? st.get("game").getAsString().replace('_', ' ') : "this game")
				: "Games it knows", x, y);
		y += 16;
		if (live) {
			var rules = st.getAsJsonArray("rules");
			if (rules.isEmpty()) {
				g.text(font, Component.literal("§7Watching... rules appear after a few rounds."), x, y, Ui.MUTED, false);
				y += 11;
			}
			for (var r : rules) {
				g.text(font, Component.literal("§f• " + font.plainSubstrByWidth(r.getAsString(), w - 10)), x, y, Ui.TEXT, false);
				y += 11;
			}
			if (st.has("borrow") && !st.getAsJsonArray("borrow").isEmpty()) {
				List<String> b = new ArrayList<>();
				st.getAsJsonArray("borrow").forEach(e -> {
					var def = io.github.mudklip.minigamebots.client.skills.SkillLibrary.get().skills.get(e.getAsString());
					b.add(def != null ? def.name : e.getAsString());
				});
				g.text(font, Component.literal("§bUses skills: §f" + String.join(", ", b)), x, y + 4, Ui.TEXT, false);
			}
			if (st.has("why") && !st.get("why").getAsString().isEmpty()) {
				g.text(font, Component.literal("§cLast loss: §f" + st.get("why").getAsString()), x, y + 16, Ui.TEXT, false);
			}
		} else if (games.isEmpty()) {
			g.text(font, Component.literal("§7None yet. Join a minigame and press Learn this game."), x, y, Ui.MUTED, false);
		} else {
			for (String gname : games) {
				if (y > height - 44) {
					break;
				}
				g.text(font, Component.literal("§f• " + gname.replace('_', ' ') + "  §7" + examLine(gname)), x, y, Ui.TEXT, false);
				y += 11;
			}
		}
	}

	/** "learned in 6 rounds, wins 70%" from the game's saved file. */
	private String examLine(String game) {
		Path d = dir();
		if (d == null) {
			return "";
		}
		try {
			var o = com.google.gson.JsonParser.parseString(Files.readString(d.resolve(game + ".json"))).getAsJsonObject();
			var sum = o.has("summary") ? o.getAsJsonObject("summary") : null;
			if (sum == null) {
				return "";
			}
			int rounds = sum.get("rounds").getAsInt();
			String learned = sum.has("learned_after") && !sum.get("learned_after").isJsonNull()
					? "learned in " + sum.get("learned_after").getAsInt() + " rounds" : rounds + " rounds, still learning";
			String why = o.has("last_reason") && !o.get("last_reason").getAsString().isEmpty() ? " · " + o.get("last_reason").getAsString() : "";
			String wins = sum.has("win_rate") && !sum.get("win_rate").isJsonNull() ? ", wins " + Math.round(sum.get("win_rate").getAsDouble() * 100) + "%" : "";
			return learned + wins + why;
		} catch (Exception e) {
			return "";
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
