package io.github.mudklip.minigamebots.training;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mudklip.minigamebots.training.dummy.DummyManager;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The inventing sandbox: tests a design (a small block plan made by the AI's inventor) for real.
 * It's built high in the sky above the player (in loaded chunks, out of the way), on a stone
 * slab with an obsidian bottom, up to 4 at once side by side. The game then runs it: for a trap a
 * dummy walks along its path for 10 seconds (does it get hurt, killed, stuck?); for a shelter, is
 * it closed in, how much room is inside, how bright. The result goes back to the client, and the
 * whole test space is cleared back to air (blocks, fluids, arrows, items).
 */
public final class Sandbox {
	private static final int R = 10; // Test box: -R..R around the origin.
	private static final int TRAP_TICKS = 200;
	private static final int SHELTER_TICKS = 40;
	private static final int[][] SLOTS = {{-11, -11}, {11, -11}, {-11, 11}, {11, 11}};

	private final DummyManager dummies;
	private final GroundTruth truth;
	private final Job[] running = new Job[SLOTS.length];
	private final ArrayDeque<Object[]> waiting = new ArrayDeque<>();

	private static final class Job {
		final UUID owner;
		final String id;
		final JsonObject design;
		final ServerLevel level;
		final BlockPos origin;
		int age;
		@Nullable UUID victim;
		float startHp;
		float minHp;
		boolean died;
		int stuckTicks;
		Vec3 last = Vec3.ZERO;
		Vec3 end = Vec3.ZERO;

		Job(UUID owner, String id, JsonObject design, ServerLevel level, BlockPos origin) {
			this.owner = owner;
			this.id = id;
			this.design = design;
			this.level = level;
			this.origin = origin;
		}

		boolean trap() {
			return "trap".equals(design.get("kind").getAsString());
		}
	}

	public Sandbox(DummyManager dummies, GroundTruth truth) {
		this.dummies = dummies;
		this.truth = truth;
	}

	/** A design to test for this player (queued when 4 are already running). */
	public void request(ServerPlayer p, String json) {
		JsonObject d;
		try {
			d = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		waiting.add(new Object[] {p.getUUID(), d});
	}

	public void tick(MinecraftServer server) {
		for (int s = 0; s < running.length; s++) {
			if (running[s] == null && !waiting.isEmpty()) {
				Object[] w = waiting.poll();
				ServerPlayer p = server.getPlayerList().getPlayer((UUID) w[0]);
				if (p != null) {
					running[s] = start(p, (JsonObject) w[1], s);
				}
			}
			Job j = running[s];
			if (j != null && step(server, j)) {
				running[s] = null;
			}
		}
	}

	private @Nullable Job start(ServerPlayer p, JsonObject d, int slot) {
		ServerLevel lv = (ServerLevel) p.level();
		int top = lv.getMaxY() - 12;
		int y = Math.min(top, p.getBlockY() + 80);
		BlockPos origin = new BlockPos(p.getBlockX() + SLOTS[slot][0], y, p.getBlockZ() + SLOTS[slot][1]);
		if (!emptySky(lv, origin)) {
			JsonObject r = new JsonObject();
			r.addProperty("id", d.has("id") ? d.get("id").getAsString() : "");
			r.addProperty("error", "no free sky above you to test in");
			truth.send(p, "sandbox_result", r);
			return null;
		}
		Job j = new Job(p.getUUID(), d.has("id") ? d.get("id").getAsString() : "", d, lv, origin);
		var obsidian = Blocks.OBSIDIAN.defaultBlockState();
		var stone = Blocks.SMOOTH_STONE.defaultBlockState();
		for (BlockPos q : BlockPos.betweenClosed(origin.offset(-R, -9, -R), origin.offset(R, -1, R))) {
			lv.setBlock(q, q.getY() <= origin.getY() - 7 ? obsidian : stone, Block.UPDATE_CLIENTS);
		}
		place(lv, origin, d);
		if (j.trap()) {
			JsonArray path = d.getAsJsonArray("path");
			JsonArray a = path.get(0).getAsJsonArray();
			JsonArray b = path.get(1).getAsJsonArray();
			Vec3 from = new Vec3(origin.getX() + a.get(0).getAsInt() + 0.5, origin.getY(), origin.getZ() + a.get(1).getAsInt() + 0.5);
			Vec3 to = new Vec3(origin.getX() + b.get(0).getAsInt() + 0.5, origin.getY(), origin.getZ() + b.get(1).getAsInt() + 0.5);
			DummySettings s = DummySettings.style("L1");
			s.fightsBack = false;
			s.infiniteHealth = false;
			Mannequin m = dummies.spawnAt(lv, from, 0, s, false);
			if (m != null) {
				var dd = dummies.byId(m.getUUID());
				if (dd != null) {
					dd.settings.infiniteHealth = false;
					dd.settings.worldDamage = true;
				}
				j.victim = m.getUUID();
				j.startHp = m.getHealth();
				j.minHp = m.getHealth();
				j.last = m.position();
				j.end = to;
				// The victim just walks the path, like someone who doesn't know the trap is there.
				dummies.setBrain(m.getUUID(), (mm, dummy, target, level) -> {
					Vec3 dir = new Vec3(to.x - mm.getX(), 0, to.z - mm.getZ());
					if (dir.lengthSqr() > 0.2 && mm.hurtTime == 0) {
						dir = dir.normalize();
						mm.setDeltaMovement(dir.x * 0.2, mm.getDeltaMovement().y, dir.z * 0.2);
						float yaw = (float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90f;
						mm.setYRot(yaw);
						mm.setYHeadRot(yaw);
						mm.setYBodyRot(yaw);
					}
					return true;
				});
			}
		}
		return j;
	}

	private static boolean emptySky(ServerLevel lv, BlockPos origin) {
		for (BlockPos q : BlockPos.betweenClosed(origin.offset(-R, -9, -R), origin.offset(R, 10, R))) {
			if (!lv.getBlockState(q).isAir()) {
				return false;
			}
		}
		return true;
	}

	private static void place(ServerLevel lv, BlockPos origin, JsonObject d) {
		JsonArray palette = d.getAsJsonArray("palette");
		List<BlockState> states = new ArrayList<>();
		var lookup = lv.registryAccess().lookupOrThrow(Registries.BLOCK);
		for (var el : palette) {
			BlockState st = Blocks.AIR.defaultBlockState();
			try {
				st = BlockStateParser.parseForBlock(lookup, el.getAsString(), false).blockState();
			} catch (Exception ignored) {
				// Unknown block: left as air.
			}
			states.add(st);
		}
		for (var el : d.getAsJsonArray("blocks")) {
			JsonArray b = el.getAsJsonArray();
			int x = b.get(0).getAsInt();
			int y = b.get(1).getAsInt();
			int z = b.get(2).getAsInt();
			if (Math.abs(x) > R - 2 || Math.abs(z) > R - 2 || y < -6 || y > 9) {
				continue; // Never outside the test space.
			}
			lv.setBlock(origin.offset(x, y, z), states.get(b.get(3).getAsInt()), Block.UPDATE_ALL);
		}
		if (d.has("items")) {
			for (var en : d.getAsJsonObject("items").entrySet()) {
				String[] xyz = en.getKey().split(",");
				BlockPos at = origin.offset(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
				JsonArray it = en.getValue().getAsJsonArray();
				var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(it.get(0).getAsString()));
				if (lv.getBlockEntity(at) instanceof net.minecraft.world.Container c) {
					c.setItem(0, new ItemStack(item, Math.min(item.getDefaultMaxStackSize(), it.get(1).getAsInt())));
				}
			}
		}
	}

	/** One tick of a test; true when it's done (result sent, space cleared). */
	private boolean step(MinecraftServer server, Job j) {
		j.age++;
		JsonObject r = new JsonObject();
		r.addProperty("id", j.id);
		if (j.trap()) {
			LivingEntity v = j.victim == null ? null : j.level.getEntity(j.victim) instanceof LivingEntity l ? l : null;
			if (v == null || !v.isAlive()) {
				j.died = true;
			} else {
				j.minHp = Math.min(j.minHp, v.getHealth());
				boolean low = v.getY() < j.origin.getY() - 0.9;
				// Standing still short of the end of the path (at the end it has simply got through).
				boolean still = v.position().distanceToSqr(j.last) < 0.0004
						&& new Vec3(v.getX() - j.end.x, 0, v.getZ() - j.end.z).lengthSqr() > 2.25;
				if (low || still) {
					j.stuckTicks++;
				}
				j.last = v.position();
			}
			if (j.age < TRAP_TICKS && !j.died) {
				return false;
			}
			boolean caught = !j.died && v != null && (v.getY() < j.origin.getY() - 0.9 || j.stuckTicks > 100);
			r.addProperty("damage", j.died ? j.startHp : j.startHp - j.minHp);
			r.addProperty("killed", j.died);
			r.addProperty("caught", caught);
			r.addProperty("stuck_seconds", j.stuckTicks / 20.0);
		} else {
			if (j.age < SHELTER_TICKS) {
				return false;
			}
			JsonArray in = j.design.getAsJsonArray("inside");
			BlockPos inside = j.origin.offset(in.get(0).getAsInt(), in.get(1).getAsInt(), in.get(2).getAsInt());
			int[] room = {0};
			boolean enclosed = enclosed(j.level, inside, j.origin, room);
			r.addProperty("enclosed", enclosed);
			r.addProperty("room", room[0]);
			r.addProperty("light", j.level.getBrightness(LightLayer.BLOCK, inside));
			r.addProperty("headroom", j.level.getBlockState(inside).isAir() && j.level.getBlockState(inside.above()).isAir());
		}
		ServerPlayer p = server.getPlayerList().getPlayer(j.owner);
		if (p != null) {
			truth.send(p, "sandbox_result", r);
		}
		clear(j);
		return true;
	}

	/** Flood fill from inside through anything you could walk or fall through: does it get out? */
	private static boolean enclosed(ServerLevel lv, BlockPos inside, BlockPos origin, int[] room) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		q.add(inside);
		seen.add(inside);
		while (!q.isEmpty()) {
			BlockPos c = q.poll();
			if (Math.abs(c.getX() - origin.getX()) >= R - 1 || Math.abs(c.getZ() - origin.getZ()) >= R - 1 || c.getY() >= origin.getY() + 9) {
				return false; // Out in the open.
			}
			room[0]++;
			for (BlockPos n : new BlockPos[] {c.above(), c.below(), c.north(), c.south(), c.east(), c.west()}) {
				if (n.getY() < origin.getY() || seen.contains(n)) {
					continue;
				}
				BlockState st = lv.getBlockState(n);
				if (st.getCollisionShape(lv, n).isEmpty() || st.is(Blocks.LAVA)) {
					seen.add(n);
					q.add(n);
				}
			}
			if (room[0] > 2000) {
				return false;
			}
		}
		return true;
	}

	private void clear(Job j) {
		if (j.victim != null) {
			dummies.remove(j.victim);
		}
		AABB box = new AABB(j.origin.getX() - R - 1, j.origin.getY() - 12, j.origin.getZ() - R - 1, j.origin.getX() + R + 2,
				j.origin.getY() + 12, j.origin.getZ() + R + 2);
		for (Entity e : j.level.getEntities((Entity) null, box, e -> !(e instanceof ServerPlayer))) {
			e.discard(); // Arrows, dropped items, lit TNT...
		}
		var air = Blocks.AIR.defaultBlockState();
		for (BlockPos q : BlockPos.betweenClosed(j.origin.offset(-R - 3, -12, -R - 3), j.origin.offset(R + 3, 11, R + 3))) {
			if (!j.level.getBlockState(q).isAir()) {
				j.level.setBlock(q, air, Block.UPDATE_CLIENTS);
			}
		}
	}

	/** The blocks a design needs, into the player's inventory (Training Grounds managers only). */
	public static void giveKit(ServerPlayer p, String json) {
		JsonObject d;
		try {
			d = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		JsonArray palette = d.getAsJsonArray("palette");
		int[] counts = new int[palette.size()];
		for (var el : d.getAsJsonArray("blocks")) {
			counts[el.getAsJsonArray().get(3).getAsInt()]++;
		}
		for (int i = 0; i < counts.length; i++) {
			String id = palette.get(i).getAsString().split("\\[")[0];
			if (id.equals("minecraft:air")) {
				continue;
			}
			if (id.equals("minecraft:lava")) {
				id = "minecraft:lava_bucket";
			} else if (id.equals("minecraft:water")) {
				id = "minecraft:water_bucket";
			}
			var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(id));
			if (item == net.minecraft.world.item.Items.AIR) {
				continue;
			}
			int n = counts[i];
			if (id.equals("minecraft:oak_door")) {
				n = Math.max(1, n / 2); // One door item makes both halves.
			}
			while (n > 0) {
				int k = Math.min(n, item.getDefaultMaxStackSize());
				p.getInventory().add(new ItemStack(item, k));
				n -= k;
			}
		}
		// What goes inside its containers (arrows for dispensers, and so on).
		if (d.has("items")) {
			for (var en : d.getAsJsonObject("items").entrySet()) {
				JsonArray it = en.getValue().getAsJsonArray();
				var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(it.get(0).getAsString()));
				if (item != net.minecraft.world.item.Items.AIR) {
					p.getInventory().add(new ItemStack(item, Math.min(item.getDefaultMaxStackSize(), it.get(1).getAsInt())));
				}
			}
		}
	}

	public void stop() {
		for (int s = 0; s < running.length; s++) {
			if (running[s] != null) {
				clear(running[s]);
				running[s] = null;
			}
		}
		waiting.clear();
	}
}
