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
	/** The world being watched (0-based), or -1. */
	public static volatile int watching = -1;

	public static boolean running() {
		return running || direct;
	}

	private static volatile boolean direct;

	/** A headless step (server thread): only the live view needs it; the brain gets it directly. */
	public static void direct(String copiesJson) {
		if (!direct) {
			direct = true;
			MultiWorldStats.reset();
		}
		try {
			MultiWorldStats.update(com.google.gson.JsonParser.parseString(copiesJson).getAsJsonArray());
		} catch (RuntimeException ignored) {
			// A bad step only skips the live view.
		}
	}

	public static void directStopped() {
		direct = false;
		watching = -1;
		var mc = net.minecraft.client.Minecraft.getInstance();
		mc.execute(() -> FastTraining.stop(mc));
	}

	private MultiWorldLink() {
	}

	public static void onEvent(GameEvent e) {
		if ("tg_mw_stop".equals(e.type())) {
			running = false;
			watching = -1;
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
		MultiWorldStats.update(el.getAsJsonArray());
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
			MultiWorldStats.reset();
			MultiWorldStats.update(el.getAsJsonArray());
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
