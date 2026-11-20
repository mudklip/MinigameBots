package io.github.mudklip.minigamebots.training.dummy;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.github.mudklip.minigamebots.training.GroundTruth;
import io.github.mudklip.minigamebots.training.TgStorage;
import io.github.mudklip.minigamebots.training.TrainingGrounds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Training dummies: vanilla mannequins (player-shaped, player-sized, wear armour, hold shields)
 * that the server moves, blocks and fights back with. Each shows its health, damage per second
 * and combo; hits float up as numbers. Settings are kept in dummies.json in the world.
 */
public final class DummyManager {
	public static final String TAG = "minigamebots_dummy";
	private static final double TARGET_RANGE = 16;
	private static final double LEASH = 12;
	private static final int RESPAWN_TICKS = 60;

	/** What we keep for each dummy. */
	public static final class Dummy {
		public UUID id;
		public String dimension;
		public double[] anchor;
		public DummySettings settings;
		/** False for dummies that belong to a scenario episode: they stay down and are removed after it. */
		public boolean respawns = true;
		/** Paused dummies stand still and don't fight (e.g. during an episode countdown). */
		transient boolean paused;
		transient HitStats stats = new HitStats();
		transient int strafeDir = 1;
		transient int nextTurn;
		transient double wanderYaw;
		transient int inReach;
		transient int cooldown;
		transient int shieldTimer;
		transient boolean shieldUp;
		transient int backOff;
		transient int deadFor = -1;
		/** 0 = not trying to crit; 1 = jumped and waiting to fall onto the target. */
		transient int critPhase;
		/** Self-play: the player whose client steers it, and its latest input. */
		transient @Nullable UUID puppetOwner;
		transient io.github.mudklip.minigamebots.common.net.@Nullable TgPuppetPayload control;
		transient long controlTick = Long.MIN_VALUE;
		transient boolean wasAttacking;
		transient int sinceAttack = 100;
		/** An axe hit knocked the shield down until this tick (like a player's shield cooldown). */
		transient long shieldDisabledUntil;
		transient long shieldRaisedAt;
		/** The last swing as a puppet: {charge, sprint (1/0), crit (1/0)}. */
		transient float @Nullable [] lastSwing;

		Dummy(UUID id, String dimension, double[] anchor, DummySettings settings) {
			this.id = id;
			this.dimension = dimension;
			this.anchor = anchor;
			this.settings = settings;
		}

		Vec3 anchorVec() {
			return new Vec3(anchor[0], anchor[1], anchor[2]);
		}

		void ensureTransient() {
			if (stats == null) {
				stats = new HitStats();
				strafeDir = 1;
				deadFor = -1;
			}
		}
	}

	private final GroundTruth truth;
	private final DamageNumbers numbers = new DamageNumbers();
	private final Map<UUID, Dummy> dummies = new HashMap<>();
	private @Nullable MinecraftServer server;
	private long tick;

	public DummyManager(GroundTruth truth) {
		this.truth = truth;
	}

	public DamageNumbers numbers() {
		return numbers;
	}

	public void load(MinecraftServer server) {
		this.server = server;
		dummies.clear();
		Path f = file();
		if (Files.isRegularFile(f)) {
			try {
				List<Dummy> list = TgStorage.GSON.fromJson(Files.readString(f), new TypeToken<List<Dummy>>() { }.getType());
				if (list != null) {
					for (Dummy d : list) {
						if (d != null && d.id != null && d.settings != null && d.anchor != null) {
							d.ensureTransient();
							dummies.put(d.id, d);
						}
					}
				}
			} catch (IOException | RuntimeException e) {
				TrainingGrounds.LOGGER.warn("Couldn't read dummies.json", e);
			}
		}
	}

	public void unload() {
		numbers.clear();
		server = null;
	}

	private Path file() {
		return TgStorage.dir(server()).resolve("dummies.json");
	}

