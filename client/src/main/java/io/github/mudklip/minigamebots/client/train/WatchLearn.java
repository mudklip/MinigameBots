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
