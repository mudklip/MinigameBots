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

	public static void tick(Minecraft c) {
		if (!on) {
			return;
		}
		var rec = MinigameBotsClient.instance().recorder();
		if (c.player == null || AgentControl.aiActive()) {
			finishPart(c); // Not while the AI plays, nor outside a world.
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
