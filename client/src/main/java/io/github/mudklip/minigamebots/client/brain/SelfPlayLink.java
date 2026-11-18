package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.common.net.TgPuppetPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Self-play rounds: when Training Grounds gives this client a "self" dummy to steer, the brain is
 * told which entity it is (obs "puppet") and its answer for the dummy is sent back each tick.
 */
public final class SelfPlayLink {
	private static volatile int puppetId = -1;
	private static volatile int round;

	private SelfPlayLink() {
	}

	/** Event listener (registered once by the client). */
	public static void onEvent(GameEvent e) {
		if ("tg_episode_start".equals(e.type())) {
			Object id = e.data().get("puppet_id");
			puppetId = id instanceof Number n ? n.intValue() : -1;
			round++;
		} else if ("tg_episode_end".equals(e.type())) {
			puppetId = -1;
		}
	}

	public static void reset() {
		puppetId = -1;
	}

	/** What the brain needs to play the dummy, or null when there's no self-play round. */
	public static JsonObject describe() {
		int id = puppetId;
		var profile = MinigameBotsClient.instance().profiles().active();
		if (id < 0 || profile == null) {
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("round", round);
		o.addProperty("profile", MinigameBotsClient.instance().profiles().store().dir(profile.id).toString());
		return o;
	}

	/** Forwards the brain's input for the dummy to the server. */
	public static void send(JsonObject p) {
		if (!p.has("id") || !ClientPlayNetworking.canSend(TgPuppetPayload.TYPE)) {
			return;
		}
		int keys = 0;
		if (p.has("keys") && p.get("keys").isJsonArray()) {
			for (var k : p.getAsJsonArray("keys")) {
				int i = TgPuppetPayload.KEYS.indexOf(k.getAsString());
				if (i >= 0) {
					keys |= 1 << i;
				}
			}
		}
		float yaw = p.has("yaw") ? p.get("yaw").getAsFloat() : 0;
		float pitch = p.has("pitch") ? p.get("pitch").getAsFloat() : 0;
		TgPuppetPayload payload = new TgPuppetPayload(p.get("id").getAsInt(), keys,
				Float.isFinite(yaw) ? yaw : 0, Float.isFinite(pitch) ? pitch : 0);
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			if (ClientPlayNetworking.canSend(TgPuppetPayload.TYPE)) {
				ClientPlayNetworking.send(payload);
			}
		});
	}
}
