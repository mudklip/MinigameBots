package io.github.mudklip.minigamebots.client.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mudklip.minigamebots.client.control.GameInput;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingFormatTest {
	@TempDir
	Path tmp;

	private static byte[] frame(int size, int shade) {
		byte[] rgb = new byte[size * size * 3];
		for (int i = 0; i < rgb.length; i += 3) {
			rgb[i] = (byte) shade;
			rgb[i + 1] = (byte) (i % 256);
			rgb[i + 2] = 40;
		}
		return RecordingCodec.jpeg(size, rgb, 0.9f);
	}

	private static TickRecord record(int tick, byte[] jpeg) {
		GameInput input = new GameInput(Set.of(VirtualKey.FORWARD, VirtualKey.HOTBAR_3), 2, 0, 1.5f, -0.25f);
		return new TickRecord(tick, input, null,
				List.of(GameEvent.client(tick, "kill", Map.of("target", "minecraft:pig"))), jpeg, 0, tick == 1,
				tick == 2 ? List.of("good") : List.of());
	}

	private Path writeChunk(TickRecord... records) throws IOException {
		Path file = tmp.resolve(RecordingStore.chunkName(0));
		try (OutputStream raw = Files.newOutputStream(file);
				DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
			for (TickRecord r : records) {
				RecordingCodec.write(out, r);
			}
		}
		return file;
	}

	@Test
	void roundTripKeepsEverything() throws IOException {
		byte[] a = frame(64, 10);
		byte[] b = frame(64, 200);
		Path file = writeChunk(record(0, a), record(1, null), record(2, b));
		List<TickRecord> read = RecordingReader.readChunk(file);
		assertEquals(3, read.size());
		TickRecord first = read.get(0);
		assertTrue(first.input().holds(VirtualKey.FORWARD));
		assertTrue(first.input().holds(VirtualKey.HOTBAR_3));
		assertEquals(2, first.input().attackClicks());
		assertEquals(1.5f, first.input().yaw());
		assertEquals(-0.25f, first.input().pitch());
		assertEquals("kill", first.events().get(0).type());
		assertEquals("minecraft:pig", first.events().get(0).data().get("target"));
		assertArrayEquals(a, first.frameJpeg());
		// "Same frame as before" is resolved to the previous frame on read.
		assertArrayEquals(a, read.get(1).frameJpeg());
		assertTrue(read.get(1).cameraJump());
		assertArrayEquals(b, read.get(2).frameJpeg());
		assertEquals(List.of("good"), read.get(2).marks());
	}

	@Test
	void jpegDecodesToTheRightSize() {
		BufferedImage image = RecordingCodec.decode(frame(128, 90));
		assertEquals(128, image.getWidth());
		assertEquals(128, image.getHeight());
	}

	@Test
	void truncatedChunkKeepsCompleteRecords() throws IOException {
		Path file = writeChunk(record(0, frame(64, 1)), record(1, null), record(2, null));
		byte[] bytes = Files.readAllBytes(file);
		// Simulate the game closing mid-write: cut the file short.
		Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 40));
		List<TickRecord> read = RecordingReader.readChunk(file);
		assertTrue(read.size() >= 1 && read.size() < 3, "read " + read.size());
	}

	@Test
	void exportAndImportRoundTrip() throws IOException {
		RecordingStore store = new RecordingStore(tmp.resolve("recordings"));
		RecordingMeta meta = new RecordingMeta();
		meta.id = "20261118-120000";
		meta.name = "PvP practice";
		meta.tags.add("pvp");
		meta.ticks = 3;
		meta.ticksPerChunk = 1200;
		meta.chunks = 1;
		meta.created = "2026-11-18T12:00:00Z";
		meta.complete = true;
		store.saveMeta(meta);
		try (OutputStream raw = Files.newOutputStream(store.dir(meta.id).resolve(RecordingStore.chunkName(0)));
				DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
			RecordingCodec.write(out, record(0, frame(64, 5)));
		}

		Path exported = store.export(meta);
		assertTrue(exported.getFileName().toString().endsWith(".mgbrec"));
		Files.createDirectories(store.importDir());
		Files.copy(exported, store.importDir().resolve("friend.mgbrec"));
		assertEquals(1, store.importAll());

		List<RecordingMeta> all = store.list();
		assertEquals(2, all.size());
		RecordingMeta imported = all.stream().filter(m -> !m.id.equals(meta.id)).findFirst().orElseThrow();
		assertEquals("PvP practice", imported.name);
		assertEquals(List.of("pvp"), imported.tags);
		RecordingReader reader = new RecordingReader(store.dir(imported.id), imported);
		assertNotNull(reader.get(0));
	}

	@Test
	void importIgnoresUnexpectedFiles() throws IOException {
		RecordingStore store = new RecordingStore(tmp.resolve("recordings"));
		Files.createDirectories(store.importDir());
		Path evil = store.importDir().resolve("evil.mgbrec");
		RecordingMeta meta = new RecordingMeta();
		meta.id = "../../escape";
		meta.created = "x";
		try (OutputStream raw = Files.newOutputStream(evil);
				java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raw)) {
			zip.putNextEntry(new java.util.zip.ZipEntry("../../outside.txt"));
			zip.write("nope".getBytes());
			zip.closeEntry();
			zip.putNextEntry(new java.util.zip.ZipEntry(RecordingStore.META));
			zip.write(RecordingCodec.GSON.toJson(meta).getBytes());
			zip.closeEntry();
		}
		assertEquals(1, store.importAll());
		assertTrue(Files.notExists(tmp.resolve("outside.txt")));
		assertTrue(Files.notExists(tmp.resolve("escape")));
		assertEquals("imported", store.list().get(0).id);
	}

	@Test
	void unfinishedRecordingIsRecovered() throws IOException {
		RecordingStore store = new RecordingStore(tmp.resolve("recordings"));
		RecordingMeta meta = new RecordingMeta();
		meta.id = "crashed";
		meta.created = "2026-11-18T12:00:00Z";
		meta.ticksPerChunk = 2;
		store.saveMeta(meta);
		for (int c = 0; c < 2; c++) {
			try (OutputStream raw = Files.newOutputStream(store.dir(meta.id).resolve(RecordingStore.chunkName(c)));
					DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
				RecordingCodec.write(out, record(c * 2, frame(32, 1)));
				if (c == 0) {
					RecordingCodec.write(out, record(1, null));
				}
			}
		}
		RecordingMeta recovered = store.list().get(0);
		assertEquals(3, recovered.ticks);
		assertEquals(2, recovered.chunks);
		assertTrue(recovered.tags.contains("recovered"));
		// The recording being written right now is left alone.
		RecordingMeta live = new RecordingMeta();
		live.id = "live";
		live.created = "2026-11-18T13:00:00Z";
		store.saveMeta(live);
		assertEquals(0, store.list("live").get(0).ticks);
	}

	@Test
	void safeNamesStripPathCharacters() {
		assertEquals("a_b_c", RecordingStore.safeName("a/b\\c"));
		assertEquals("recording", RecordingStore.safeName("///"));
		assertEquals("recording", RecordingStore.safeName(".."));
		assertEquals("hidden", RecordingStore.safeName(".hidden"));
	}
}
