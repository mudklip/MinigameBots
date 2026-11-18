package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.observe.Frame;
import io.github.mudklip.minigamebots.client.observe.Observation;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Starts, stops and talks to the brain process. All slow work happens on a background thread;
 * the game thread only ever queues messages.
 */
public final class BrainManager {
	public enum Status {
		STOPPED,
		STARTING,
		RUNNING,
		FAILED
	}

	private static final int CONNECT_TIMEOUT_MS = 60_000;
	/** Observations not yet sent beyond this are dropped instead of piling up behind a slow brain. */
	private static final int MAX_PENDING_SENDS = 2;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private final BrainRuntime runtime;
	private final Deque<String> output = BrainProcess.newSink();
	private final ExecutorService control = Executors.newSingleThreadExecutor(r -> daemon(r, "MinigameBots brain control"));
	private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> daemon(r, "MinigameBots brain sender"));
	private final AtomicInteger pendingSends = new AtomicInteger();

	private volatile Status status = Status.STOPPED;
	private volatile String message = "";
	private volatile @Nullable JsonObject system;
	private volatile @Nullable JsonObject lastStatus;
	private volatile String policy = "idle";
	private volatile @Nullable BrainSource source;
	private @Nullable BrainProcess process;
	private @Nullable BrainConnection connection;
	private @Nullable FrameRing frames;
	private volatile boolean stopping;

	public BrainManager(BrainRuntime runtime) {
		this.runtime = runtime;
	}

	public BrainRuntime runtime() {
		return runtime;
	}

	public Status status() {
		return status;
	}

	public String message() {
		return message;
	}

	public @Nullable JsonObject system() {
		return system;
	}

	public @Nullable JsonObject lastStatus() {
		return lastStatus;
	}

	public String policy() {
		return policy;
	}

	public @Nullable BrainSource source() {
		return source;
	}

	public List<String> output() {
		synchronized (output) {
			return List.copyOf(output);
		}
	}

	public Deque<String> outputSink() {
		return output;
	}

	/** Starts the brain (if needed) and hands it control of the AI with {@code policyName}. */
	public void play(Minecraft client, String policyName) {
		play(client, policyName, new JsonObject());
	}

	/** Lets {@code profile}'s trained network play. */
	public void playLearned(Minecraft client, io.github.mudklip.minigamebots.client.profile.ProfileManifest profile) {
		JsonObject args = new JsonObject();
		args.addProperty("profile", MinigameBotsClient.instance().profiles().store().dir(profile.id).toString());
		args.addProperty("foundations", MinigameBotsClient.instance().profiles().foundations().root().toString());
		args.addProperty("temperature", MgbConfig.get().predictability >= 1 ? 0.0 : 1.0 - MgbConfig.get().predictability);
		play(client, "learned", args);
	}

	/** Plays with a foundation model as-is (no AI profile on top): mostly to see what it already knows. */
	public void playFoundation(Minecraft client, String foundationId) {
		JsonObject args = new JsonObject();
		args.addProperty("foundation", MinigameBotsClient.instance().profiles().foundations().root().resolve(foundationId).toString());
		args.addProperty("temperature", MgbConfig.get().predictability >= 1 ? 0.0 : 1.0 - MgbConfig.get().predictability);
		play(client, "vpt", args);
	}

	/** Starts the brain (if needed) and hands it control with a policy and its arguments. */
	public void play(Minecraft client, String policyName, JsonObject args) {
		policy = policyName;
		control.execute(() -> {
			if (status != Status.RUNNING && !startBlocking()) {
				return;
			}
			sendSetPolicy(policyName, args);
			BrainSource s = new BrainSource(policyName);
			source = s;
			client.execute(() -> AgentControl.get().start(client, s));
		});
	}

	/** Starts the brain process without giving it control. */
	public void start() {
		control.execute(() -> {
			if (status != Status.RUNNING) {
				startBlocking();
			}
		});
	}

	public void stop() {
		control.execute(this::stopBlocking);
	}

	private boolean startBlocking() {
		stopBlocking();
		stopping = false;
		status = Status.STARTING;
		message = "Starting...";
		try {
			if (!runtime.installed()) {
				throw new IOException("The brain isn't installed yet. Open Setup and install it first.");
			}
			var code = runtime.prepareCode();
			Files.createDirectories(runtime.runDir());
			frames = new FrameRing(runtime.runDir().resolve("frames.bin"), MgbConfig.get().frameSize);
			connection = new BrainConnection(RecordingCodec.GSON);
			List<String> command = List.of(runtime.venvPython().toString(), "-m", "minigamebots_brain", "run",
					"--port", Integer.toString(connection.port()), "--token", connection.token(),
					"--frames", frames.path().toString());
			process = BrainProcess.start(command, runtime.root(), Map.of("PYTHONPATH", code.toString()),
					runtime.logsDir().resolve("brain.log"), output, line -> { });
			BrainConnection c = connection;
			JsonObject hello = c.accept(CONNECT_TIMEOUT_MS, this::onMessage, this::onClosed);
			system = hello.has("system") ? hello.getAsJsonObject("system") : null;
			status = Status.RUNNING;
			message = "Running";
			MinigameBotsClient.LOGGER.info("Brain connected: {}", system);
			return true;
		} catch (IOException | RuntimeException e) {
			MinigameBotsClient.LOGGER.error("Couldn't start the brain", e);
			fail(e.getMessage() == null ? e.toString() : e.getMessage());
			cleanup();
			return false;
		}
	}

	private void stopBlocking() {
		stopping = true;
		BrainConnection c = connection;
		if (c != null && c.connected()) {
			try {
				JsonObject bye = new JsonObject();
				bye.addProperty("type", "shutdown");
				c.send(bye);
			} catch (IOException ignored) {
				// It's going away anyway.
			}
		}
		cleanup();
		if (status != Status.FAILED) {
			status = Status.STOPPED;
			message = "Stopped";
		}
	}

	private void cleanup() {
		if (connection != null) {
			connection.close();
			connection = null;
		}
		if (process != null) {
			process.kill();
			process = null;
		}
		if (frames != null) {
			try {
				frames.close();
			} catch (IOException ignored) {
				// Nothing to do.
			}
			frames = null;
		}
		source = null;
	}

	private void fail(String why) {
		status = Status.FAILED;
		message = why;
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (AgentControl.aiActive() && AgentControl.get().source() instanceof BrainSource) {
				AgentControl.get().stop(client, Component.translatable("minigamebots.brain.stopped_ai"));
			}
			SystemToast.addOrUpdate(client.gui.toastManager(), TOAST, Component.translatable("minigamebots.brain.title"),
					Component.literal(why));
		});
	}

	private void onMessage(JsonObject m) {
		String type = m.has("type") ? m.get("type").getAsString() : "";
		switch (type) {
			case "action" -> {
				BrainSource s = source;
				if (s != null) {
					s.offer(m);
				}
			}
			case "status" -> lastStatus = m;
			case "policy" -> {
				if (m.has("ok") && !m.get("ok").getAsBoolean()) {
					String error = m.has("error") ? m.get("error").getAsString() : "unknown error";
					fail("Policy " + m.get("name").getAsString() + " failed: " + error);
				}
			}
			default -> {
				// pong and future messages
			}
		}
	}

	private void onClosed() {
		if (stopping) {
			return;
		}
		BrainProcess p = process;
		int code = p == null ? -1 : waitExit(p);
		control.execute(this::cleanup);
		fail("The brain stopped unexpectedly" + (code >= 0 ? " (exit code " + code + ")" : "") + ". See logs/brain.log.");
	}

	private static int waitExit(BrainProcess p) {
		for (int i = 0; i < 20 && p.alive(); i++) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return p.exitCode();
	}

	private void sendSetPolicy(String name, JsonObject args) {
		JsonObject m = new JsonObject();
		m.addProperty("type", "set_policy");
		m.addProperty("name", name);
		m.add("args", args);
		sendAsync(m);
	}

	/** Called at the end of each tick while the brain drives the AI. */
	public void sendObservation(BrainSource s, long agentTick, Observation obs, @Nullable Frame frame) {
		if (status != Status.RUNNING || s != source) {
			return;
		}
		if (pendingSends.get() >= MAX_PENDING_SENDS) {
			return;
		}
		JsonObject m = new JsonObject();
		m.addProperty("type", "obs");
		m.addProperty("tick", agentTick);
		FrameRing ring = frames;
		int slot = frame != null && ring != null ? ring.write(frame) : -1;
		m.addProperty("slot", slot);
		m.addProperty("frameTick", frame == null ? -1 : frame.tick());
		m.add("obs", RecordingCodec.GSON.toJsonTree(obs));
		s.sent(agentTick);
		sendAsync(m);
	}

	private void sendAsync(JsonObject m) {
		pendingSends.incrementAndGet();
		sender.execute(() -> {
			try {
				BrainConnection c = connection;
				if (c != null && c.connected()) {
					c.send(m);
				}
			} catch (IOException e) {
				MinigameBotsClient.LOGGER.debug("Couldn't send to brain", e);
			} finally {
				pendingSends.decrementAndGet();
			}
		});
	}

	/** Stops everything when the game closes. */
	public void shutdown() {
		stopBlocking();
		control.shutdownNow();
		sender.shutdownNow();
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}
}
