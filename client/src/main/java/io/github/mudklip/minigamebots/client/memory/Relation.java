package io.github.mudklip.minigamebots.client.memory;

/** How the AI should treat someone: remembered per player name or per insignia. */
public enum Relation {
	ENEMY(-1, "Enemy", 0xFFFF6060),
	NEUTRAL(0, "Neutral", 0xFFC0C0C0),
	FRIEND(1, "Friend", 0xFF60E060);

	public final int value;
	public final String label;
	public final int color;

	Relation(int value, String label, int color) {
		this.value = value;
		this.label = label;
		this.color = color;
	}

	public Relation next() {
		return values()[(ordinal() + 1) % values().length];
	}
}
