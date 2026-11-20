package io.github.mudklip.minigamebots.client.invent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.video.VideoLab;
import io.github.mudklip.minigamebots.common.net.TgRequestPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * An inventing session: the brain proposes a few designs (new combinations of parts, and changes
 * to its best ideas so far), Training Grounds tests each one for real in its sky sandbox, the
 * results go back to the brain, which keeps the best in its hall of fame. Repeats until time's up.
 * Runs in the background; you can keep playing (the sandbox is high above you).
 */
public final class InventSession {
	private static final Gson GSON = new Gson();
	private static volatile boolean running;
	private static volatile String status = "";
	private static volatile int tested;
	private static volatile @Nullable JsonObject hall;
	private static final Map<String, JsonObject> RESULTS = new ConcurrentHashMap<>();

	private InventSession() {
	}

	public static boolean running() {
		return running;
	}

	public static String status() {
		return status;
	}

	public static @Nullable JsonObject hall() {
		return hall;
	}

	public static boolean available() {
		return ClientPlayNetworking.canSend(TgRequestPayload.TYPE);
	}

	/** Results from the sandbox (Training Grounds events). */
	public static void onEvent(GameEvent e) {
		if ("tg_sandbox_result".equals(e.type())) {
			JsonObject r = GSON.toJsonTree(e.data()).getAsJsonObject();
			if (r.has("id")) {
				RESULTS.put(r.get("id").getAsString(), r);
			}
		}
	}

	private static Path profileDir() {
		var mgb = MinigameBotsClient.instance();
		var p = mgb.profiles().active();
		return p == null ? null : mgb.profiles().store().dir(p.id);
	}

	/** Loads the hall of fame (in the background). */
	public static void refresh() {
		Path dir = profileDir();
		if (dir == null) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				JsonObject r = VideoLab.run(List.of("invent", "show", "--profile", dir.toString()), m -> { });
				if (r.has("hall")) {
					hall = r;
				}
			} catch (Exception ignored) {
				// Nothing to show yet.
			}
		}, "MinigameBots inventions");
		t.setDaemon(true);
		t.start();
	}

	public static void stop() {
		running = false;
	}

	public static String start(String kind, int minutes) {
		Path dir = profileDir();
		if (dir == null) {
			return "Pick an AI first (AI Profiles > Use this AI)";
		}
		if (!available()) {
			return "Needs Training Grounds (1.23 or newer) on this world or server";
		}
		if (running) {
			return "Already inventing";
		}
		running = true;
		long until = System.currentTimeMillis() + minutes * 60_000L;
		Thread t = new Thread(() -> {
			int gen = 0;
			try {
				while (running && System.currentTimeMillis() < until) {
					gen++;
					status = "Round " + gen + ": imagining new " + kind + "s...";
					JsonObject r = VideoLab.run(List.of("invent", "propose", "--profile", dir.toString(), "--kind", kind, "--n", "4"),
							m -> { });
					JsonArray designs = r.has("designs") ? r.getAsJsonArray("designs") : new JsonArray();
					if (designs.isEmpty()) {
						status = "It has run out of new ideas for now";
						break;
					}
					status = "Round " + gen + ": testing " + designs.size() + " ideas in the sandbox above you...";
					for (var d : designs) {
						String json = d.toString();
						Minecraft.getInstance().execute(() -> ClientPlayNetworking.send(new TgRequestPayload("sandbox_test", json)));
					}
					JsonArray batch = new JsonArray();
					long deadline = System.currentTimeMillis() + 40_000;
					for (var d : designs) {
						String id = d.getAsJsonObject().get("id").getAsString();
						JsonObject res = null;
						while (running && res == null && System.currentTimeMillis() < deadline) {
							res = RESULTS.remove(id);
							if (res == null) {
								Thread.sleep(200);
							}
						}
						if (res == null) {
							continue;
						}
						if (res.has("error")) {
							status = "Stopped: " + res.get("error").getAsString();
							running = false;
							break;
						}
						JsonObject one = new JsonObject();
						one.add("design", d);
						one.add("result", res);
						batch.add(one);
					}
					if (!batch.isEmpty()) {
						Path f = Files.createTempFile("mgb-invent", ".json");
						Files.writeString(f, batch.toString());
						JsonObject rec = VideoLab.run(List.of("invent", "record", "--profile", dir.toString(), "--results", f.toString()),
								m -> { });
						Files.deleteIfExists(f);
						tested = rec.has("tested") ? rec.get("tested").getAsInt() : tested;
						long works = 0;
						if (rec.has("scored")) {
							for (var s : rec.getAsJsonArray("scored")) {
								works += s.getAsJsonObject().get("works").getAsBoolean() ? 1 : 0;
							}
						}
						status = "Round " + gen + ": " + works + " of " + batch.size() + " worked (" + tested + " ideas tested so far)";
						refresh();
					}
				}
				if (running) {
					status = "Done: " + tested + " ideas tested in all. The best are below.";
				}
			} catch (Exception e) {
				status = "Stopped: " + e.getMessage();
			}
			running = false;
		}, "MinigameBots inventing");
		t.setDaemon(true);
		t.start();
		return "Inventing " + kind + "s for " + minutes + " min";
	}
}
