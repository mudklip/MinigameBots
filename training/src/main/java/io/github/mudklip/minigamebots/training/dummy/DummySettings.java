package io.github.mudklip.minigamebots.training.dummy;

/** How one training dummy behaves. Presets L1 (punching bag) to L5 (sweaty). */
public final class DummySettings {
	public enum Movement {
		STATIONARY("Stationary"), STRAFE("Strafe"), CIRCLE("Circle"), RANDOM("Random"), RETREAT("Retreat"),
		SWEATY("Sweaty");

		public final String label;

		Movement(String label) {
			this.label = label;
		}
	}

	public enum Defense {
		NONE("No shield"), SHIELD_TIMED("Shield (timed)"), SHIELD_RANDOM("Shield (random)");

		public final String label;

		Defense(String label) {
			this.label = label;
		}
	}

	public enum Armor {
		NONE, LEATHER, IRON, DIAMOND, NETHERITE
	}

	public int level = 1;
	public Movement movement = Movement.STATIONARY;
	public Defense defense = Defense.NONE;
	public Armor armor = Armor.NONE;
	public boolean fightsBack;
	/** Attacks per second when fighting back. */
	public double cps = 2;
	/** Chance that an attack connects (0-1). */
	public double accuracy = 0.5;
	public double reach = 3.0;
	public float damage = 2;
	/** Ticks a player must be in reach before the first attack. */
	public int reactionTicks = 8;
	public boolean jumps;
	/** Heals fully after every hit and never dies (counts damage instead). */
	public boolean infiniteHealth = true;

	public static DummySettings preset(int level) {
		DummySettings s = new DummySettings();
		s.level = Math.max(1, Math.min(5, level));
		switch (s.level) {
			case 1 -> {
				// Punching bag.
			}
			case 2 -> s.movement = Movement.STRAFE;
			case 3 -> {
				s.movement = Movement.STRAFE;
				s.jumps = true;
				s.defense = Defense.SHIELD_RANDOM;
				s.armor = Armor.LEATHER;
				s.fightsBack = true;
				s.cps = 2;
				s.accuracy = 0.5;
				s.reach = 2.5;
				s.damage = 2;
				s.reactionTicks = 10;
			}
			case 4 -> {
				s.movement = Movement.CIRCLE;
				s.jumps = true;
				s.defense = Defense.SHIELD_TIMED;
				s.armor = Armor.IRON;
				s.fightsBack = true;
				s.cps = 4;
				s.accuracy = 0.7;
				s.reach = 3.0;
				s.damage = 3;
				s.reactionTicks = 6;
			}
			default -> {
				s.movement = Movement.SWEATY;
				s.jumps = true;
				s.defense = Defense.SHIELD_TIMED;
				s.armor = Armor.DIAMOND;
				s.fightsBack = true;
				s.cps = 6;
				s.accuracy = 0.85;
				s.reach = 3.0;
				s.damage = 4;
				s.reactionTicks = 4;
				s.infiniteHealth = false;
			}
		}
		return s;
	}

	public DummySettings copy() {
		DummySettings s = new DummySettings();
		s.level = level;
		s.movement = movement;
		s.defense = defense;
		s.armor = armor;
		s.fightsBack = fightsBack;
		s.cps = cps;
		s.accuracy = accuracy;
		s.reach = reach;
		s.damage = damage;
		s.reactionTicks = reactionTicks;
		s.jumps = jumps;
		s.infiniteHealth = infiniteHealth;
		return s;
	}
}
