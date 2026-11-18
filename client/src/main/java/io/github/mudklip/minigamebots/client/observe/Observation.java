package io.github.mudklip.minigamebots.client.observe;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything the AI knows at one tick, apart from the pixels ({@link Frame}). With fair
 * perception on, it holds only what a human player could see or hear.
 */
public record Observation(
		long tick,
		Self self,
		List<ItemInfo> inventory,
		int selectedSlot,
		List<EntityInfo> entities,
		BlockSense blocks,
		List<SoundInfo> sounds,
		Text text,
		@Nullable Gui gui) {

	/** The player's own state, as the HUD and F3 basics show it. */
	public record Self(float health, float maxHealth, int food, float saturation, int armor, int xpLevel,
			float xpProgress, int air, int maxAir, boolean onGround, boolean inWater, boolean inLava,
			boolean sneaking, boolean sprinting, boolean usingItem, float yaw, float pitch, double x, double y,
			double z, double vx, double vy, double vz, String dimension, List<String> effects, boolean dead) {
	}

	/** An item stack. {@code slot} follows the player inventory (0-8 hotbar, 9-35 main, then armor, offhand). */
	public record ItemInfo(int slot, String id, int count, int damage, int maxDamage, boolean enchanted,
			@Nullable String customName) {
	}

	/**
	 * A nearby entity, relative to the player's eyes. {@code visible} is false when it was seen
	 * recently but is now hidden; its position is then where it was last seen.
	 */
	public record EntityInfo(int id, String type, boolean player, @Nullable String name, @Nullable String team,
			double dx, double dy, double dz, double vx, double vy, double vz, float health, float maxHealth,
			String heldItem, int armor, boolean visible, int ticksSinceSeen) {
	}

	/** A heard sound, relative to the player. */
	public record SoundInfo(String id, String source, double dx, double dy, double dz, int ageTicks) {
	}

	/** Text the game is showing. */
	public record Text(List<String> recentChat, @Nullable String title, @Nullable String subtitle,
			@Nullable String actionbar, @Nullable String sidebarTitle, Map<String, Integer> sidebar,
			List<String> bossBars, @Nullable String tabHeader, @Nullable String tabFooter) {
	}

	/** The open screen, if any. */
	public record Gui(String screen, String title, List<ItemInfo> slots, double cursorX, double cursorY) {
	}
}
