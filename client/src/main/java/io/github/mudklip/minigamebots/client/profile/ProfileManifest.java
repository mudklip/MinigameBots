package io.github.mudklip.minigamebots.client.profile;

import io.github.mudklip.minigamebots.client.control.HumanizerSettings;

/**
 * The {@code manifest.json} of an AI profile. Plain mutable fields so Gson can read and write it
 * and the editor can change it.
 */
public final class ProfileManifest {
	public static final int FORMAT_VERSION = 1;

	public int formatVersion = FORMAT_VERSION;
	public String id = "";
	public String name = "";
	public String description = "";
	/** ISO-8601 creation time. */
	public String created = "";
	public String modVersion = "";
	/** Pretrained base the AI's skills are built on; null until it has a brain (milestone 6). */
	public String foundation;
	public Skin skin = new Skin();
	public Stats stats = new Stats();
	public Humanizer humanizer = Humanizer.from(HumanizerSettings.DEFAULT);
	/** Attitude when it plays on its own: neutral, hunter, guard, peaceful, or personality (from its character). */
	public String attitude = "neutral";

	/** Chat in character (optional, needs the mind). */
	public Character character = new Character();

	public static final class Character {
		/** Talks in chat when spoken to, while it plays. */
		public boolean chat;
		public String name = "";
		public String personality = "";
		public String style = "";
		public String background = "";
		/** Comma-separated topics it must never talk about. */
		public String avoid = "";
	}

	/** Ids of the profiles this one was duplicated or merged from. */
	public java.util.List<String> parents = new java.util.ArrayList<>();

	/** Whose skin the profile shows. The skin image itself is cached in the profile as skin.png. */
	public static final class Skin {
		/** Minecraft username typed by you; empty = default skin. */
		public String username = "";
		/** Mojang UUID found for it, once looked up. */
		public String uuid = "";
		/** True for the slim (Alex) arm model. */
		public boolean slim;
		/** ISO-8601 time the skin was last downloaded. */
		public String fetched = "";
	}

	public static final class Stats {
		public double trainingHours;
		public long decisions;
		public int skills;
		public int exams;
	}

	/** This AI's own human limits; see {@link HumanizerSettings}. */
	public static final class Humanizer {
		public boolean enabled = true;
		public int reactionMs;
		public int reactionJitterMs;
		public float maxTurnSpeed;
		public float maxTurnAccel;
		public float aimNoise;
		public int maxCps;

		public static Humanizer from(HumanizerSettings s) {
			Humanizer h = new Humanizer();
			h.enabled = s.enabled();
			h.reactionMs = s.reactionMs();
			h.reactionJitterMs = s.reactionJitterMs();
			h.maxTurnSpeed = s.maxTurnSpeed();
			h.maxTurnAccel = s.maxTurnAccel();
			h.aimNoise = s.aimNoise();
			h.maxCps = s.maxCps();
			return h;
		}

		public HumanizerSettings toSettings() {
			if (!enabled) {
				return HumanizerSettings.OFF;
			}
			if (reactionMs == 180 && reactionJitterMs == 35 && maxTurnSpeed == 1000f && maxTurnAccel == 15000f
					&& Math.abs(aimNoise - 0.12f) < 1e-4 && maxCps == 12) {
				return HumanizerSettings.DEFAULT; // Never customised: use the new top-level default.
			}
			try {
				return new HumanizerSettings(true, reactionMs, reactionJitterMs, maxTurnSpeed, maxTurnAccel, aimNoise, maxCps);
			} catch (IllegalArgumentException e) {
				// A hand-edited or damaged file: fall back to safe defaults rather than crash.
				return HumanizerSettings.DEFAULT;
			}
		}
	}
}
