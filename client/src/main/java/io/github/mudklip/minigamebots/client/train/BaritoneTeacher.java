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
		HOME("Baritone: go home", "Paths back to the marked base"),
		BUILD("Baritone: build", "Builds one of the AI's own inventions efficiently and safely, so its building skill can learn how");

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
				case BUILD -> {
					String err = build(b, client);
					if (err != null) {
						return err;
					}
				}
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

	/**
	 * Hands one of the AI's kept inventions to Baritone's builder, as a schematic that only covers
	 * the design's own blocks (everything else is "don't care", so the ground around isn't dug up).
	 * Baritone is only ever a teacher here: the AI learns from watching it build.
	 */
	private static @Nullable String build(Object b, Minecraft client) throws ReflectiveOperationException {
		var hall = io.github.mudklip.minigamebots.client.invent.InventSession.hall();
		if (hall == null || !hall.has("hall")) {
			io.github.mudklip.minigamebots.client.invent.InventSession.refresh();
			return "No inventions yet to build: invent some first (main menu > Inventions)";
		}
		java.util.List<com.google.gson.JsonObject> designs = new java.util.ArrayList<>();
		for (var kind : hall.getAsJsonObject("hall").entrySet()) {
			for (var q : kind.getValue().getAsJsonObject().entrySet()) {
				q.getValue().getAsJsonArray().forEach(d -> designs.add(d.getAsJsonObject()));
			}
		}
		if (designs.isEmpty()) {
			return "No inventions yet to build: invent some first (main menu > Inventions)";
		}
		// Only a design that fits here: nothing below the world's bottom and nothing unbreakable in the way.
		var dir = client.player.getDirection();
		var anchor = client.player.blockPosition().relative(dir, 6);
		java.util.Collections.shuffle(designs, new java.util.Random(client.player.getRandom().nextLong()));
		com.google.gson.JsonObject d = null;
		for (var cand : designs) {
			boolean fits = true;
			for (var el : cand.getAsJsonArray("blocks")) {
				var a = el.getAsJsonArray();
				var at = anchor.offset(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
				if (at.getY() <= client.level.getMinY() || client.level.getBlockState(at).getDestroySpeed(client.level, at) < 0) {
					fits = false;
					break;
				}
			}
			if (fits) {
				d = cand;
				break;
			}
		}
		if (d == null) {
			return "None of its inventions fit here (too close to bedrock or the world's bottom): try somewhere with deeper ground";
		}
		var lookup = client.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
		var pal = d.getAsJsonArray("palette");
		java.util.List<net.minecraft.world.level.block.state.BlockState> states = new java.util.ArrayList<>();
		for (var el : pal) {
			try {
				var st = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(lookup, el.getAsString(), false).blockState();
				// Baritone can't pour liquids: it digs that cell, and the AI's own builder adds the lava or water.
				states.add(st.getFluidState().isEmpty() ? st : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			} catch (Exception e) {
				states.add(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			}
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (var el : d.getAsJsonArray("blocks")) {
			var a = el.getAsJsonArray();
			minX = Math.min(minX, a.get(0).getAsInt());
			minY = Math.min(minY, a.get(1).getAsInt());
			minZ = Math.min(minZ, a.get(2).getAsInt());
			maxX = Math.max(maxX, a.get(0).getAsInt());
			maxY = Math.max(maxY, a.get(1).getAsInt());
			maxZ = Math.max(maxZ, a.get(2).getAsInt());
		}
		java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> cells = new java.util.HashMap<>();
		for (var el : d.getAsJsonArray("blocks")) {
			var a = el.getAsJsonArray();
			cells.put(net.minecraft.core.BlockPos.asLong(a.get(0).getAsInt() - minX, a.get(1).getAsInt() - minY, a.get(2).getAsInt() - minZ),
					states.get(a.get(3).getAsInt()));
		}
		int w = maxX - minX + 1;
		int h = maxY - minY + 1;
		int l = maxZ - minZ + 1;
		Class<?> iface = Class.forName("baritone.api.schematic.ISchematic");
		Object schematic = java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, (proxy, m, args) -> {
			switch (m.getName()) {
				case "widthX":
					return w;
				case "heightY":
					return h;
				case "lengthZ":
					return l;
				case "inSchematic":
					return cells.containsKey(net.minecraft.core.BlockPos.asLong((int) args[0], (int) args[1], (int) args[2]));
				case "desiredState":
					var st = cells.get(net.minecraft.core.BlockPos.asLong((int) args[0], (int) args[1], (int) args[2]));
					return st != null ? st : args[3];
				default:
					if (m.isDefault()) {
						return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
					}
					return m.getName().equals("hashCode") ? System.identityHashCode(proxy) : m.getName().equals("equals") ? proxy == args[0] : null;
			}
		});
		// A few blocks ahead, at the player's feet level (anchor, chosen above).
		var origin = new net.minecraft.core.Vec3i(anchor.getX() + minX, anchor.getY() + minY, anchor.getZ() + minZ);
		if (io.github.mudklip.minigamebots.client.invent.InventSession.available()) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new io.github.mudklip.minigamebots.common.net.TgRequestPayload("design_kit", d.toString()));
		}
		// The kit may land outside the hotbar: let the builder take blocks from the whole inventory.
		try {
			Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
			Object allow = settings.getClass().getField("allowInventory").get(settings);
			allow.getClass().getField("value").set(allow, Boolean.TRUE);
		} catch (ReflectiveOperationException | RuntimeException e) {
			MinigameBotsClient.LOGGER.debug("Couldn't set allowInventory", e);
		}
		Object builder = call(b, "getBuilderProcess", new Class<?>[0]);
		call(builder, "build", new Class<?>[] {String.class, iface, net.minecraft.core.Vec3i.class}, d.get("name").getAsString(),
				schematic, origin);
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
			if (task == Task.BUILD) {
				on = on || Boolean.TRUE.equals(call(call(baritone(), "getBuilderProcess", new Class<?>[0]), "isActive", new Class<?>[0]));
			}
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
