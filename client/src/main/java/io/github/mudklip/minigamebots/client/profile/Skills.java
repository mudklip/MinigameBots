package io.github.mudklip.minigamebots.client.profile;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An AI's skills: separately trained networks the Manager switches between. "general" is the
 * profile's own {@code core/}; others live in {@code skills/<name>/core/} with the same files.
 */
public final class Skills {
	/** Names the Manager knows what to use for (see brain/minigamebots_brain/policies/manager.py). */
	public static final List<String> COMMON = List.of("general", "combat", "mining", "hunting", "travel", "explore",
			"survival", "flee", "building");

	private Skills() {
	}

	/** The folder trained like a profile: {@code <profile>} for general, else {@code <profile>/skills/<name>}. */
	public static Path dir(Path profileDir, String name) {
		return "general".equals(name) ? profileDir : profileDir.resolve("skills").resolve(name);
	}

	public static boolean trained(Path profileDir, String name) {
		return Files.isRegularFile(dir(profileDir, name).resolve("core/model.json"));
	}

	/** The common names, then any other skill folders the profile has. */
	public static List<String> all(Path profileDir) {
		List<String> out = new ArrayList<>(COMMON);
		Path d = profileDir.resolve("skills");
		if (Files.isDirectory(d)) {
			try (DirectoryStream<Path> dirs = Files.newDirectoryStream(d, Files::isDirectory)) {
				for (Path p : dirs) {
					String n = p.getFileName().toString();
					if (!out.contains(n)) {
						out.add(n);
					}
				}
			} catch (IOException ignored) {
				// Only the common names.
			}
		}
		return out;
	}

	public static List<String> trainedOnes(Path profileDir) {
		return all(profileDir).stream().filter(n -> trained(profileDir, n)).toList();
	}
}
