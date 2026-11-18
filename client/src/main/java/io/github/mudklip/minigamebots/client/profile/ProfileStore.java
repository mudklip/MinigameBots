package io.github.mudklip.minigamebots.client.profile;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * AI profiles on disk: {@code .minecraft/minigamebots/profiles/<id>/}.
 *
 * <pre>
 * manifest.json   name, description, skin, stats, humanizer, foundation
 * skin.png        cached skin, so it shows offline
 * skills/         one folder per skill module (milestone 10)
 * manager/        skill selection network
 * objectives/     reward rules it was trained with
 * history/        training curves and exam results
 * memories/       per-world memory (milestone 9)
 * </pre>
 *
 * Export packs a profile into one shareable {@code .mgbot} file; import unpacks it.
 */
public final class ProfileStore {
	public static final String MANIFEST = "manifest.json";
	public static final String SKIN = "skin.png";
	public static final String EXTENSION = ".mgbot";
	static final List<String> FOLDERS = List.of("skills", "manager", "objectives", "history", "memories");
	/** Biggest file accepted from an imported .mgbot (models can be large). */
	private static final long MAX_IMPORT_FILE = 4L * 1024 * 1024 * 1024;

	private final Path root;

	public ProfileStore(Path root) {
		this.root = root;
	}

	public Path root() {
		return root;
	}

	public Path dir(String id) {
		return root.resolve(id);
	}

	public Path exportsDir() {
		return root.resolve("exports");
	}

	public Path importDir() {
		return root.resolve("import");
	}

