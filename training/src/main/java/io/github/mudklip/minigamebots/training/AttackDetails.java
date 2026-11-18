package io.github.mudklip.minigamebots.training;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.Weapon;

/**
 * How the last melee attack of each player was made, for technique drills: how charged it was,
 * whether it was a sprint hit (extra knockback, the point of W-tapping) and whether it crit.
 * Filled in by PlayerAttackMixin just before the damage it causes.
 */
public final class AttackDetails {
	private record Info(long tick, float charge, boolean sprint, boolean crit) {
	}

	private static final Map<UUID, Info> LAST = new ConcurrentHashMap<>();

	private AttackDetails() {
	}

	public static void begin(ServerPlayer p, float charge, boolean sprint) {
		LAST.put(p.getUUID(), new Info(p.level().getGameTime(), charge, sprint, false));
	}

	public static void crit(ServerPlayer p) {
		LAST.computeIfPresent(p.getUUID(), (k, i) -> new Info(i.tick, i.charge, i.sprint, true));
	}

	public static void forget(UUID id) {
		LAST.remove(id);
	}

	/** Adds charge/sprint_hit/crit/shield_disabled to a damage_dealt event made by {@code p} this tick. */
	static void describe(JsonObject d, ServerPlayer p, LivingEntity victim, boolean blocked) {
		Info i = LAST.get(p.getUUID());
		if (i != null && i.tick == p.level().getGameTime()) {
			d.addProperty("charge", Math.round(i.charge * 100) / 100.0);
			d.addProperty("sprint_hit", i.sprint);
			d.addProperty("crit", i.crit);
		}
		if (blocked) {
			Weapon w = p.getMainHandItem().get(DataComponents.WEAPON);
			d.addProperty("shield_disabled", w != null && w.disableBlockingForSeconds() > 0);
		}
	}
}
