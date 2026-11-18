package io.github.mudklip.minigamebots.client.profile;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.brain.BrainProcess;
import io.github.mudklip.minigamebots.client.brain.BrainRuntime;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Downloads a foundation model with the brain's Python ({@code minigamebots_brain foundation
 * install}): it checks the file's SHA-256, loads it as plain tensors only and converts it to
 * safetensors. One install at a time; it keeps going if you close the screen.
 */
public final class FoundationInstaller {
	/** What can be installed. Mirrors CATALOG in brain/minigamebots_brain/vpt/foundation.py. */
	public record Entry(String id, String name, String description, long downloadBytes) {
	}

	public static final List<Entry> CATALOG = List.of(
			new Entry("vpt-1x", "VPT 1x (recommended)",
					"OpenAI's Minecraft foundation model, 71M parameters. Runs well on a 6 GB GPU.", 284_045_685L),
			new Entry("vpt-2x", "VPT 2x (large)",
					"248M parameters. Smarter but slower; needs a strong GPU to play smoothly.", 994_032_309L));

	private final BrainRuntime runtime;
	private final FoundationStore store;
	private final Deque<String> output = new ArrayDeque<>();
	private volatile @Nullable String installing;
	private volatile double progress = -1;
	private volatile String message = "";
	private volatile boolean failed;
	private @Nullable BrainProcess process;

	public FoundationInstaller(BrainRuntime runtime, FoundationStore store) {
		this.runtime = runtime;
		this.store = store;
	}

	public @Nullable String installing() {
		return installing;
	}

	public boolean busy() {
		return installing != null;
	}

	public double progress() {
		return progress;
	}

	public String message() {
		return message;
	}

	public boolean failed() {
		return failed;
	}

	public static @Nullable Entry entry(String id) {
		return CATALOG.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
	}

	public void install(String id) throws IOException {
		if (busy()) {
			throw new IOException("Already installing " + installing);
		}
		if (!runtime.installed()) {
			throw new IOException("Set up the brain first (Install GPU or CPU)");
		}
		var code = runtime.prepareCode();
		List<String> command = List.of(runtime.venvPython().toString(), "-m", "minigamebots_brain", "foundation", "install",
				"--id", id, "--root", store.root().toString());
		installing = id;
		progress = 0;
		failed = false;
		message = "Starting download...";
		process = BrainProcess.start(command, runtime.root(), Map.of("PYTHONPATH", code.toString()),
				runtime.logsDir().resolve("foundation.log"), output, this::onLine);
		Thread waiter = new Thread(this::waitForExit, "MinigameBots foundation install");
		waiter.setDaemon(true);
		waiter.start();
	}

	public void cancel() {
		BrainProcess p = process;
		if (p != null) {
			try {
				p.sendLine("stop");
			} catch (IOException e) {
				p.kill();
			}
		}
	}

	/** Deletes an installed foundation. AIs trained on it need it again to play. */
	public void remove(String id) throws IOException {
		if (id.equals(installing)) {
			throw new IOException("It's being installed right now");
		}
		Path dir = store.root().resolve(id).normalize();
		if (!dir.startsWith(store.root()) || !Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	private void onLine(String line) {
		if (!line.startsWith("{")) {
			return;
		}
		JsonObject m;
		try {
			m = RecordingCodec.GSON.fromJson(line, JsonObject.class);
		} catch (RuntimeException e) {
			return;
		}
		String type = m.has("type") ? m.get("type").getAsString() : "";
		switch (type) {
			case "progress" -> {
				double total = m.get("total").getAsDouble();
				double done = m.get("done").getAsDouble();
				progress = total > 0 ? done / total : -1;
				message = String.format("Downloading %.0f / %.0f MB", done / 1e6, total / 1e6);
			}
			case "log" -> {
				message = m.get("message").getAsString();
				progress = -1;
			}
			case "error" -> {
				message = m.get("message").getAsString();
				failed = true;
			}
			case "done" -> {
				message = "Installed";
				progress = 1;
			}
			default -> {
				// Future message types.
			}
		}
	}

	private void waitForExit() {
		BrainProcess p = process;
		while (p != null && p.alive()) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		if (p != null && p.exitCode() != 0 && !failed) {
			failed = true;
			message = "Install failed. See logs/foundation.log.";
		}
		process = null;
		installing = null;
	}

	/** Stops a running download when the game closes (the partial file is deleted by Python). */
	public void shutdown() {
		BrainProcess p = process;
		if (p != null) {
			cancel();
			p.kill();
		}
	}
}
