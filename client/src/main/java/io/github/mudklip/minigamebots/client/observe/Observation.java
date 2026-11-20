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
		@Nullable Gui gui,
		Crowd crowd,
		List<WaypointInfo> waypoints,
		@Nullable List<PrivilegedPlayer> privileged) {

	/**
	 * Watch & learn only, for training: every nearby player's TRUE state, including ones behind
	 * walls. Never an input of the playing network (the brain's features ignore it, and the
	 * player-demo converter only uses it to follow players and judge outcomes).
	 */
	public record PrivilegedPlayer(int id, String name, double dx, double dy, double dz, float yaw, float pitch,
			boolean onGround, float health, int hurtTime, boolean swinging, boolean usingItem, boolean sprinting,
			boolean sneaking, String heldItem) {
	}

	/**
	 * A remembered place near the AI (same dimension), relative to its eyes. {@code type} is one of
	 * Waypoint.TYPES. The nearest few are included.
	 */
	public record WaypointInfo(String type, String name, double dx, double dy, double dz, double distance, boolean pinned) {
	}

	/** The player's own state, as the HUD and F3 basics show it. */
	public record Self(float health, float maxHealth, int food, float saturation, int armor, int xpLevel,
			float xpProgress, int air, int maxAir, boolean onGround, boolean inWater, boolean inLava,
			boolean sneaking, boolean sprinting, boolean usingItem, float yaw, float pitch, double x, double y,
			double z, double vx, double vy, double vz, String dimension, List<String> effects, boolean dead,
			float attackReady, boolean critReady, float fallDamage, long timeOfDay, boolean dark,
			/** The block under its feet (id), and the marked zone it's in ("" if none). */
			String standingOn, String zone) {
	}

	/** An item stack. {@code slot} follows the player inventory (0-8 hotbar, 9-35 main, then armor, offhand). */
	public record ItemInfo(int slot, String id, int count, int damage, int maxDamage, boolean enchanted,
			@Nullable String customName,
			/** For potions and tipped arrows: which potion (e.g. "minecraft:strong_healing"); else null. */
			@Nullable String detail) {
	}

	/**
	 * A nearby entity, relative to the player's eyes. {@code visible} is false when it was seen
	 * recently but is now hidden; its position is then where it was last seen.
	 *
	 * <p>Relations: ticks since it hit the player ({@code hitMe}), since the player hit it
	 * ({@code hitByMe}) and since it hit the entity being protected ({@code hitProtected}); -1 = not
	 * in the last 30 seconds.
	 */
	public record EntityInfo(int id, String type, boolean player, @Nullable String name, @Nullable String team,
			double dx, double dy, double dz, double vx, double vy, double vz, float health, float maxHealth,
			String heldItem, int armor, boolean visible, int ticksSinceSeen, int hitMe, int hitByMe, int hitProtected,
			boolean sameTeam, boolean protectedTarget, boolean hostile, String insignia, int relation,
			boolean allyInsignia, boolean enemyInsignia, int myHitsToKill, int theirHitsToKill, float fightOdds,
			boolean blocking, float yaw, float pitch, boolean onGround, boolean swinging, boolean usingItem, boolean sprinting,
			boolean sneaking, int hurtTime) {
	}

	/**
	 * The crowd radar: everyone that didn't fit in the detailed entity list, counted by kind
	 * (players, hostile mobs, others) x distance band (within 8, 24, 64 blocks) x compass sector
	 * (8 x 45 degrees, 0 = south, 2 = west, 4 = north, 6 = east). Index = (kind * 3 + band) * 8 + sector.
	 */
	public record Crowd(int[] counts) {
		public static final int SIZE = 3 * 3 * 8;

		public static int index(int kind, int band, int sector) {
			return (kind * 3 + band) * 8 + sector;
		}
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
	/**
	 * An open screen. {@code slotPos}: every slot of a container screen as {index, x, y, mine}
	 * (centre, in GUI pixels like the cursor; mine = 1 for the player's own inventory slots).
	 */
	public record Gui(String screen, String title, List<ItemInfo> slots, double cursorX, double cursorY, List<int[]> slotPos) {
	}
}
