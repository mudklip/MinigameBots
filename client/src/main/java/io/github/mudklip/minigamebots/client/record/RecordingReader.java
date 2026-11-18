package io.github.mudklip.minigamebots.client.record;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/** Random access to a recording's ticks, one chunk in memory at a time. */
public final class RecordingReader {
	private final Path dir;
	private final RecordingMeta meta;
	private int loadedChunk = -1;
	private List<TickRecord> chunk = List.of();

	public RecordingReader(Path dir, RecordingMeta meta) {
		this.dir = dir;
		this.meta = meta;
	}

	public RecordingMeta meta() {
		return meta;
	}

	/** The record for {@code tick}, or null if it isn't on disk (e.g. the game closed while recording). */
	public @Nullable TickRecord get(int tick) throws IOException {
		if (tick < 0 || tick >= meta.ticks || meta.ticksPerChunk <= 0) {
			return null;
		}
		int index = tick / meta.ticksPerChunk;
		if (index != loadedChunk) {
			chunk = readChunk(dir.resolve(RecordingStore.chunkName(index)));
			loadedChunk = index;
		}
		int offset = tick - index * meta.ticksPerChunk;
		return offset < chunk.size() ? chunk.get(offset) : null;
	}

	/** Reads a whole chunk. A chunk cut short by a crash returns the records that are intact. */
	public static List<TickRecord> readChunk(Path file) throws IOException {
		List<TickRecord> records = new ArrayList<>();
		byte[][] lastFrame = new byte[1][];
		try (InputStream raw = Files.newInputStream(file);
				DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw), 65536))) {
			TickRecord r;
			while ((r = RecordingCodec.read(in, lastFrame)) != null) {
				records.add(r);
			}
		} catch (NoSuchFileException e) {
			return records;
		} catch (java.io.EOFException e) {
			// Truncated by a crash: keep what was readable.
		}
		return records;
	}
}
