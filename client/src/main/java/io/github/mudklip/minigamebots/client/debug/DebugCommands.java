package io.github.mudklip.minigamebots.client.debug;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.ActionSource;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.observe.BlockSense;
import io.github.mudklip.minigamebots.client.observe.Frame;
import io.github.mudklip.minigamebots.client.observe.FrameCapture;
import io.github.mudklip.minigamebots.client.observe.Observation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-only test commands, {@code /mgb ...}. They never reach the server. They exist to try the
 * AI's body before it has a brain, and to check what it perceives.
 */
public final class DebugCommands {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("mgb")
				.then(literal("run").then(argument("script", StringArgumentType.greedyString()).executes(DebugCommands::run)))
				.then(literal("ai")
						.then(literal("on").executes(ctx -> {
							AgentControl.get().start(Minecraft.getInstance(), ActionSource.IDLE);
							return 1;
						}))
						.then(literal("off").executes(ctx -> {
							AgentControl.get().stop(Minecraft.getInstance(), Component.translatable("minigamebots.control.human_on"));
							return 1;
						})))
				.then(literal("observe").executes(DebugCommands::observe))
				.then(literal("events")
						.executes(ctx -> events(ctx, 10))
						.then(argument("count", IntegerArgumentType.integer(1, 100))
								.executes(ctx -> events(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
				.then(literal("watch")
						.then(literal("on").executes(ctx -> watch(ctx, true)))
						.then(literal("off").executes(ctx -> watch(ctx, false))))
				.then(literal("rec")
						.then(literal("start").executes(ctx -> {
							Minecraft c = Minecraft.getInstance();
							c.schedule(() -> MinigameBotsClient.instance().recorder().start(c));
							return 1;
						}))
						.then(literal("stop").executes(ctx -> {
							Minecraft c = Minecraft.getInstance();
							c.schedule(() -> MinigameBotsClient.instance().recorder().stop(c));
							return 1;
						}))
						.then(literal("mark").then(argument("type", StringArgumentType.word()).executes(ctx -> {
							MinigameBotsClient.instance().recorder().mark(Minecraft.getInstance(),
									StringArgumentType.getString(ctx, "type"));
							return 1;
						})))
						.then(literal("list").executes(ctx -> {
							var rec = MinigameBotsClient.instance().recorder();
							var all = rec.store().list(rec.currentId());
							for (var m : all) {
								ctx.getSource().sendFeedback(Component.literal(m.id + "  " + m.ticks + " ticks, "
										+ m.chunks + " chunk(s), " + m.sizeBytes / 1000 + " KB, " + m.marks.size()
										+ " marks, complete=" + m.complete));
							}
							return all.size();
						}))
						.then(literal("open").executes(ctx -> {
							Minecraft c = Minecraft.getInstance();
							c.schedule(() -> c.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.RecorderScreen(null)));
							return 1;
						})))
				.then(literal("config").executes(ctx -> {
					Minecraft client = Minecraft.getInstance();
					// Open after the chat screen has closed.
					client.schedule(() -> client.gui.setScreen(MgbConfig.screen(null)));
					return 1;
				})));
	}

	private static int run(CommandContext<FabricClientCommandSource> ctx) {
		String source = StringArgumentType.getString(ctx, "script");
		Script script;
		try {
			script = Script.parse(source);
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendError(Component.literal(e.getMessage()));
			return 0;
		}
		Minecraft client = Minecraft.getInstance();
		// Start once the chat screen has closed, so the first actions happen in the world.
		client.schedule(() -> AgentControl.get().start(client, new ScriptSource(script)));
		ctx.getSource().sendFeedback(Component.translatable("minigamebots.debug.script_started", script.ticks().size()));
		return 1;
	}

	private static int observe(CommandContext<FabricClientCommandSource> ctx) {
		MinigameBotsClient mod = MinigameBotsClient.instance();
		Observation obs = mod.observeNow();
		if (obs == null) {
			ctx.getSource().sendError(Component.literal("Not in a world"));
			return 0;
		}
		Path dir = FabricLoader.getInstance().getGameDir().resolve("minigamebots/debug");
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("observation.json"), GSON.toJson(obs));
			Frame frame = FrameCapture.get().latest();
			if (frame != null) {
				frame.savePng(dir.resolve("frame.png"));
			}
		} catch (IOException e) {
			ctx.getSource().sendError(Component.literal("Couldn't save: " + e.getMessage()));
			return 0;
		}
		Observation.Self s = obs.self();
		BlockSense b = obs.blocks();
		ctx.getSource().sendFeedback(Component.literal(String.format(
				"HP %.1f/%.0f, food %d, %d items, %d entities (%d visible), blocks: %d solid %d air %d hidden, %d sounds, sidebar %s",
				s.health(), s.maxHealth(), s.food(), obs.inventory().size(), obs.entities().size(),
				obs.entities().stream().filter(Observation.EntityInfo::visible).count(), b.count(BlockSense.Category.SOLID),
				b.count(BlockSense.Category.AIR), b.count(BlockSense.Category.UNKNOWN), obs.sounds().size(),
				obs.text().sidebar())));
		ctx.getSource().sendFeedback(Component.translatable("minigamebots.debug.saved", dir.toString()));
		return 1;
	}

	private static int events(CommandContext<FabricClientCommandSource> ctx, int count) {
		var recent = MinigameBotsClient.events().recent(count);
		if (recent.isEmpty()) {
			ctx.getSource().sendFeedback(Component.translatable("minigamebots.debug.no_events"));
		}
		for (GameEvent e : recent) {
			ctx.getSource().sendFeedback(Component.literal("[" + e.tick() + "] " + e));
		}
		return recent.size();
	}

	private static int watch(CommandContext<FabricClientCommandSource> ctx, boolean on) {
		MinigameBotsClient.instance().setWatching(on);
		ctx.getSource().sendFeedback(Component.translatable(on ? "minigamebots.debug.watch_on" : "minigamebots.debug.watch_off"));
		return 1;
	}

	private DebugCommands() {
	}
}
