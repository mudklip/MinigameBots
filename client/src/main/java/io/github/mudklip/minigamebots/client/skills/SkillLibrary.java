package io.github.mudklip.minigamebots.client.skills;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The ready-made skills and the names of everything a skill can use (the brain's rl/skills.json). */
public final class SkillLibrary {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String PATH = "/minigamebots/brain/minigamebots_brain/rl/skills.json";

	public static final class Speed {
		public String label = "";
		public String help = "";
		public double lr = 1;
		public double stay_human = 0.05;
	}

	public static final class Lock {
		public String label = "";
		public List<String> keys = List.of();
	}

	public List<String> categories = List.of();
	public Map<String, Speed> speeds = new LinkedHashMap<>();
	public Map<String, Lock> locks = new LinkedHashMap<>();
	public Map<String, String> setups = new LinkedHashMap<>();
	public Map<String, String> behaviours = new LinkedHashMap<>();
	public Map<String, String> events = new LinkedHashMap<>();
	public Map<String, SkillDef> skills = new LinkedHashMap<>();

	private static @Nullable SkillLibrary instance;

	public static synchronized SkillLibrary get() {
		if (instance == null) {
			SkillLibrary lib = null;
			try (InputStream in = SkillLibrary.class.getResourceAsStream(PATH)) {
				if (in != null) {
					lib = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), SkillLibrary.class);
				}
			} catch (Exception ignored) {
				// Empty library below.
			}
			instance = lib != null ? lib : new SkillLibrary();
		}
		return instance;
	}

	/** Plain-language name of a rule's event or behaviour. */
	public String label(String event) {
		if (event.startsWith("b:")) {
			return behaviours.getOrDefault(event.substring(2), event.substring(2));
		}
		return events.getOrDefault(event, event);
	}

	public @Nullable SkillDef preset(String id) {
		SkillDef d = skills.get(id);
		return d == null ? null : d.copy();
	}
}
