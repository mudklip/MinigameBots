package io.github.mudklip.minigamebots.client.train;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.GameInput;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import java.lang.reflect.Method;
import java.util.EnumSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Teacher demos driven by Baritone (optional, LGPL-3.0, a separate mod: we only call its public
 * API by reflection, so nothing of it is copied and MinigameBots works without it).
 *
 * <p>Baritone moves the player itself, so while it plays, the recorder takes the input from
 * here: movement keys from the player's movement input, the camera from how the view turned,
 * clicks from Baritone's own input state, and hotbar changes. That is the same format as a
 * human or AI recording, so it trains the same way.
 */
public final class BaritoneTeacher {
	public enum Task {
		TRAVEL("Baritone: travel", "Walks, jumps, bridges and digs its way to far-away places"),
		MINE("Baritone: mine ores", "Finds and mines coal, iron, copper, gold and diamond ore"),
		HOME("Baritone: go home", "Paths back to the marked base");

		public final String label;
		public final String help;

		Task(String label, String help) {
			this.label = label;
			this.help = help;
		}
	}

	private static @Nullable Task task;
	private static boolean haveRotation;
	private static float lastYaw;
	private static float lastPitch;
	private static int lastSlot = -1;
	private static int idleTicks;

	private BaritoneTeacher() {
	}

	public static boolean installed() {
		return FabricLoader.getInstance().isModLoaded("baritone");
	}

	public static boolean active() {
		return task != null;
	}

	private static Object baritone() throws ReflectiveOperationException {
		Class<?> api = Class.forName("baritone.api.BaritoneAPI");
		Object provider = api.getMethod("getProvider").invoke(null);
		return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
	}

	private static Object call(Object target, String method, Class<?>[] types, Object... args) throws ReflectiveOperationException {
		Method m = findMethod(target.getClass(), method, types);
		m.setAccessible(true);
		return m.invoke(target, args);
	}

	private static Method findMethod(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			for (Class<?> i : k.getInterfaces()) {
				try {
					return i.getMethod(name, types);
				} catch (NoSuchMethodException ignored) {
					// Keep looking.
				}
			}
		}
		return c.getMethod(name, types);
	}

	/** Starts a task; returns an error message, or null when it started. */
	public static @Nullable String start(Minecraft client, Task t) {
		if (!installed()) {
			return "Baritone isn't installed (put its jar in the mods folder)";
		}
		LocalPlayer p = client.player;
		if (p == null) {
			return "Join a world first";
		}
		try {
			Object b = baritone();
			switch (t) {
				case TRAVEL -> {
					double a = p.getRandom().nextDouble() * Math.PI * 2;
					int d = 120 + p.getRandom().nextInt(200);
					goTo(b, (int) (p.getX() + Math.cos(a) * d), (int) (p.getZ() + Math.sin(a) * d));
				}
				case MINE -> call(call(b, "getMineProcess", new Class<?>[0]), "mineByName", new Class<?>[] {int.class, String[].class},
						64, new String[] {"coal_ore", "iron_ore", "copper_ore", "gold_ore", "diamond_ore", "deepslate_iron_ore",
								"deepslate_diamond_ore", "deepslate_gold_ore", "deepslate_coal_ore", "deepslate_copper_ore"});
				case HOME -> {
					var base = MinigameBotsClient.instance().memory()
							.nearest(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), 64).stream()
							.filter(w -> "base".equals(w.type)).findFirst().orElse(null);
					if (base == null) {
						return "Mark a base first (Memory screen, or the Mark base key)";
					}
					goTo(b, base.x, base.z);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			MinigameBotsClient.LOGGER.warn("Baritone didn't start", e);
			return "Baritone didn't start: " + e;
		}
		task = t;
		haveRotation = false;
		lastSlot = -1;
		idleTicks = 0;
		return null;
	}

	private static void goTo(Object b, int x, int z) throws ReflectiveOperationException {
		Class<?> goalXZ = Class.forName("baritone.api.pathing.goals.GoalXZ");
		Class<?> goal = Class.forName("baritone.api.pathing.goals.Goal");
		Object g = goalXZ.getConstructor(int.class, int.class).newInstance(x, z);
		call(call(b, "getCustomGoalProcess", new Class<?>[0]), "setGoalAndPath", new Class<?>[] {goal}, g);
	}

	public static void stop() {
		if (task == null) {
			return;
		}
		task = null;
		try {
			call(call(baritone(), "getPathingBehavior", new Class<?>[0]), "cancelEverything", new Class<?>[0]);
		} catch (ReflectiveOperationException | RuntimeException e) {
			MinigameBotsClient.LOGGER.debug("Couldn't stop Baritone", e);
		}
	}

	/** True while Baritone is still working on the task (it gives up after a while idle). */
	public static boolean busy() {
		if (task == null) {
			return false;
		}
		try {
			Object pathing = call(baritone(), "getPathingBehavior", new Class<?>[0]);
			boolean on = Boolean.TRUE.equals(call(pathing, "isPathing", new Class<?>[0]));
			idleTicks = on ? 0 : idleTicks + 1;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
		return idleTicks < 200;
	}

	private static boolean forced(Object handler, String input) {
		try {
			Class<?> in = Class.forName("baritone.api.utils.input.Input");
			Object v = in.getField(input).get(null);
			return Boolean.TRUE.equals(call(handler, "isInputForcedDown", new Class<?>[] {in}, v));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** What Baritone did this tick, as recorder input; null when it isn't teaching. */
	public static @Nullable GameInput snapshot(Minecraft client) {
		LocalPlayer p = client.player;
		if (task == null || p == null) {
			return null;
		}
		EnumSet<VirtualKey> keys = EnumSet.noneOf(VirtualKey.class);
		var k = p.input.keyPresses;
		if (k.forward()) {
			keys.add(VirtualKey.FORWARD);
		}
		if (k.backward()) {
			keys.add(VirtualKey.BACK);
		}
		if (k.left()) {
			keys.add(VirtualKey.LEFT);
		}
		if (k.right()) {
			keys.add(VirtualKey.RIGHT);
		}
		if (k.jump()) {
			keys.add(VirtualKey.JUMP);
		}
		if (k.shift()) {
			keys.add(VirtualKey.SNEAK);
		}
		if (k.sprint() || p.isSprinting()) {
			keys.add(VirtualKey.SPRINT);
		}
		try {
			Object handler = call(baritone(), "getInputOverrideHandler", new Class<?>[0]);
			if (forced(handler, "CLICK_LEFT")) {
				keys.add(VirtualKey.ATTACK);
			}
			if (forced(handler, "CLICK_RIGHT")) {
				keys.add(VirtualKey.USE);
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// No clicks this tick.
		}
		int slot = p.getInventory().getSelectedSlot();
		if (lastSlot >= 0 && slot != lastSlot) {
			keys.add(VirtualKey.valueOf("HOTBAR_" + (slot + 1)));
		}
		lastSlot = slot;
		float yaw = 0;
		float pitch = 0;
		if (haveRotation) {
			yaw = Mth.wrapDegrees(p.getYRot() - lastYaw);
			pitch = p.getXRot() - lastPitch;
		}
		haveRotation = true;
		lastYaw = p.getYRot();
		lastPitch = p.getXRot();
		return new GameInput(keys, 0, 0, yaw, pitch);
	}
}
