package io.github.mudklip.minigamebots.client.control;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.memory.WorldMemory;
import java.util.EnumSet;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The autonomy and safety layer (DESIGN 3.12), always on while the AI plays:
 * watchdog (stuck detection, auto-respawn), session limits, protected zones (a hard filter on
 * the AI's own clicks) and optional canned chat replies.
 */
public final class Autonomy {
	private static final int STUCK_TICKS = 30 * 20;

	private static long tick;
	private static double anchorX = Double.NaN;
	private static double anchorZ;
	private static long anchorTick;
	private static long startTick = -1;
	private static int deaths;
	private static int criticalTicks;
	private static boolean wasDead;
	private static long lastChatTick = -10_000;

	private Autonomy() {
	}

	/** Every client tick. */
	public static void tick(Minecraft client) {
		tick++;
		LocalPlayer p = client.player;
		if (!AgentControl.aiActive() || p == null) {
			startTick = -1;
			deaths = 0;
			anchorX = Double.NaN;
			return;
		}
		MgbConfig cfg = MgbConfig.get();
		if (startTick < 0) {
			startTick = tick;
		}
		// Death: count it, and respawn by itself.
		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead) {
			deaths++;
		}
		wasDead = dead;
		if (dead && cfg.autoRespawn && tick % 40 == 0) {
			p.respawn();
		}
		// Session limits.
		String stop = null;
		if (cfg.sessionHours > 0 && tick - startTick > cfg.sessionHours * 72_000L) {
			stop = "Session time limit reached";
		} else if (cfg.maxDeaths > 0 && deaths >= cfg.maxDeaths) {
			stop = "Died " + deaths + " times: stopping";
		}
		criticalTicks = !dead && p.getHealth() <= 4 ? criticalTicks + 1 : 0;
		if (cfg.stopWhenHealthCritical && criticalTicks > 30 * 20) {
			stop = "Health stayed critical: stopping";
		}
		if (stop != null) {
			AgentControl.get().stop(client, Component.literal(stop));
			return;
		}
		// Watchdog: barely moved for 30 s while it has something to do -> tell the brain.
		if (Double.isNaN(anchorX) || Math.hypot(p.getX() - anchorX, p.getZ() - anchorZ) > 3) {
			anchorX = p.getX();
			anchorZ = p.getZ();
			anchorTick = tick;
		} else if (tick - anchorTick > STUCK_TICKS && !dead && client.gui.screen() == null) {
			anchorTick = tick;
			MinigameBotsClient.events().emit("stuck", java.util.Map.of("seconds", STUCK_TICKS / 20));
		}
	}

	/** Protected zones: drops the AI's break/use clicks aimed at a block inside one. */
	public static GameInput filter(Minecraft client, GameInput in) {
		if (!(in.held().contains(VirtualKey.ATTACK) || in.held().contains(VirtualKey.USE) || in.attackClicks() > 0
				|| in.useClicks() > 0) || client.level == null) {
			return in;
		}
		HitResult hit = client.hitResult;
		if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) {
			return in;
		}
		WorldMemory m = MinigameBotsClient.instance().memory().memory();
		if (m == null || m.zones.isEmpty()) {
			return in;
		}
		String dim = client.level.dimension().identifier().toString();
		var pos = b.getBlockPos();
		for (WorldMemory.Zone z : m.zones) {
			if (z.contains(dim, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
				EnumSet<VirtualKey> keys = in.held().isEmpty() ? EnumSet.noneOf(VirtualKey.class) : EnumSet.copyOf(in.held());
				keys.remove(VirtualKey.ATTACK);
				keys.remove(VirtualKey.USE);
				return new GameInput(keys, 0, 0, in.yaw(), in.pitch());
			}
		}
		return in;
	}

	/** Canned chat replies (off by default): "gg" after a round, a greeting back. Rate-limited. */
	public static void onEvent(GameEvent e) {
		MgbConfig cfg = MgbConfig.get();
		Minecraft c = Minecraft.getInstance();
		var prof = MinigameBotsClient.instance().profiles().active();
		if (prof != null && prof.character.chat) {
			return; // Its character does the talking (ChatMind).
		}
		if (!cfg.chatReplies || !AgentControl.aiActive() || c.player == null || tick - lastChatTick < 200) {
			return;
		}
		String reply = null;
		if ("tg_episode_end".equals(e.type()) && !"stopped".equals(e.data().get("reason"))) {
			reply = "gg";
		} else if ("chat".equals(e.type())) {
			String text = String.valueOf(e.data().getOrDefault("text", "")).toLowerCase(Locale.ROOT);
			String me = c.player.getGameProfile().name().toLowerCase(Locale.ROOT);
			if (!text.contains("<" + me + ">") && text.contains(me) && text.matches(".*\\b(hi|hello|hey|yo)\\b.*")) {
				reply = "hi";
			} else if (text.matches(".*\\bgg\\b.*") && !text.contains("<" + me + ">")) {
				reply = "gg";
			}
		}
		if (reply != null) {
			lastChatTick = tick;
			String msg = (cfg.tagChatAsAi ? "[AI] " : "") + reply;
			c.execute(() -> {
				if (c.player != null) {
					c.player.connection.sendChat(msg);
				}
			});
		}
	}

	/** Adds a protected zone centred here (Memory screen). */
	public static @Nullable String protectHere(Minecraft c) {
		WorldMemory m = MinigameBotsClient.instance().memory().memory();
		if (m == null || c.player == null || c.level == null) {
			return "Join a world first";
		}
		WorldMemory.Zone z = new WorldMemory.Zone();
		z.dimension = c.level.dimension().identifier().toString();
		z.x = c.player.getBlockX();
		z.y = c.player.getBlockY();
		z.z = c.player.getBlockZ();
		z.radius = MgbConfig.get().protectRadius;
		z.name = "Zone " + (m.zones.size() + 1);
		m.zones.add(z);
		MinigameBotsClient.instance().memory().changed();
		return null;
	}
}
