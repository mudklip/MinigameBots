package io.github.mudklip.minigamebots.client.memory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * An insignia: what someone visibly wears that marks their side: shield colours and banner
 * pattern, a banner worn on the head, and dyed leather armour. Two players with the same
 * insignia look like the same side. Empty when they wear nothing distinctive.
 */
public final class Insignia {
	private Insignia() {
	}

	/** A short stable id ("" if nothing distinctive), and a readable description. */
	public record Info(String id, String description) {
		public static final Info NONE = new Info("", "");

		public boolean present() {
			return !id.isEmpty();
		}
	}

	public static Info of(LivingEntity e) {
		List<String> parts = new ArrayList<>();
		for (EquipmentSlot hand : new EquipmentSlot[] {EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND}) {
			ItemStack s = e.getItemBySlot(hand);
			if (s.is(Items.SHIELD)) {
				var base = s.get(DataComponents.BASE_COLOR);
				String layers = layers(s.get(DataComponents.BANNER_PATTERNS));
				if (base != null || !layers.isEmpty()) {
					parts.add("shield " + (base == null ? "plain" : base.getSerializedName()) + layers);
				}
				break;
			}
		}
		ItemStack head = e.getItemBySlot(EquipmentSlot.HEAD);
		String headId = BuiltInRegistries.ITEM.getKey(head.getItem()).getPath();
		if (headId.endsWith("_banner")) {
			parts.add("banner " + headId.replace("_banner", "") + layers(head.get(DataComponents.BANNER_PATTERNS)));
		}
		List<String> dyes = new ArrayList<>();
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			var dyed = e.getItemBySlot(slot).get(DataComponents.DYED_COLOR);
			if (dyed != null) {
				dyes.add(String.format("%06x", dyed.rgb() & 0xFFFFFF));
			}
		}
		if (!dyes.isEmpty()) {
			parts.add("dyed " + String.join("/", dyes));
		}
		if (parts.isEmpty()) {
			return Info.NONE;
		}
		String desc = String.join(", ", parts);
		return new Info(String.format("%08x", desc.hashCode()), desc);
	}

	private static String layers(BannerPatternLayers layers) {
		if (layers == null || layers.layers().isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (var layer : layers.layers()) {
			String pattern = layer.pattern().unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			sb.append(" + ").append(layer.color().getSerializedName()).append(' ').append(pattern);
		}
		return sb.toString();
	}
}
