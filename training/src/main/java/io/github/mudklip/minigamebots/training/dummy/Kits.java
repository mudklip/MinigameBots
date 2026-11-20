package io.github.mudklip.minigamebots.training.dummy;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Kits for the training worlds: the same gear on both sides, so a fight is fair. "default" keeps
 * whatever the opponent style comes with. Damage follows the weapon; opponents with a shield use
 * it (and it wears out and breaks like a player's).
 */
public final class Kits {
	private Kits() {
	}

	public static void apply(Mannequin m, DummyManager.@Nullable Dummy d, String kit, boolean opponent) {
		if (d == null || "default".equals(kit)) {
			return;
		}
		Item weapon;
		Item[] armour;
		boolean shield = true;
		float damage;
		switch (kit) {
			case "shield" -> {
				weapon = Items.IRON_SWORD;
				armour = new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
				damage = 6;
			}
			case "axe" -> {
				weapon = Items.IRON_AXE;
				armour = new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
				damage = 9;
			}
			case "diamond" -> {
				weapon = Items.DIAMOND_SWORD;
				armour = new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
				damage = 7;
			}
			default -> { // bare
				weapon = Items.AIR;
				armour = new Item[] {Items.AIR, Items.AIR, Items.AIR, Items.AIR};
				shield = false;
				damage = 1;
			}
		}
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < 4; i++) {
			m.setItemSlot(slots[i], new ItemStack(armour[i]));
		}
		m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
		m.setItemSlot(EquipmentSlot.OFFHAND, shield ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
		d.settings.damage = damage;
		if (opponent) {
			d.settings.smartShield = shield;
			if (!shield) {
				d.settings.defense = DummySettings.Defense.NONE;
			}
			if ("axe".equals(kit)) {
				d.settings.cps = Math.min(d.settings.cps, 1.1); // An axe swings slowly.
			}
		}
	}
}
