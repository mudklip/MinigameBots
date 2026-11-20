package io.github.mudklip.minigamebots.client.record;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code meta.json} of a recording. Plain mutable fields so Gson can read and write it and
 * so the Recorder screen can edit name, tags, trim and marks.
 */
public final class RecordingMeta {
	public static final int FORMAT_VERSION = 1;

	public int formatVersion = FORMAT_VERSION;
	public String id = "";
	public String name = "";
	public List<String> tags = new ArrayList<>();
	/** ISO-8601 time the recording started. */
	public String created = "";
	/** {@code human} for your own play, {@code ai} when the AI was in control. */
	public String source = "human";
	/** Singleplayer world name or server address. */
	public String world = "";
	/** Game the recording is from (sidebar title), e.g. for watched players' demos. */
	public String game = "";
	public String minecraftVersion = "";
	public String modVersion = "";
	public int frameSize;
	public int ticksPerChunk;
	/** Number of ticks recorded. */
	public int ticks;
	public int chunks;
	public long sizeBytes;
	/** First and last tick to use for training (inclusive); -1 means not trimmed. */
	public int trimStart = -1;
	public int trimEnd = -1;
	public List<Mark> marks = new ArrayList<>();
	/** True once the recording was stopped cleanly. False means the game closed mid-recording. */
	public boolean complete;

	/** A moment you marked while recording or in the viewer. */
	public static final class Mark {
		public int tick;
		/** {@code good}, {@code mistake}, {@code base}, or a custom label. */
		public String type = "";
		public String note = "";

		public Mark() {
		}

		public Mark(int tick, String type) {
			this.tick = tick;
			this.type = type;
		}
	}

	public int firstUsableTick() {
		return trimStart >= 0 ? trimStart : 0;
	}

	public int lastUsableTick() {
		return trimEnd >= 0 ? Math.min(trimEnd, ticks - 1) : ticks - 1;
	}
}