	private MinecraftServer server() {
		if (server == null) {
			throw new IllegalStateException("No server");
		}
		return server;
	}

	private void save() {
		try {
			TgStorage.writeAtomic(file(), TgStorage.GSON.toJson(new ArrayList<>(dummies.values())));
		} catch (IOException e) {
			TrainingGrounds.LOGGER.warn("Couldn't save dummies.json", e);
		}
	}

	public boolean isDummy(Entity e) {
		return e instanceof Mannequin && dummies.containsKey(e.getUUID());
	}

	public @Nullable Dummy get(Entity e) {
		return dummies.get(e.getUUID());
	}

	/** Ticks since the dummy last swung, for its attack charge (multi-world observations). */
	public int sinceAttack(UUID id) {
		Dummy d = dummies.get(id);
		return d == null ? 100 : d.sinceAttack;
	}

	public @Nullable Dummy byId(UUID id) {
		return dummies.get(id);
	}

	public void setPuppetOwner(UUID dummy, UUID owner) {
		Dummy d = dummies.get(dummy);
		if (d != null) {
			d.ensureTransient();
			d.puppetOwner = owner;
		}
	}

	/** Input for a learner from the brain directly (headless training worlds, same game). */
	public void controlDirect(ServerLevel level, io.github.mudklip.minigamebots.common.net.TgPuppetPayload c) {
		Entity e = level.getEntity(c.entityId());
		Dummy d = e == null ? null : dummies.get(e.getUUID());
		if (d == null || d.puppetOwner == null) {
			return;
		}
		d.control = c;
		d.controlTick = tick;
	}

	/** Self-play input from a client; ignored unless that player owns the dummy. */
	public void control(ServerPlayer p, io.github.mudklip.minigamebots.common.net.TgPuppetPayload c) {
		Entity e = p.level().getEntity(c.entityId());
		Dummy d = e == null ? null : dummies.get(e.getUUID());
		if (d == null || !p.getUUID().equals(d.puppetOwner)) {
			return;
		}
		if (d.control == null) {
			io.github.mudklip.minigamebots.training.TrainingGrounds.LOGGER.info("Self-play: {}'s AI is steering dummy {}",
					p.getPlainTextName(), c.entityId());
		}
		d.control = c;
		d.controlTick = tick;
	}

	/** How a puppet dummy's last swing was made, or null: {charge, sprint, crit}. */
	public float @Nullable [] lastSwing(UUID id) {
		Dummy d = dummies.get(id);
		return d == null ? null : d.lastSwing;
	}

	public void setPaused(UUID id, boolean paused) {
		Dummy d = dummies.get(id);
		if (d != null) {
			d.paused = paused;
		}
	}

	/** Removes a dummy by id (loaded or not). */
	/** A game-mode brain steering a dummy instead of the usual fighting (see {@link ModeBots}). */
	public interface Brain {
		/** Moves and acts for this tick; true = handled (skip the normal fighting). */
		boolean tick(Mannequin m, Dummy d, @Nullable LivingEntity target, ServerLevel level);
	}

	private final java.util.Map<UUID, Brain> brains = new java.util.HashMap<>();

	public void setBrain(UUID id, @Nullable Brain brain) {
		if (brain == null) {
			brains.remove(id);
		} else {
			brains.put(id, brain);
		}
	}

	public void remove(UUID id) {
		brains.remove(id);
		Dummy d = dummies.remove(id);
		if (d != null) {
			Entity e = entity(d);
			if (e != null) {
				e.discard();
			}
			save();
		}
	}

	public int count() {
		return dummies.size();
	}

	/** Spawns a dummy 3 blocks in front of the player, facing them. */
	public @Nullable Mannequin spawn(ServerPlayer player, DummySettings settings) {
		Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
		Vec3 at = player.position().add(look.scale(3));
		return spawnAt(player.level(), at, player.getYRot() + 180, settings);
	}

