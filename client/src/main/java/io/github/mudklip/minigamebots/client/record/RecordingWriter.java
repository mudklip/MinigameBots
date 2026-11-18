package io.github.mudklip.minigamebots.client.record;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

/**
 * Writes tick records to disk on a background thread, so recording never slows the game down.
 * Chunks are closed every {@code ticksPerChunk} ticks and meta.json is updated each time, so a
 * crash loses at most the last minute.
 */
final class RecordingWriter implements Runnable {
	/** Frames are encoded on this thread too; this many pending ticks is ~25 s of backlog. */
	private static final int MAX_QUEUE = 500;
	private static final Object STOP = new Object();

	private final RecordingStore store;
	private final RecordingMeta meta;
	private final float jpegQuality;
	private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
	private final Thread thread;
	private volatile boolean failed;
	private volatile int dropped;

	private DataOutputStream chunk;
	private int ticksInChunk;
	private byte[] lastJpeg;

	/** A tick waiting to be written; the frame is encoded here, off the client thread. */
	record Pending(TickRecord record, Supplier<byte[]> frame) {
	}

	RecordingWriter(RecordingStore store, RecordingMeta meta, float jpegQuality) {
		this.store = store;
		this.meta = meta;
		this.jpegQuality = jpegQuality;
		this.thread = new Thread(this, "MinigameBots recorder");
		this.thread.setDaemon(true);
	}

	void start() {
		thread.start();
	}

	boolean failed() {
		return failed;
	}

	int dropped() {
		return dropped;
	}

	/** Queues a tick. If the disk can't keep up, ticks are dropped and counted rather than lagging the game. */
	void submit(Pending pending) {
		if (queue.size() >= MAX_QUEUE) {
			dropped++;
			return;
		}
		queue.add(pending);
	}

	/** Finishes writing everything queued and closes the recording. Blocks until done. */
	void finish() {
		queue.add(STOP);
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void run() {
		try {
			while (true) {
				Object next = queue.take();
				if (next == STOP) {
					closeChunk();
					meta.complete = true;
					saveMeta();
					return;
				}
				write((Pending) next);
			}
		} catch (IOException | RuntimeException e) {
			failed = true;
			MinigameBotsClient.LOGGER.error("Recording {} failed", meta.id, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void write(Pending pending) throws IOException {
		if (chunk == null) {
			chunk = new DataOutputStream(new BufferedOutputStream(new java.util.zip.GZIPOutputStream(
					Files.newOutputStream(store.dir(meta.id).resolve(RecordingStore.chunkName(meta.chunks))), 65536)));
			meta.chunks++;
			ticksInChunk = 0;
		}
		TickRecord r = pending.record();
		byte[] jpeg = pending.frame() == null ? null : pending.frame().get();
		if (jpeg != null) {
			lastJpeg = jpeg;
		} else if (ticksInChunk == 0) {
			// Every chunk starts with a full frame so it can be read on its own.
			jpeg = lastJpeg;
		}
		RecordingCodec.write(chunk, new TickRecord(r.tick(), r.input(), r.observation(), r.events(), jpeg, r.frameAge(),
				r.cameraJump(), r.marks()));
		meta.ticks = r.tick() + 1;
		ticksInChunk++;
		if (ticksInChunk >= meta.ticksPerChunk) {
			closeChunk();
			saveMeta();
		}
	}

	private void closeChunk() throws IOException {
		if (chunk != null) {
			chunk.close();
			chunk = null;
		}
	}

	private void saveMeta() throws IOException {
		synchronized (meta) {
			meta.sizeBytes = RecordingStore.folderSize(store.dir(meta.id));
			store.saveMeta(meta);
		}
	}

	float jpegQuality() {
		return jpegQuality;
	}
}
