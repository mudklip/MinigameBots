package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import net.minecraft.client.Minecraft;

/**
 * Multi-world training (optional): Training Grounds sends one observation per learner each
 * tick (event "mw"); they ride along with the AI's own observation, and the brain's answers for
 * the learners come back as "puppets". Starting /tg multitrain starts the practice policy here.
 */
public final class MultiWorldLink {
	private static volatile JsonArray latest;
	private static volatile boolean running;

	private MultiWorldLink() {
	}

	public static void onEvent(GameEvent e) {
		if ("tg_mw_stop".equals(e.type())) {
			running = false;
			latest = null;
			Minecraft c = Minecraft.getInstance();
			c.execute(() -> {
				if (io.github.mudklip.minigamebots.client.control.AgentControl.aiActive()) {
					io.github.mudklip.minigamebots.client.control.AgentControl.get().stop(c,
							net.minecraft.network.chat.Component.literal("Multi-world training stopped"));
				}
			});
			return;
		}
		if (!"tg_mw".equals(e.type()) || !(e.data().get("copies") instanceof JsonElement el) || !el.isJsonArray()) {
			return;
		}
		JsonArray prev = latest;
		if (prev != null) {
			// Keep events from ticks the brain hasn't seen yet.
			for (JsonElement p : prev) {
				p.getAsJsonObject().remove("obs");
			}
			JsonArray merged = new JsonArray();
			prev.forEach(p -> {
				if (p.getAsJsonObject().has("events") && p.getAsJsonObject().getAsJsonArray("events").size() > 0) {
					merged.add(p);
				}
			});
			el.getAsJsonArray().forEach(merged::add);
			latest = merged;
		} else {
			latest = el.getAsJsonArray();
		}
		if (!running) {
			running = true;
			Minecraft c = Minecraft.getInstance();
			var profile = MinigameBotsClient.instance().profiles().active();
			if (profile != null) {
				JsonObject args = new JsonObject();
				args.addProperty("profile", MinigameBotsClient.instance().profiles().store().dir(profile.id).toString());
				c.execute(() -> MinigameBotsClient.instance().brain().play(c, "multiworld", args));
			}
		}
	}

	/** The copies since the last observation sent (then cleared). */
	public static JsonArray take() {
		JsonArray a = latest;
		latest = null;
		return a;
	}
}
