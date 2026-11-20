package io.github.mudklip.minigamebots.client.brain;

import net.minecraft.client.Minecraft;

/**
 * Fast background training: while training worlds run, the built-in server runs at 40 ticks a
 * second (twice the practice per minute) and the game draws as little as possible (10 FPS, a
 * 2-chunk view), so the window can be minimised and the PC stays usable. Everything goes back
 * when training stops. Singleplayer only (it changes the built-in server directly; no cheats
 * needed).
 */
public final class FastTraining {
	private static boolean on;
	private static int fps;
	private static int view;
	private static long since;

	private FastTraining() {
	}

	public static boolean on() {
		return on;
	}

	/** On for a few seconds already (the worlds take a moment to start). */
	public static boolean settled() {
		return on && System.currentTimeMillis() - since > 8000;
	}

	public static void start(Minecraft mc) {
		var server = mc.getSingleplayerServer();
		if (on || server == null) {
			return;
		}
		on = true;
		since = System.currentTimeMillis();
		fps = mc.options.framerateLimit().get();
		view = mc.options.renderDistance().get();
		mc.options.framerateLimit().set(10);
		mc.options.renderDistance().set(2);
		server.execute(() -> server.tickRateManager().setTickRate(40));
	}

	public static void stop(Minecraft mc) {
		if (!on) {
			return;
		}
		on = false;
		mc.options.framerateLimit().set(fps);
		mc.options.renderDistance().set(view);
		var server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.tickRateManager().setTickRate(20));
		}
	}
}
