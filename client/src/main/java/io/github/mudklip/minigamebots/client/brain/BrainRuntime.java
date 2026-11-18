package io.github.mudklip.minigamebots.client.brain;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Where the brain lives on disk, under {@code .minecraft/minigamebots/brain/}:
 *
 * <pre>
 * runtime/python/    a standalone Python downloaded by the Setup screen (unless you use your own)
 * runtime/venv/      the brain's own Python environment with numpy and PyTorch
 * runtime/state.json what was installed, and the last self-test
 * code/              the brain's Python code, unpacked from the mod jar
 * run/frames.bin     shared memory for game frames
 * </pre>
 */
public final class BrainRuntime {
	/** Packaged by the build: list of the brain's files inside the jar. */
	private static final String RESOURCE_ROOT = "/minigamebots/brain/";
	private static final String INDEX = RESOURCE_ROOT + "files.txt";

	private final Path root;
	private final Path logs;

	public BrainRuntime(Path minigamebotsDir) {
		this.root = minigamebotsDir.resolve("brain");
		this.logs = minigamebotsDir.resolve("logs");
	}

	public Path root() {
		return root;
	}

	public Path runtimeDir() {
		return root.resolve("runtime");
	}

	public Path standalonePython() {
		return runtimeDir().resolve("python");
	}

	public Path venv() {
		return runtimeDir().resolve("venv");
	}

	public Path stateFile() {
		return runtimeDir().resolve("state.json");
	}

	public Path codeDir() {
		return root.resolve("code");
	}

	public Path runDir() {
		return root.resolve("run");
	}

	public Path logsDir() {
		return logs;
	}

	public static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** The brain's Python: inside its own environment. */
	public Path venvPython() {
		return windows() ? venv().resolve("Scripts/python.exe") : venv().resolve("bin/python");
	}

	/** Python of a standalone install (python-build-standalone "install_only" layout). */
	public Path standaloneExecutable() {
		return windows() ? standalonePython().resolve("python.exe") : standalonePython().resolve("bin/python3");
	}

	/** True once setup finished successfully (it writes state.json as its very last step). */
	public boolean installed() {
		return Files.isRegularFile(stateFile()) && (Files.isExecutable(venvPython()) || Files.isRegularFile(venvPython()));
	}

	public @Nullable BrainState readState() {
		try {
			return Files.isRegularFile(stateFile())
					? RecordingCodec.GSON.fromJson(Files.readString(stateFile()), BrainState.class)
					: null;
		} catch (IOException | RuntimeException e) {
			MinigameBotsClient.LOGGER.warn("Couldn't read brain state", e);
			return null;
		}
	}

	private @Nullable BrainState cached;
	private long cachedAt;

	/** {@link #readState()} at most once a second, for screens that redraw every frame. */
	public @Nullable BrainState readStateCached() {
		long now = System.currentTimeMillis();
		if (now - cachedAt > 1000) {
			cached = readState();
			cachedAt = now;
		}
		return cached;
	}

	public void writeState(BrainState state) throws IOException {
		cachedAt = 0;
		Files.createDirectories(runtimeDir());
		Files.writeString(stateFile(), RecordingCodec.PRETTY.toJson(state));
	}

	/**
	 * Unpacks the brain's code from the jar if it changed. A developer can point
	 * {@code MINIGAMEBOTS_BRAIN_DIR} at the repository's {@code brain/} folder to skip this.
	 *
	 * @return the folder to put on PYTHONPATH
	 */
	public Path prepareCode() throws IOException {
		String dev = System.getenv("MINIGAMEBOTS_BRAIN_DIR");
		if (dev != null && Files.isDirectory(Path.of(dev, "minigamebots_brain"))) {
			return Path.of(dev);
		}
		List<String> files = new ArrayList<>();
		try (InputStream index = BrainRuntime.class.getResourceAsStream(INDEX)) {
			if (index == null) {
				throw new IOException("The brain's code is missing from the mod jar");
			}
			for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String name = line.trim();
				if (!name.isEmpty() && !name.contains("..")) {
					files.add(name);
				}
			}
		}
		Path code = codeDir();
		Files.createDirectories(code);
		for (String name : files) {
			try (InputStream in = BrainRuntime.class.getResourceAsStream(RESOURCE_ROOT + name)) {
				if (in == null) {
					throw new IOException("Missing brain file " + name);
				}
				byte[] bytes = in.readAllBytes();
				Path target = code.resolve(name);
				if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
					continue;
				}
				Files.createDirectories(target.getParent());
				Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return code;
	}

	/** What was installed, saved after setup. */
	public static final class BrainState {
		/** {@code standalone} or {@code custom}. */
		public String pythonSource = "";
		public String basePython = "";
		/** {@code cuda} or {@code cpu}. */
		public String torchVariant = "";
		public String torchIndex = "";
		public String installedAt = "";
		/** Last self-test report from the brain (Python, PyTorch, GPU). */
		public com.google.gson.JsonObject report;
	}
}
