package io.github.mudklip.minigamebots.client.brain;

import io.github.mudklip.minigamebots.client.observe.Frame;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Shares game frames with the brain through a memory-mapped file, so 20 frames a second never
 * travel over the socket. Layout (little-endian), read by {@code frames.py}:
 *
 * <pre>
 * header (32 bytes): "MGBF", version 1, slots, frameSize, slotBytes, 0, 0, 0
 * slot i at 32 + i * slotBytes: seq (int32), tick (int64), size (int32), RGB pixels
 * </pre>
 *
 * seq is odd while a slot is being written, so the reader never sees half a frame.
 */
public final class FrameRing implements AutoCloseable {
	public static final int SLOTS = 8;
	private static final int HEADER = 32;
	private static final int SLOT_HEADER = 16;

	private final Path path;
	private final int frameSize;
	private final int slotBytes;
	private final RandomAccessFile file;
	private final MappedByteBuffer map;
	private final int[] seq = new int[SLOTS];

	public FrameRing(Path path, int frameSize) throws IOException {
		this.path = path;
		this.frameSize = frameSize;
		this.slotBytes = SLOT_HEADER + frameSize * frameSize * 3;
		long total = HEADER + (long) SLOTS * slotBytes;
		this.file = new RandomAccessFile(path.toFile(), "rw");
		file.setLength(total);
		this.map = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, total);
		map.order(ByteOrder.LITTLE_ENDIAN);
		map.put(0, new byte[] {'M', 'G', 'B', 'F'});
		map.putInt(4, 1);
		map.putInt(8, SLOTS);
		map.putInt(12, frameSize);
		map.putInt(16, slotBytes);
	}

	public Path path() {
		return path;
	}

	public int frameSize() {
		return frameSize;
	}

	/** Writes a frame and returns its slot, or -1 if it doesn't fit (resolution changed). */
	public synchronized int write(Frame frame) {
		if (frame.size() != frameSize) {
			return -1;
		}
		int slot = (int) Math.floorMod(frame.tick(), SLOTS);
		int base = HEADER + slot * slotBytes;
		// Odd while writing, even when done (seq starts at 0).
		map.putInt(base, ++seq[slot]);
		map.putLong(base + 4, frame.tick());
		map.putInt(base + 12, frame.size());
		map.put(base + SLOT_HEADER, frame.rgb());
		map.putInt(base, ++seq[slot]);
		return slot;
	}

	@Override
	public void close() throws IOException {
		file.close();
	}
}
