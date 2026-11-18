package io.github.mudklip.minigamebots.client.observe;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

/**
 * Combat maths a good player does by instinct, from real item stats (so modded and server
 * weapons work too): damage per hit both ways after armour and protection, hits to kill, and
 * who wins a straight damage race. Formulas follow vanilla; see the knowledge base (combat).
 */
public final class Calculator {
	/** Damage can land at most once per 10 ticks (invulnerability frames): 2 hits per second. */
	private static final double MAX_HITS_PER_SECOND = 2.0;

	private Calculator() {
	}

	/** What a straight fight between the player and {@code other} looks like. */
	public record Fight(int myHitsToKill, int theirHitsToKill, float odds) {
		public static final Fight NONE = new Fight(0, 0, 0);
	}

	public static Fight fight(Player me, LivingEntity other) {
		Level level = me.level();
		Hit mine = hit(me, level);
		Hit theirs = hit(other, level);
		double toThem = afterDefense(mine.damage, other, level);
		double toMe = afterDefense(theirs.damage, me, level);
		double theirHealth = other.getHealth() + other.getAbsorptionAmount();
		double myHealth = me.getHealth() + me.getAbsorptionAmount();
		int myHits = toThem <= 0 ? 999 : (int) Math.ceil(theirHealth / toThem);
		int theirHits = toMe <= 0 ? 999 : (int) Math.ceil(myHealth / toMe);
		double myDps = toThem * Math.min(mine.perSecond, MAX_HITS_PER_SECOND);
		double theirDps = toMe * Math.min(theirs.perSecond, MAX_HITS_PER_SECOND);
		double ttkThem = myDps <= 0 ? 1e6 : theirHealth / myDps;
		double ttkMe = theirDps <= 0 ? 1e6 : myHealth / theirDps;
		float odds = (float) ((ttkMe - ttkThem) / Math.max(1e-6, ttkMe + ttkThem));
		return new Fight(Math.min(myHits, 999), Math.min(theirHits, 999), odds);
	}

	private record Hit(double damage, double perSecond) {
	}

	/** Damage of one full-strength melee hit and how many such hits per second. */
	static Hit hit(LivingEntity e, Level level) {
		ItemStack held = e.getMainHandItem();
		double damage = e instanceof Player ? 1.0 : base(e, Attributes.ATTACK_DAMAGE, 1.0);
		double speed = e instanceof Player ? 4.0 : 1.0; // Mobs attack about once a second.
		ItemAttributeModifiers mods = held.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double[] dmg = {0, 0};
		double[] spd = {0, 0};
		mods.forEach(EquipmentSlot.MAINHAND, (Holder<Attribute> attr, AttributeModifier m) -> {
			double[] t = attr.is(Attributes.ATTACK_DAMAGE) ? dmg : attr.is(Attributes.ATTACK_SPEED) ? spd : null;
			if (t != null) {
				if (m.operation() == AttributeModifier.Operation.ADD_VALUE) {
					t[0] += m.amount();
				} else {
					t[1] += m.amount();
				}
			}
		});
		damage = (damage + dmg[0]) * (1 + dmg[1]);
		if (e instanceof Player) {
			speed = Math.max(0.1, (speed + spd[0]) * (1 + spd[1]));
		}
		int sharpness = level(level, Enchantments.SHARPNESS, held);
		if (sharpness > 0) {
			damage += 0.5 * sharpness + 0.5;
		}
		var strength = e.getEffect(MobEffects.STRENGTH);
		if (strength != null) {
			damage += 3 * (strength.getAmplifier() + 1);
		}
		var weakness = e.getEffect(MobEffects.WEAKNESS);
		if (weakness != null) {
			damage -= 4 * (weakness.getAmplifier() + 1);
		}
		return new Hit(Math.max(0, damage), speed);
	}

	/** Vanilla armour formula, then Protection (4% per level, at most 80%) and Resistance (20% per level). */
	static double afterDefense(double damage, LivingEntity target, Level level) {
		double armor = target.getAttributeValue(Attributes.ARMOR);
		double toughness = target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
		double reduced = damage * (1 - Math.min(20, Math.max(armor / 5, armor - 4 * damage / (toughness + 8))) / 25);
		int epf = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			epf += level(level, Enchantments.PROTECTION, target.getItemBySlot(slot));
		}
		reduced *= 1 - Math.min(20, epf) / 25.0;
		var resistance = target.getEffect(MobEffects.RESISTANCE);
		if (resistance != null) {
			reduced *= Math.max(0, 1 - 0.2 * (resistance.getAmplifier() + 1));
		}
		return reduced;
	}

	/** Fall damage if the player landed now on an ordinary block (Feather Falling counted). */
	public static float fallDamage(Player me) {
		double raw = Math.max(0, me.fallDistance - 3);
		if (raw <= 0) {
			return 0;
		}
		int feather = level(me.level(), Enchantments.FEATHER_FALLING, me.getItemBySlot(EquipmentSlot.FEET)) * 3;
		int prot = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			prot += level(me.level(), Enchantments.PROTECTION, me.getItemBySlot(slot));
		}
		return (float) (raw * (1 - Math.min(20, feather + prot) / 25.0));
	}

	/** A full-strength hit right now would be a critical hit (×1.5). */
	public static boolean critReady(Player me) {
		return me.fallDistance > 0 && !me.onGround() && !me.onClimbable() && !me.isInWater() && !me.isPassenger()
				&& !me.isSprinting() && !me.hasEffect(MobEffects.BLINDNESS) && me.getAttackStrengthScale(0.5f) > 0.9f;
	}

	private static double base(LivingEntity e, Holder<Attribute> attr, double fallback) {
		var inst = e.getAttribute(attr);
		return inst == null ? fallback : inst.getBaseValue();
	}

	private static int level(Level level, ResourceKey<Enchantment> key, ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		var holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key);
		return holder.map(h -> stack.getEnchantments().getLevel(h)).orElse(0);
	}
}
