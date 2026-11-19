package io.github.mudklip.minigamebots.training;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Exact player recording (DESIGN 4.10, M14): while on, every tick each linked MinigameBots
 * client gets the exact keys, attacks and item use of the players near it (event "inputs"),
 * so its recordings can turn other players' play into demos with real inputs instead of
 * guessed ones. Everyone is told when it's on, and anyone can opt out with /tg record optout.
 */
public final class InputRecorder {
	private static final double RANGE = 32;

	private boolean on;
	private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
	private final GroundTruth truth;

	public InputRecorder(GroundTruth truth) {
		this.truth = truth;
	}

	public boolean on() {
		return on;
	}

	public void set(MinecraftServer server, boolean value) {
		on = value;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			notice(p);
		}
	}

	public void setOptedOut(ServerPlayer p, boolean out) {
		if (out) {
			optedOut.add(p.getUUID());
		} else {
			optedOut.remove(p.getUUID());
		}
	}

	public boolean optedOut(ServerPlayer p) {
		return optedOut.contains(p.getUUID());
	}

	/** Tells a player that inputs are being recorded (on join and when it's switched on or off). */
	public void notice(ServerPlayer p) {
		p.sendSystemMessage(Component.literal(on
				? "[Training Grounds] Player inputs here are recorded for AI training. Opt out: /tg record optout"
				: "[Training Grounds] Player input recording is off.").withStyle(ChatFormatting.GRAY));
	}

	public void tick(MinecraftServer server) {
		if (!on) {
			return;
		}
		long now = server.overworld().getGameTime();
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (!truth.wants(viewer)) {
				continue;
			}
			JsonArray list = new JsonArray();
			for (ServerPlayer p : viewer.level().players()) {
				if (p == viewer || p.isSpectator() || optedOut.contains(p.getUUID()) || p.distanceTo(viewer) > RANGE) {
					continue;
				}
				var in = p.getLastClientInput();
				JsonObject o = new JsonObject();
				o.addProperty("name", p.getGameProfile().name());
				JsonArray keys = new JsonArray();
				if (in.forward()) {
					keys.add("forward");
				}
				if (in.backward()) {
					keys.add("back");
				}
				if (in.left()) {
					keys.add("left");
				}
				if (in.right()) {
					keys.add("right");
				}
				if (in.jump()) {
					keys.add("jump");
				}
				if (in.shift()) {
					keys.add("sneak");
				}
				if (in.sprint() || p.isSprinting()) {
					keys.add("sprint");
				}
				if (AttackDetails.attackedAt(p) == now) {
					keys.add("attack");
				}
				if (p.isUsingItem()) {
					keys.add("use");
				}
				o.add("keys", keys);
				o.addProperty("slot", p.getInventory().getSelectedSlot());
				list.add(o);
			}
			if (!list.isEmpty()) {
				JsonObject d = new JsonObject();
				d.add("players", list);
				truth.send(viewer, "inputs", d);
			}
		}
	}
}
