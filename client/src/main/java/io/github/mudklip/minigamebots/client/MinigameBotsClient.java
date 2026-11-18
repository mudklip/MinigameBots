package io.github.mudklip.minigamebots.client;

import io.github.mudklip.minigamebots.client.brain.BrainManager;
import io.github.mudklip.minigamebots.client.brain.BrainRuntime;
import io.github.mudklip.minigamebots.client.brain.BrainSource;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.control.Hotkeys;
import io.github.mudklip.minigamebots.client.debug.DebugCommands;
import io.github.mudklip.minigamebots.client.events.EventDetector;
import io.github.mudklip.minigamebots.client.events.EventTypes;
import io.github.mudklip.minigamebots.client.observe.FrameCapture;
import io.github.mudklip.minigamebots.client.observe.Observation;
import io.github.mudklip.minigamebots.client.observe.Observer;
import io.github.mudklip.minigamebots.client.observe.SoundTracker;
import io.github.mudklip.minigamebots.client.observe.TextTracker;
import io.github.mudklip.minigamebots.client.profile.ProfileManager;
import io.github.mudklip.minigamebots.client.train.TrainingManager;
import io.github.mudklip.minigamebots.client.record.HumanInput;
import io.github.mudklip.minigamebots.client.record.Recorder;
import io.github.mudklip.minigamebots.client.record.RecordingIndicator;
import io.github.mudklip.minigamebots.client.record.RecordingStore;
import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import io.github.mudklip.minigamebots.common.Protocol;
import io.github.mudklip.minigamebots.common.net.Compatibility;
import io.github.mudklip.minigamebots.common.net.HelloPayload;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MinigameBots client mod: the AI and everything around it. */
public final class MinigameBotsClient implements ClientModInitializer {
	private static MinigameBotsClient instance;

	private final SoundTracker sounds = new SoundTracker();
	private final TextTracker text = new TextTracker();
	private final Observer observer = new Observer(sounds, text);
	private final EventDetector events = new EventDetector();
	private final HumanInput humanInput = new HumanInput();
	private final Recorder recorder = new Recorder(
			new RecordingStore(FabricLoader.getInstance().getGameDir().resolve("minigamebots/recordings")), humanInput);
	private final BrainManager brain = new BrainManager(
			new BrainRuntime(FabricLoader.getInstance().getGameDir().resolve("minigamebots")));
	private final ProfileManager profiles = new ProfileManager(FabricLoader.getInstance().getGameDir().resolve("minigamebots"));
	private final TrainingManager training = new TrainingManager(brain.runtime(), profiles.store(), recorder.store());
	private volatile @Nullable Observation lastObservation;
	private long tick;
	private volatile boolean watching;
	public static final String MOD_ID = "minigamebots";
	public static final Logger LOGGER = LoggerFactory.getLogger("MinigameBots");

	private static final List<String> FEATURES = List.of(Protocol.Features.HANDSHAKE);
	private static final SystemToast.SystemToastId LINK_TOAST = new SystemToast.SystemToastId();

	@Override
	public void onInitializeClient() {
		instance = this;
		MinigameBotsCommon.init();
		MgbConfig.HANDLER.load();
		Hotkeys.init();
		registerBody();

		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		HelloPayload hello = HelloPayload.local(version, FEATURES);

		ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) -> {
			// Always answer, so the server knows our version even when we can't work together.
			context.responseSender().sendPacket(hello);

			Compatibility.Result result = Compatibility.check(HelloPayload.localVersion(), payload.version());
			TrainingLink.set(new TrainingLink.State(payload, result));
			showLinkToast(context.client(), payload, result);
			if (result == Compatibility.Result.COMPATIBLE) {
				LOGGER.info("Linked to Training Grounds {} (protocol v{}, features {})",
						payload.modVersion(), payload.protocol(), payload.features());
			} else {
				LOGGER.warn("Training Grounds {} uses incompatible protocol v{} ({}); link disabled",
						payload.modVersion(), payload.protocol(), result);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TrainingLink.clear());

		LOGGER.info("MinigameBots {} loaded (protocol v{})", version, Protocol.VERSION);
	}

	public static MinigameBotsClient instance() {
		return instance;
	}

	public static EventDetector events() {
		return instance.events;
	}

	public HumanInput humanInput() {
		return humanInput;
	}

	public TrainingManager training() {
		return training;
	}

	public ProfileManager profiles() {
		return profiles;
	}

	public BrainManager brain() {
		return brain;
	}

	public Recorder recorder() {
		return recorder;
	}

	/** Client ticks since the game started. */
	public long tick() {
		return tick;
	}

	public void setWatching(boolean on) {
		watching = on;
	}

	/** Observes right now (for /mgb observe), whether or not the AI is active. */
	public @Nullable Observation observeNow() {
		return observer.observe(Minecraft.getInstance(), tick);
	}

	/** Wires the AI's body into the game: input every tick, perception and events. */
	private void registerBody() {
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			AgentControl.get().onStartTick(client);
			recorder.onStartTick(client, lastObservation);
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "recording"), new RecordingIndicator(recorder));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			recorder.stop(client);
			brain.shutdown();
			training.shutdown();
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> client.getSoundManager().addListener(sounds));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> DebugCommands.register(dispatcher));

		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
			text.onChat(message.getString());
			events.onChat(message.getString(), false);
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				text.onChat(message.getString());
			}
			events.onChat(message.getString(), overlay);
		});
		ClientReceiveMessageEvents.TITLE.register(title -> events.onTitle(title.getString(), false));
		ClientReceiveMessageEvents.SUBTITLE.register(subtitle -> events.onTitle(subtitle.getString(), true));
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> events.onBlockBroken(pos, state));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			events.reset();
			text.clear();
		});

		events.addListener(recorder::onEvent);
		events.addListener(event -> {
			if (watching) {
				Minecraft client = Minecraft.getInstance();
				if (client.player != null && !event.type().equals(EventTypes.SOUND_HEARD)
						&& !event.type().equals(EventTypes.CHAT)) {
					// Straight into the chat box: going through the message system would be seen
					// as a new chat message, and loop forever.
					client.gui.hud.getChat().addClientSystemMessage(Component.literal("§7[event] §f" + event));
				}
			}
		});
	}

	private void endTick(Minecraft client) {
		tick++;
		sounds.setTick(tick);
		if (client.level == null || client.player == null) {
			lastObservation = null;
			return;
		}
		// Perception costs CPU, so it only runs while something needs it.
		if (AgentControl.aiActive() || watching || recorder.recording()) {
			Observation obs = observer.observe(client, tick);
			lastObservation = obs;
			if (obs != null) {
				events.onTick(tick, obs, client.level);
				AgentControl control = AgentControl.get();
				if (control.active() && control.source() instanceof BrainSource source) {
					brain.sendObservation(source, control.nextTick(), obs, FrameCapture.get().latest());
				}
			}
			FrameCapture.get().request(tick);
		} else {
			lastObservation = null;
		}
	}

	private static void showLinkToast(Minecraft client, HelloPayload payload, Compatibility.Result result) {
		Component title = Component.translatable("minigamebots.toast.link.title");
		Component body = switch (result) {
			case COMPATIBLE -> Component.translatable("minigamebots.toast.link.ok", payload.modVersion());
			case LOCAL_TOO_OLD -> Component.translatable("minigamebots.toast.link.update_client");
			case REMOTE_TOO_OLD -> Component.translatable("minigamebots.toast.link.update_server");
		};
		SystemToast.addOrUpdate(client.gui.toastManager(), LINK_TOAST, title, body);
	}
}
