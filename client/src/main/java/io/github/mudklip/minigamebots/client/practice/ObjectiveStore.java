package io.github.mudklip.minigamebots.client.practice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Ready-made Objectives (templates, read from the brain's templates.json inside the jar) and
 * the profile's own ones in {@code <profile>/objectives/<id>.json}.
 */
public final class ObjectiveStore {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String TEMPLATES = "/minigamebots/brain/minigamebots_brain/rl/templates.json";
	private static @Nullable Map<String, Objective> templates;

	/** Template id -> objective, with parameters still as {placeholders}. */
	public static synchronized Map<String, Objective> templates() {
		if (templates == null) {
			Map<String, Objective> out = new LinkedHashMap<>();
			try (InputStream in = ObjectiveStore.class.getResourceAsStream(TEMPLATES)) {
				if (in != null) {
					Map<String, Objective> read = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
							new TypeToken<LinkedHashMap<String, Objective>>() { }.getType());
					if (read != null) {
						out.putAll(read);
					}
				}
			} catch (IOException | RuntimeException e) {
				// No templates: only the profile's own objectives are offered.
			}
			templates = out;
		}
		return templates;
	}

	/** A template with its parameters filled in (e.g. {target}). */
	public static Objective fill(Objective template, Map<String, String> params) {
		String json = GSON.toJson(template);
		Map<String, Object> defaults = template.params == null ? Map.of() : template.params;
		for (var e : defaults.entrySet()) {
			String v = params.getOrDefault(e.getKey(), String.valueOf(e.getValue()));
			json = json.replace("{" + e.getKey() + "}", v.replace("\"", ""));
		}
		Objective o = GSON.fromJson(json, Objective.class);
		o.params = null;
		return o;
	}

	public static Path dir(Path profileDir) {
		return profileDir.resolve("objectives");
	}

	public static List<String> list(Path profileDir) {
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(dir(profileDir))) {
			return out;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir(profileDir), "*.json")) {
			for (Path f : files) {
				String n = f.getFileName().toString();
				out.add(n.substring(0, n.length() - 5));
			}
		} catch (IOException e) {
			// Unreadable folder: none listed.
		}
		out.sort(String::compareTo);
		return out;
	}

	public static @Nullable Objective load(Path profileDir, String id) {
		try {
			return GSON.fromJson(Files.readString(dir(profileDir).resolve(id + ".json")), Objective.class);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static void save(Path profileDir, String id, Objective o) throws IOException {
		Files.createDirectories(dir(profileDir));
		Path tmp = dir(profileDir).resolve(id + ".json.tmp");
		Files.writeString(tmp, GSON.toJson(o));
		Files.move(tmp, dir(profileDir).resolve(id + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	public static void delete(Path profileDir, String id) throws IOException {
		Files.deleteIfExists(dir(profileDir).resolve(id + ".json"));
	}

	/** A file-name-safe id for a new objective. */
	public static String newId(Path profileDir, String name) {
		String base = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
		if (base.isEmpty()) {
			base = "objective";
		}
		base = base.substring(0, Math.min(32, base.length()));
		String id = base;
		for (int i = 2; Files.exists(dir(profileDir).resolve(id + ".json")); i++) {
			id = base + "-" + i;
		}
		return id;
	}

	public static JsonObject toJson(Objective o) {
		return GSON.toJsonTree(o).getAsJsonObject();
	}
}
