package io.github.mudklip.minigamebots.client.observe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * Remembers who hit whom, from the damage events the server sends every nearby client (they
 * name the attacker exactly). Like a person remembering who just attacked them; it forgets
 * after {@link #FORGET_TICKS}.
 */
public final class RelationTracker {
	public static final int FORGET_TICKS = 600;
	public static final int NEVER = -1;

	private final Map<Integer, Long> hitMe = new ConcurrentHashMap<>();
	private final Map<Integer, Long> hitByMe = new ConcurrentHashMap<>();
	private final Map<Integer, Long> hitProtected = new ConcurrentHashMap<>();
	private volatile long tick;
	private volatile int protectedId = -1;
	private IntConsumer onHitMe = id -> { };
	private IntConsumer onHitProtected = id -> { };

	public void setTick(long tick) {
		this.tick = tick;
		if (tick % 200 == 0) {
			hitMe.values().removeIf(t -> tick - t > FORGET_TICKS);
			hitByMe.values().removeIf(t -> tick - t > FORGET_TICKS);
			hitProtected.values().removeIf(t -> tick - t > FORGET_TICKS);
		}
	}

	/** The entity the AI is protecting (set by goals), or -1. */
	public void setProtected(int entityId) {
		protectedId = entityId;
	}

	public int protectedId() {
		return protectedId;
	}

	/** Called with the attacker's entity id whenever something hits the local player. */
	/** Called with the attacker's id whenever the protected entity is hit. */
	public void onHitProtected(IntConsumer listener) {
		this.onHitProtected = listener;
	}

	public void onHitMe(IntConsumer listener) {
		this.onHitMe = listener;
	}

	/**
	 * A damage event: {@code victim} was hurt; {@code cause} is who's responsible (the shooter of
	 * an arrow) and {@code direct} what touched it (the arrow); either may be -1.
	 */
	public void onDamage(int victim, int cause, int direct, int me) {
		int attacker = cause >= 0 ? cause : direct;
		if (attacker < 0 || attacker == victim) {
			return;
		}
		long now = tick;
		if (victim == me) {
			hitMe.put(attacker, now);
			onHitMe.accept(attacker);
		}
		if (attacker == me) {
			hitByMe.put(victim, now);
		}
		if (victim == protectedId && protectedId >= 0) {
			hitProtected.put(attacker, now);
			onHitProtected.accept(attacker);
		}
	}

	public int ticksSinceHitMe(int id) {
		return since(hitMe, id);
	}

	public int ticksSinceHitByMe(int id) {
		return since(hitByMe, id);
	}

	public int ticksSinceHitProtected(int id) {
		return since(hitProtected, id);
	}

	private int since(Map<Integer, Long> map, int id) {
		Long t = map.get(id);
		if (t == null || tick - t > FORGET_TICKS) {
			return NEVER;
		}
		return (int) (tick - t);
	}

	public void clear() {
		hitMe.clear();
		hitByMe.clear();
		hitProtected.clear();
		protectedId = -1;
	}

	/**
	 * How much an entity deserves one of the limited detailed slots: lower comes first. Distance
	 * in blocks, minus bonuses for what matters: the person being protected, whoever is
	 * attacking, players, hostile mobs and projectiles.
	 */
	public static double importance(double distance, boolean isProtected, int hitMeAge, int hitProtectedAge,
			int hitByMeAge, boolean player, boolean hostileOrProjectile, boolean droppedItem) {
		double score = distance;
		if (isProtected) {
			score -= 1000;
		}
		if (hitMeAge >= 0 && hitMeAge <= 200) {
			score -= 500;
		}
		if (hitProtectedAge >= 0 && hitProtectedAge <= 200) {
			score -= 400;
		}
		if (hitByMeAge >= 0 && hitByMeAge <= 200) {
			score -= 150;
		}
		if (player) {
			score -= 60;
		} else if (hostileOrProjectile) {
			score -= 40;
		} else if (droppedItem) {
			// Dropped items and XP orbs pile up after fights; they shouldn't crowd out creatures.
			score += 30;
		}
		return score;
	}

	/**
	 * Compass sector (0-7, 45 degrees each) of an offset, numbered like Minecraft yaw:
	 * 0 = south (+Z), 2 = west (-X), 4 = north, 6 = east.
	 */
	public static int sector(double dx, double dz) {
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		return (int) Math.floorMod((long) Math.floor((yaw + 360 + 22.5) / 45.0), 8L);
	}

	/** Distance band for the crowd radar: 0 = within 8, 1 = within 24, 2 = farther. */
	public static int band(double distance) {
		return distance < 8 ? 0 : distance < 24 ? 1 : 2;
	}
}
