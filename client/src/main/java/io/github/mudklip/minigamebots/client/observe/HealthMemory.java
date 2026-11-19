package io.github.mudklip.minigamebots.client.observe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Short-term memory of other players' health, worked out the way a sharp player counts it
 * instead of reading it from the game (fair mode). It remembers, per player:
 * <ul>
 * <li>its own hits on them (weapon, how charged, crits) against the armour it can see, assuming
 * Protection IV on any enchanted (glinting) piece;</li>
 * <li>hits from others (seen as a hurt flash, amount unknown: less sure);</li>
 * <li>totems popped (health drops to 1, then absorption and regeneration), golden apples eaten;</li>
 * <li>time to heal: the longer since a hit, the less sure it is (they may have regenerated).</li>
 * </ul>
 * The estimate is a best guess plus a range (lowest and highest it could be).
 */
public final class HealthMemory {
	public static final class Est {
		public double est;
		public double min;
		public double max;
		public double cap;
		public int myHits;
		public double myDamage;
		public int otherHits;
		public int totems;
		public int gapples;
		long lastSeen;
		long lastHurt = -10_000;
		int eatingTicks;
		long regenUntil;
	}

	private final Map<Integer, Est> byId = new HashMap<>();
	private long tick;
	private float strength = 1;
	private float prevStrength = 1;
	private boolean falling;
	private boolean prevFalling;

	public synchronized @org.jspecify.annotations.Nullable Est get(int id) {
		return byId.get(id);
	}

	/** Every tick, before entities are observed. */
	public synchronized void tick(Player me, Iterable<? extends LivingEntity> players, long now) {
		tick = now;
		prevStrength = strength;
		prevFalling = falling;
		strength = me.getAttackStrengthScale(0.5f);
		falling = me.fallDistance > 0 && !me.onGround() && !me.isInWater() && !me.onClimbable() && !me.isSprinting();
		for (LivingEntity p : players) {
			if (p == me || !p.isAlive()) {
				continue;
			}
			Est e = byId.computeIfAbsent(p.getId(), k -> fresh(p));
			e.lastSeen = now;
			// Eating a golden apple: about 1.6 s of using one.
			boolean gapple = p.isUsingItem() && (p.getUseItem().is(Items.GOLDEN_APPLE) || p.getUseItem().is(Items.ENCHANTED_GOLDEN_APPLE));
			if (gapple) {
				e.eatingTicks++;
			} else if (e.eatingTicks > 0) {
				if (e.eatingTicks >= 28) {
					boolean notch = p.getMainHandItem().is(Items.ENCHANTED_GOLDEN_APPLE) || p.getOffhandItem().is(Items.ENCHANTED_GOLDEN_APPLE);
					e.gapples++;
					double absorb = notch ? 16 : 4;
					e.cap = Math.max(e.cap, p.getMaxHealth() + absorb);
					e.est = Math.min(e.cap, e.est + absorb);
					e.max = e.cap;
					e.min = Math.min(e.cap, e.min + absorb);
					e.regenUntil = now + (notch ? 400 : 100);
				}
				e.eatingTicks = 0;
			}
			// Healing over time: certain while regenerating, possible otherwise (more the longer it's been).
			double base = p.getMaxHealth();
			if (now < e.regenUntil) {
				e.est = Math.min(e.cap, e.est + 0.8 / 20 * 2);
				e.min = Math.min(e.cap, e.min + 0.8 / 20);
			}
			if (now - e.lastHurt > 40) {
				e.est = Math.min(Math.max(base, e.est), e.est + 0.5 / 20);
				e.max = Math.min(e.cap, e.max + 2.0 / 20);
			}
			e.est = clamp(e);
		}
		byId.entrySet().removeIf(en -> now - en.getValue().lastSeen > 20 * 60);
	}

	private Est fresh(LivingEntity p) {
		Est e = new Est();
		e.cap = p.getMaxHealth();
		e.est = e.cap;
		e.max = e.cap;
		e.min = e.cap * 0.5; // Probably full when first met, not certainly.
		e.lastSeen = tick;
		return e;
	}

