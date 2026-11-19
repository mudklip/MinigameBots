package io.github.mudklip.minigamebots.training;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.training.arena.Arena;
import io.github.mudklip.minigamebots.training.arena.ArenaManager;
import io.github.mudklip.minigamebots.training.dummy.DummyManager;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Multi-world training (optional): many copies of an arena side by side, each with a "learner"
 * body steered by the player's AI (like self-play's puppet) and an opponent dummy. Every tick the
 * owner's client gets one compact observation per learner (no rendering needed), its brain
 * answers for all of them at once, and each copy restarts by itself when a round ends. N copies
 * = about N times the practice per second; /tick sprint speeds it up further.
 */
public final class MultiTrainer {
	private static final int GAP = 24;
	private static final int RESPAWN_DELAY = 20;

	private static final class Copy {
		final int index;
		final Vec3 a;
		final Vec3 b;
		@Nullable UUID learner;
		@Nullable UUID opponent;
		int restartIn = -1;
		final JsonArray events = new JsonArray();

		Copy(int index, Vec3 a, Vec3 b) {
			this.index = index;
			this.a = a;
			this.b = b;
		}
	}

	private final ArenaManager arenas;
	private final DummyManager dummies;
	private final GroundTruth truth;
	private final List<Copy> copies = new ArrayList<>();
	private @Nullable UUID owner;
	private @Nullable ServerLevel level;
	private String style = "L3";
	private int rounds;
	/** Watching a learner (spectating it): which copy, and how to put the player back after. */
	private int watching = -1;
	private net.minecraft.world.level.@Nullable GameType watchMode;
	private @Nullable Vec3 watchPos;
	private float watchYaw;
	private float watchPitch;

	public MultiTrainer(ArenaManager arenas, DummyManager dummies, GroundTruth truth) {
		this.arenas = arenas;
		this.dummies = dummies;
		this.truth = truth;
	}

	public boolean running() {
		return owner != null;
	}

	/** Blocks replaced by arenas built around the player, put back when training stops. */
	private final java.util.List<java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState>> replaced =
			new java.util.ArrayList<>();
	private static final int HALF = 7; // Arena 15 x 15 inside the walls' outer edge.
	private static final int STEP = 2 * HALF + 1 + 3;

	/** Grid size for n worlds around the player: {cols, rows, skipCentre}. 8 = a ring of 3 x 3. */
	static int[] grid(int n) {
		int cols = (int) Math.ceil(Math.sqrt(n));
		int rows = (int) Math.ceil(n / (double) cols);
		boolean odd = cols % 2 == 1 && rows % 2 == 1;
		if (odd && cols * rows - 1 < n) {
			rows++; // The centre is the player's spot: one more row instead.
			odd = false;
		}
		if (n == 8) {
			return new int[] {3, 3, 1}; // A ring around the player.
		}
		return new int[] {cols, rows, odd ? 1 : 0};
	}

	/**
	 * Training worlds built in a compact block around the player (no arena or setup needed):
	 * 4 = 2 x 2, 8 = a ring, 12 = 4 x 3. Everything stays near the player, so every world keeps
	 * running; the blocks they replace are put back exactly when training stops.
	 */
	public String startAround(ServerPlayer p, int count, String opponentStyle) {
		stop(p.level().getServer());
		if (!truth.supports(p, io.github.mudklip.minigamebots.common.Protocol.Features.PUPPET)) {
			return "Join with the MinigameBots client mod to steer the learners";
		}
		ServerLevel lv = (ServerLevel) p.level();
		level = lv;
		style = opponentStyle;
		owner = p.getUUID();
		int[] g = grid(count);
		BlockPos c = p.blockPosition();
		// Cell (i, j) centre, measured from the player in steps; the grid is centred on the player.
		double ox = (g[0] - 1) / 2.0;
		double oz = (g[1] - 1) / 2.0;
		int made = 0;
		for (int j = 0; j < g[1] && made < count; j++) {
			for (int i = 0; i < g[0] && made < count; i++) {
				if (g[2] == 1 && i == g[0] / 2 && j == g[1] / 2) {
					continue; // The player's spot.
				}
				int cx = c.getX() + (int) Math.round((i - ox) * STEP);
				int cz = c.getZ() + (int) Math.round((j - oz) * STEP);
				build(lv, new BlockPos(cx, c.getY(), cz));
				Vec3 sa = new Vec3(cx - 4 + 0.5, c.getY(), cz + 0.5);
				Vec3 sb = new Vec3(cx + 4 + 0.5, c.getY(), cz + 0.5);
				Copy cp = new Copy(made, sa, sb);
				copies.add(cp);
				spawn(cp);
				made++;
			}
		}
		return "Started " + made + " training worlds around you (opponent: " + style + "); they're removed when you stop";
	}

