package io.github.mudklip.minigamebots.training.aim;

import com.google.gson.JsonObject;
import com.mojang.math.Transformation;
import io.github.mudklip.minigamebots.training.GroundTruth;
import io.github.mudklip.minigamebots.training.dummy.DamageNumbers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Aim trainer: small targets in front of the player, hit with left click.
 * Flick: one target at a time, gone after 2 s. Track: a moving target to keep the crosshair on.
 * Switch: three targets at once. Scores: hits, accuracy, time to hit, tracking %.
 */
public final class AimTrainer {
	public enum Mode {
		FLICK("Flick"), TRACK("Track"), SWITCH("Switch");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	private static final float SIZE = 0.5f;
	private static final int FLICK_LIFE = 40;

	private static final class Target {
		Interaction hitbox;
		Display.BlockDisplay look;
		int spawnTick;
		double yawOff;
		double pitchOff;
		double dist;
	}

	private static final class Session {
		final UUID player;
		final Mode mode;
		final int length;
		final Vec3 eye;
		final float yaw;
		final List<Target> targets = new ArrayList<>();
		final AimScore score = new AimScore();
		int age;

		Session(UUID player, Mode mode, int length, Vec3 eye, float yaw) {
			this.player = player;
			this.mode = mode;
			this.length = length;
			this.eye = eye;
			this.yaw = yaw;
		}
	}

	private final GroundTruth truth;
	private final Map<UUID, Session> sessions = new HashMap<>();
	private final Map<String, Double> best = new HashMap<>();

	public AimTrainer(GroundTruth truth) {
		this.truth = truth;
	}

	public boolean active(ServerPlayer p) {
		return sessions.containsKey(p.getUUID());
	}

	public void start(ServerPlayer p, Mode mode, int seconds) {
		stop(p, false);
		Session s = new Session(p.getUUID(), mode, Math.max(5, Math.min(300, seconds)) * 20, p.getEyePosition(), p.getYRot());
		sessions.put(p.getUUID(), s);
		int count = mode == Mode.SWITCH ? 3 : 1;
		for (int i = 0; i < count; i++) {
			s.targets.add(spawn(p.level(), s, p.getRandom()));
		}
		p.sendSystemMessage(Component.literal("Aim trainer: " + mode.label + " for " + seconds + " s. Hit the targets with left click.")
				.withStyle(ChatFormatting.GREEN));
	}

	public void stop(ServerPlayer p, boolean report) {
		Session s = sessions.remove(p.getUUID());
		if (s == null) {
			return;
		}
		s.targets.forEach(AimTrainer::discard);
		if (report) {
			finish(p, s);
		}
	}

	/** A left click by this player (sent for every swing, hit or not). */
	public void onClick(ServerPlayer p) {
		Session s = sessions.get(p.getUUID());
		if (s != null) {
			s.score.click();
		}
	}

	/** Returns true if the attacked entity was one of this player's targets. */
	public boolean onAttack(ServerPlayer p, Entity entity) {
		Session s = sessions.get(p.getUUID());
		if (s == null) {
			return false;
		}
		for (int i = 0; i < s.targets.size(); i++) {
			Target t = s.targets.get(i);
			if (t.hitbox == entity) {
				int took = s.age - t.spawnTick;
				s.score.hit(took);
				ServerLevel level = p.level();
				level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.4f, 1.4f);
				JsonObject j = new JsonObject();
				j.addProperty("mode", s.mode.name().toLowerCase());
				j.addProperty("ms", took * 50);
				truth.send(p, "aim_hit", j);
				if (s.mode != Mode.TRACK) {
					discard(t);
					s.targets.set(i, spawn(level, s, p.getRandom()));
				}
				return true;
			}
		}
		return false;
	}

	public void tick(Iterable<ServerPlayer> players) {
		Map<UUID, ServerPlayer> online = new HashMap<>();
		players.forEach(p -> online.put(p.getUUID(), p));
		for (Session s : new ArrayList<>(sessions.values())) {
			ServerPlayer p = online.get(s.player);
			if (p == null || !p.isAlive()) {
				sessions.remove(s.player);
				s.targets.forEach(AimTrainer::discard);
				continue;
			}
			s.age++;
			ServerLevel level = p.level();
			for (int i = 0; i < s.targets.size(); i++) {
				Target t = s.targets.get(i);
				if (t.hitbox.level() != level || t.hitbox.isRemoved()) {
					discard(t);
					s.targets.set(i, spawn(level, s, p.getRandom()));
					continue;
				}
				if (s.mode == Mode.TRACK) {
					double phase = s.age * 0.06;
					t.yawOff = Math.sin(phase) * 45 + Math.sin(phase * 2.3) * 10;
					t.pitchOff = Math.sin(phase * 1.7) * 12;
					place(t, s);
				} else if (s.mode == Mode.FLICK && s.age - t.spawnTick > FLICK_LIFE) {
					s.score.expired++;
					discard(t);
					s.targets.set(i, spawn(level, s, p.getRandom()));
				}
			}
			if (s.mode == Mode.TRACK) {
				s.score.trackTicks++;
				if (lookingAtTarget(p, s)) {
					s.score.onTargetTicks++;
				}
			}
			if (s.age % 10 == 0) {
				p.sendOverlayMessage(status(s));
			}
			if (s.age >= s.length) {
				sessions.remove(s.player);
				s.targets.forEach(AimTrainer::discard);
				finish(p, s);
			}
		}
	}

