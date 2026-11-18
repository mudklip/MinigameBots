package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Sets up the brain's Python environment (one-time, about 3-5 GB with the GPU build of PyTorch):
 *
 * <ol>
 *   <li>Get a Python: download a standalone build (python-build-standalone, the newest release,
 *       checked against its published SHA-256) or use one you already have.</li>
 *   <li>Create the brain's own virtual environment from it, so your own Python stays untouched.</li>
 *   <li>Install numpy and safetensors, then PyTorch from PyTorch's own index (CUDA build for
 *       NVIDIA GPUs, otherwise CPU).</li>
 *   <li>Run the brain's self-test and save the report.</li>
 * </ol>
 */
public final class RuntimeInstaller {
	public enum Variant {
		CUDA,
		CPU
	}

	/** CUDA builds to try, newest first; the first one pip can install wins. */
	public static final List<String> CUDA_INDEXES = List.of(
			"https://download.pytorch.org/whl/cu128",
			"https://download.pytorch.org/whl/cu126");
	public static final String CPU_INDEX = "https://download.pytorch.org/whl/cpu";
	static final String RELEASES_API = "https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest";
	static final String PYTHON_MINOR = "3.12";

	private final BrainRuntime runtime;
	private final Deque<String> output;
	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.proxy(ProxySelector.getDefault())
			.connectTimeout(Duration.ofSeconds(20))
			.build();

	private volatile String stage = "";
	private volatile double progress;
	private volatile boolean cancelled;
	private volatile @Nullable String error;
	private volatile boolean done;

	public RuntimeInstaller(BrainRuntime runtime, Deque<String> output) {
		this.runtime = runtime;
		this.output = output;
	}

	public String stage() {
		return stage;
	}

	/** 0 to 1 for the current stage; negative when unknown. */
	public double progress() {
		return progress;
	}

	public @Nullable String error() {
		return error;
	}

	public boolean done() {
		return done;
	}

	/** Stops after the current step (a running download stops right away). */
	public void cancel() {
		cancelled = true;
	}

