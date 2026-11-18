package io.github.mudklip.minigamebots.client.record;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Where recordings live: {@code .minecraft/minigamebots/recordings/<id>/}. Each folder holds
 * {@code meta.json} and numbered chunk files. Export packs a folder into one shareable
 * {@code .mgbrec} file; import unpacks it again.
 */
public final class RecordingStore {
	public static final String META = "meta.json";
	public static final String EXTENSION = ".mgbrec";

	private final Path root;

	public RecordingStore(Path root) {
		this.root = root;
	}

	public Path root() {
		return root;
	}

	public Path exportsDir() {
		return root.resolve("exports");
	}

	public Path importDir() {
		return root.resolve("import");
	}

	public Path dir(String id) {
		return root.resolve(id);
	}

	public static String chunkName(int index) {
		return String.format("chunk-%05d.bin.gz", index);
	}

	/** All recordings, newest first. Broken folders are skipped. */
	public List<RecordingMeta> list() {
		return list(null);
	}

	/**
	 * All recordings, newest first. Recordings cut off by a crash or the game closing are
	 * repaired: their tick count is rebuilt from the chunks actually on disk.
	 *
	 * @param activeId the recording being written right now (never repaired), or null
	 */
	public List<RecordingMeta> list(String activeId) {
		List<RecordingMeta> out = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return out;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
			for (Path dir : dirs) {
				if (Files.isRegularFile(dir.resolve(META))) {
					try {
						RecordingMeta meta = readMeta(dir);
						if (!meta.complete && !meta.id.equals(activeId)) {
							recover(meta);
						}
						out.add(meta);
					} catch (RuntimeException | IOException e) {
						MinigameBotsClient.LOGGER.warn("Skipping unreadable recording {}", dir, e);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		out.sort(Comparator.comparing((RecordingMeta m) -> m.created).reversed());
		return out;
	}

	public static RecordingMeta readMeta(Path dir) throws IOException {
		return RecordingCodec.GSON.fromJson(Files.readString(dir.resolve(META)), RecordingMeta.class);
	}

	/** Rebuilds tick and chunk counts of an unfinished recording from its chunk files. */
	void recover(RecordingMeta meta) throws IOException {
		Path dir = dir(meta.id);
		int chunks = 0;
		int ticks = 0;
		while (Files.exists(dir.resolve(chunkName(chunks)))) {
			List<TickRecord> records = RecordingReader.readChunk(dir.resolve(chunkName(chunks)));
			chunks++;
			if (records.isEmpty()) {
				break;
			}
			ticks = records.get(records.size() - 1).tick() + 1;
		}
		meta.chunks = chunks;
		meta.ticks = ticks;
		meta.sizeBytes = folderSize(dir);
		// Mark it finished so it's only repaired once; the viewer still shows it as recovered.
		meta.complete = true;
		meta.tags.remove("recovered");
		meta.tags.add("recovered");
		saveMeta(meta);
	}

	/** Writes meta.json atomically, so a crash never leaves it half-written. */
	public void saveMeta(RecordingMeta meta) throws IOException {
		Path dir = dir(meta.id);
		Files.createDirectories(dir);
		Path tmp = dir.resolve(META + ".tmp");
		Files.writeString(tmp, RecordingCodec.PRETTY.toJson(meta), StandardCharsets.UTF_8);
		Files.move(tmp, dir.resolve(META), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public void delete(String id) throws IOException {
		Path dir = dir(id);
		if (!dir.normalize().startsWith(root.normalize()) || !Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	public static long folderSize(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
		}
	}

	/** Packs a recording into {@code exports/<name>.mgbrec} and returns the file. */
	public Path export(RecordingMeta meta) throws IOException {
		Files.createDirectories(exportsDir());
		Path out = exportsDir().resolve(safeName(meta.name.isBlank() ? meta.id : meta.name) + EXTENSION);
		try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file);
				DirectoryStream<Path> files = Files.newDirectoryStream(dir(meta.id), Files::isRegularFile)) {
			// Chunks are already compressed; storing them again compressed would only cost time.
			zip.setLevel(1);
			for (Path p : files) {
				zip.putNextEntry(new ZipEntry(p.getFileName().toString()));
				Files.copy(p, zip);
				zip.closeEntry();
			}
		}
		return out;
	}

	/**
	 * Imports every {@code .mgbrec} file in the {@code import/} folder, then moves each one into
	 * {@code import/done/}. Returns how many were imported.
	 */
	public int importAll() throws IOException {
		Path in = importDir();
		Files.createDirectories(in);
		int count = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(in, "*" + EXTENSION)) {
			for (Path file : files) {
				importFile(file);
				Path done = in.resolve("done");
				Files.createDirectories(done);
				Files.move(file, done.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				count++;
			}
		}
		return count;
	}

	private void importFile(Path file) throws IOException {
		Path tmp = Files.createTempDirectory(root, "import-");
		try (InputStream raw = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(raw)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				// Only plain file names: never let an archive write outside its folder.
				String name = Path.of(entry.getName()).getFileName().toString();
				if (entry.isDirectory() || !(name.equals(META) || name.matches("chunk-\\d{5}\\.bin\\.gz"))) {
					continue;
				}
				Files.copy(zip, tmp.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		RecordingMeta meta = readMeta(tmp);
		String id = meta.id.matches("[A-Za-z0-9_-]{1,64}") ? meta.id : "imported";
		String unique = id;
		for (int i = 2; Files.exists(dir(unique)); i++) {
			unique = id + "-" + i;
		}
		meta.id = unique;
		Files.move(tmp, dir(unique));
		saveMeta(meta);
	}

	static String safeName(String name) {
		String cleaned = name.replaceAll("[^A-Za-z0-9 _.-]", "_").trim().replaceAll("^\\.+", "");
		if (cleaned.matches("[_. -]*")) {
			return "recording";
		}
		return cleaned.substring(0, Math.min(64, cleaned.length()));
	}
}
