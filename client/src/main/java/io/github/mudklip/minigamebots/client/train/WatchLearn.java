package io.github.mudklip.minigamebots.client.train;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.video.VideoLab;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Watch & learn: while it's on and you're playing or spectating (the AI not in control), the
 * mod records in 10-minute parts with the privileged extra data (other players' true states),
 * and after each part turns every other player into demos from their point of view, weighted
 * by how their fights went. Those demos appear in Training (source "player") and the Coach's
 * PvP plan trains on them. Training itself happens later (Coach or Train), so play stays
 * smooth. Only use it where you're allowed to record other players.
 */
public final class WatchLearn {
	private static final int PART_TICKS = 10 * 60 * 20;
	private static boolean on;
	private static boolean ours;
	private static int ticks;
	private static volatile boolean converting;
	private static volatile String status = "";
	// Live readout (since the switch was turned on).
	private static final java.util.Set<String> seen = new java.util.HashSet<>();
	private static final java.util.Map<Integer, Integer> lastHurt = new java.util.HashMap<>();
	private static int hitsSeen;
	private static long watchedTicks;
	private static double demoSeconds;
	private static int demos;
	private static java.util.List<String> top = java.util.List.of();

	/** {players seen, hits seen, seconds watched, demos made, demo seconds}. */
	public static double[] stats() {
		return new double[] {seen.size(), hitsSeen, watchedTicks / 20.0, demos, demoSeconds};
	}

	public static java.util.List<String> top() {
		return top;
	}

	public static boolean converting() {
		return converting;
	}

	/** Same key as the brain's rule finder: a game's sidebar title, simplified. */
	public static String gameKey(String title) {
		String k = title.replaceAll("§.", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
		return k.length() > 40 ? k.substring(0, 40) : k;
	}

	private record Job(String skill, java.util.List<io.github.mudklip.minigamebots.client.record.RecordingMeta> recs) {
	}

	private static final java.util.ArrayDeque<Job> queue = new java.util.ArrayDeque<>();
	private static io.github.mudklip.minigamebots.client.profile.@org.jspecify.annotations.Nullable ProfileManifest queueFor;

	/**
	 * Trains on everything watched: the main network on all of it, and each game (Bedwars, duels...)
	 * its own network on its own demos (the Core plays a known game with that network). One after
	 * another, in the background. Returns what was queued, for the screen.
	 */
	public static String trainAll(io.github.mudklip.minigamebots.client.profile.ProfileManifest p) {
		var mgb = MinigameBotsClient.instance();
		var recs = mgb.recorder().store().list().stream().filter(m -> "player".equals(m.source)).toList();
		if (recs.isEmpty()) {
			return "Nothing watched yet: turn watching on, then spectate or play near others for a while";
		}
		queue.clear();
		queueFor = p;
		queue.add(new Job("general", recs));
		java.util.Map<String, java.util.List<io.github.mudklip.minigamebots.client.record.RecordingMeta>> byGame = new java.util.TreeMap<>();
		for (var m : recs) {
			if (m.game != null && !m.game.isBlank()) {
				byGame.computeIfAbsent(gameKey(m.game), k -> new java.util.ArrayList<>()).add(m);
			}
		}
		int games = 0;
		for (var e : byGame.entrySet()) {
			if (e.getValue().size() >= 3 && !e.getKey().isEmpty()) {
				queue.add(new Job("game_" + e.getKey(), e.getValue()));
				games++;
			}
		}
		return "§aTraining on " + recs.size() + " watched demo(s): the main network" + (games > 0 ? ", then " + games
				+ " game network(s) (" + String.join(", ", byGame.keySet()) + ")" : "") + ". Runs in the background.";
	}

	public static int queued() {
		return queue.size();
	}

	private static void runQueue() {
		var mgb = MinigameBotsClient.instance();
		if (queue.isEmpty() || queueFor == null || mgb.training().busy()) {
			return;
		}
		Job j = queue.poll();
		var p = queueFor;
		try {
			boolean lite = p.foundation == null || p.foundation.isBlank() || p.foundation.startsWith("lite");
			mgb.training().start(p, j.recs(), TrainingManager.Plan.QUICK, TrainingManager.Resources.BALANCED,
					!io.github.mudklip.minigamebots.client.profile.Skills.trained(mgb.profiles().store().dir(p.id), j.skill()),
					lite ? null : p.foundation, j.skill());
			status = "Training " + (j.skill().equals("general") ? "the main network" : j.skill().substring(5).replace('_', ' '))
					+ " on watched demos" + (queue.isEmpty() ? "" : " (" + queue.size() + " more to go)");
		} catch (Exception e) {
			status = "Couldn't train " + j.skill() + ": " + e.getMessage();
		}
	}

	private WatchLearn() {
	}

	public static boolean on() {
		return on;
	}

	public static String status() {
		return status;
	}

	public static void set(Minecraft c, boolean value) {
		on = value;
		if (!on) {
			finishPart(c);
		}
		status = on ? "Watching: records others' fights, turns them into demos every 10 min" : "Off";
	}

	/**
	 * Watching is allowed while you're in control (any game mode), or while the player is a real
	 * spectator (then the AI may be in control, e.g. spectating games by itself). Never while the AI
	 * plays as a non-spectator.
	 */
	public static boolean allowed(Minecraft c) {
		return c.player != null && (!AgentControl.aiActive() || c.player.isSpectator());
	}

	public static void tick(Minecraft c) {
		runQueue();
		if (!on) {
			return;
		}
		var rec = MinigameBotsClient.instance().recorder();
		if (!allowed(c)) {
			finishPart(c); // Not while the AI plays (outside spectator mode), nor outside a world.
			return;
		}
		if (ours && !rec.recording()) {
			// You stopped the recording yourself (F7): that also switches Watch & learn off, instead of restarting it.
			on = false;
			status = "Off (you stopped the recording)";
			if (c.player != null) {
				c.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Watch & learn switched off (you stopped recording)"));
			}
			finishPart(c);
			return;
		}
		watchedTicks++;
		if (c.level != null) {
			for (var pl : c.level.players()) {
				if (pl == c.player || pl.isSpectator() || pl.distanceTo(c.player) > 64) {
					continue;
				}
				seen.add(pl.getGameProfile().name());
				int before = lastHurt.getOrDefault(pl.getId(), 0);
				if (pl.hurtTime > before) {
					hitsSeen++;
				}
				lastHurt.put(pl.getId(), pl.hurtTime);
			}
		}
		if (!rec.recording()) {
			rec.start(c);
			rec.label("Watch & learn", "human");
			ours = true;
			ticks = 0;
		}
		if (ours && ++ticks >= PART_TICKS) {
			finishPart(c);
		}
	}

	private static void finishPart(Minecraft c) {
		var rec = MinigameBotsClient.instance().recorder();
		if (!ours) {
			return;
		}
		ours = false;
		if (rec.recording()) {
			rec.stop(c);
		}
		if (converting) {
			return;
		}
		converting = true;
		String me = c.getUser().getName();
		Thread t = new Thread(() -> {
			try {
				var r = VideoLab.run(List.of("players", "--recordings", rec.store().root().toString(), "--me", me), m -> { });
				status = r.has("recordings") ? "Last part: " + r.get("recordings").getAsInt() + " demo(s) from "
						+ r.getAsJsonArray("players").size() + " player(s)" : "Last part: nothing to learn from";
				if (r.has("recordings")) {
					demos += r.get("recordings").getAsInt();
					demoSeconds += r.has("seconds") ? r.get("seconds").getAsDouble() : 0;
				}
				if (r.has("top")) {
					java.util.List<String> t2 = new java.util.ArrayList<>();
					r.getAsJsonArray("top").forEach(e -> t2.add(e.getAsJsonObject().get("name").getAsString() + " "
							+ Math.round(e.getAsJsonObject().get("rating").getAsDouble() * 100)));
					top = t2;
				}
			} catch (Exception e) {
				status = "Couldn't convert: " + e.getMessage();
			}
			converting = false;
		}, "MinigameBots watch & learn");
		t.setDaemon(true);
		t.start();
		if (c.player != null) {
			c.player.sendOverlayMessage(Component.literal("Watch & learn: turning what you watched into demos..."));
		}
	}
}
