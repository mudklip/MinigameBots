package io.github.mudklip.minigamebots.client.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Memory like a person's (docs/MIND_DESIGN.md section 2), kept per world inside memory.json:
 * remembered moments (episodes) with a feeling, person profiles boiled down from them, and how
 * far each insignia can be trusted. Small records: they cost nothing until something brings them
 * up (seeing that person, that insignia, that place).
 */
public final class Mind {
	/** A remembered moment. */
	public static final class Episode {
		public long time;
		public String who = "";
		public String insignia = "";
		/** attacked_me, betrayed_me, knocked_me_out, i_knocked_out, joke_hit. */
		public String what = "";
		public String weapon = "";
		public String place = "";
		public String dimension = "";
		public int x;
		public int y;
		public int z;
		/** -1 (very bad) .. 1 (very good). */
		public double feeling;
		/** 0..1: how strongly it was felt; strong memories last longer. */
		public double strength;
	}

	/** What it knows about one player. */
	public static final class Person {
		public int seen;
		public long lastSeen;
		public String lastPlace = "";
		public int realAttacks;
		public int jokeHits;
		public int knockedMeOut;
		public int iKnockedOut;
		public int betrayals;
		public Map<String, Integer> weapons = new LinkedHashMap<>();

		/** 0..1: how dangerous, from what it did to the AI. */
		public double danger() {
			double d = 0.12 * realAttacks + 0.35 * knockedMeOut + 0.3 * betrayals - 0.15 * iKnockedOut;
			return Math.max(0, Math.min(1, d));
		}

		/** -1..1: how much the AI likes them. */
		public double like() {
			double l = -0.5 * betrayals - 0.1 * realAttacks - 0.2 * knockedMeOut + 0.02 * Math.min(seen, 50) * (realAttacks == 0 ? 1 : 0);
			return Math.max(-1, Math.min(1, l));
		}

		public String usualWeapon() {
			return weapons.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
		}
	}

	/** How far an insignia can be trusted (only matters for friendly ones). */
	public static final class Trust {
		public double trust = 1.0;
		public int betrayals;
		public Set<String> betrayers = new HashSet<>();
		public long lastIncident;

		/** Trust now, recovering slowly (+0.05 a day) since the last betrayal. */
		public double now(long nowMs) {
			double days = lastIncident == 0 ? 0 : (nowMs - lastIncident) / 86_400_000.0;
			return Math.min(1.0, trust + 0.05 * days);
		}

		public String level(long nowMs) {
			double t = now(nowMs);
			return t >= 0.4 ? "trusted" : t >= 0.15 ? "careful" : "distrusted";
		}
	}

	/** Feelings about places (16 x 16 areas): "knocked out here twice" -> avoid. */
	public static final class Place {
		public int bad;
		public int good;
		public long last;

		/** -1 (avoid) .. 1 (good place); fades with time (a risk zone cools down after a few days). */
		public double feeling() {
			double days = last == 0 ? 0 : (System.currentTimeMillis() - last) / 86_400_000.0;
			double fade = Math.pow(0.5, days / 3);
			// One bad moment is just a memory; repeated ones make a risk zone.
			double badness = bad >= 2 ? 0.5 * bad : 0.15 * bad;
			return Math.max(-1, Math.min(1, (0.4 * good - badness) * fade));
		}
	}

	public Map<String, Place> places = new LinkedHashMap<>();

	// --- How dangerous this server is, overall ---
	/** Attacks it didn't agree to (not duels, matches or games). */
	public int unprovokedAttacks;
	/** Seconds spent with other players nearby, times how many (player-seconds of exposure). */
	public double playerSeconds;
	public int agreedFights;

	/** Unprovoked attacks per hour of having one player nearby. */
	public double attackRate() {
		return unprovokedAttacks / Math.max(0.5, playerSeconds / 3600.0);
	}

	/**
	 * When attacks happen all over (a PvP-heavy server), single places stop meaning much: risk then
	 * comes from how many players are around instead.
	 */
	public boolean dangerEverywhere() {
		long risky = places.values().stream().filter(pl -> pl.bad >= 1).count();
		return unprovokedAttacks >= 6 && risky >= 4 && risky >= unprovokedAttacks * 0.4;
	}

	public static String placeKey(String dimension, int x, int z) {
		return dimension + ":" + Math.floorDiv(x, 16) + ":" + Math.floorDiv(z, 16);
	}

	public List<Episode> episodes = new ArrayList<>();
	public Map<String, Person> people = new LinkedHashMap<>();
	public Map<String, Trust> trust = new LinkedHashMap<>();

	public static final int MAX_EPISODES = 1000;

	/** Fades old, weak memories (forgetting curve): strong ones last weeks, trivial ones days. */
	public void forget(long nowMs) {
		episodes.removeIf(e -> {
			double days = (nowMs - e.time) / 86_400_000.0;
			double halfLife = 1 + 13 * e.strength;
			return e.strength * Math.pow(0.5, days / halfLife) < 0.05;
		});
		while (episodes.size() > MAX_EPISODES) {
			episodes.remove(0);
		}
	}
}