	private static double clamp(Est e) {
		e.min = Math.max(0.5, Math.min(e.min, e.cap));
		e.max = Math.max(e.min, Math.min(e.max, e.cap));
		return Math.max(e.min, Math.min(e.max, e.est));
	}

	/** A damage event: someone hurt {@code victim}; {@code cause} is who did it. */
	public synchronized void onDamage(Player me, LivingEntity victim, int cause) {
		Est e = byId.get(victim.getId());
		if (e == null || victim == me) {
			return;
		}
		e.lastHurt = tick;
		if (cause == me.getId()) {
			double dmg = myHit(me, victim);
			e.myHits++;
			e.myDamage += dmg;
			e.est -= dmg;
			e.min -= dmg * 1.25;
			e.max -= dmg * 0.75;
		} else {
			e.otherHits++;
			e.est -= 2.5;
			e.min -= 7;
			e.max -= 0.5;
		}
		e.est = clamp(e);
	}

	/** A totem saved them: health 1 plus absorption, then regeneration. */
	public synchronized void onTotem(LivingEntity who) {
		Est e = byId.get(who.getId());
		if (e == null) {
			return;
		}
		e.totems++;
		e.cap = Math.max(e.cap, who.getMaxHealth() + 4);
		e.est = 5;
		e.min = 1;
		e.max = 5;
		e.lastHurt = tick;
		e.regenUntil = tick + 900;
	}

	/** Damage of my hit on them: my weapon, charge and crit, against their visible armour (assumed Protection IV if enchanted). */
	double myHit(Player me, LivingEntity target) {
		double dmg = Calculator.hit(me, me.level()).damage();
		float s = Math.max(strength, prevStrength);
		dmg *= 0.2 + s * s * 0.8;
		if ((falling || prevFalling) && s > 0.9) {
			dmg *= 1.5;
		}
		double armor = 0;
		double toughness = 0;
		int epf = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			var item = target.getItemBySlot(slot);
			if (item.isEmpty()) {
				continue;
			}
			double[] a = {0, 0};
			item.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).forEach(slot, (attr, m) -> {
				if (attr.is(Attributes.ARMOR)) {
					a[0] += m.amount();
				} else if (attr.is(Attributes.ARMOR_TOUGHNESS)) {
					a[1] += m.amount();
				}
			});
			armor += a[0];
			toughness += a[1];
			if (item.hasFoil()) {
				epf += 4;
			}
		}
		dmg *= 1 - Math.min(20, Math.max(armor / 5, armor - 4 * dmg / (toughness + 8))) / 25;
		dmg *= 1 - Math.min(20, epf) / 25.0;
		return Math.max(0, dmg);
	}

	/** How sure the estimate is: 1 = exactly known, 0 = no idea. */
	public static double sure(Est e) {
		return e.cap <= 0 ? 0 : Math.max(0, 1 - (e.max - e.min) / e.cap);
	}

	/** The memory as the brain sees it (one entry per remembered player). */
	public synchronized JsonArray toJson() {
		JsonArray out = new JsonArray();
		for (var en : byId.entrySet()) {
			Est e = en.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("id", en.getKey());
			o.addProperty("est", Math.round(e.est * 10) / 10.0);
			o.addProperty("min", Math.round(e.min * 10) / 10.0);
			o.addProperty("max", Math.round(e.max * 10) / 10.0);
			o.addProperty("sure", Math.round(sure(e) * 100) / 100.0);
			o.addProperty("myHits", e.myHits);
			o.addProperty("myDamage", Math.round(e.myDamage * 10) / 10.0);
			o.addProperty("otherHits", e.otherHits);
			o.addProperty("totems", e.totems);
			o.addProperty("gapples", e.gapples);
			o.addProperty("sinceHurt", Math.min(9999, tick - e.lastHurt));
			out.add(o);
		}
		return out;
	}
}