	/** Is there an NVIDIA GPU with a working driver? (nvidia-smi runs.) */
	public static boolean hasNvidiaGpu() {
		try {
			Process p = new ProcessBuilder("nvidia-smi", "-L").redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Runs the whole setup. Blocks; call from a background thread.
	 *
	 * @param customPython your own Python 3.10+ to build from, or null to download one
	 */
	public void install(Variant variant, @Nullable String customPython) {
		try {
			// Until this run succeeds, the brain counts as not installed.
			Files.deleteIfExists(runtime.stateFile());
			Path base;
			BrainRuntime.BrainState state = new BrainRuntime.BrainState();
			if (customPython != null && !customPython.isBlank()) {
				base = Path.of(customPython.trim());
				state.pythonSource = "custom";
				setStage("Checking your Python", -1);
				checkPythonVersion(base);
			} else {
				base = downloadPython();
				state.pythonSource = "standalone";
			}
			state.basePython = base.toString();
			checkCancelled();

			setStage("Creating the brain's Python environment", -1);
			deleteTree(runtime.venv());
			run(List.of(base.toString(), "-m", "venv", runtime.venv().toString()));
			Path python = runtime.venvPython();
			checkCancelled();

			setStage("Updating pip", -1);
			run(List.of(python.toString(), "-m", "pip", "install", "--upgrade", "pip"));
			checkCancelled();

			setStage("Installing numpy and safetensors", -1);
			Path code = runtime.prepareCode();
			run(List.of(python.toString(), "-m", "pip", "install", "-r", code.resolve("requirements.txt").toString()));
			checkCancelled();

			state.torchVariant = variant.name().toLowerCase(Locale.ROOT);
			state.torchIndex = installTorch(python, variant);
			checkCancelled();

			setStage("Testing", -1);
			StringBuilder json = new StringBuilder();
			int code1 = BrainProcess.run(List.of(python.toString(), "-m", "minigamebots_brain", "selftest"), runtime.root(),
					Map.of("PYTHONPATH", code.toString()), log(), output, line -> {
						if (line.startsWith("{")) {
							json.append(line);
						}
					});
			if (code1 != 0 || json.isEmpty()) {
				throw new IOException("The self-test failed. See logs/install.log.");
			}
			state.report = RecordingCodec.GSON.fromJson(json.toString(), JsonObject.class);
			state.installedAt = OffsetDateTime.now().toString();
			runtime.writeState(state);
			setStage(summary(state.report), 1);
		} catch (CancelledException e) {
			error = "Cancelled";
			log("Setup cancelled");
		} catch (IOException | RuntimeException e) {
			MinigameBotsClient.LOGGER.error("Brain setup failed", e);
			error = e.getMessage() == null ? e.toString() : e.getMessage();
			log("Setup failed: " + error);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			error = "Interrupted";
		} finally {
			done = true;
		}
	}

	/** Human summary of a self-test report, e.g. "Ready: PyTorch 2.15 on NVIDIA GeForce RTX 2060 (6 GB)". */
	public static String summary(@Nullable JsonObject report) {
		if (report == null) {
			return "Not tested";
		}
		String torch = str(report, "torch");
		if (torch == null) {
			return "PyTorch is missing";
		}
		String where = report.has("gpu") && !report.get("gpu").isJsonNull()
				? str(report, "gpu") + " (" + report.get("vram_gb").getAsString() + " GB)"
				: "CPU";
		String problems = report.has("problems") && report.getAsJsonArray("problems").size() > 0
				? ". Problem: " + report.getAsJsonArray("problems").get(0).getAsString()
				: "";
		return "Ready: PyTorch " + torch + " on " + where + problems;
	}

	private String installTorch(Path python, Variant variant) throws IOException, InterruptedException {
		List<String> indexes = variant == Variant.CUDA ? CUDA_INDEXES : List.of(CPU_INDEX);
		IOException last = null;
		for (String index : indexes) {
			setStage("Installing PyTorch (" + (variant == Variant.CUDA ? "GPU" : "CPU") + "), this is the big one", -1);
			try {
				run(List.of(python.toString(), "-m", "pip", "install", "--upgrade", "torch", "--index-url", index));
				return index;
			} catch (IOException e) {
				last = e;
				log("Couldn't install from " + index + ", trying the next one");
			}
			checkCancelled();
		}
		throw last != null ? last : new IOException("No PyTorch index worked");
	}

	// ---- Standalone Python download ----

	private Path downloadPython() throws IOException, InterruptedException {
		Path exe = runtime.standaloneExecutable();
		if (Files.exists(exe)) {
			log("Standalone Python already downloaded: " + exe);
			return exe;
		}
		setStage("Finding the newest standalone Python", -1);
		JsonObject release = getJson(RELEASES_API);
		Asset asset = pickAsset(release, platformTriple());
		if (asset == null) {
			throw new IOException("No standalone Python " + PYTHON_MINOR + " for " + platformTriple()
					+ ". Install Python yourself and choose \"Use my own Python\".");
		}
		String expectedSha = findSha256(release, asset.name());
		Path downloads = runtime.runtimeDir().resolve("download");
		Files.createDirectories(downloads);
		Path archive = downloads.resolve(asset.name());
		setStage("Downloading Python (" + asset.size() / 1_000_000 + " MB)", 0);
		download(asset.url(), archive, asset.size());
		if (expectedSha != null) {
			String actual = sha256(archive);
			if (!actual.equalsIgnoreCase(expectedSha)) {
				Files.deleteIfExists(archive);
				throw new IOException("The Python download is corrupted (checksum mismatch). Try again.");
			}
			log("Checksum OK: " + actual);
		} else {
			log("No published checksum found for " + asset.name());
		}
		setStage("Unpacking Python", -1);
		deleteTree(runtime.standalonePython());
		TarExtractor.extractTarGz(archive, runtime.runtimeDir());
		Files.deleteIfExists(archive);
		if (!Files.exists(exe)) {
			throw new IOException("Unpacked Python is missing " + exe);
		}
		return exe;
	}

	record Asset(String name, String url, long size) {
	}

	/** The python-build-standalone target name for this PC. */
	static String platformTriple() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm64");
		if (os.contains("win")) {
			return arm ? "aarch64-pc-windows-msvc" : "x86_64-pc-windows-msvc";
		}
		if (os.contains("mac")) {
			return arm ? "aarch64-apple-darwin" : "x86_64-apple-darwin";
		}
		return arm ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu";
	}

	/** Picks the newest CPython 3.12 "install_only" build for the triple. */
	static @Nullable Asset pickAsset(JsonObject release, String triple) {
		Pattern pattern = Pattern.compile("cpython-" + Pattern.quote(PYTHON_MINOR) + "\\.(\\d+)\\+\\d+-"
				+ Pattern.quote(triple) + "-install_only\\.tar\\.gz");
		List<Asset> matches = new ArrayList<>();
		List<Integer> patches = new ArrayList<>();
		for (JsonElement e : release.getAsJsonArray("assets")) {
			JsonObject a = e.getAsJsonObject();
			String name = a.get("name").getAsString();
			Matcher m = pattern.matcher(name);
			if (m.matches()) {
				matches.add(new Asset(name, a.get("browser_download_url").getAsString(), a.get("size").getAsLong()));
				patches.add(Integer.parseInt(m.group(1)));
			}
		}
		if (matches.isEmpty()) {
			return null;
		}
		int best = 0;
		for (int i = 1; i < matches.size(); i++) {
			if (patches.get(i) > patches.get(best)) {
				best = i;
			}
		}
		return matches.get(best);
	}

	/** Looks for the asset's checksum: a per-file .sha256 asset or a SHA256SUMS file. */
	private @Nullable String findSha256(JsonObject release, String assetName) {
		JsonArray assets = release.getAsJsonArray("assets");
		for (JsonElement e : assets) {
			JsonObject a = e.getAsJsonObject();
			String name = a.get("name").getAsString();
			try {
				if (name.equals(assetName + ".sha256")) {
					return getText(a.get("browser_download_url").getAsString()).trim().split("\\s+")[0];
				}
				if (name.equals("SHA256SUMS")) {
					for (String line : getText(a.get("browser_download_url").getAsString()).split("\n")) {
						String[] parts = line.trim().split("\\s+");
						if (parts.length == 2 && parts[1].equals(assetName)) {
							return parts[0];
						}
					}
				}
			} catch (IOException | InterruptedException e2) {
				log("Couldn't fetch checksums: " + e2.getMessage());
			}
		}
		return null;
	}

	private JsonObject getJson(String url) throws IOException, InterruptedException {
		return RecordingCodec.GSON.fromJson(getText(url), JsonObject.class);
	}

	private String getText(String url) throws IOException, InterruptedException {
		HttpResponse<String> r = http.send(request(url), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) {
			throw new IOException("HTTP " + r.statusCode() + " from " + url);
		}
		return r.body();
	}

	private static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "MinigameBots-installer")
				.header("Accept", "application/vnd.github+json, */*")
				.timeout(Duration.ofMinutes(30))
				.build();
	}

	/** Downloads with progress, to a temp file first so a broken download never looks complete. */
	void download(String url, Path target, long expectedSize) throws IOException, InterruptedException {
		HttpResponse<InputStream> r = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
		if (r.statusCode() != 200) {
			r.body().close();
			throw new IOException("HTTP " + r.statusCode() + " downloading " + url);
		}
		long total = r.headers().firstValueAsLong("Content-Length").orElse(expectedSize);
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
			byte[] buffer = new byte[1 << 16];
			long read = 0;
			int n;
			while ((n = in.read(buffer)) > 0) {
				out.write(buffer, 0, n);
				read += n;
				progress = total > 0 ? (double) read / total : -1;
				checkCancelled();
			}
		}
		Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			int n;
			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---- helpers ----

	private void checkPythonVersion(Path python) throws IOException, InterruptedException {
		StringBuilder out = new StringBuilder();
		int code = BrainProcess.run(List.of(python.toString(), "-c",
				"import sys; print('PYVER', sys.version_info[0], sys.version_info[1])"), runtime.root(), Map.of(), log(),
				output, line -> {
					if (line.startsWith("PYVER")) {
						out.append(line);
					}
				});
		String[] parts = out.toString().split(" ");
		if (code != 0 || parts.length != 3) {
			throw new IOException("Couldn't run " + python);
		}
		int major = Integer.parseInt(parts[1]);
		int minor = Integer.parseInt(parts[2]);
		if (major != 3 || minor < 10) {
			throw new IOException("Python 3.10 or newer is needed, " + python + " is " + major + "." + minor);
		}
	}

	private void run(List<String> command) throws IOException, InterruptedException {
		log("> " + String.join(" ", command));
		Files.createDirectories(runtime.root());
		int code = BrainProcess.run(command, runtime.root(), Map.of(), log(), output, line -> { });
		if (code != 0) {
			throw new IOException("Step failed (exit code " + code + "): " + stage + ". See logs/install.log.");
		}
	}

	private Path log() {
		return runtime.logsDir().resolve("install.log");
	}

	private void log(String line) {
		synchronized (output) {
			output.addLast(line);
			while (output.size() > 200) {
				output.pollFirst();
			}
		}
		MinigameBotsClient.LOGGER.info("[brain setup] {}", line);
	}

	private void setStage(String text, double p) {
		stage = text;
		progress = p;
		log("== " + text);
	}

	private void checkCancelled() {
		if (cancelled) {
			throw new CancelledException();
		}
	}

	private static final class CancelledException extends RuntimeException {
	}

	private static @Nullable String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}
}