	/** All profiles, oldest first (so cards keep their places). */
	public List<ProfileManifest> list() {
		List<ProfileManifest> out = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return out;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
			for (Path dir : dirs) {
				if (Files.isRegularFile(dir.resolve(MANIFEST))) {
					try {
						out.add(read(dir));
					} catch (IOException | RuntimeException e) {
						MinigameBotsClient.LOGGER.warn("Skipping unreadable profile {}", dir, e);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		out.sort(Comparator.comparing((ProfileManifest m) -> m.created).thenComparing(m -> m.id));
		return out;
	}

	public @Nullable ProfileManifest get(String id) {
		if (id == null || id.isEmpty() || !Files.isRegularFile(dir(id).resolve(MANIFEST))) {
			return null;
		}
		try {
			return read(dir(id));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static ProfileManifest read(Path dir) throws IOException {
		ProfileManifest m = RecordingCodec.GSON.fromJson(Files.readString(dir.resolve(MANIFEST)), ProfileManifest.class);
		if (m.skin == null) {
			m.skin = new ProfileManifest.Skin();
		}
		if (m.stats == null) {
			m.stats = new ProfileManifest.Stats();
		}
		if (m.humanizer == null) {
			m.humanizer = ProfileManifest.Humanizer.from(io.github.mudklip.minigamebots.client.control.HumanizerSettings.DEFAULT);
		}
		if (m.parents == null) {
			m.parents = new ArrayList<>();
		}
		return m;
	}

	/** Writes manifest.json atomically. */
	public void save(ProfileManifest m) throws IOException {
		Path dir = dir(m.id);
		Files.createDirectories(dir);
		Path tmp = dir.resolve(MANIFEST + ".tmp");
		Files.writeString(tmp, RecordingCodec.PRETTY.toJson(m), StandardCharsets.UTF_8);
		Files.move(tmp, dir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Makes a new, empty profile. */
	public ProfileManifest create(String name, String modVersion) throws IOException {
		ProfileManifest m = new ProfileManifest();
		m.id = newId(name);
		m.name = name.isBlank() ? "New AI" : name.trim();
		m.created = OffsetDateTime.now().toString();
		m.modVersion = modVersion;
		for (String folder : FOLDERS) {
			Files.createDirectories(dir(m.id).resolve(folder));
		}
		save(m);
		return m;
	}

	/** Copies a profile (everything, brain included) under a new name. */
	public ProfileManifest duplicate(ProfileManifest source, String name) throws IOException {
		String id = newId(name);
		copyTree(dir(source.id), dir(id));
		ProfileManifest m = read(dir(id));
		m.id = id;
		m.name = name;
		m.created = OffsetDateTime.now().toString();
		m.parents = new ArrayList<>(List.of(source.id));
		save(m);
		return m;
	}

	public void delete(String id) throws IOException {
		Path dir = dir(id).normalize();
		if (!dir.startsWith(root.normalize()) || dir.equals(root.normalize()) || !Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	/** Packs a profile into {@code exports/<name>.mgbot}. */
	public Path export(ProfileManifest m, boolean includeMemories) throws IOException {
		Files.createDirectories(exportsDir());
		Path out = exportsDir().resolve(safeName(m.name) + EXTENSION);
		Path dir = dir(m.id);
		try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file);
				Stream<Path> files = Files.walk(dir)) {
			for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
				String rel = dir.relativize(p).toString().replace('\\', '/');
				if (!includeMemories && rel.startsWith("memories/")) {
					continue;
				}
				zip.putNextEntry(new ZipEntry(rel));
				Files.copy(p, zip);
				zip.closeEntry();
			}
		}
		return out;
	}

	/** Imports every .mgbot in {@code import/}, moving each into {@code import/done/}. */
	public int importAll() throws IOException {
		Files.createDirectories(importDir());
		int count = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(importDir(), "*" + EXTENSION)) {
			for (Path file : files) {
				importFile(file);
				Path done = importDir().resolve("done");
				Files.createDirectories(done);
				Files.move(file, done.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				count++;
			}
		}
		return count;
	}

	ProfileManifest importFile(Path file) throws IOException {
		Path tmp = Files.createTempDirectory(root, "import-");
		try {
			try (InputStream raw = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(raw)) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					if (entry.isDirectory()) {
						continue;
					}
					Path target = tmp.resolve(entry.getName()).normalize();
					// Never let an archive write outside its own folder.
					if (!target.startsWith(tmp) || entry.getName().startsWith("/") || entry.getName().contains("..")) {
						throw new IOException("Unsafe path in " + file.getFileName() + ": " + entry.getName());
					}
					Files.createDirectories(target.getParent());
					try (OutputStream out = Files.newOutputStream(target)) {
						copyLimited(zip, out, entry.getName());
					}
				}
			}
			ProfileManifest m = read(tmp);
			m.id = newId(m.name);
			for (String folder : FOLDERS) {
				Files.createDirectories(tmp.resolve(folder));
			}
			Files.move(tmp, dir(m.id));
			save(m);
			return m;
		} catch (IOException | RuntimeException e) {
			deleteQuietly(tmp);
			throw e instanceof IOException io ? io : new IOException("Not a valid " + EXTENSION + " file", e);
		}
	}

	/** Copies but gives up as soon as a file passes the size limit, so a bad archive can't fill the disk. */
	private static void copyLimited(InputStream in, OutputStream out, String name) throws IOException {
		byte[] buffer = new byte[1 << 16];
		long total = 0;
		int n;
		while ((n = in.read(buffer)) > 0) {
			total += n;
			if (total > MAX_IMPORT_FILE) {
				throw new IOException("File too large in archive: " + name);
			}
			out.write(buffer, 0, n);
		}
	}

	private String newId(String name) {
		String base = safeName(name).toLowerCase(Locale.ROOT).replace(' ', '-').replaceAll("[^a-z0-9_-]", "");
		if (base.isEmpty() || base.equals("recording")) {
			base = "ai";
		}
		base = base.substring(0, Math.min(32, base.length()));
		String id = base + "-" + UUID.randomUUID().toString().substring(0, 6);
		while (Files.exists(dir(id))) {
			id = base + "-" + UUID.randomUUID().toString().substring(0, 6);
		}
		return id;
	}

	static String safeName(String name) {
		String cleaned = name.replaceAll("[^A-Za-z0-9 _.-]", "_").trim().replaceAll("^\\.+", "");
		if (cleaned.matches("[_. -]*")) {
			return "ai";
		}
		return cleaned.substring(0, Math.min(64, cleaned.length()));
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path p : files.toList()) {
				Path target = to.resolve(from.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(target);
				} else {
					Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void deleteQuietly(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		} catch (IOException ignored) {
			// Best effort.
		}
	}
}