	/** One small arena centred at c (floor at c.y - 1, glass walls), remembering what was there. */
	private void build(ServerLevel lv, BlockPos c) {
		var stone = net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState();
		var glass = net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState();
		var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		BlockPos min = c.offset(-HALF, -1, -HALF);
		BlockPos max = c.offset(HALF, 5, HALF);
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			if (lv.getBlockEntity(p) != null) {
				continue; // Never overwrite chests and the like.
			}
			boolean floor = p.getY() == min.getY();
			boolean wall = p.getX() == min.getX() || p.getX() == max.getX() || p.getZ() == min.getZ() || p.getZ() == max.getZ();
			var want = floor ? stone : wall && p.getY() <= c.getY() + 2 ? glass : air;
			var had = lv.getBlockState(p);
			if (had != want) {
				replaced.add(java.util.Map.entry(p.immutable(), had));
				lv.setBlock(p, want, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
			}
		}
	}

	private void restoreTerrain() {
		ServerLevel lv = level;
		if (lv != null) {
			for (int k = replaced.size() - 1; k >= 0; k--) {
				var e = replaced.get(k);
				lv.setBlock(e.getKey(), e.getValue(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
			}
		}
		replaced.clear();
	}

	public String start(ServerPlayer p, Arena arena, int count, String opponentStyle) throws ArenaManager.ArenaException {
		stop(p.level().getServer());
		if (!truth.supports(p, io.github.mudklip.minigamebots.common.Protocol.Features.PUPPET)) {
			throw new ArenaManager.ArenaException("Join with the MinigameBots client mod to steer the learners");
		}
		level = arenas.level(arena);
		style = opponentStyle;
		owner = p.getUUID();
		var box = arena.box();
		int step = (int) Math.ceil(box.getXsize()) + GAP;
		for (int i = 1; i <= count; i++) {
			BlockPos at = arena.min().offset(i * step, 0, 0);
			arenas.placeCopy(arena, at);
			Vec3 shift = new Vec3(i * step, 0, 0);
			Vec3 sa;
			Vec3 sb;
			if (arena.spawns.size() >= 2) {
				sa = new Vec3(arena.spawns.get(0)[0], arena.spawns.get(0)[1], arena.spawns.get(0)[2]).add(shift);
				sb = new Vec3(arena.spawns.get(1)[0], arena.spawns.get(1)[1], arena.spawns.get(1)[2]).add(shift);
			} else {
				Vec3 c = box.getCenter().add(shift);
				double y = box.minY + 1;
				sa = new Vec3(c.x - Math.min(4, box.getXsize() / 3), y, c.z);
				sb = new Vec3(c.x + Math.min(4, box.getXsize() / 3), y, c.z);
			}
			Copy cp = new Copy(i - 1, sa, sb);
			copies.add(cp);
			spawn(cp);
		}
		return "Started " + count + " training worlds next to " + arena.name + " (opponent: " + style + ")";
	}

	private void spawn(Copy c) {
		ServerLevel lv = level;
		if (lv == null || owner == null) {
			return;
		}
		var scoreboard = lv.getServer().getScoreboard();
		var learnTeam = team(scoreboard, "mgb_mw_learn");
		var oppTeam = team(scoreboard, "mgb_mw_opp");
		DummySettings ls = DummySettings.style("self");
		Mannequin l = dummies.spawnAt(lv, c.a, -90, ls, false);
		Mannequin o = dummies.spawnAt(lv, c.b, 90, DummySettings.style(style), false);
		if (l != null) {
			dummies.setPuppetOwner(l.getUUID(), owner);
			scoreboard.addPlayerToTeam(l.getScoreboardName(), learnTeam);
			c.learner = l.getUUID();
		}
		if (o != null) {
			o.getAttributes();
			dummies.byId(o.getUUID()).settings.infiniteHealth = false;
			scoreboard.addPlayerToTeam(o.getScoreboardName(), oppTeam);
			c.opponent = o.getUUID();
		}
	}

	private static net.minecraft.world.scores.PlayerTeam team(net.minecraft.world.scores.Scoreboard sb, String name) {
		var t = sb.getPlayerTeam(name);
		return t != null ? t : sb.addPlayerTeam(name);
	}

	/** Spectates copy {@code index}'s learner (follows it across rounds). */
	public String watch(ServerPlayer p, int index) {
		if (index < 0 || index >= copies.size()) {
			return "There are " + copies.size() + " training worlds";
		}
		if (watching < 0) {
			watchMode = p.gameMode();
			watchPos = p.position();
			watchYaw = p.getYRot();
			watchPitch = p.getXRot();
		}
		watching = index;
		p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
		LivingEntity l = entity(copies.get(index).learner);
		if (l != null) {
			p.setCamera(l);
		}
		return "Watching training world " + (index + 1);
	}

	/** Back to the player's own view, place and game mode. */
	public void unwatch(ServerPlayer p) {
		if (watching < 0) {
			return;
		}
		watching = -1;
		p.setCamera(p);
		if (watchPos != null && level != null) {
			p.teleportTo(level, watchPos.x, watchPos.y, watchPos.z, java.util.Set.of(), watchYaw, watchPitch, true);
		}
		if (watchMode != null) {
			p.setGameMode(watchMode);
		}
	}

	public void stop(net.minecraft.server.MinecraftServer server) {
		if (owner != null && server != null && watching >= 0) {
			ServerPlayer w = server.getPlayerList().getPlayer(owner);
			if (w != null) {
				unwatch(w);
			}
		}
		for (Copy c : copies) {
			if (c.learner != null) {
				dummies.remove(c.learner);
			}
			if (c.opponent != null) {
				dummies.remove(c.opponent);
			}
		}
		if (owner != null && server != null) {
			ServerPlayer p = server.getPlayerList().getPlayer(owner);
			if (p != null) {
				truth.send(p, "mw_stop", new JsonObject());
			}
		}
		copies.clear();
		restoreTerrain();
		owner = null;
	}

	private @Nullable LivingEntity entity(@Nullable UUID id) {
		return id == null || level == null ? null : level.getEntity(id) instanceof LivingEntity l ? l : null;
	}

	private @Nullable Copy copyOf(Entity e) {
		for (Copy c : copies) {
			if (e.getUUID().equals(c.learner) || e.getUUID().equals(c.opponent)) {
				return c;
			}
		}
		return null;
	}

	private static JsonObject event(String type, String key, double value) {
		JsonObject e = new JsonObject();
		e.addProperty("type", type);
		JsonObject d = new JsonObject();
		if (key != null) {
			d.addProperty(key, value);
		}
		e.add("data", d);
		return e;
	}

	public void onDamage(LivingEntity victim, DamageSource source, float taken) {
		Copy c = copyOf(victim);
		if (c == null) {
			return;
		}
		boolean learnerHurt = victim.getUUID().equals(c.learner);
		c.events.add(event(learnerHurt ? "damage_taken" : "damage_dealt", "amount", taken));
	}

	public void onDeath(LivingEntity victim) {
		Copy c = copyOf(victim);
		if (c == null || c.restartIn >= 0) {
			return;
		}
		boolean learnerDied = victim.getUUID().equals(c.learner);
		c.events.add(event(learnerDied ? "death" : "kill", null, 0));
		JsonObject end = event("tg_episode_end", null, 0);
		end.getAsJsonObject("data").addProperty("result", learnerDied ? "loss" : "win");
		c.events.add(end);
		c.restartIn = RESPAWN_DELAY;
		rounds++;
	}

	public void tick(net.minecraft.server.MinecraftServer server) {
		if (owner == null) {
			return;
		}
		ServerPlayer p = server.getPlayerList().getPlayer(owner);
		if (p == null) {
			stop(server);
			return;
		}
		if (watching >= 0 && watching < copies.size()) {
			LivingEntity l = entity(copies.get(watching).learner);
			if (l != null && p.getCamera() != l) {
				p.setCamera(l); // A new round: a new learner body.
			}
		}
		JsonArray out = new JsonArray();
		for (Copy c : copies) {
			if (c.restartIn >= 0 && --c.restartIn < 0) {
				if (c.learner != null) {
					dummies.remove(c.learner);
				}
				if (c.opponent != null) {
					dummies.remove(c.opponent);
				}
				spawn(c);
			}
			LivingEntity l = entity(c.learner);
			LivingEntity o = entity(c.opponent);
			if (l == null || !l.isAlive()) {
				if (c.events.size() > 0) {
					JsonObject only = new JsonObject();
					only.addProperty("copy", c.index);
					only.add("events", c.events.deepCopy());
					out.add(only);
					clear(c.events);
				}
				continue;
			}
			JsonObject o1 = new JsonObject();
			o1.addProperty("copy", c.index);
			o1.addProperty("puppet_id", l.getId());
			o1.add("obs", observe(l, o, c));
			o1.add("events", c.events.deepCopy());
			clear(c.events);
			out.add(o1);
		}
		JsonObject d = new JsonObject();
		d.add("copies", out);
		d.addProperty("rounds", rounds);
		truth.send(p, "mw", d);
	}

	private static void clear(JsonArray a) {
		while (a.size() > 0) {
			a.remove(0);
		}
	}

	private JsonObject observe(LivingEntity l, @Nullable LivingEntity o, Copy c) {
		JsonObject self = new JsonObject();
		Vec3 v = l.getDeltaMovement();
		self.addProperty("health", l.getHealth());
		self.addProperty("maxHealth", l.getMaxHealth());
		self.addProperty("food", 20);
		self.addProperty("yaw", l.getYRot());
		self.addProperty("pitch", l.getXRot());
		self.addProperty("onGround", l.onGround());
		self.addProperty("vx", v.x);
		self.addProperty("vy", v.y);
		self.addProperty("vz", v.z);
		self.addProperty("attackReady", Math.min(1.0, dummies.sinceAttack(l.getUUID()) / 12.5));
		self.addProperty("armor", l.getArmorValue());
		JsonObject obs = new JsonObject();
		obs.add("self", self);
		JsonArray ents = new JsonArray();
		if (o != null && o.isAlive()) {
			JsonObject e = new JsonObject();
			Vec3 eye = l.getEyePosition();
			Vec3 ov = o.getDeltaMovement();
			e.addProperty("id", o.getId());
			e.addProperty("type", "minecraft:player");
			e.addProperty("player", true);
			e.addProperty("visible", true);
			e.addProperty("dx", o.getX() - eye.x);
			e.addProperty("dy", o.getY() - eye.y);
			e.addProperty("dz", o.getZ() - eye.z);
			e.addProperty("vx", ov.x);
			e.addProperty("vy", ov.y);
			e.addProperty("vz", ov.z);
			e.addProperty("health", o.getHealth());
			e.addProperty("maxHealth", o.getMaxHealth());
			e.addProperty("yaw", o.getYHeadRot());
			e.addProperty("hurtTime", o.hurtTime);
			e.addProperty("blocking", o.isBlocking());
			e.addProperty("armor", o.getArmorValue());
			e.addProperty("hostile", true);
			e.addProperty("heldItem", BuiltInRegistries.ITEM.getKey(o.getMainHandItem().getItem()).toString());
			ents.add(e);
		}
		obs.add("entities", ents);
		JsonArray inv = new JsonArray();
		JsonObject sword = new JsonObject();
		sword.addProperty("slot", 0);
		sword.addProperty("id", BuiltInRegistries.ITEM.getKey(l.getMainHandItem().getItem()).toString());
		sword.addProperty("count", 1);
		inv.add(sword);
		obs.add("inventory", inv);
		obs.addProperty("selectedSlot", 0);
		return obs;
	}
}
