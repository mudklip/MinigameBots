package io.github.mudklip.minigamebots.client.events;

/** Names of every event the client can detect on its own. */
public final class EventTypes {
	public static final String DAMAGE_TAKEN = "damage_taken";
	/** Something hit the player; data says what (and which player). Exact, from the server's damage event. */
	public static final String HIT_BY = "hit_by";
	public static final String HEALED = "healed";
	public static final String HUNGER_CHANGED = "hunger_changed";
	public static final String ITEM_GAINED = "item_gained";
	public static final String ITEM_LOST = "item_lost";
	public static final String XP_GAINED = "xp_gained";
	public static final String DEATH = "death";
	public static final String RESPAWN = "respawn";
	public static final String BLOCK_BROKEN = "block_broken";
	public static final String BLOCK_PLACED = "block_placed";
	public static final String ATTACK = "attack";
	public static final String DAMAGE_DEALT = "damage_dealt";
	public static final String KILL = "kill";
	public static final String CHAT = "chat";
	public static final String ACTIONBAR = "actionbar";
	public static final String TITLE = "title";
	public static final String SUBTITLE = "subtitle";
	public static final String SCOREBOARD_CHANGED = "scoreboard_changed";
	public static final String SOUND_HEARD = "sound_heard";
	public static final String SCREEN_OPENED = "screen_opened";
	public static final String SCREEN_CLOSED = "screen_closed";
	public static final String DIMENSION_CHANGED = "dimension_changed";

	private EventTypes() {
	}
}
