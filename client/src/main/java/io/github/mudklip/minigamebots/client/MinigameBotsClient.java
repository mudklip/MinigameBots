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
import io.github.mudklip.minigamebots.client.observe.RelationTracker;
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
	private final RelationTracker relations = new RelationTracker();
	private final EventDetector events = new EventDetector();
	private final io.github.mudklip.minigamebots.client.memory.MemoryManager memory =
			new io.github.mudklip.minigamebots.client.memory.MemoryManager(events);
	private final Observer observer = new Observer(sounds, text, relations, memory);
	private final HumanInput humanInput = new HumanInput();
	private final Recorder recorder = new Recorder(
			new RecordingStore(FabricLoader.getInstance().getGameDir().resolve("minigamebots/recordings")), humanInput);
	private final BrainManager brain = new BrainManager(
			new BrainRuntime(FabricLoader.getInstance().getGameDir().resolve("minigamebots")));
	private final ProfileManager profiles = new ProfileManager(FabricLoader.getInstance().getGameDir().resolve("minigamebots"));
	private final io.github.mudklip.minigamebots.client.practice.ExamManager exams =
			new io.github.mudklip.minigamebots.client.practice.ExamManager();
	private final TrainingManager training = new TrainingManager(brain.runtime(), profiles.store(), recorder.store());
	private final io.github.mudklip.minigamebots.client.profile.FoundationInstaller foundationInstaller =
			new io.github.mudklip.minigamebots.client.profile.FoundationInstaller(brain.runtime(), profiles.foundations());
	private volatile @Nullable Observation lastObservation;
	private long tick;
	private volatile boolean watching;
	public static final String MOD_ID = "minigamebots";
	public static final Logger LOGGER = LoggerFactory.getLogger("MinigameBots");

	private static final List<String> FEATURES = List.of(Protocol.Features.HANDSHAKE, Protocol.Features.EVENTS, Protocol.Features.STATUS,
			Protocol.Features.PUPPET);
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

		// Ground truth from Training Grounds: becomes "tg_<kind>" events with source "server".
		ClientPlayNetworking.registerGlobalReceiver(io.github.mudklip.minigamebots.common.net.TgEventPayload.TYPE,
				(payload, context) -> {
					java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
					try {
						var obj = com.google.gson.JsonParser.parseString(payload.json()).getAsJsonObject();
						for (var e : obj.entrySet()) {
							var v = e.getValue();
							if (v.isJsonPrimitive()) {
								var p = v.getAsJsonPrimitive();
								data.put(e.getKey(), p.isNumber() ? p.getAsDouble() : p.isBoolean() ? p.getAsBoolean() : p.getAsString());
							} else {
								data.put(e.getKey(), v); // Lists (e.g. "inputs") stay JSON and are recorded as they are.
							}
						}
					} catch (RuntimeException e) {
						LOGGER.debug("Bad Training Grounds event {}", payload.kind(), e);
						return;
					}
					events.emitServer("tg_" + payload.kind(), data);
				});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TrainingLink.clear());

		LOGGER.info("MinigameBots {} loaded (protocol v{})", version, Protocol.VERSION);
	}

	/**
	 * Tells Training Grounds whether the AI is in control and how many ticks per second its brain
	 * keeps up with (from the measured round trip), for "match AI speed".
	 */
	private void sendStatus() {
		var link = TrainingLink.get().orElse(null);
		if (link == null || link.result() != Compatibility.Result.COMPATIBLE
				|| !link.server().features().contains(Protocol.Features.STATUS)
				|| !ClientPlayNetworking.canSend(io.github.mudklip.minigamebots.common.net.TgStatusPayload.TYPE)) {
			return;
		}
		boolean active = AgentControl.aiActive();
		float tps = 20;
		if (active && AgentControl.get().source() instanceof io.github.mudklip.minigamebots.client.brain.BrainSource b
				&& b.latencyMs() > 0) {
			tps = (float) Math.max(2, Math.min(20, 1000.0 / (b.latencyMs() + 5)));
		}
		ClientPlayNetworking.send(new io.github.mudklip.minigamebots.common.net.TgStatusPayload(active, tps));
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

	public RelationTracker relations() {
		return relations;
	}

	public io.github.mudklip.minigamebots.client.practice.ExamManager exams() {
		return exams;
	}

	public io.github.mudklip.minigamebots.client.memory.MemoryManager memory() {
		return memory;
	}

	public TrainingManager training() {
		return training;
	}

	public io.github.mudklip.minigamebots.client.profile.FoundationInstaller foundationInstaller() {
		return foundationInstaller;
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
			foundationInstaller.shutdown();
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
		// F1 on any MinigameBots screen opens the guide page about it.
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			String page = io.github.mudklip.minigamebots.client.gui.GuideScreen.PAGE_FOR.get(screen.getClass().getSimpleName());
			if (page == null || !screen.getClass().getName().startsWith("io.github.mudklip.minigamebots")) {
				return;
			}
			net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.afterKeyPress(screen).register((s, key) -> {
				if (key.key() == com.mojang.blaze3d.platform.InputConstants.KEY_F1 && !(s instanceof io.github.mudklip.minigamebots.client.gui.GuideScreen)) {
					client.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.GuideScreen(s, page));
				}
			});
		});
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
		relations.onHitMe(attackerId -> {
			Minecraft client = Minecraft.getInstance();
			if (client.level == null) {
				return;
			}
			var attacker = client.level.getEntity(attackerId);
			if (attacker != null) {
				var data = new java.util.HashMap<String, Object>();
				data.put("attacker", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
				if (attacker instanceof net.minecraft.world.entity.player.Player p) {
					data.put("player", p.getName().getString());
				}
				events.emit(EventTypes.HIT_BY, data);
				memory.onAttackedBy(attacker);
			}
		});
		relations.onHitProtected(attackerId -> {
			Minecraft client = Minecraft.getInstance();
			if (client.level != null) {
				memory.onAttackedBy(client.level.getEntity(attackerId));
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			events.reset();
			text.clear();
			relations.clear();
		});

		events.addListener(recorder::onEvent);
		events.addListener(brain::onEvent);
		events.addListener(exams::onEvent);
		events.addListener(io.github.mudklip.minigamebots.client.control.Autonomy::onEvent);
		events.addListener(io.github.mudklip.minigamebots.client.brain.ChatMind::onEvent);
		events.addListener(io.github.mudklip.minigamebots.client.brain.SelfPlayLink::onEvent);
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
		relations.setTick(tick);
		if (client.level == null || client.player == null) {
			lastObservation = null;
			return;
		}
		if (tick % 20 == 0) {
			sendStatus();
		}
		io.github.mudklip.minigamebots.client.train.TeacherDemos.tick(client);
		io.github.mudklip.minigamebots.client.control.Autonomy.tick(client);
		io.github.mudklip.minigamebots.client.brain.Voice.tick(client,
				io.github.mudklip.minigamebots.client.control.Hotkeys.PUSH_TO_TALK.isDown());
		memory.tick(client);
		// Perception costs CPU, so it only runs while something needs it.
		if (AgentControl.aiActive() || watching || recorder.recording()) {
			Observation obs = observer.observe(client, tick);
			lastObservation = obs;
			if (obs != null) {
				events.onTick(tick, obs, client.level);
				memory.observe(client, obs);
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
