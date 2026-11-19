package io.github.mudklip.minigamebots.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Instant practice areas for skills (no setup): built around the player, with the tools they
 * need, and removed again (the ground put back exactly) when cleared or replaced.
 * Kinds: mining, trees, farm, animals, mobs, bridge, parkour, build, pool, inventory.
 */
public final class PracticeAreas {
	public static final List<String> KINDS = List.of("mining", "trees", "farm", "animals", "mobs", "bridge", "parkour", "build",
			"pool", "inventory");
	private final List<Map.Entry<BlockPos, BlockState>> replaced = new ArrayList<>();
	private final List<net.minecraft.world.entity.Entity> spawned = new ArrayList<>();
	private @Nullable ServerLevel level;

	public String setup(ServerPlayer p, String kind) {
		clear();
		ServerLevel lv = (ServerLevel) p.level();
		level = lv;
		BlockPos c = p.blockPosition();
		RandomSource r = lv.getRandom();
		switch (kind) {
			case "mining" -> {
				box(c.offset(2, -1, -5), c.offset(12, 5, 5), Blocks.STONE.defaultBlockState());
				Block[] ores = {Blocks.COAL_ORE, Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE,
						Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE};
				for (int i = 0; i < 70; i++) {
					set(c.offset(2 + r.nextInt(11), r.nextInt(6), -5 + r.nextInt(11)), ores[r.nextInt(ores.length)].defaultBlockState());
				}
				give(p, Items.IRON_PICKAXE, 1, "pickaxe");
				face(p, c, -90);
				return "Mining practice: a block of stone with ores in front of you (and a pickaxe)";
			}
			case "trees" -> {
				for (int i = 0; i < 6; i++) {
					tree(c.offset(4 + (i % 3) * 5, 0, -5 + (i / 3) * 8));
				}
				give(p, Items.IRON_AXE, 1, "_axe");
				face(p, c, -90);
				return "Tree practice: a small grove in front of you (and an axe)";
			}
			case "farm" -> {
				for (int x = 2; x <= 10; x++) {
					for (int z = -4; z <= 4; z++) {
						BlockPos f = c.offset(x, -1, z);
						if (x == 6 && z == 0) {
							set(f, Blocks.WATER.defaultBlockState());
						} else {
							set(f, Blocks.FARMLAND.defaultBlockState());
							set(f.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
						}
					}
				}
				give(p, Items.WHEAT_SEEDS, 16, "wheat_seeds");
				face(p, c, -90);
				return "Farm practice: a field of ripe wheat (harvest and replant)";
			}
			case "animals", "mobs" -> {
				pen(c.offset(2, 0, -6), c.offset(14, 0, 6));
				EntityType<?>[] types = kind.equals("mobs") ? new EntityType<?>[] {net.minecraft.world.entity.EntityTypes.ZOMBIE, net.minecraft.world.entity.EntityTypes.ZOMBIE, net.minecraft.world.entity.EntityTypes.SKELETON,
						net.minecraft.world.entity.EntityTypes.SPIDER} : new EntityType<?>[] {net.minecraft.world.entity.EntityTypes.COW, net.minecraft.world.entity.EntityTypes.PIG, net.minecraft.world.entity.EntityTypes.SHEEP, net.minecraft.world.entity.EntityTypes.CHICKEN};
				for (int i = 0; i < 6; i++) {
					var e = types[i % types.length].spawn(lv, c.offset(5 + r.nextInt(8), 0, -4 + r.nextInt(9)), EntitySpawnReason.COMMAND);
					if (e != null) {
						spawned.add(e);
					}
				}
				give(p, Items.IRON_SWORD, 1, "_sword");
				if (kind.equals("animals")) {
					give(p, Items.WHEAT, 16, "minecraft:wheat");
				}
				face(p, c, -90);
				return kind.equals("mobs") ? "Mob practice: a fenced arena with monsters (and a sword)" : "Animal practice: a pen of animals";
			}
			case "bridge" -> {
				box(c.offset(-2, -12, -2), c.offset(26, -1, 2), Blocks.AIR.defaultBlockState());
				box(c.offset(-2, -1, -2), c.offset(1, -1, 2), Blocks.SMOOTH_STONE.defaultBlockState());
				box(c.offset(22, -1, -2), c.offset(26, -1, 2), Blocks.SMOOTH_STONE.defaultBlockState());
				box(c.offset(-2, -12, -2), c.offset(26, -12, 2), Blocks.HAY_BLOCK.defaultBlockState());
				give(p, Items.OAK_PLANKS, 64, "_planks");
				face(p, c, -90);
				return "Bridge practice: cross the gap to the far platform (soft landing below)";
			}
			case "parkour" -> {
				int y = 0;
				int x = 2;
				for (int i = 0; i < 12; i++) {
					x += 2 + r.nextInt(2);
					y += r.nextInt(3) == 0 ? 1 : 0;
					set(c.offset(x, y - 1, r.nextInt(3) - 1), Blocks.QUARTZ_BLOCK.defaultBlockState());
				}
				set(c.offset(x + 2, y - 1, 0), Blocks.GOLD_BLOCK.defaultBlockState());
				face(p, c, -90);
				return "Parkour practice: jump along the course to the gold block";
			}
			case "build" -> {
				box(c.offset(2, -1, -7), c.offset(16, -1, 7), Blocks.SMOOTH_STONE.defaultBlockState());
				box(c.offset(2, 0, -7), c.offset(16, 8, 7), Blocks.AIR.defaultBlockState());
				give(p, Items.OAK_PLANKS, 64, "_planks");
				give(p, Items.COBBLESTONE, 64, "cobblestone");
				give(p, Items.OAK_DOOR, 2, "door");
				face(p, c, -90);
				return "Build practice: a flat pad and building blocks";
			}
			case "pool" -> {
				box(c.offset(2, -6, -5), c.offset(12, -1, 5), Blocks.WATER.defaultBlockState());
				box(c.offset(2, -7, -5), c.offset(12, -7, 5), Blocks.SAND.defaultBlockState());
				give(p, Items.FISHING_ROD, 1, "fishing_rod");
				face(p, c, -90);
				return "Pool: swim, surface for air, climb out; or fish with the rod";
			}
			case "inventory" -> {
				Item[] mess = {Items.ROTTEN_FLESH, Items.DIRT, Items.STONE_SWORD, Items.BREAD, Items.COBBLESTONE, Items.WHEAT_SEEDS,
						Items.IRON_PICKAXE, Items.ARROW, Items.COOKED_BEEF, Items.OAK_PLANKS, Items.GRAVEL, Items.TORCH};
				for (int i = 0; i < 18; i++) {
					p.getInventory().add(new ItemStack(mess[r.nextInt(mess.length)], 1 + r.nextInt(16)));
				}
				return "Inventory practice: a messy inventory to sort (weapon, food, blocks, tools in the hotbar)";
			}
			default -> {
				return "Kinds: " + String.join(", ", KINDS);
			}
		}
	}

	/** Removes the area: the ground goes back exactly as it was, spawned creatures go. */
	public void clear() {
		ServerLevel lv = level;
		if (lv != null) {
			for (int k = replaced.size() - 1; k >= 0; k--) {
				lv.setBlock(replaced.get(k).getKey(), replaced.get(k).getValue(), Block.UPDATE_CLIENTS);
			}
		}
		spawned.forEach(e -> e.discard());
		spawned.clear();
		replaced.clear();
	}

	private void set(BlockPos p, BlockState want) {
		ServerLevel lv = level;
		if (lv == null || lv.getBlockEntity(p) != null) {
			return;
		}
		BlockState had = lv.getBlockState(p);
		if (had != want) {
			replaced.add(Map.entry(p.immutable(), had));
			lv.setBlock(p, want, Block.UPDATE_CLIENTS);
		}
	}

	private void box(BlockPos a, BlockPos b, BlockState s) {
		for (BlockPos p : BlockPos.betweenClosed(a, b)) {
			set(p, s);
		}
	}

	private void tree(BlockPos base) {
		set(base.below(), Blocks.DIRT.defaultBlockState());
		for (int y = 0; y < 5; y++) {
			set(base.above(y), Blocks.OAK_LOG.defaultBlockState());
		}
		for (BlockPos p : BlockPos.betweenClosed(base.offset(-2, 3, -2), base.offset(2, 5, 2))) {
			if (level != null && level.getBlockState(p).isAir()) {
				set(p, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
			}
		}
	}

	private void pen(BlockPos a, BlockPos b) {
		box(a.below(), b.below(), Blocks.GRASS_BLOCK.defaultBlockState());
		box(a, b.above(3), Blocks.AIR.defaultBlockState());
		for (BlockPos p : BlockPos.betweenClosed(a, b)) {
			if (p.getX() == a.getX() || p.getX() == b.getX() || p.getZ() == a.getZ() || p.getZ() == b.getZ()) {
				set(p, Blocks.OAK_FENCE.defaultBlockState());
				set(p.above(), Blocks.OAK_FENCE.defaultBlockState());
			}
		}
		set(a.offset(0, 0, (b.getZ() - a.getZ()) / 2), Blocks.AIR.defaultBlockState()); // A gap to walk in.
		set(a.offset(0, 1, (b.getZ() - a.getZ()) / 2), Blocks.AIR.defaultBlockState());
	}

	/** Gives the item unless the player already has something matching {@code have}. */
	private static void give(ServerPlayer p, Item item, int count, String have) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			var st = inv.getItem(i);
			if (!st.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString().contains(have)) {
				return;
			}
		}
		inv.add(new ItemStack(item, count));
	}

	private static void face(ServerPlayer p, BlockPos c, float yaw) {
		p.teleportTo((ServerLevel) p.level(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), yaw, 10, true);
	}
}
