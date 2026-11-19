package io.github.mudklip.minigamebots.client.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Snapshots of an AI's trained network ({@code core/history/<id>/}), taken automatically before
 * each training or practice session, so any earlier state can be restored in one click. The
 * last {@link #KEEP} are kept, plus pinned ones.
 */
public final class VersionHistory {
	public static final int KEEP = 10;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** The files that make up a trained network (optimizer state is not worth keeping). */
	private static final List<String> FILES = List.of("model.safetensors", "latest.safetensors", "model.json");

	/** What a snapshot was. */
	public static final class Snapshot {
		public String id = "";
		public String date = "";
		public String label = "";
		public long step;
		public String phase = "";
		public boolean pinned;
		/** Latest exam before the snapshot, e.g. "won 3/10 in duel". */
		public String exam = "";
		public transient long bytes;
	}

	private VersionHistory() {
	}

	public static Path dir(Path coreDir) {
		return coreDir.resolve("history");
	}

	/** Saves the current network (if there is one). Returns the snapshot, or null if nothing to save. */
	public static @Nullable Snapshot snapshot(Path coreDir, String label, String exam) throws IOException {
		if (!Files.isRegularFile(coreDir.resolve("model.json"))) {
			return null;
		}
		Snapshot s = new Snapshot();
		LocalDateTime now = LocalDateTime.now();
		s.date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		s.label = label;
		s.exam = exam == null ? "" : exam;
		try {
			JsonObject info = JsonParser.parseString(Files.readString(coreDir.resolve("model.json"))).getAsJsonObject();
			s.step = info.has("step") && !info.get("step").isJsonNull() ? info.get("step").getAsLong() : 0;
			s.phase = info.has("phase") ? info.get("phase").getAsString() : "imitation";
		} catch (RuntimeException e) {
			// Unreadable info: still keep the weights.
		}
		String base = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + s.step;
		Path target = dir(coreDir).resolve(base);
		for (int i = 2; Files.exists(target); i++) {
			target = dir(coreDir).resolve(base + "-" + i);
		}
		s.id = target.getFileName().toString();
		Path tmp = dir(coreDir).resolve(s.id + ".tmp");
		Files.createDirectories(tmp);
		for (String f : FILES) {
			if (Files.isRegularFile(coreDir.resolve(f))) {
				Files.copy(coreDir.resolve(f), tmp.resolve(f), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.writeString(tmp.resolve("snapshot.json"), GSON.toJson(s));
		Files.move(tmp, target);
		prune(coreDir);
		return s;
	}

	/** Snapshot before a session; never fails the session itself. */
	public static void before(Path profileDir, Path coreDir, String label) {
		Backups.background(() -> beforeNow(profileDir, coreDir, label));
	}

	private static void beforeNow(Path profileDir, Path coreDir, String label) {
		try {
			var exams = io.github.mudklip.minigamebots.client.practice.ExamManager.load(profileDir);
			String exam = "";
			if (!exams.isEmpty()) {
				var r = exams.get(exams.size() - 1);
				exam = String.format("won %d/%d in %s", r.wins, r.rounds, r.scenario);
			}
			snapshot(coreDir, label, exam);
		} catch (IOException | RuntimeException e) {
			io.github.mudklip.minigamebots.client.MinigameBotsClient.LOGGER.warn("Couldn't save a version snapshot", e);
		}
	}

	/** Newest first. */
	public static List<Snapshot> list(Path coreDir) {
		List<Snapshot> out = new ArrayList<>();
		Path d = dir(coreDir);
		if (!Files.isDirectory(d)) {
			return out;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(d, Files::isDirectory)) {
			for (Path p : dirs) {
				if (p.getFileName().toString().endsWith(".tmp")) {
					continue;
				}
				try {
					Snapshot s = GSON.fromJson(Files.readString(p.resolve("snapshot.json")), Snapshot.class);
					s.id = p.getFileName().toString();
					try (Stream<Path> files = Files.list(p)) {
						s.bytes = files.mapToLong(f -> f.toFile().length()).sum();
					}
					out.add(s);
				} catch (IOException | RuntimeException e) {
					// Broken snapshot: not listed.
				}
			}
		} catch (IOException e) {
			return out;
		}
		out.sort(Comparator.comparing((Snapshot s) -> s.id).reversed());
		return out;
	}

	/** Puts a snapshot back as the current network; the current one is snapshotted first. */
	public static void revert(Path coreDir, String id) throws IOException {
		Path src = dir(coreDir).resolve(id);
		if (!Files.isRegularFile(src.resolve("model.json"))) {
			throw new IOException("That version is missing its files");
		}
		snapshot(coreDir, "Before going back to " + id, "");
		for (String f : FILES) {
			Path from = src.resolve(f);
			Path to = coreDir.resolve(f);
			if (Files.isRegularFile(from)) {
				Files.copy(from, coreDir.resolve(f + ".tmp"), StandardCopyOption.REPLACE_EXISTING);
				Files.move(coreDir.resolve(f + ".tmp"), to, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.deleteIfExists(to);
			}
		}
		// Old optimizer state belongs to the newer weights.
		Files.deleteIfExists(coreDir.resolve("optimizer.pt"));
	}

	public static void setPinned(Path coreDir, String id, boolean pinned) throws IOException {
		Path f = dir(coreDir).resolve(id).resolve("snapshot.json");
		Snapshot s = GSON.fromJson(Files.readString(f), Snapshot.class);
		s.pinned = pinned;
		Files.writeString(f, GSON.toJson(s));
	}

	public static void delete(Path coreDir, String id) throws IOException {
		Path d = dir(coreDir).resolve(id).normalize();
		if (!d.startsWith(dir(coreDir)) || !Files.isDirectory(d)) {
			return;
		}
		try (Stream<Path> files = Files.walk(d)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	/** Keeps the newest {@link #KEEP} unpinned snapshots. */
	static void prune(Path coreDir) throws IOException {
		int kept = 0;
		for (Snapshot s : list(coreDir)) {
			if (s.pinned) {
				continue;
			}
			if (++kept > KEEP) {
				delete(coreDir, s.id);
			}
		}
	}
}
