package io.github.mudklip.minigamebots.client.brain;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs a Python process and collects its output: every line goes to a log file and the last
 * lines are kept for the Brain screen.
 */
public final class BrainProcess {
	private static final int KEEP_LINES = 200;

	private final Process process;
	private final Deque<String> lines;

	private BrainProcess(Process process, Deque<String> lines) {
		this.process = process;
		this.lines = lines;
	}

	/**
	 * Starts {@code command}. Output is appended to {@code log} and to {@code sink} (shared, so
	 * the screen can show it), and each line is also passed to {@code onLine}.
	 */
	public static BrainProcess start(List<String> command, Path workDir, Map<String, String> env, Path log,
			Deque<String> sink, Consumer<String> onLine) throws IOException {
		Files.createDirectories(log.getParent());
		Files.createDirectories(workDir);
		ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
		builder.environment().putAll(env);
		builder.environment().put("PYTHONUNBUFFERED", "1");
		builder.environment().put("PYTHONIOENCODING", "utf-8");
		Process process = builder.start();
		Thread pump = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
					Writer out = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
							StandardOpenOption.APPEND)) {
				String line;
				while ((line = in.readLine()) != null) {
					out.write(line);
					out.write('\n');
					out.flush();
					synchronized (sink) {
						sink.addLast(line);
						while (sink.size() > KEEP_LINES) {
							sink.pollFirst();
						}
					}
					onLine.accept(line);
				}
			} catch (IOException e) {
				MinigameBotsClient.LOGGER.debug("Brain output closed", e);
			}
		}, "MinigameBots python output");
		pump.setDaemon(true);
		pump.start();
		return new BrainProcess(process, sink);
	}

	/** Runs a command to completion and returns its exit code. */
	public static int run(List<String> command, Path workDir, Map<String, String> env, Path log, Deque<String> sink,
			Consumer<String> onLine) throws IOException, InterruptedException {
		BrainProcess p = start(command, workDir, env, log, sink, onLine);
		return p.process.waitFor();
	}

	/** Sends one line to the process's standard input (e.g. "stop" to the trainer). */
	public void sendLine(String line) throws IOException {
		var in = process.getOutputStream();
		in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		in.flush();
	}

	public boolean alive() {
		return process.isAlive();
	}

	public int exitCode() {
		return process.isAlive() ? -1 : process.exitValue();
	}

	/** Waits up to {@code ms} for the process to end by itself; true if it did. */
	public boolean awaitExit(long ms) {
		try {
			return process.waitFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return !process.isAlive();
		}
	}

	public void kill() {
		process.destroy();
		try {
			if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	public List<String> lastLines() {
		synchronized (lines) {
			return new ArrayList<>(lines);
		}
	}

	static Deque<String> newSink() {
		return new ArrayDeque<>();
	}
}
