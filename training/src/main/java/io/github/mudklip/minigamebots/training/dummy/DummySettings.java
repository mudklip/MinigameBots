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
	/** Jumps before attacking and hits while falling, for critical hits (x1.5). */
	public boolean crits;
	/** Sprint-resets after each hit (W-tap): steps back, then hits with extra knockback. */
	public boolean wtap;
	/** Keeps the shield up between its own attacks and lowers it only to swing. */
	public boolean smartShield;
	/** Teacher opponent: allowed to cheat (disables raised shields instantly, never misses). */
	public boolean cheats;
	/** Self-play: steered by its owner's client (a past version of their AI); plays like L3 without it. */
	public boolean puppet;
	/** Name of the style it was made from ("L3", "godly", ...), shown on its label. */
	public String style = "";

	/** Opponent styles for matches and the league: levels plus named fighting styles. */
	public static final java.util.List<String> STYLES = java.util.List.of("L1", "L2", "L3", "L4", "L5", "rusher",
			"blocker", "critter", "w_tapper", "kiter", "godly", "self");

	/** Settings for a style from {@link #STYLES} ("L1".."L5" or a name); unknown names give L3. */
	public static DummySettings style(String name) {
		String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
		DummySettings s;
		if (n.matches("l[1-5]")) {
			s = preset(n.charAt(1) - '0');
		} else {
			s = preset(4);
			s.infiniteHealth = false;
			switch (n) {
				case "rusher" -> {
					s.movement = Movement.SWEATY;
					s.defense = Defense.NONE;
					s.cps = 8;
					s.accuracy = 0.6;
					s.reactionTicks = 3;
				}
				case "blocker" -> {
					s.movement = Movement.STRAFE;
					s.smartShield = true;
					s.cps = 2;
					s.accuracy = 0.8;
					s.armor = Armor.DIAMOND;
				}
				case "critter" -> {
					s.movement = Movement.CIRCLE;
					s.crits = true;
					s.cps = 1.6;
					s.accuracy = 0.8;
				}
				case "w_tapper" -> {
					s.movement = Movement.SWEATY;
					s.wtap = true;
					s.cps = 2.5;
					s.accuracy = 0.8;
				}
				case "kiter" -> {
					s.movement = Movement.RETREAT;
					s.cps = 3;
					s.accuracy = 0.75;
					s.reach = 3.0;
				}
				case "self" -> {
					s = preset(3);
					s.infiniteHealth = false;
					s.puppet = true;
				}
				case "godly" -> {
					// A teacher: perfect timing and every technique, and it may cheat.
					s.movement = Movement.SWEATY;
					s.defense = Defense.NONE;
					s.smartShield = true;
					s.crits = true;
					s.wtap = true;
					s.cheats = true;
					s.armor = Armor.NETHERITE;
					s.cps = 1.6;
					s.accuracy = 1.0;
					s.reach = 3.4;
					s.damage = 5;
					s.reactionTicks = 1;
				}
				default -> {
					s = preset(3);
					n = "l3";
				}
			}
		}
		s.style = n.toUpperCase(java.util.Locale.ROOT).startsWith("L") && n.length() == 2 ? n.toUpperCase(java.util.Locale.ROOT) : n;
		return s;
	}

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
		s.crits = crits;
		s.wtap = wtap;
		s.smartShield = smartShield;
		s.cheats = cheats;
		s.style = style;
		s.puppet = puppet;
		return s;
	}
}
