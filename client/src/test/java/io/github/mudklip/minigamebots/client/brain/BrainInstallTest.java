package io.github.mudklip.minigamebots.client.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.control.AgentAction;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrainInstallTest {
	@TempDir
	Path tmp;

	/** Minimal tar writer for tests. */
	private static final class Tar {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Tar entry(String name, char type, byte[] data, String link, int mode) throws IOException {
			byte[] h = new byte[512];
			put(h, 0, name, 100);
			put(h, 100, String.format("%07o", mode), 8);
			put(h, 124, String.format("%011o", data.length), 12);
			h[156] = (byte) type;
			put(h, 157, link, 100);
			put(h, 257, "ustar", 6);
			for (int i = 148; i < 156; i++) {
				h[i] = ' ';
			}
			int sum = 0;
			for (byte b : h) {
				sum += b & 0xFF;
			}
			put(h, 148, String.format("%06o", sum), 7);
			out.write(h);
			out.write(data);
			out.write(new byte[(512 - data.length % 512) % 512]);
			return this;
		}

		Tar file(String name, String text) throws IOException {
			return entry(name, '0', text.getBytes(StandardCharsets.UTF_8), "", 0755);
		}

		byte[] bytes() throws IOException {
			out.write(new byte[1024]);
			return out.toByteArray();
		}

		static void put(byte[] h, int off, String s, int len) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			System.arraycopy(b, 0, h, off, Math.min(b.length, len));
		}
	}

	@Test
	void extractsFilesFoldersLinksAndLongNames() throws IOException {
		String longName = "python/lib/" + "x".repeat(120) + ".py";
		String pax = "path=" + longName + "\n";
		String record = (pax.length() + 3 + 1) + " " + pax;
		byte[] tar = new Tar()
				.entry("python/", '5', new byte[0], "", 0755)
				.file("python/bin/python3.12", "#!binary")
				.entry("python/bin/python3", '2', new byte[0], "python3.12", 0777)
				.entry("PaxHeader", 'x', record.getBytes(StandardCharsets.UTF_8), "", 0644)
				.file("ignored-short-name", "long file")
				.bytes();
		TarExtractor.extract(new ByteArrayInputStream(tar), tmp);
		assertEquals("#!binary", Files.readString(tmp.resolve("python/bin/python3.12")));
		assertTrue(Files.exists(tmp.resolve("python/bin/python3")));
		assertEquals("long file", Files.readString(tmp.resolve(longName)));
		assertTrue(Files.isExecutable(tmp.resolve("python/bin/python3.12")));
	}

	@Test
	void refusesEntriesOutsideTarget() throws IOException {
		byte[] evil = new Tar().file("../escape.txt", "nope").bytes();
		assertThrows(IOException.class, () -> TarExtractor.extract(new ByteArrayInputStream(evil), tmp.resolve("t")));
		assertFalse(Files.exists(tmp.resolve("escape.txt")));
		byte[] evilLink = new Tar().entry("python/link", '2', new byte[0], "../../etc/passwd", 0777).bytes();
		assertThrows(IOException.class, () -> TarExtractor.extract(new ByteArrayInputStream(evilLink), tmp.resolve("u")));
	}

	@Test
	void picksNewestMatchingPython() {
		String json = """
				{"tag_name": "20261101", "assets": [
				 {"name": "cpython-3.12.9+20261101-x86_64-pc-windows-msvc-install_only.tar.gz", "browser_download_url": "u9", "size": 1},
				 {"name": "cpython-3.12.12+20261101-x86_64-pc-windows-msvc-install_only.tar.gz", "browser_download_url": "u12", "size": 2},
				 {"name": "cpython-3.12.12+20261101-x86_64-pc-windows-msvc-install_only_stripped.tar.gz", "browser_download_url": "s", "size": 3},
				 {"name": "cpython-3.13.5+20261101-x86_64-pc-windows-msvc-install_only.tar.gz", "browser_download_url": "x", "size": 4},
				 {"name": "cpython-3.12.12+20261101-x86_64-unknown-linux-gnu-install_only.tar.gz", "browser_download_url": "l", "size": 5}
				]}""";
		JsonObject release = RecordingCodec.GSON.fromJson(json, JsonObject.class);
		RuntimeInstaller.Asset a = RuntimeInstaller.pickAsset(release, "x86_64-pc-windows-msvc");
		assertNotNull(a);
		assertEquals("u12", a.url());
		assertEquals("l", RuntimeInstaller.pickAsset(release, "x86_64-unknown-linux-gnu").url());
		assertNull(RuntimeInstaller.pickAsset(release, "aarch64-apple-darwin"));
	}

	@Test
	void sha256OfKnownText() throws IOException {
		Path f = tmp.resolve("abc.txt");
		Files.writeString(f, "abc");
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RuntimeInstaller.sha256(f));
	}

	@Test
	void brainActionsAreParsedAndClamped() {
		JsonObject m = RecordingCodec.GSON.fromJson(
				"{\"type\":\"action\",\"tick\":5,\"keys\":[\"forward\",\"fly\",\"attack\"],\"attacks\":99,\"yaw\":720,\"pitch\":\"NaN\"}",
				JsonObject.class);
		AgentAction a = BrainSource.parse(m);
		assertTrue(a.holds(VirtualKey.FORWARD));
		assertTrue(a.holds(VirtualKey.ATTACK));
		assertEquals(5, a.extraAttacks());
		assertEquals(180f, a.yaw());
		assertEquals(0f, a.pitch());
	}

	@Test
	void lateBrainHoldsKeysBrieflyThenLetsGo() {
		BrainSource s = new BrainSource("test");
		s.offer(RecordingCodec.GSON.fromJson("{\"tick\":10,\"keys\":[\"forward\"],\"yaw\":5}", JsonObject.class));
		AgentAction first = s.next(null);
		assertTrue(first.holds(VirtualKey.FORWARD));
		assertEquals(5f, first.yaw());
		assertEquals(10, s.decisionTick(12), "decision time is the tick the brain decided for");
		for (int i = 0; i < BrainSource.HOLD_TICKS_WHEN_LATE; i++) {
			AgentAction held = s.next(null);
			assertTrue(held.holds(VirtualKey.FORWARD));
			assertEquals(0f, held.yaw(), "no repeated turns while waiting");
		}
		assertEquals(AgentAction.NOOP, s.next(null));
		assertTrue(s.lateFraction() > 0);
	}
}
