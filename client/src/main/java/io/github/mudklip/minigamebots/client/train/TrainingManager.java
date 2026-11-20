package io.github.mudklip.minigamebots.client.train;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.brain.BrainProcess;
import io.github.mudklip.minigamebots.client.brain.BrainRuntime;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.ProfileStore;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.record.RecordingStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Runs imitation training for one AI at a time, as a background process. Training keeps going
 * when you close the screen; you get a toast when it's done.
 */
public final class TrainingManager {
	public enum Status {
		IDLE,
		RUNNING,
		STOPPING,
		DONE,
		FAILED
	}

	/** How long to train. Steps are what the trainer counts; time depends on the PC. */
	public enum Plan {
		QUICK(500, "Quick try"),
		STANDARD(3000, "Standard"),
		LONG(15000, "Long"),
		OVERNIGHT(60000, "Overnight");

		public final int steps;
		public final String label;

		Plan(int steps, String label) {
			this.steps = steps;
			this.label = label;
		}
	}

	/** How hard training may push the PC (see the design's resource profiles). */
	public enum Resources {
		QUIET("Quiet"),
		BALANCED("Balanced"),
		MAX("Max");

		public final String label;

		Resources(String label) {
			this.label = label;
		}
	}

	/** One point of the progress graph. */
	public record Point(int step, double loss, double valLoss) {
	}

	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private final BrainRuntime runtime;
	private final ProfileStore profiles;
	private final RecordingStore recordings;
	private final Deque<String> output = new java.util.ArrayDeque<>();

	private volatile Status status = Status.IDLE;
	private volatile String message = "";
	private volatile @Nullable String profileId;
	private volatile int step;
	private volatile int totalSteps;
	private volatile double loss = Double.NaN;
	private volatile double valLoss = Double.NaN;
	private volatile double keyAcc = Double.NaN;
	private volatile double cameraAcc = Double.NaN;
	private volatile double etaSeconds = Double.NaN;
	private volatile String device = "";
	private volatile int parameters;
	/** Value of {@link ProfileManifest#foundation} for the Lite network. */
	public static final String LITE = "lite-v1";
	private volatile String base = LITE;
	private final List<Point> points = new ArrayList<>();
	private @Nullable BrainProcess process;

	public TrainingManager(BrainRuntime runtime, ProfileStore profiles, RecordingStore recordings) {
		this.runtime = runtime;
		this.profiles = profiles;
		this.recordings = recordings;
	}

	public Status status() {
		return status;
	}

	public boolean busy() {
		return status == Status.RUNNING || status == Status.STOPPING;
	}

	public String message() {
		return message;
	}

	public @Nullable String profileId() {
		return profileId;
	}

	public int step() {
		return step;
	}

	public int totalSteps() {
		return totalSteps;
	}

	public double loss() {
		return loss;
	}

	public double valLoss() {
		return valLoss;
	}

	public double keyAcc() {
		return keyAcc;
	}

	public double cameraAcc() {
		return cameraAcc;
	}

	public double etaSeconds() {
		return etaSeconds;
	}

	public String device() {
		return device;
	}

	public int parameters() {
		return parameters;
	}

	public List<String> output() {
		synchronized (output) {
			return List.copyOf(output);
		}
	}

	public List<Point> points() {
		synchronized (points) {
			return List.copyOf(points);
		}
	}

	/** Starts training {@code profile} on {@code chosen} recordings. */
	public void start(ProfileManifest profile, List<RecordingMeta> chosen, Plan plan, Resources resources, boolean fresh)
			throws IOException {
		start(profile, chosen, plan, resources, fresh, null);
	}

	/**
	 * Starts training; {@code foundationId} null trains the small Lite network from scratch,
	 * otherwise the AI's own layers are trained on top of that installed foundation model.
	 */
	public void start(ProfileManifest profile, List<RecordingMeta> chosen, Plan plan, Resources resources, boolean fresh,
			@Nullable String foundationId) throws IOException {
		start(profile, chosen, plan, resources, fresh, foundationId, "general");
	}

	private boolean aimDrill;
	private String skillTrained = "general";

	/** Also train on the built-in fast-sim aim drill (Lite only): recordings become optional. */
	public void setAimDrill(boolean on) {
		aimDrill = on;
	}

	public boolean aimDrill() {
		return aimDrill;
	}

