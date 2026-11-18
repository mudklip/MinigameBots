package io.github.mudklip.minigamebots.training.arena;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** A saved training area: its box, spawn points and starting kit. Stored as arenas/<name>.json. */
public final class Arena {
	public String name = "";
	public String dimension = "minecraft:overworld";
	public int minX;
	public int minY;
	public int minZ;
	public int sizeX;
	public int sizeY;
	public int sizeZ;
	/** x, y, z, yaw, pitch. Players are placed on them in turn when the arena resets. */
	public List<double[]> spawns = new ArrayList<>();
	/** Inventory slot -> item (vanilla item JSON); empty = players keep their own items. */
	public JsonArray kit = new JsonArray();
	public long created;

	public BlockPos min() {
		return new BlockPos(minX, minY, minZ);
	}

	public BlockPos max() {
		return new BlockPos(minX + sizeX - 1, minY + sizeY - 1, minZ + sizeZ - 1);
	}

	public AABB box() {
		return new AABB(minX, minY, minZ, minX + sizeX, minY + sizeY, minZ + sizeZ);
	}

	public long volume() {
		return (long) sizeX * sizeY * sizeZ;
	}

	/** Valid names: letters, digits, '-' and '_', up to 32 characters (they become file names). */
	public static boolean validName(String name) {
		return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
	}
}
