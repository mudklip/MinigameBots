package io.github.mudklip.minigamebots.client.record;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.control.GameInput;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.observe.Frame;
import io.github.mudklip.minigamebots.client.observe.FrameCapture;
import io.github.mudklip.minigamebots.client.observe.Observation;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Records gameplay as training demos: every tick, what was seen (frame and observation) and
 * what was done (input in the AI's own format), plus detected events and your marks.
 */
public final class Recorder {
	private static final int TICKS_PER_CHUNK = 1200;
	private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private final RecordingStore store;
	private final HumanInput humanInput;

	private @Nullable RecordingWriter writer;
	private @Nullable RecordingMeta meta;
	private int tick;
	private long lastFrameTick = Long.MIN_VALUE;
	private final List<GameEvent> pendingEvents = new ArrayList<>();
	private final List<String> pendingMarks = new ArrayList<>();

	public Recorder(RecordingStore store, HumanInput humanInput) {
		this.store = store;
		this.humanInput = humanInput;
	}

	public RecordingStore store() {
		return store;
	}

	public boolean recording() {
		return writer != null;
	}

	/** Id of the recording being written, or null. */
	public @Nullable String currentId() {
		RecordingMeta m = meta;
		return m == null ? null : m.id;
	}

	/** Ticks recorded so far in the current recording. */
	public int ticks() {
		return tick;
	}

	public void toggle(Minecraft client) {
		if (recording()) {
			stop(client);
		} else {
			start(client);
		}
	}

	public void start(Minecraft client) {
		if (recording() || client.player == null) {
			return;
		}
		RecordingMeta m = new RecordingMeta();
		LocalDateTime now = LocalDateTime.now();
		m.id = now.format(ID_FORMAT);
		for (int i = 2; Files.exists(store.dir(m.id)); i++) {
			m.id = now.format(ID_FORMAT) + "-" + i;
		}
		m.name = m.id;
		m.created = OffsetDateTime.now().toString();
		m.source = AgentControl.aiActive() ? "ai" : "human";
		m.world = worldName(client);
		m.minecraftVersion = SharedConstants.getCurrentVersion().name();
		m.modVersion = FabricLoader.getInstance().getModContainer("minigamebots")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		m.frameSize = MgbConfig.get().frameSize;
		m.ticksPerChunk = TICKS_PER_CHUNK;
		try {
			store.saveMeta(m);
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.error("Couldn't start recording", e);
			toast(client, Component.translatable("minigamebots.recorder.failed"));
			return;
		}
		meta = m;
		tick = 0;
		lastFrameTick = Long.MIN_VALUE;
		pendingEvents.clear();
		pendingMarks.clear();
		humanInput.reset(client);
		writer = new RecordingWriter(store, m, MgbConfig.get().recordingJpegQuality / 100f);
		writer.start();
		toast(client, Component.translatable("minigamebots.recorder.started"));
	}

	public void stop(Minecraft client) {
		RecordingWriter w = writer;
		RecordingMeta m = meta;
		if (w == null || m == null) {
			return;
		}
		writer = null;
		meta = null;
		w.finish();
		if (w.failed()) {
			toast(client, Component.translatable("minigamebots.recorder.failed"));
		} else {
			toast(client, Component.translatable("minigamebots.recorder.saved", m.name, time(m.ticks)));
		}
		if (w.dropped() > 0) {
			MinigameBotsClient.LOGGER.warn("Recording {} dropped {} ticks because the disk was too slow", m.id, w.dropped());
		}
	}

	/** Names the recording being made and notes where its input came from (e.g. "teacher"). */
	public void label(String name, String source) {
		RecordingMeta m = meta;
		if (m == null) {
			return;
		}
		synchronized (m) {
			m.name = name;
			m.source = source;
		}
		try {
			store.saveMeta(m);
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.warn("Couldn't name recording {}", m.id, e);
		}
	}

	/** Adds a mark at the current tick ({@code good}, {@code mistake}, {@code base}). */
	public void mark(Minecraft client, String type) {
		RecordingMeta m = meta;
		if (m == null) {
			toast(client, Component.translatable("minigamebots.recorder.not_recording"));
			return;
		}
		synchronized (m) {
			m.marks.add(new RecordingMeta.Mark(tick, type));
		}
		pendingMarks.add(type);
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.translatable("minigamebots.recorder.marked." + type));
		}
	}

	/** Collects events as they happen; they're stored with the next tick. */
	public void onEvent(GameEvent event) {
		if (recording()) {
			pendingEvents.add(event);
		}
	}

	/**
	 * Called at the start of each client tick. Pairs the observation from the end of the last
	 * tick with the input for this tick.
	 */
	public void onStartTick(Minecraft client, @Nullable Observation lastObservation) {
		RecordingWriter w = writer;
		if (w == null) {
			return;
		}
		if (client.player == null || client.level == null) {
			stop(client);
			return;
		}
		if (w.failed()) {
			stop(client);
			return;
		}
		int limit = MgbConfig.get().maxRecordingMinutes * 60 * 20;
		if (tick >= limit) {
			stop(client);
			toast(client, Component.translatable("minigamebots.recorder.limit", MgbConfig.get().maxRecordingMinutes));
			return;
		}

		GameInput input = AgentControl.aiActive() ? AgentControl.get().lastInput() : humanInput.snapshot(client);
		boolean jump = !AgentControl.aiActive() && humanInput.lastWasCameraJump();

		Frame frame = FrameCapture.get().latest();
		RecordingWriter.Pending pending;
		int frameAge = frame == null ? -1 : (int) Math.max(0, MinigameBotsClient.instance().tick() - frame.tick());
		boolean newFrame = frame != null && frame.tick() != lastFrameTick;
		TickRecord record = new TickRecord(tick, input, lastObservation, List.copyOf(pendingEvents), null, frameAge, jump,
				List.copyOf(pendingMarks));
		if (newFrame) {
			lastFrameTick = frame.tick();
			float quality = w.jpegQuality();
			pending = new RecordingWriter.Pending(record, () -> RecordingCodec.jpeg(frame.size(), frame.rgb(), quality));
		} else {
			pending = new RecordingWriter.Pending(record, null);
		}
		w.submit(pending);
		pendingEvents.clear();
		pendingMarks.clear();
		tick++;
	}

	private static String worldName(Minecraft client) {
		if (client.getSingleplayerServer() != null) {
			return client.getSingleplayerServer().getWorldData().getLevelName();
		}
		var server = client.getCurrentServer();
		return server != null ? server.ip : "unknown";
	}

	public static String time(int ticks) {
		int seconds = ticks / 20;
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	private static void toast(Minecraft client, Component body) {
		SystemToast.addOrUpdate(client.gui.toastManager(), TOAST, Component.translatable("minigamebots.recorder.title"), body);
	}
}