	public @Nullable Mannequin spawnAt(ServerLevel level, Vec3 at, float yaw, DummySettings settings) {
		return spawnAt(level, at, yaw, settings, true);
	}

	public @Nullable Mannequin spawnAt(ServerLevel level, Vec3 at, float yaw, DummySettings settings, boolean respawns) {
		Mannequin m = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
		if (m == null) {
			return null;
		}
		m.snapTo(at.x, at.y, at.z, yaw, 0);
		m.setYHeadRot(yaw);
		m.addTag(TAG);
		m.setCustomNameVisible(true);
		equip(m, settings);
		if (!level.addFreshEntity(m)) {
			return null;
		}
		Dummy d = new Dummy(m.getUUID(), level.dimension().identifier().toString(), new double[] {at.x, at.y, at.z},
				settings);
		d.respawns = respawns;
		d.ensureTransient();
		dummies.put(d.id, d);
		updateName(m, d);
		save();
		return m;
	}

	/** Applies changed settings (armour and items) to a live dummy. */
	public void apply(Mannequin m, Dummy d) {
		equip(m, d.settings);
		m.setHealth(m.getMaxHealth());
		d.stats.reset();
		updateName(m, d);
		save();
	}

	public void remove(Entity e) {
		Dummy d = dummies.remove(e.getUUID());
		e.discard();
		if (d != null) {
			save();
		}
	}

	/** Removes every dummy within {@code radius} of the player (all loaded ones if radius <= 0). */
	public int removeNear(ServerPlayer player, double radius) {
		int n = 0;
		for (Dummy d : new ArrayList<>(dummies.values())) {
			Entity e = entity(d);
			if (e != null && e.level() == player.level() && (radius <= 0 || e.distanceTo(player) <= radius)) {
				remove(e);
				n++;
			}
		}
		return n;
	}

