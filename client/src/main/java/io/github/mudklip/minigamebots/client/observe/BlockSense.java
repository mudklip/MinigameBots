package io.github.mudklip.minigamebots.client.observe;

/**
 * A coarse cube of block categories around the player, {@code SIZE} blocks on each side,
 * centred on the block at the player's feet. Index with {@link #at}.
 */
public record BlockSense(byte[] categories) {
	public static final int RADIUS = 5;
	public static final int SIZE = RADIUS * 2 + 1;

	public enum Category {
		/** Hidden behind other blocks (fair perception). */
		UNKNOWN,
		AIR,
		SOLID,
		/** Has no collision but isn't air: grass, flowers, torches. */
		PASSABLE,
		WATER,
		LAVA,
		/** Hurts on contact: fire, cactus, magma, berry bushes, powder snow. */
		DANGER,
		ORE,
		/** Opens a screen or does something when used: chests, furnaces, crafting tables, doors. */
		INTERACTIVE,
		CLIMBABLE
	}

	public BlockSense {
		if (categories.length != SIZE * SIZE * SIZE) {
			throw new IllegalArgumentException("wrong block sense size");
		}
	}

	public static int index(int dx, int dy, int dz) {
		return ((dy + RADIUS) * SIZE + (dz + RADIUS)) * SIZE + (dx + RADIUS);
	}

	public Category at(int dx, int dy, int dz) {
		return Category.values()[categories[index(dx, dy, dz)]];
	}

	public int count(Category category) {
		int n = 0;
		for (byte b : categories) {
			if (b == category.ordinal()) {
				n++;
			}
		}
		return n;
	}
}
