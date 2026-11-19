package io.github.mudklip.minigamebots.client.skills;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * An AI's skills: every ready-made one (as shipped, or as you edited it) plus your own, saved
 * as {@code <profile>/skills/<id>/skill.json}. The stage it reached is in {@code stage.json}.
 */
public final class SkillStore {
	/** A skill as listed. */
	public record Entry(String id, SkillDef def, boolean preset, boolean edited) {
	}

	private SkillStore() {
	}

	public static Path dir(Path profileDir, String id) {
		return profileDir.resolve("skills").resolve(id);
	}

	public static List<Entry> list(Path profileDir) {
		List<Entry> out = new ArrayList<>();
		SkillLibrary lib = SkillLibrary.get();
		for (var e : lib.skills.entrySet()) {
			SkillDef own = read(profileDir, e.getKey());
			out.add(new Entry(e.getKey(), own != null ? own : e.getValue().copy(), true, own != null));
		}
		Path d = profileDir.resolve("skills");
		if (Files.isDirectory(d)) {
			try (DirectoryStream<Path> dirs = Files.newDirectoryStream(d, Files::isDirectory)) {
				List<Entry> custom = new ArrayList<>();
				for (Path p : dirs) {
					String id = p.getFileName().toString();
					SkillDef own = lib.skills.containsKey(id) ? null : read(profileDir, id);
					if (own != null) {
						custom.add(new Entry(id, own, false, true));
					}
				}
				custom.sort(Comparator.comparing(x -> x.def().name.toLowerCase(Locale.ROOT)));
				out.addAll(custom);
			} catch (IOException ignored) {
				// Only the ready-made ones.
			}
		}
		return out;
	}

	public static @Nullable SkillDef read(Path profileDir, String id) {
		Path f = dir(profileDir, id).resolve("skill.json");
		if (!Files.isRegularFile(f)) {
			return null;
		}
		try {
			return SkillLibrary.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), SkillDef.class);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static void save(Path profileDir, String id, SkillDef def) throws IOException {
		Path d = dir(profileDir, id);
		Files.createDirectories(d);
		Files.writeString(d.resolve("skill.json"), SkillLibrary.GSON.toJson(def), StandardCharsets.UTF_8);
	}

	/** Back to the ready-made version (what it learned is kept). */
	public static void reset(Path profileDir, String id) throws IOException {
		Files.deleteIfExists(dir(profileDir, id).resolve("skill.json"));
	}

	/** Removes a custom skill, with what it learned. */
	public static void delete(Path profileDir, String id) throws IOException {
		Path d = dir(profileDir, id);
		if (!Files.isDirectory(d)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(d)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** A free folder name for a new skill. */
	public static String newId(Path profileDir, String name) {
		String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
		if (base.isEmpty()) {
			base = "skill";
		}
		base = "my_" + base;
		String id = base;
		for (int i = 2; Files.exists(dir(profileDir, id)) || SkillLibrary.get().skills.containsKey(id); i++) {
			id = base + "_" + i;
		}
		return id;
	}

	/** Stage reached and tries done in it: {stage, episodes}; {0, 0} if never practised. */
	public static int[] progress(Path profileDir, String id) {
		Path f = dir(profileDir, id).resolve("stage.json");
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			return new int[] {o.get("stage").getAsInt(), o.get("episodes").getAsInt()};
		} catch (Exception e) {
			return new int[] {0, 0};
		}
	}

	/** Which network practising this skill trains: its own folder, or the Manager's for its area. */
	public static String network(String id, SkillDef def) {
		return def.advanced.separate || def.manager == null || def.manager.isBlank() ? id : def.manager;
	}
}