	/** Trains one of the AI's skills ("general" is the AI's main network). */
	public void start(ProfileManifest profile, List<RecordingMeta> chosen, Plan plan, Resources resources, boolean fresh,
			@Nullable String foundationId, String skill) throws IOException {
		if (busy()) {
			throw new IOException("Already training " + profileId);
		}
		if (!runtime.installed()) {
			throw new IOException("Set up the brain first (F10 > Brain > Install)");
		}
		boolean sim = aimDrill && foundationId == null;
		skillTrained = skill;
		if (chosen.isEmpty() && !sim) {
			throw new IOException("Pick at least one recording to learn from (or tick Aim drill)");
		}
		var code = runtime.prepareCode();
		java.nio.file.Path skillDir = io.github.mudklip.minigamebots.client.profile.Skills.dir(profiles.dir(profile.id), skill);
		io.github.mudklip.minigamebots.client.profile.VersionHistory.before(profiles.dir(profile.id), skillDir.resolve("core"),
				String.format("Before training %s (%s, %d recordings%s%s)", skill, plan.label, chosen.size(), sim ? ", aim drill" : "",
						foundationId == null ? "" : ", " + foundationId));
		List<String> command = new ArrayList<>(List.of(runtime.venvPython().toString(), "-m", "minigamebots_brain", "train",
				"--profile", skillDir.toString(), "--steps", Integer.toString(plan.steps),
				"--resources", resources.name().toLowerCase(Locale.ROOT)));
		for (RecordingMeta r : chosen) {
			command.add("--recording");
			command.add(recordings.dir(r.id).toString());
		}
		if (fresh) {
			command.add("--fresh");
		}
		if (sim) {
			command.addAll(List.of("--sim", "aim"));
		}
		var foundations = MinigameBotsClient.instance().profiles().foundations();
		if (foundationId != null) {
			if (!foundations.has(foundationId)) {
				throw new IOException("Install " + foundationId + " first (Brain > Foundations)");
			}
			command.addAll(List.of("--base", "vpt", "--foundation", foundations.root().resolve(foundationId).toString()));
		}
		base = foundationId == null ? LITE : foundationId;
		synchronized (points) {
			points.clear();
		}
		profileId = profile.id;
		step = 0;
		totalSteps = plan.steps;
		loss = valLoss = keyAcc = cameraAcc = etaSeconds = Double.NaN;
		message = "Starting...";
		status = Status.RUNNING;
		Files.createDirectories(runtime.logsDir());
		process = BrainProcess.start(command, runtime.root(), Map.of("PYTHONPATH", code.toString()),
				runtime.logsDir().resolve("training.log"), output, this::onLine);
		Thread waiter = new Thread(this::waitForExit, "MinigameBots training watcher");
		waiter.setDaemon(true);
		waiter.start();
	}

	/** Asks the trainer to save and stop after the current step. */
	public void stop() {
		BrainProcess p = process;
		if (p == null || !busy()) {
			return;
		}
		status = Status.STOPPING;
		message = "Saving and stopping...";
		try {
			p.sendLine("stop");
		} catch (IOException e) {
			p.kill();
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
		switch (str(m, "type")) {
			case "start" -> {
				device = str(m, "device");
				parameters = m.get("parameters").getAsInt();
				message = String.format("Training on %s: %,d ticks of recordings", device.toUpperCase(Locale.ROOT),
						m.get("train_ticks").getAsInt());
			}
			case "progress" -> {
				step = m.get("step").getAsInt();
				loss = num(m, "loss");
				valLoss = num(m, "val_loss");
				keyAcc = num(m, "key_acc");
				cameraAcc = num(m, "camera_acc");
				etaSeconds = num(m, "eta_s");
				synchronized (points) {
					points.add(new Point(step, loss, valLoss));
				}
			}
			case "checkpoint" -> {
				valLoss = num(m, "val_loss");
				if (m.has("best") && m.get("best").getAsBoolean()) {
					message = "New best checkpoint at step " + m.get("step").getAsInt();
				}
			}
			case "error" -> message = str(m, "message");
			case "done" -> finish(m);
			case "log" -> message = str(m, "message");
			default -> {
				// Future message types.
			}
		}
	}

	private void finish(JsonObject m) {
		String id = profileId;
		ProfileManifest p = id == null ? null : profiles.get(id);
		if (p != null) {
			p.stats.trainingHours += num(m, "hours");
			p.stats.decisions = m.get("global_step").getAsLong();
			p.stats.skills = Math.max(1, p.stats.skills);
			p.foundation = base;
			try {
				profiles.save(p);
			} catch (IOException e) {
				MinigameBotsClient.LOGGER.warn("Couldn't update stats of {}", id, e);
			}
		}
		status = Status.DONE;
		message = "Finished at step " + m.get("global_step").getAsInt();
		Minecraft client = Minecraft.getInstance();
		String name = p == null ? "AI" : p.name;
		client.execute(() -> SystemToast.addOrUpdate(client.gui.toastManager(), TOAST,
				Component.translatable("minigamebots.training.title"), Component.translatable("minigamebots.training.finished", name)));
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
		if (status == Status.RUNNING || status == Status.STOPPING) {
			// Exited without a "done" message.
			status = Status.FAILED;
			if (message.isEmpty() || message.startsWith("Training on") || message.startsWith("Saving")) {
				message = "Training stopped unexpectedly. See logs/training.log.";
			}
			Minecraft client = Minecraft.getInstance();
			String why = message;
			client.execute(() -> SystemToast.addOrUpdate(client.gui.toastManager(), TOAST,
					Component.translatable("minigamebots.training.title"), Component.literal(why)));
		}
		if (status == Status.DONE) {
			Minecraft.getInstance().execute(() -> {
				io.github.mudklip.minigamebots.client.coach.Progress.unlock("first_training");
				io.github.mudklip.minigamebots.client.coach.Progress.addXp(
						"general".equals(skillTrained) ? "pvp" : skillTrained.equals("travel") ? "movement" : skillTrained, 15,
						"training finished");
				// A2: a quick exam of what it just learned (fighting networks, singleplayer).
				var trainedProfile = profileId == null ? null : profiles.get(profileId);
				if (trainedProfile != null) {
					io.github.mudklip.minigamebots.client.practice.AutoExam.afterTraining(trainedProfile, skillTrained,
							"imitation training", Long.MAX_VALUE);
				}
			});
		}
		process = null;
	}

	/** Stops training when the game closes. */
	public void shutdown() {
		BrainProcess p = process;
		if (p != null) {
			try {
				p.sendLine("stop");
				for (int i = 0; i < 50 && p.alive(); i++) {
					Thread.sleep(100);
				}
			} catch (IOException | InterruptedException ignored) {
				// Kill below.
			}
			p.kill();
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static double num(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : Double.NaN;
	}
}
