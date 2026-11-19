package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonArray;
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

	/**
	 * Practice: the AI plays and improves its own network from an Objective's rewards.
	 * {@code learn} false only plays and scores (used by exams).
	 */
	public void practice(Minecraft client, io.github.mudklip.minigamebots.client.profile.ProfileManifest profile,
			io.github.mudklip.minigamebots.client.practice.Objective objective, boolean learn, double stayHuman, String resources) {
		practice(client, profile, objective, learn, stayHuman, resources, "general");
	}

	/** Practice for one of the AI's skills. */
	public void practice(Minecraft client, io.github.mudklip.minigamebots.client.profile.ProfileManifest profile,
			io.github.mudklip.minigamebots.client.practice.Objective objective, boolean learn, double stayHuman, String resources,
			String skill) {
		JsonObject args = new JsonObject();
		var profileDir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		var dir = io.github.mudklip.minigamebots.client.profile.Skills.dir(profileDir, skill);
		if (learn) {
			io.github.mudklip.minigamebots.client.profile.VersionHistory.before(profileDir, dir.resolve("core"),
					"Before practising " + skill + " (" + (objective.name == null || objective.name.isBlank() ? "custom" : objective.name) + ")");
		}
		args.addProperty("profile", dir.toString());
		args.add("objective", io.github.mudklip.minigamebots.client.practice.ObjectiveStore.toJson(objective));
		args.addProperty("learn", learn);
		args.addProperty("stay_human", stayHuman);
		args.addProperty("resources", resources);
		play(client, "practice", args);
	}

	/**
	 * The Manager: works through a queue of goals (or free play), choosing the AI's skills and
	 * letting instincts interrupt. Goals are JSON objects like {"type": "mine", "item": "iron", "count": 4}.
	 */
	public void playManager(Minecraft client, io.github.mudklip.minigamebots.client.profile.ProfileManifest profile,
			com.google.gson.JsonArray goals, boolean freePlay, JsonObject likes) {
		JsonObject args = new JsonObject();
		args.addProperty("profile", MinigameBotsClient.instance().profiles().store().dir(profile.id).toString());
		args.addProperty("foundations", MinigameBotsClient.instance().profiles().foundations().root().toString());
		args.add("goals", goals);
		args.addProperty("free_play", freePlay);
		args.add("likes", likes);
		args.addProperty("mode", profile.attitude == null || profile.attitude.isBlank() ? "neutral" : profile.attitude);
		args.addProperty("personality", profile.character == null ? "" : profile.character.personality);
		args.addProperty("temperature", MgbConfig.get().predictability >= 1 ? 0.0 : 1.0 - MgbConfig.get().predictability);
		play(client, "manager", args);
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
		play(client, policyName, args, () -> { });
	}

	private void play(Minecraft client, String policyName, JsonObject args, Runnable afterStart) {
		policy = policyName;
		control.execute(() -> {
			if (status != Status.RUNNING && !startBlocking()) {
				return;
			}
			sendSetPolicy(policyName, args);
			BrainSource s = new BrainSource(policyName);
			source = s;
			client.execute(() -> {
				AgentControl.get().start(client, s);
				afterStart.run();
			});
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

	private int commandId;
	private final java.util.Map<Integer, java.util.function.Consumer<JsonObject>> asks = new java.util.concurrent.ConcurrentHashMap<>();

	/** Sends a request to the brain (starting it if needed); {@code onReply} runs on the client thread. */
	public void ask(JsonObject message, java.util.function.Consumer<JsonObject> onReply) {
		int id = ++commandId;
		message.addProperty("id", id);
		asks.put(id, onReply);
		control.execute(() -> {
			if (status != Status.RUNNING && !startBlocking()) {
				asks.remove(id);
				JsonObject err = new JsonObject();
				err.addProperty("error", "The brain isn't set up or didn't start (F10 > Brain)");
				Minecraft.getInstance().execute(() -> onReply.accept(err));
				return;
			}
			sendAsync(message);
		});
	}

	/**
	 * A command you typed with /ai: the brain turns it into goals (rule parser, or the optional
	 * mind model). Only this client's own player can give commands.
	 */
	public void command(Minecraft client, String text) {
		int id = ++commandId;
		String me = client.player == null ? "" : client.player.getGameProfile().name();
		say(client, "§7Thinking...");
		control.execute(() -> {
			if (status != Status.RUNNING && !startBlocking()) {
				client.execute(() -> say(client, "§cThe brain isn't set up or didn't start (F10 > Brain)."));
				return;
			}
			JsonObject m = new JsonObject();
			m.addProperty("type", "command");
			m.addProperty("id", id);
			m.addProperty("text", text);
			m.addProperty("me", me);
			sendAsync(m);
		});
	}

	/** Push-to-talk audio for the brain to hear (then handled like /ai). */
	public void voice(Minecraft client, String wavPath) {
		int id = ++commandId;
		String me = client.player == null ? "" : client.player.getGameProfile().name();
		say(client, "§7Listening to what you said...");
		control.execute(() -> {
			if (status != Status.RUNNING && !startBlocking()) {
				client.execute(() -> say(client, "§cThe brain isn't set up or didn't start (F10 > Brain)."));
				return;
			}
			JsonObject m = new JsonObject();
			m.addProperty("type", "voice");
			m.addProperty("id", id);
			m.addProperty("path", wavPath);
			m.addProperty("me", me);
			sendAsync(m);
		});
	}

	/** Sends a message only if the brain is already running (no start-up for optional extras). */
	public void sendIfRunning(JsonObject m) {
		if (status == Status.RUNNING) {
			sendAsync(m);
		}
	}

	private static void say(Minecraft client, String text) {
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal("§b[AI]§r " + text));
		}
	}

	private void onCommandResult(JsonObject m) {
		Minecraft client = Minecraft.getInstance();
		String reply = m.has("reply") ? m.get("reply").getAsString() : "";
		client.execute(() -> {
			say(client, reply);
			if (m.has("progress")) {
				return;
			}
			Voice.speak(reply);
			if (m.has("stop") && m.get("stop").getAsBoolean()) {
				if (io.github.mudklip.minigamebots.client.control.AgentControl.aiActive()) {
					io.github.mudklip.minigamebots.client.control.AgentControl.get().stop(client,
							Component.literal("Stopped by your command"));
				}
				return;
			}
			var goals = m.has("goals") ? m.getAsJsonArray("goals") : new com.google.gson.JsonArray();
			var profile = MinigameBotsClient.instance().profiles().active();
			if (goals.isEmpty()) {
				return;
			}
			if (profile == null) {
				say(client, "§cPick an AI first: F10 > AI Profiles > Use this AI.");
				return;
			}
			playManager(client, profile, goals, false, new JsonObject());
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
			case "command_result" -> onCommandResult(m);
			case "speech" -> Voice.play(m.get("path").getAsString());
			case "chat_reply", "character" -> {
				var cb = m.has("id") ? asks.remove(m.get("id").getAsInt()) : null;
				if (cb != null) {
					Minecraft.getInstance().execute(() -> cb.accept(m));
				}
			}
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

	/** Game events since the last observation sent (the brain turns them into rewards when practicing). */
	private final java.util.concurrent.ConcurrentLinkedQueue<io.github.mudklip.minigamebots.client.events.GameEvent> pendingEvents =
			new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static final int MAX_PENDING_EVENTS = 256;

	/** Event listener: keeps events for the next observation, only while the brain drives the AI. */
	public void onEvent(io.github.mudklip.minigamebots.client.events.GameEvent e) {
		if (source != null && status == Status.RUNNING && pendingEvents.size() < MAX_PENDING_EVENTS) {
			pendingEvents.add(e);
		}
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
		JsonArray mw = MultiWorldLink.take();
		if (mw != null) {
			m.getAsJsonObject("obs").add("multiworld", mw);
		}
		m.getAsJsonObject("obs").add("look", look());
		m.getAsJsonObject("obs").add("healthMemory", io.github.mudklip.minigamebots.client.observe.Observer.healthMemory.toJson());
		JsonObject puppet = SelfPlayLink.describe();
		if (puppet != null) {
			m.getAsJsonObject("obs").add("puppet", puppet);
		}
		com.google.gson.JsonArray events = new com.google.gson.JsonArray();
		io.github.mudklip.minigamebots.client.events.GameEvent e;
		while ((e = pendingEvents.poll()) != null) {
			com.google.gson.JsonObject ev = new com.google.gson.JsonObject();
			ev.addProperty("type", e.type());
			ev.addProperty("source", e.source());
			ev.add("data", RecordingCodec.GSON.toJsonTree(e.data()));
			events.add(ev);
		}
		m.add("events", events);
		s.sent(agentTick);
		sendAsync(m);
	}

	/** What's in the crosshair, as the outline and the cracks show it (for skill hints such as "keep mining"). */
	private static JsonObject look() {
		JsonObject o = new JsonObject();
		Minecraft mc = Minecraft.getInstance();
		o.addProperty("breaking", mc.gameMode != null && mc.gameMode.isDestroying());
		if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b && b.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
				&& mc.level != null) {
			o.addProperty("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b.getBlockPos()).getBlock()).toString());
		} else if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult e) {
			o.addProperty("entity", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString());
		}
		return o;
	}

	/** Practises one skill: its rules, hints and locks; trains the network it belongs to. */
	public void practiceSkill(Minecraft client, io.github.mudklip.minigamebots.client.profile.ProfileManifest profile, String id,
			io.github.mudklip.minigamebots.client.skills.SkillDef def, boolean learn) {
		var profileDir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		String network = io.github.mudklip.minigamebots.client.skills.SkillStore.network(id, def);
		var dir = io.github.mudklip.minigamebots.client.profile.Skills.dir(profileDir, network);
		if (!io.github.mudklip.minigamebots.client.profile.Skills.trained(profileDir, network)
				&& io.github.mudklip.minigamebots.client.profile.Skills.trained(profileDir, "general")) {
			// A skill's first practice starts from what the AI already knows, not from nothing.
			try {
				java.nio.file.Files.createDirectories(dir.resolve("core"));
				for (String f : new String[] {"model.json", "model.safetensors", "latest.safetensors"}) {
					var from = profileDir.resolve("core").resolve(f);
					if (java.nio.file.Files.isRegularFile(from)) {
						java.nio.file.Files.copy(from, dir.resolve("core").resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
			} catch (java.io.IOException e) {
				MinigameBotsClient.LOGGER.warn("Couldn't start {} from the general network", network, e);
			}
		}
		if (learn) {
			io.github.mudklip.minigamebots.client.profile.VersionHistory.before(profileDir, dir.resolve("core"),
					"Before practising the skill " + def.name);
		}
		JsonObject args = new JsonObject();
		args.addProperty("profile", dir.toString());
		args.addProperty("stage_dir", io.github.mudklip.minigamebots.client.skills.SkillStore.dir(profileDir, id).toString());
		args.add("skill_def", io.github.mudklip.minigamebots.client.skills.SkillLibrary.GSON.toJsonTree(def));
		args.addProperty("objective", "skill");
		args.addProperty("learn", learn);
		args.addProperty("resources", "balanced");
		java.util.List<String> keys = new java.util.ArrayList<>();
		var lib = io.github.mudklip.minigamebots.client.skills.SkillLibrary.get();
		for (String g : def.locks) {
			var l = lib.locks.get(g);
			if (l != null) {
				keys.addAll(l.keys);
			}
		}
		play(client, "practice", args, () -> io.github.mudklip.minigamebots.client.control.ActionLocks.set(keys,
				def.locks.contains("camera")));
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
