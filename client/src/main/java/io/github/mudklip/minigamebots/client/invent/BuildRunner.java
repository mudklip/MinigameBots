package io.github.mudklip.minigamebots.client.invent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.common.net.TgRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Builds an invention with the AI's own hands (the "build" policy: walk, pick the block, look,
 * right-click), a few blocks in front of you. While it builds, it records the session as a demo
 * ("Build: name", teacher) so a building skill can learn from its own building.
 */
public final class BuildRunner {
	private static boolean active;
	private static boolean ours;
	private static String status = "";

	private BuildRunner() {
	}

	public static String status() {
		return status;
	}

	public static boolean active() {
		return active;
	}

	/** Asks Training Grounds for the blocks a design needs (Training Grounds managers only). */
	public static String giveBlocks(JsonObject design) {
		if (!InventSession.available()) {
			return "Needs Training Grounds on this world or server";
		}
		ClientPlayNetworking.send(new TgRequestPayload("design_kit", design.toString()));
		return "Asked for the blocks (they go into your inventory)";
	}

	public static String build(Minecraft mc, JsonObject design) {
		var mgb = MinigameBotsClient.instance();
		var p = mgb.profiles().active();
		if (p == null || mc.player == null) {
			return "Pick an AI first (AI Profiles > Use this AI)";
		}
		// Where: 4 blocks ahead along the nearest axis, at your feet's level.
		var dir = mc.player.getDirection();
		BlockPos at = mc.player.blockPosition().relative(dir, 4 + 2);
		JsonObject args = new JsonObject();
		args.add("design", design);
		JsonArray anchor = new JsonArray();
		anchor.add(at.getX());
		anchor.add(at.getY());
		anchor.add(at.getZ());
		args.add("anchor", anchor);
		mgb.brain().play(mc, "build", args);
		var rec = mgb.recorder();
		ours = !rec.recording();
		if (ours) {
			rec.start(mc);
			rec.label("Build: " + design.get("name").getAsString(), "teacher");
		}
		active = true;
		status = "Building " + design.get("name").getAsString() + " in front of you (F9 stops it)";
		return status;
	}

	/** Every client tick: done when the brain says so, or when you take control back. */
	public static void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		var brain = MinigameBotsClient.instance().brain();
		JsonObject st = brain.lastStatus();
		JsonObject ps = st != null && st.has("policy_status") && st.get("policy_status").isJsonObject()
				? st.getAsJsonObject("policy_status") : null;
		boolean finished = ps != null && "build".equals(brain.policy()) && ps.has("finished") && ps.get("finished").getAsBoolean();
		if (ps != null && ps.has("placed")) {
			status = "Building: " + ps.get("placed").getAsInt() + "/" + ps.get("total").getAsInt()
					+ (ps.has("missing") && !ps.getAsJsonArray("missing").isEmpty() ? " (missing: " + ps.getAsJsonArray("missing") + ")" : "");
		}
		if (finished || !AgentControl.aiActive()) {
			active = false;
			if (AgentControl.aiActive()) {
				AgentControl.get().stop(mc, Component.literal("Built it"));
			}
			if (ours && MinigameBotsClient.instance().recorder().recording()) {
				MinigameBotsClient.instance().recorder().stop(mc);
			}
			status = (finished ? "Finished: " : "Stopped: ") + status.replace("Building: ", "") + (ours ? " · recorded as a building demo" : "");
		}
	}

	public static @Nullable String doing() {
		return active ? status : null;
	}
}
