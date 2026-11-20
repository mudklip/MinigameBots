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

	/**
	 * One training world: a team of learners (1-4 bodies, each steered by the same AI on its own,
	 * with no shared link: each only sees what it sees) against as many opponents.
	 */
	private static final class Copy {
		final int index;
		final Vec3 a;
		final Vec3 b;
		final int size;
		final List<UUID> learners = new ArrayList<>();
		final List<UUID> opponents = new ArrayList<>();
		final List<JsonArray> events = new ArrayList<>();
		/** Per opponent: {learner slot that hit it last, tick}. For focus fire and kill credit. */
		final java.util.Map<UUID, long[]> lastHitOn = new java.util.HashMap<>();
		/** Per opponent: {learner slot it hit last, tick}. For helping a teammate. */
		final java.util.Map<UUID, long[]> lastAttackBy = new java.util.HashMap<>();
		int restartIn = -1;

		Copy(int index, Vec3 a, Vec3 b, int size) {
			this.index = index;
			this.a = a;
			this.b = b;
			this.size = size;
			for (int k = 0; k < size; k++) {
				events.add(new JsonArray());
			}
		}

		int slot(UUID id) {
			return learners.indexOf(id);
		}

		/** The first learner still alive (the one a spectator follows). */
		@Nullable UUID learner(java.util.function.Function<UUID, LivingEntity> get) {
			for (UUID u : learners) {
				LivingEntity l = get.apply(u);
				if (l != null && l.isAlive()) {
					return u;
				}
			}
			return learners.isEmpty() ? null : learners.get(0);
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
	private int teamSize = 1;
	private long now;
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
	private BlockPos gridCentre = BlockPos.ZERO;
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
		return startAround(p, count, opponentStyle, 1);
	}

	/** As above, with teams of {@code team} learners against as many opponents in each world. */
	public String startAround(ServerPlayer p, int count, String opponentStyle, int team) {
		stop(p.level().getServer());
		teamSize = Math.max(1, Math.min(4, team));
		if (!truth.supports(p, io.github.mudklip.minigamebots.common.Protocol.Features.PUPPET)) {
			return "Join with the MinigameBots client mod to steer the learners";
		}
		ServerLevel lv = (ServerLevel) p.level();
		level = lv;
		style = opponentStyle;
		owner = p.getUUID();
		int[] g = grid(count);
		BlockPos c = PracticeAreas.ground(p);
		gridCentre = c;
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
				Copy cp = new Copy(made, sa, sb, teamSize);
				copies.add(cp);
				spawn(cp);
				made++;
			}
		}
		return "Started " + made + " training worlds around you (" + (teamSize > 1 ? teamSize + "v" + teamSize + ", " : "")
				+ "opponent: " + style + "); they're removed when you stop";
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
		teamSize = 1;
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
			Copy cp = new Copy(i - 1, sa, sb, 1);
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
		c.learners.clear();
		c.opponents.clear();
		c.lastHitOn.clear();
		c.lastAttackBy.clear();
		for (int k = 0; k < c.size; k++) {
			// Teams line up side by side, 2 blocks apart, facing each other.
			Vec3 side = new Vec3(0, 0, (k - (c.size - 1) / 2.0) * 2);
			Mannequin l = dummies.spawnAt(lv, c.a.add(side), -90, DummySettings.style("self"), false);
			Mannequin o = dummies.spawnAt(lv, c.b.add(side), 90, DummySettings.style(style), false);
			if (l != null) {
				dummies.setPuppetOwner(l.getUUID(), owner);
				scoreboard.addPlayerToTeam(l.getScoreboardName(), learnTeam);
				c.learners.add(l.getUUID());
			}
			if (o != null) {
				dummies.byId(o.getUUID()).settings.infiniteHealth = false;
				scoreboard.addPlayerToTeam(o.getScoreboardName(), oppTeam);
				c.opponents.add(o.getUUID());
			}
		}
	}

	private void removeBodies(Copy c) {
		c.learners.forEach(dummies::remove);
		c.opponents.forEach(dummies::remove);
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
		LivingEntity l = entity(copies.get(index).learner(this::entity));
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
			removeBodies(c);
		}
		if (owner != null && server != null) {
			ServerPlayer p = server.getPlayerList().getPlayer(owner);
			if (p != null) {
				truth.send(p, "mw_stop", new JsonObject());
			}
		}
		copies.clear();
		if (owner != null && System.getProperties().get("minigamebots.worldStop") instanceof Runnable r) {
			r.run();
		}
		if (owner != null && server != null && level != null && !replaced.isEmpty()) {
			ServerPlayer back = server.getPlayerList().getPlayer(owner);
			if (back != null && back.blockPosition().distSqr(gridCentre) < 64 * 64) {
				back.teleportTo(level, gridCentre.getX() + 0.5, gridCentre.getY(), gridCentre.getZ() + 0.5, java.util.Set.of(),
						back.getYRot(), back.getXRot(), true);
			}
		}
		restoreTerrain();
		owner = null;
	}

	private @Nullable LivingEntity entity(@Nullable UUID id) {
		return id == null || level == null ? null : level.getEntity(id) instanceof LivingEntity l ? l : null;
	}

	private @Nullable Copy copyOf(Entity e) {
		for (Copy c : copies) {
			if (c.learners.contains(e.getUUID()) || c.opponents.contains(e.getUUID())) {
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
		Entity by = source.getEntity();
		int attacker = by == null ? -1 : c.slot(by.getUUID());
		int hurt = c.slot(victim.getUUID());
		if (hurt >= 0) {
			c.events.get(hurt).add(event("damage_taken", "amount", taken));
			if (attacker >= 0 && attacker != hurt) {
				c.events.get(attacker).add(event("friendly_fire", "amount", taken)); // Hit its own teammate.
			} else if (by != null && c.opponents.contains(by.getUUID())) {
				c.lastAttackBy.put(by.getUUID(), new long[] {hurt, now});
			}
			return;
		}
		if (c.size == 1) {
			attacker = 0; // 1v1: whatever hurts the opponent is the learner's doing (as before).
		}
		if (attacker < 0) {
			return;
		}
		JsonArray ev = c.events.get(attacker);
		ev.add(event("damage_dealt", "amount", taken));
		if (c.size > 1) {
			long[] hit = c.lastHitOn.get(victim.getUUID());
			if (hit != null && hit[0] != attacker && now - hit[1] <= 60) {
				ev.add(event("team_focus", null, 0)); // Same target as a teammate.
			}
			long[] att = c.lastAttackBy.get(victim.getUUID());
			if (att != null && att[0] != attacker && now - att[1] <= 40) {
				ev.add(event("team_help", null, 0)); // Took on whoever was hitting a teammate.
			}
		}
		c.lastHitOn.put(victim.getUUID(), new long[] {attacker, now});
	}

	public void onDeath(LivingEntity victim) {
		Copy c = copyOf(victim);
		if (c == null || c.restartIn >= 0) {
			return;
		}
		int died = c.slot(victim.getUUID());
		if (died >= 0) {
			c.events.get(died).add(event("death", null, 0));
		} else {
			long[] hit = c.lastHitOn.get(victim.getUUID());
			int killer = c.size == 1 ? 0 : hit == null ? -1 : (int) hit[0];
			for (int k = 0; k < c.size; k++) {
				c.events.get(k).add(event(k == killer ? "kill" : "team_kill", null, 0));
			}
		}
		boolean learnersLeft = false;
		boolean opponentsLeft = false;
		for (UUID u : c.learners) {
			LivingEntity l = entity(u);
			learnersLeft |= l != null && l.isAlive() && l != victim;
		}
		for (UUID u : c.opponents) {
			LivingEntity o = entity(u);
			opponentsLeft |= o != null && o.isAlive() && o != victim;
		}
		if (learnersLeft && opponentsLeft) {
			return; // The round goes on.
		}
		for (int k = 0; k < c.size; k++) {
			JsonObject end = event("tg_episode_end", null, 0);
			end.getAsJsonObject("data").addProperty("result", learnersLeft ? "win" : "loss"); // Shared: the team wins or loses.
			c.events.get(k).add(end);
		}
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
		now++;
		if (watching >= 0 && watching < copies.size()) {
			LivingEntity l = entity(copies.get(watching).learner(this::entity));
			if (l != null && p.getCamera() != l) {
				p.setCamera(l); // A new round: a new learner body.
			}
		}
		JsonArray out = new JsonArray();
		for (Copy c : copies) {
			if (c.restartIn >= 0 && --c.restartIn < 0) {
				removeBodies(c);
				spawn(c);
			}
			for (int k = 0; k < c.learners.size(); k++) {
				LivingEntity l = entity(c.learners.get(k));
				JsonArray ev = c.events.get(k);
				JsonObject o1 = new JsonObject();
				o1.addProperty("copy", c.index);
				if (c.size > 1) {
					o1.addProperty("slot", k);
					o1.addProperty("team", true);
				}
				if (l == null || !l.isAlive()) {
					if (ev.size() > 0) {
						o1.add("events", ev.deepCopy());
						out.add(o1);
						clear(ev);
					}
					continue;
				}
				if (c.size > 1 && now % 20 == 0) {
					for (UUID u : c.learners) {
						LivingEntity mate = entity(u);
						if (mate != null && mate != l && mate.isAlive() && mate.distanceTo(l) < 1.5) {
							ev.add(event("team_clump", null, 0)); // Bunched up: easy to hit both, hard to flank.
							break;
						}
					}
				}
				o1.addProperty("puppet_id", l.getId());
				o1.add("obs", observe(l, c));
				o1.add("events", ev.deepCopy());
				clear(ev);
				out.add(o1);
			}
		}
		JsonObject d = new JsonObject();
		d.add("copies", out);
		d.addProperty("rounds", rounds);
		var step = server.isDedicatedServer() || server.getPlayerCount() != 1 ? null : directLink();
		if (step != null) {
			// Headless: straight to the brain in this same game (no client relay); answers come back
			// one tick later and steer the learners here.
			applyPuppets(step.apply(out.toString()));
			return;
		}
		truth.send(p, "mw", d);
	}

	/** The client's direct brain link, only in singleplayer (the game and this server share a JVM). */
	@SuppressWarnings("unchecked")
	private static java.util.function.@Nullable Function<String, String> directLink() {
		Object f = System.getProperties().get("minigamebots.worldStep");
		return f instanceof java.util.function.Function<?, ?> fn ? (java.util.function.Function<String, String>) fn : null;
	}

	private void applyPuppets(String json) {
		if (level == null) {
			return;
		}
		try {
			for (var el : com.google.gson.JsonParser.parseString(json).getAsJsonArray()) {
				var o = el.getAsJsonObject();
				int keys = 0;
				if (o.has("keys")) {
					for (var k : o.getAsJsonArray("keys")) {
						int i = io.github.mudklip.minigamebots.common.net.TgPuppetPayload.KEYS.indexOf(k.getAsString());
						if (i >= 0) {
							keys |= 1 << i;
						}
					}
				}
				float yaw = o.has("yaw") ? o.get("yaw").getAsFloat() : 0;
				float pitch = o.has("pitch") ? o.get("pitch").getAsFloat() : 0;
				dummies.controlDirect(level, new io.github.mudklip.minigamebots.common.net.TgPuppetPayload(o.get("id").getAsInt(), keys,
						Float.isFinite(yaw) ? yaw : 0, Float.isFinite(pitch) ? pitch : 0));
			}
		} catch (RuntimeException ignored) {
			// A bad answer: the learners just keep their last input.
		}
	}

	private static void clear(JsonArray a) {
		while (a.size() > 0) {
			a.remove(0);
		}
	}

	private JsonObject observe(LivingEntity l, Copy c) {
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
		// Opponents first (the nearest first), then teammates: only what a player standing there could see.
		List<LivingEntity> seen = new ArrayList<>();
		c.opponents.stream().map(this::entity).filter(x -> x != null && x.isAlive())
				.sorted(java.util.Comparator.comparingDouble(x -> x.distanceToSqr(l))).forEach(seen::add);
		for (UUID u : c.learners) {
			LivingEntity mate = entity(u);
			if (mate != null && mate != l && mate.isAlive()) {
				seen.add(mate);
			}
		}
		for (LivingEntity o : seen) {
			boolean mate = c.learners.contains(o.getUUID());
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
			e.addProperty("hostile", !mate);
			if (mate) {
				e.addProperty("sameTeam", true);
				e.addProperty("relation", 1);
			}
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