	private static boolean lookingAtTarget(ServerPlayer p, Session s) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(6));
		for (Target t : s.targets) {
			AABB box = t.hitbox.getBoundingBox();
			if (box.contains(eye) || box.clip(eye, end).isPresent()) {
				return true;
			}
		}
		return false;
	}

	private Component status(Session s) {
		int left = Math.max(0, (s.length - s.age) / 20);
		String text = s.mode == Mode.TRACK
				? String.format("%s  %ds  on target %.0f%%", s.mode.label, left, s.score.tracking() * 100)
				: String.format("%s  %ds  hits %d  accuracy %.0f%%  %.0f ms", s.mode.label, left, s.score.hits,
						s.score.accuracy() * 100, s.score.avgMillis());
		return Component.literal(text).withStyle(ChatFormatting.YELLOW);
	}

	private void finish(ServerPlayer p, Session s) {
		AimScore sc = s.score;
		double score = sc.score(s.mode);
		String key = p.getUUID() + "/" + s.mode;
		Double prev = best.get(key);
		boolean record = prev == null || score > prev;
		if (record) {
			best.put(key, score);
		}
		String summary = s.mode == Mode.TRACK
				? String.format("on target %.0f%% of the time, %d hits", sc.tracking() * 100, sc.hits)
				: String.format("%d hits, %d missed targets, accuracy %.0f%%, average %.0f ms", sc.hits, sc.expired,
						sc.accuracy() * 100, sc.avgMillis());
		p.sendSystemMessage(Component.literal("Aim trainer " + s.mode.label + ": " + summary + String.format(" (score %.0f%s)", score,
				record ? ", personal best!" : "")).withStyle(ChatFormatting.GREEN));
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(String.format("%.0f", score))
				.withStyle(record ? ChatFormatting.GOLD : ChatFormatting.WHITE)));
		JsonObject j = new JsonObject();
		j.addProperty("mode", s.mode.name().toLowerCase());
		j.addProperty("hits", sc.hits);
		j.addProperty("clicks", sc.clicks);
		j.addProperty("expired", sc.expired);
		j.addProperty("accuracy", sc.accuracy());
		j.addProperty("avg_ms", sc.avgMillis());
		j.addProperty("tracking", sc.tracking());
		j.addProperty("score", score);
		truth.send(p, "aim_result", j);
	}

	private static Target spawn(ServerLevel level, Session s, RandomSource r) {
		Target t = new Target();
		t.spawnTick = s.age;
		t.yawOff = s.mode == Mode.TRACK ? 0 : (r.nextDouble() * 2 - 1) * 60;
		t.pitchOff = s.mode == Mode.TRACK ? 0 : (r.nextDouble() * 2 - 1) * 22;
		t.dist = 2.2 + r.nextDouble() * 0.7; // Inside melee reach.
		t.hitbox = new Interaction(EntityTypes.INTERACTION, level);
		t.hitbox.setWidth(SIZE);
		t.hitbox.setHeight(SIZE);
		DamageNumbers.markTemp(t.hitbox);
		t.look = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		t.look.setBlockState(Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
		t.look.setTransformation(new Transformation(new Vector3f(-SIZE / 2, 0, -SIZE / 2), new Quaternionf(),
				new Vector3f(SIZE, SIZE, SIZE), new Quaternionf()));
		t.look.setPosRotInterpolationDuration(2);
		DamageNumbers.markTemp(t.look);
		place(t, s);
		level.addFreshEntity(t.hitbox);
		level.addFreshEntity(t.look);
		return t;
	}

	private static void place(Target t, Session s) {
		Vec3 dir = Vec3.directionFromRotation((float) t.pitchOff, (float) (s.yaw + t.yawOff));
		Vec3 center = s.eye.add(dir.scale(t.dist));
		Vec3 base = center.subtract(0, SIZE / 2, 0);
		t.hitbox.setPos(base);
		t.look.setPos(base);
	}

	private static void discard(@Nullable Target t) {
		if (t != null) {
			t.hitbox.discard();
			t.look.discard();
		}
	}

	public void clear() {
		sessions.values().forEach(s -> s.targets.forEach(AimTrainer::discard));
		sessions.clear();
	}
}
