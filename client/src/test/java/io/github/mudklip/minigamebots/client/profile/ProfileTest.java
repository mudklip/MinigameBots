package io.github.mudklip.minigamebots.client.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.control.HumanizerSettings;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileTest {
	@TempDir
	Path tmp;

	private ProfileStore store() {
		return new ProfileStore(tmp.resolve("profiles"));
	}

	@Test
	void createSaveListAndDelete() throws IOException {
		ProfileStore store = store();
		ProfileManifest a = store.create("Steve Jr", "0.1.0");
		ProfileManifest b = store.create("PvP God", "0.1.0");
		assertTrue(a.id.startsWith("steve-jr-"));
		for (String folder : ProfileStore.FOLDERS) {
			assertTrue(Files.isDirectory(store.dir(a.id).resolve(folder)), folder);
		}
		a.description = "Learns building";
		a.humanizer.reactionMs = 220;
		store.save(a);
		List<ProfileManifest> all = store.list();
		assertEquals(2, all.size());
		ProfileManifest loaded = store.get(a.id);
		assertEquals("Learns building", loaded.description);
		assertEquals(220, loaded.humanizer.toSettings().reactionMs());
		store.delete(b.id);
		assertEquals(1, store.list().size());
		assertNull(store.get(b.id));
	}

	@Test
	void duplicateCopiesEverythingWithNewIdentity() throws IOException {
		ProfileStore store = store();
		ProfileManifest a = store.create("Builder", "0.1.0");
		Files.writeString(store.dir(a.id).resolve("skills/build.bin"), "weights");
		ProfileManifest copy = store.duplicate(a, "Builder 2");
		assertNotEquals(a.id, copy.id);
		assertEquals("Builder 2", copy.name);
		assertEquals(List.of(a.id), copy.parents);
		assertEquals("weights", Files.readString(store.dir(copy.id).resolve("skills/build.bin")));
	}

	@Test
	void exportImportRoundTripSkipsMemoriesByDefault() throws IOException {
		ProfileStore store = store();
		ProfileManifest a = store.create("Sharer", "0.1.0");
		Files.writeString(store.dir(a.id).resolve("skills/pvp.bin"), "pvp");
		Files.writeString(store.dir(a.id).resolve("memories/world1.json"), "secret base");
		Path out = store.export(a, false);
		assertTrue(out.toString().endsWith(".mgbot"));
		ProfileManifest imported = store.importFile(out);
		assertNotEquals(a.id, imported.id);
		assertEquals("Sharer", imported.name);
		assertEquals("pvp", Files.readString(store.dir(imported.id).resolve("skills/pvp.bin")));
		assertFalse(Files.exists(store.dir(imported.id).resolve("memories/world1.json")));
	}

	@Test
	void importRejectsPathEscapes() throws IOException {
		ProfileStore store = store();
		Files.createDirectories(store.root());
		Path evil = tmp.resolve("evil.mgbot");
		try (OutputStream raw = Files.newOutputStream(evil); ZipOutputStream zip = new ZipOutputStream(raw)) {
			zip.putNextEntry(new ZipEntry(ProfileStore.MANIFEST));
			zip.write("{\"name\":\"x\",\"created\":\"x\"}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("../../outside.txt"));
			zip.write("nope".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		assertThrows(IOException.class, () -> store.importFile(evil));
		assertFalse(Files.exists(tmp.resolve("outside.txt")));
		assertTrue(store.list().isEmpty());
	}

	@Test
	void importRejectsGarbage() throws IOException {
		ProfileStore store = store();
		Files.createDirectories(store.root());
		Path junk = tmp.resolve("junk.mgbot");
		Files.writeString(junk, "not a zip");
		assertThrows(IOException.class, () -> store.importFile(junk));
	}

	@Test
	void damagedHumanizerFallsBackToDefaults() {
		ProfileManifest.Humanizer h = ProfileManifest.Humanizer.from(HumanizerSettings.DEFAULT);
		h.maxCps = 0;
		assertEquals(HumanizerSettings.DEFAULT, h.toSettings());
	}

	private static String texturesProperty(String json) {
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void parsesSkinTexturesAndSlimModel() {
		String value = texturesProperty("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\","
				+ "\"metadata\":{\"model\":\"slim\"}}}}");
		JsonObject profile = RecordingCodec.GSON.fromJson(
				"{\"id\":\"u\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + value + "\"}]}", JsonObject.class);
		SkinFetcher.SkinInfo info = SkinFetcher.parseTextures(profile);
		assertNotNull(info);
		assertEquals("https://textures.minecraft.net/texture/abc123", info.url());
		assertTrue(info.slim());
	}

	@Test
	void refusesSkinsFromOtherHosts() {
		String value = texturesProperty("{\"textures\":{\"SKIN\":{\"url\":\"http://evil.example/texture/x\"}}}");
		JsonObject profile = RecordingCodec.GSON.fromJson(
				"{\"properties\":[{\"name\":\"textures\",\"value\":\"" + value + "\"}]}", JsonObject.class);
		assertNull(SkinFetcher.parseTextures(profile));
	}

	@Test
	void oldSkinsArePaddedAndJunkIsRejected() throws IOException {
		BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
		legacy.setRGB(10, 10, 0xFFFF0000);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(legacy, "png", png);
		Path target = tmp.resolve("skin.png");
		SkinFetcher.saveAs64x64(png.toByteArray(), target);
		BufferedImage saved = ImageIO.read(target.toFile());
		assertEquals(64, saved.getHeight());
		assertEquals(0xFFFF0000, saved.getRGB(10, 10));

		BufferedImage wrong = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream bad = new ByteArrayOutputStream();
		ImageIO.write(wrong, "png", bad);
		assertThrows(IOException.class, () -> SkinFetcher.saveAs64x64(bad.toByteArray(), tmp.resolve("bad.png")));
	}

	@Test
	void solidHatLayerIsCleared() {
		BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 64; x++) {
				skin.setRGB(x, y, 0xFF000000);
			}
		}
		SkinFetcher.clearOpaqueHat(skin);
		assertEquals(0, skin.getRGB(40, 8) >>> 24, "hat cleared");
		assertEquals(0xFF, skin.getRGB(8, 8) >>> 24, "face kept");

		BufferedImage modern = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		modern.setRGB(40, 8, 0xFF00FF00);
		SkinFetcher.clearOpaqueHat(modern);
		assertEquals(0xFF00FF00, modern.getRGB(40, 8), "a real hat with transparency stays");
	}

	@Test
	void baseHeadIsMadeOpaque() {
		BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		skin.setRGB(9, 9, 0x00123456);
		SkinFetcher.makeHeadOpaque(skin);
		assertEquals(0xFF123456, skin.getRGB(9, 9));
		assertEquals(0, skin.getRGB(40, 8) >>> 24, "hat layer left alone");
	}

	@Test
	void usernameRules() {
		assertTrue(SkinFetcher.validName("Notch"));
		assertTrue(SkinFetcher.validName("a_b_1"));
		assertFalse(SkinFetcher.validName("this_name_is_way_too_long"));
		assertFalse(SkinFetcher.validName("bad name"));
		assertFalse(SkinFetcher.validName("../etc"));
		assertEquals(SkinFetcher.Outcome.INVALID_NAME, new SkinFetcher().fetchBlocking("no spaces!", tmp.resolve("s.png")).outcome());
	}
}
