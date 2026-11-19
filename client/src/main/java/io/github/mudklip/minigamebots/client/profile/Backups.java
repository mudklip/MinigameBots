package io.github.mudklip.minigamebots.client.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Whole-AI backups ({@code <profile>/backups/<id>/}): every network, skill settings and stages,
 * levels, memory, objectives and the profile itself. Restore all of it, or only some parts
 * (e.g. just the combat network). Made by hand, and automatically before coaching or training
 * (at most every few hours; the last {@link #KEEP_AUTO} automatic ones are kept).
 */
public final class Backups {
	public static final int KEEP_AUTO = 5;
	private static final long AUTO_EVERY_MS = 3 * 3600_000L;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Info {
		public String id = "";
		public String date = "";
		public String label = "";
		public boolean auto;
		public boolean pinned;
		public long created;
		public String modVersion = "";
		/** Part -> bytes. */
		public Map<String, Long> parts = new LinkedHashMap<>();
		public transient long bytes;
	}

	private Backups() {
	}

	/** This mod's version, stored with each backup. */
	public static String modVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minigamebots")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	public static void auto(Path profileDir, String label) {
		auto(profileDir, label, modVersion());
	}

	public static Path root(Path profileDir) {
		return profileDir.resolve("backups");
	}

	/** Which part a file (relative to the profile) belongs to, or null if it isn't backed up. */
	public static @Nullable String partOf(String rel) {
		String r = rel.replace('\\', '/');
		if (r.startsWith("backups/") || r.contains("/history/") || r.startsWith("history/") || r.endsWith("optimizer.pt")) {
			return null;
		}
		if (r.startsWith("core/")) {
			return "network:general";
		}
		if (r.startsWith("skills/")) {
			String[] p = r.split("/");
			if (p.length >= 4 && p[2].equals("core")) {
				return "network:" + p[1];
			}
			return p.length >= 3 ? "skill:" + p[1] : "skills";
		}
		if (r.equals("progress.json") || r.equals("exams.json")) {
			return "progress";
		}
		if (r.startsWith("memories/")) {
			return "memory";
		}
		if (r.startsWith("objectives/")) {
			return "objectives";
		}
		return "profile";
	}

	/** A friendly name for a part. */
	public static String label(String part) {
		if (part.startsWith("network:")) {
			String n = part.substring(8);
			if (n.equals("general")) {
				return "Main network (what it learned)";
			}
			var lib = io.github.mudklip.minigamebots.client.skills.SkillLibrary.get();
			if (lib.skills.containsKey(n)) {
				return "Learned: " + lib.skills.get(n).name;
			}
			return "Learned: " + n + " (shared)";
		}
		if (part.startsWith("skill:")) {
			String n = part.substring(6);
			var d = io.github.mudklip.minigamebots.client.skills.SkillLibrary.get().skills.get(n);
			return "Skill: " + (d != null ? d.name : n.startsWith("my_") ? n.substring(3).replace('_', ' ') : n);
		}
		return switch (part) {
			case "skills" -> "Skill settings and stages";
			case "progress" -> "Levels, ratings and exams";
			case "memory" -> "Memory (places, allies, enemies)";
			case "objectives" -> "Your objectives";
			case "profile" -> "Name, skin, character, Manager";
			default -> part;
		};
	}

	private static List<Path> files(Path profileDir) throws IOException {
		if (!Files.isDirectory(profileDir)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(profileDir)) {
			return walk.filter(Files::isRegularFile).filter(f -> partOf(profileDir.relativize(f).toString()) != null).toList();
		}
	}

	/** Backs up everything now. */
	public static Info create(Path profileDir, String label, boolean auto, String modVersion) throws IOException {
		Info info = new Info();
		LocalDateTime now = LocalDateTime.now();
		info.created = System.currentTimeMillis();
		info.date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		info.id = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + (auto ? "-auto" : "");
		info.label = label;
		info.auto = auto;
		info.modVersion = modVersion;
		Path dir = root(profileDir).resolve(info.id);
		Path data = dir.resolve("data");
		for (Path f : files(profileDir)) {
			String rel = profileDir.relativize(f).toString();
			Path to = data.resolve(rel);
			Files.createDirectories(to.getParent());
			Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING);
			info.parts.merge(partOf(rel), Files.size(f), Long::sum);
		}
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("backup.json"), GSON.toJson(info), StandardCharsets.UTF_8);
		if (auto) {
			prune(profileDir);
		}
		return info;
	}

	/** An automatic backup, unless there's a recent one already. Never throws. */
	public static void auto(Path profileDir, String label, String modVersion) {
		try {
			List<Info> all = list(profileDir);
			if (!all.isEmpty() && System.currentTimeMillis() - all.get(0).created < AUTO_EVERY_MS) {
				return;
			}
			if (!Files.isRegularFile(profileDir.resolve("core/model.json")) && !Files.isDirectory(profileDir.resolve("skills"))) {
				return; // Nothing learned yet.
			}
			create(profileDir, label, true, modVersion);
		} catch (IOException ignored) {
			// A missed automatic backup isn't worth stopping training for.
		}
	}

	public static List<Info> list(Path profileDir) {
		List<Info> out = new ArrayList<>();
		Path root = root(profileDir);
		if (!Files.isDirectory(root)) {
			return out;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
			for (Path d : dirs) {
				try {
					Info i = GSON.fromJson(Files.readString(d.resolve("backup.json"), StandardCharsets.UTF_8), Info.class);
					i.bytes = i.parts.values().stream().mapToLong(Long::longValue).sum();
					out.add(i);
				} catch (IOException | RuntimeException ignored) {
					// Unfinished or damaged: not listed.
				}
			}
		} catch (IOException ignored) {
			// None listed.
		}
		out.sort(Comparator.comparingLong((Info i) -> i.created).reversed());
		return out;
	}

	/** When the AI last learned something (newest network file), 0 if never. */
	public static long lastLearned(Path profileDir) {
		long t = 0;
		try {
			for (Path f : files(profileDir)) {
				String part = partOf(profileDir.relativize(f).toString());
				if (part != null && part.startsWith("network:")) {
					t = Math.max(t, Files.getLastModifiedTime(f).toMillis());
				}
			}
		} catch (IOException ignored) {
			// Unknown.
		}
		return t;
	}

	/** Puts the chosen parts back as they were in the backup (the rest stays as it is now). */
	public static void restore(Path profileDir, String id, List<String> parts) throws IOException {
		Path data = root(profileDir).resolve(id).resolve("data");
		if (!Files.isDirectory(data)) {
			throw new IOException("that backup is missing");
		}
		// Remove the current files of those parts (a network's optimizer state too: it belongs to the newer one).
		for (Path f : files(profileDir)) {
			String part = partOf(profileDir.relativize(f).toString());
			if (parts.contains(part)) {
				Files.deleteIfExists(f);
			}
		}
		for (String p : parts) {
			if (p.startsWith("network:")) {
				Path core = Skills.dir(profileDir, p.substring(8)).resolve("core");
				Files.deleteIfExists(core.resolve("optimizer.pt"));
			}
		}
		try (Stream<Path> walk = Files.walk(data)) {
			for (Path f : walk.filter(Files::isRegularFile).toList()) {
				String rel = data.relativize(f).toString();
				if (parts.contains(partOf(rel))) {
					Path to = profileDir.resolve(rel);
					Files.createDirectories(to.getParent());
					Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	public static void setPinned(Path profileDir, String id, boolean pinned) throws IOException {
		Path f = root(profileDir).resolve(id).resolve("backup.json");
		Info i = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Info.class);
		i.pinned = pinned;
		Files.writeString(f, GSON.toJson(i), StandardCharsets.UTF_8);
	}

	public static void delete(Path profileDir, String id) throws IOException {
		Path d = root(profileDir).resolve(id);
		if (!Files.isDirectory(d)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(d)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static void prune(Path profileDir) throws IOException {
		List<Info> autos = list(profileDir).stream().filter(i -> i.auto && !i.pinned).toList();
		for (int k = KEEP_AUTO; k < autos.size(); k++) {
			delete(profileDir, autos.get(k).id);
		}
	}
}