	private static void equip(Mannequin m, DummySettings s) {
		Item[] set = switch (s.armor) {
			case NONE -> null;
			case LEATHER -> new Item[] {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
			case IRON -> new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
			case DIAMOND -> new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
			case NETHERITE -> new Item[] {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS,
					Items.NETHERITE_BOOTS};
		};
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < 4; i++) {
			m.setItemSlot(slots[i], set == null ? ItemStack.EMPTY : new ItemStack(set[i]));
		}
		m.setItemSlot(EquipmentSlot.MAINHAND, s.fightsBack ? new ItemStack(s.cheats ? Items.NETHERITE_SWORD
				: s.level >= 4 ? Items.DIAMOND_SWORD : Items.IRON_SWORD) : ItemStack.EMPTY);
		boolean shield = s.defense != DummySettings.Defense.NONE || s.smartShield;
		m.setItemSlot(EquipmentSlot.OFFHAND, shield ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
		if (m.isUsingItem() && !shield) {
			m.stopUsingItem();
		}
	}

	private @Nullable Entity entity(Dummy d) {
		ServerLevel level = level(d);
		return level == null ? null : level.getEntity(d.id);
	}

	private @Nullable ServerLevel level(Dummy d) {
		if (server == null) {
			return null;
		}
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(d.dimension)));
	}

	// --- Ticking ---

	public void tick() {
		tick++;
		numbers.tick();
		for (Dummy d : new ArrayList<>(dummies.values())) {
			Entity e = entity(d);
			if (d.deadFor >= 0) {
				if (++d.deadFor >= RESPAWN_TICKS) {
					respawn(d);
				}
				continue;
			}
			if (!(e instanceof Mannequin m) || !m.isAlive()) {
				continue; // Unloaded chunk, or removed by something else.
			}
			ServerLevel level = (ServerLevel) m.level();
			d.sinceAttack++;
			if (d.settings.puppet && d.control != null && tick - d.controlTick <= 10 && !d.paused) {
				puppet(m, d, level);
				if (tick % 5 == 0) {
					updateName(m, d);
				}
				continue;
			}
			LivingEntity target = d.paused ? null : target(m, level);
			Brain brain = brains.get(d.id);
			if (brain != null && !d.paused && brain.tick(m, d, target, level)) {
				if (tick % 5 == 0) {
					updateName(m, d);
				}
				continue;
			}
			move(m, d, target, level);
			defend(m, d, target);
			if (target != null && d.settings.fightsBack) {
				fight(m, d, target, level);
			}
			if (tick % 5 == 0) {
				updateName(m, d);
			}
		}
	}

	/**
	 * The nearest opponent: a player (never a teammate), or a dummy on another scoreboard team.
	 * Dummies without teams leave each other alone.
	 */
	private @Nullable LivingEntity target(Mannequin m, ServerLevel level) {
		LivingEntity best = null;
		double bestD = TARGET_RANGE * TARGET_RANGE;
		for (ServerPlayer p : level.players()) {
			if (p.isSpectator() || !p.isAlive() || m.isAlliedTo(p)) {
				continue;
			}
			double dist = p.distanceToSqr(m);
			if (dist < bestD) {
				best = p;
				bestD = dist;
			}
		}
		if (m.getTeam() != null) {
			for (Mannequin other : level.getEntitiesOfClass(Mannequin.class, m.getBoundingBox().inflate(TARGET_RANGE),
					o -> o != m && o.isAlive() && o.getTeam() != null && !m.isAlliedTo(o) && dummies.containsKey(o.getUUID()))) {
				double dist = other.distanceToSqr(m);
				if (dist < bestD) {
					best = other;
					bestD = dist;
				}
			}
		}
		return best;
	}

	private void move(Mannequin m, Dummy d, @Nullable LivingEntity target, ServerLevel level) {
		var rand = level.getRandom();
		Vec3 pos = m.position();
		Vec3 toAnchor = flat(d.anchorVec().subtract(pos));
		Vec3 desired = Vec3.ZERO;
		DummySettings s = d.settings;
		if (target != null) {
			Vec3 toT = flat(target.position().subtract(pos));
			double dist = toT.length();
			Vec3 dir = dist > 1e-4 ? toT.scale(1 / dist) : Vec3.ZERO;
			Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(d.strafeDir);
			if (--d.nextTurn <= 0) {
				d.strafeDir = -d.strafeDir;
				d.nextTurn = 15 + rand.nextInt(25);
				d.wanderYaw = rand.nextDouble() * Math.PI * 2;
			}
			desired = switch (s.movement) {
				case STATIONARY -> toAnchor.length() > 0.6 ? toAnchor.normalize().scale(0.15) : Vec3.ZERO;
				case STRAFE -> side.scale(0.18);
				case CIRCLE -> side.scale(0.2).add(dir.scale(Mth.clamp((dist - 3) * 0.1, -0.15, 0.15)));
				case RANDOM -> toAnchor.length() > 5 ? toAnchor.normalize().scale(0.2)
						: new Vec3(Math.cos(d.wanderYaw), 0, Math.sin(d.wanderYaw)).scale(0.17);
				case RETREAT -> dist < 5 ? dir.scale(-0.2) : Vec3.ZERO;
				case SWEATY -> d.backOff > 0 ? dir.scale(-0.2).add(side.scale(0.1))
						: dist > 2.6 ? dir.scale(0.26) : side.scale(0.2);
			};
			// A dummy that fights back first closes in to its reach, then moves in its own style.
			boolean closesIn = s.fightsBack && s.movement != DummySettings.Movement.STATIONARY
					&& s.movement != DummySettings.Movement.RETREAT && s.movement != DummySettings.Movement.SWEATY;
			if (closesIn && dist > s.reach) {
				desired = dir.scale(0.22).add(side.scale(0.05));
			}
			face(m, target);
		} else if (toAnchor.length() > 0.6) {
			desired = toAnchor.normalize().scale(0.15);
		}
		if (d.backOff > 0) {
			d.backOff--;
		}
		if (toAnchor.length() > LEASH) {
			desired = toAnchor.normalize().scale(0.25);
		}
		// While knocked back, let the knockback play out like it would for a player.
		if (m.hurtTime == 0) {
			Vec3 dm = m.getDeltaMovement();
			double y = dm.y;
			if (s.jumps && m.onGround() && desired.lengthSqr() > 0 && rand.nextFloat() < 0.04f) {
				y = 0.42;
			}
			m.setDeltaMovement(desired.x, y, desired.z);
			m.setSprinting(desired.length() > 0.22);
		}
	}

	/** Plays a self-play dummy from its owner's input, with a player's movement and attack rules. */
	private void puppet(Mannequin m, Dummy d, ServerLevel level) {
		var c = d.control;
		float yaw = m.getYRot() + Mth.clamp(c.yaw(), -40, 40);
		float pitch = Mth.clamp(m.getXRot() + Mth.clamp(c.pitch(), -40, 40), -90, 90);
		m.setYRot(yaw);
		m.setYHeadRot(yaw);
		m.setYBodyRot(yaw);
		m.setXRot(pitch);
		double fwd = (c.has("forward") ? 1 : 0) - (c.has("back") ? 1 : 0);
		double side = (c.has("left") ? 1 : 0) - (c.has("right") ? 1 : 0);
		boolean sprint = c.has("sprint") && fwd > 0 && !c.has("sneak") && !c.has("use");
		double speed = c.has("sneak") || c.has("use") ? 0.07 : sprint ? 0.28 : 0.21;
		double len = Math.sqrt(fwd * fwd + side * side);
		double rad = Math.toRadians(yaw);
		Vec3 desired = Vec3.ZERO;
		if (len > 0) {
			fwd /= len;
			side /= len;
			desired = new Vec3(-Math.sin(rad) * fwd + Math.cos(rad) * side, 0, Math.cos(rad) * fwd + Math.sin(rad) * side).scale(speed);
		}
		if (m.hurtTime == 0) {
			double y = m.getDeltaMovement().y;
			if (c.has("jump") && m.onGround()) {
				y = 0.42;
			}
			m.setDeltaMovement(desired.x, y, desired.z);
		}
		m.setSprinting(sprint);
		// Shield in the off hand while "use" is held.
		boolean holding = m.isUsingItem() && m.getUsedItemHand() == InteractionHand.OFF_HAND;
		if (c.has("use") && !holding && m.getOffhandItem().is(Items.SHIELD)) {
			m.startUsingItem(InteractionHand.OFF_HAND);
		} else if (!c.has("use") && holding) {
			m.stopUsingItem();
		}
		boolean attack = c.has("attack");
		if (attack && !d.wasAttacking) {
			swingAs(m, d, level, sprint);
		}
		d.wasAttacking = attack;
	}

	/** A player-style swing: hits what the dummy looks at within reach, scaled by how charged it was. */
	private void swingAs(Mannequin m, Dummy d, ServerLevel level, boolean sprint) {
		m.swing(InteractionHand.MAIN_HAND, m.getMainHandItem().getAttackAnimation(), false);
		float charge = Mth.clamp(d.sinceAttack / 12.5f, 0, 1);
		d.sinceAttack = 0;
		if (m.isUsingItem()) {
			m.stopUsingItem();
		}
		Vec3 eye = m.getEyePosition();
		Vec3 end = eye.add(m.getLookAngle().scale(3.0));
		LivingEntity hit = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity t : level.getEntitiesOfClass(LivingEntity.class, m.getBoundingBox().inflate(4),
				t -> t != m && t.isAlive() && !m.isAlliedTo(t) && !(t instanceof ServerPlayer sp && sp.isSpectator()))) {
			var clip = t.getBoundingBox().inflate(0.1).clip(eye, end);
			if (clip.isPresent() && clip.get().distanceToSqr(eye) < best) {
				best = clip.get().distanceToSqr(eye);
				hit = t;
			}
		}
		if (hit == null) {
			return;
		}
		boolean crit = charge > 0.9f && !m.onGround() && m.getDeltaMovement().y < 0 && !sprint;
		d.lastSwing = new float[] {charge, sprint ? 1 : 0, crit ? 1 : 0};
		float dmg = d.settings.damage * (0.2f + charge * charge * 0.8f) * (crit ? 1.5f : 1f);
		hit.hurtServer(level, attack(m, level), dmg);
		if (crit) {
			level.getChunkSource().sendToTrackingPlayersAndSelf(m, new net.minecraft.network.protocol.game.ClientboundAnimatePacket(hit, 4));
		}
		if (sprint && charge > 0.9f) {
			Vec3 dir = flat(hit.position().subtract(m.position()));
			if (dir.lengthSqr() > 1e-4) {
				dir = dir.normalize();
				hit.push(dir.x * 0.5, 0.1, dir.z * 0.5);
				hit.needsSync = true;
			}
		}
	}

	private static Vec3 flat(Vec3 v) {
		return new Vec3(v.x, 0, v.z);
	}

	private static void face(Mannequin m, LivingEntity target) {
		Vec3 d = target.getEyePosition().subtract(m.getEyePosition());
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
		m.setYRot(yaw);
		m.setYHeadRot(yaw);
		m.setYBodyRot(yaw);
		m.setXRot(pitch);
	}

	private static final int MIN_SHIELD_TICKS = 20;

	private void defend(Mannequin m, Dummy d, @Nullable LivingEntity target) {
		DummySettings.Defense def = d.settings.defense;
		boolean want = false;
		if (d.settings.smartShield && target != null) {
			// Shield up while waiting for its own attack to recharge; down just before swinging.
			want = target.distanceTo(m) < 5 && d.cooldown > 2 && d.critPhase == 0;
		} else if (def != DummySettings.Defense.NONE && target != null && target.distanceTo(m) < 6) {
			if (--d.shieldTimer <= 0) {
				d.shieldUp = !d.shieldUp;
				var r = m.getRandom();
				d.shieldTimer = def == DummySettings.Defense.SHIELD_TIMED ? (d.shieldUp ? 60 : 15)
						: (d.shieldUp ? 30 + r.nextInt(40) : 10 + r.nextInt(20));
			}
			want = d.shieldUp;
		}
		if (tick < d.shieldDisabledUntil) {
			want = false; // Knocked down by an axe: no blocking until it recovers.
		}
		boolean holding = m.isUsingItem() && m.getUsedItemHand() == InteractionHand.OFF_HAND;
		if (want && !holding && m.getOffhandItem().is(Items.SHIELD)) {
			m.startUsingItem(InteractionHand.OFF_HAND);
			d.shieldRaisedAt = tick;
		} else if (!want && holding && (tick - d.shieldRaisedAt >= MIN_SHIELD_TICKS || tick < d.shieldDisabledUntil)) {
			// Kept up at least a second: a shield only blocks after it's been raised for a moment.
			m.stopUsingItem();
		}
	}

	private void fight(Mannequin m, Dummy d, LivingEntity target, ServerLevel level) {
		DummySettings s = d.settings;
		double reach = Math.sqrt(target.getBoundingBox().distanceToSqr(m.getEyePosition()));
		if (d.cooldown > 0) {
			d.cooldown--;
		}
		if (reach > s.reach) {
			d.inReach = 0;
			return;
		}
		d.inReach++;
		if (d.inReach < s.reactionTicks || d.cooldown > 0) {
			return;
		}
		boolean crit = false;
		if (s.crits) {
			// Jump when ready, then strike on the way down (a critical hit, like a player's).
			if (d.critPhase == 0 && m.onGround()) {
				Vec3 dm = m.getDeltaMovement();
				m.setDeltaMovement(dm.x, 0.42, dm.z);
				d.critPhase = 1;
				return;
			}
			if (d.critPhase == 1) {
				if (m.onGround() && m.getDeltaMovement().y <= 0) {
					d.critPhase = 0; // Landed without striking: try again.
					return;
				}
				if (m.getDeltaMovement().y >= 0) {
					return; // Still rising.
				}
				crit = true;
				d.critPhase = 0;
			}
		}
		if (m.isUsingItem()) {
			m.stopUsingItem(); // Can't swing with the shield up.
			d.shieldUp = false;
			d.shieldTimer = 6;
		}
		if (s.cheats && target.isBlocking()) {
			// Teacher cheat: knocks the shield down instantly (a player would switch to an axe).
			target.stopUsingItem();
			if (target instanceof net.minecraft.world.entity.player.Player p) {
				p.getCooldowns().addCooldown(target.getOffhandItem().isEmpty() ? new ItemStack(Items.SHIELD)
						: target.getOffhandItem(), 100);
			}
		}
		m.swing(InteractionHand.MAIN_HAND, m.getMainHandItem().getAttackAnimation(), false);
		d.cooldown = (int) Math.max(2, Math.round(20 / Math.max(0.5, s.cps)));
		if (m.getRandom().nextDouble() < s.accuracy) {
			float dmg = crit ? s.damage * 1.5f : s.damage;
			target.hurtServer(level, attack(m, level), dmg);
			if (crit) {
				level.getChunkSource().sendToTrackingPlayersAndSelf(m,
						new net.minecraft.network.protocol.game.ClientboundAnimatePacket(target, 4));
			}
			if (s.wtap || s.movement == DummySettings.Movement.SWEATY) {
				d.backOff = 6; // W-tap style: step back after a hit.
			}
			if (s.wtap) {
				// A sprint hit: extra knockback, as a player's sprint-reset gives.
				Vec3 dir = flat(target.position().subtract(m.position()));
				if (dir.lengthSqr() > 1e-4) {
					dir = dir.normalize();
					target.push(dir.x * 0.5, 0.1, dir.z * 0.5);
					target.needsSync = true; // Sends the new velocity to a player.
				}
			}
		}
	}

	/**
	 * A melee hit from the dummy. On Peaceful the game cancels all damage "caused by" non-player
	 * mobs, so there the dummy is only the direct source (knockback still comes from it, and
	 * GroundTruth.attacker() still credits it); on other difficulties it's a normal mob attack.
	 */
	static DamageSource attack(Mannequin m, ServerLevel level) {
		DamageSource normal = level.damageSources().mobAttack(m);
		if (level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
			return new DamageSource(normal.typeHolder(), m, null);
		}
		return normal;
	}

	private void updateName(Mannequin m, Dummy d) {
		double dps = d.stats.dps(tick);
		int combo = d.stats.combo(tick);
		m.setCustomName(Component.literal((d.settings.style.isEmpty() ? "L" + d.settings.level : d.settings.style) + " Dummy ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(String.format("\u2764 %.1f", m.getHealth())).withStyle(ChatFormatting.RED)));
		// The label line under the name (vanilla "NPC") shows the stats.
		String stats = dps > 0 || combo > 1 ? String.format("%.1f DPS", dps) + (combo > 1 ? "   combo x" + combo : "")
				: String.format("%d hits, %.0f damage", d.stats.count(), d.stats.total());
		((io.github.mudklip.minigamebots.training.mixin.MannequinAccessor) m)
				.minigamebots$setDescription(Component.literal(stats).withStyle(ChatFormatting.YELLOW));
	}

	// --- Damage hooks (called from Fabric events) ---

	/** Dummies only take damage from players (and their arrows etc.) or other dummies, not falls or walls. */
	public boolean allowDamage(LivingEntity e, DamageSource source) {
		Entity attacker = GroundTruth.attacker(source);
		return !isDummy(e) || attacker instanceof ServerPlayer || attacker != null && isDummy(attacker);
	}

	/** Infinite-health dummies never die: they heal instead. */
	public boolean allowDeath(LivingEntity e) {
		Dummy d = dummies.get(e.getUUID());
		if (d != null && d.settings.infiniteHealth && e instanceof Mannequin) {
			e.setHealth(e.getMaxHealth());
			return false;
		}
		return true;
	}

	public void afterDamage(LivingEntity e, DamageSource source, float base, float taken, boolean blocked) {
		Dummy d = dummies.get(e.getUUID());
		if (d == null || !(e.level() instanceof ServerLevel level)) {
			return;
		}
		if (blocked && e instanceof Mannequin) {
			// Vanilla only wears down a player's shield; a dummy's wears and breaks the same way.
			ItemStack shield = e.getUseItem().isEmpty() ? e.getOffhandItem() : e.getUseItem();
			var blocks = shield.get(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS);
			if (blocks != null) {
				int wear = blocks.itemDamage().apply(base);
				if (wear > 0) {
					shield.hurtAndBreak(wear, e, EquipmentSlot.OFFHAND);
				}
				if (shield.isEmpty() || !e.getOffhandItem().is(Items.SHIELD)) {
					e.stopUsingItem();
					d.shieldUp = false;
				}
			}
		}
		float amount = blocked ? 0 : taken;
		d.stats.hit(tick, amount);
		Entity hitter = GroundTruth.attacker(source);
		if (blocked && hitter instanceof LivingEntity le) {
			var weapon = le.getMainHandItem().get(net.minecraft.core.component.DataComponents.WEAPON);
			if (weapon != null && weapon.disableBlockingForSeconds() > 0) {
				// Axe on a raised shield: it goes down for a while, exactly as it would for a player.
				e.stopUsingItem();
				d.shieldUp = false;
				d.shieldDisabledUntil = tick + Math.round(weapon.disableBlockingForSeconds() * 20);
				level.playSound(null, e.blockPosition(), net.minecraft.sounds.SoundEvents.SHIELD_BREAK.value(),
						net.minecraft.sounds.SoundSource.PLAYERS, 1f, 0.8f + level.getRandom().nextFloat() * 0.4f);
			}
		}
		Component text = blocked ? Component.literal("blocked").withStyle(ChatFormatting.GRAY)
				: Component.literal(String.format("-%.1f", amount)).withStyle(amount >= 6 ? ChatFormatting.GOLD : ChatFormatting.RED);
		numbers.spawn(level, e.position().add(0, e.getBbHeight() * 0.7, 0), text);
		if (d.settings.infiniteHealth) {
			e.setHealth(e.getMaxHealth());
		}
		if (e instanceof Mannequin m) {
			updateName(m, d);
		}
		if (source.getEntity() instanceof ServerPlayer p) {
			JsonObject j = new JsonObject();
			j.addProperty("amount", amount);
			j.addProperty("blocked", blocked);
			j.addProperty("combo", d.stats.combo(tick));
			j.addProperty("dps", d.stats.dps(tick));
			j.addProperty("level", d.settings.level);
			truth.send(p, "dummy_hit", j);
		}
	}

	public void afterDeath(LivingEntity e) {
		Dummy d = dummies.get(e.getUUID());
		if (d != null && d.respawns) {
			d.deadFor = 0; // Comes back at its anchor after a few seconds.
		} else if (d != null) {
			dummies.remove(d.id);
			save();
		}
	}

	private void respawn(Dummy old) {
		dummies.remove(old.id);
		ServerLevel level = level(old);
		if (level == null) {
			save();
			return;
		}
		Mannequin m = spawnAt(level, old.anchorVec(), 0, old.settings);
		if (m == null) {
			save();
		}
	}
}
