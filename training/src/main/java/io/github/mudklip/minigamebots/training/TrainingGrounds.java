package io.github.mudklip.minigamebots.training;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import io.github.mudklip.minigamebots.common.Protocol;
import io.github.mudklip.minigamebots.common.net.Compatibility;
import io.github.mudklip.minigamebots.common.net.HelloPayload;
import io.github.mudklip.minigamebots.training.aim.AimTrainer;
import io.github.mudklip.minigamebots.training.arena.Arena;
import io.github.mudklip.minigamebots.training.arena.ArenaManager;
import io.github.mudklip.minigamebots.training.dummy.DamageNumbers;
import io.github.mudklip.minigamebots.training.dummy.DummyManager;
import io.github.mudklip.minigamebots.training.menu.TgMenus;
import com.google.gson.JsonObject;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.Mannequin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MinigameBots Training Grounds: server-side training rooms. Also runs inside singleplayer. */
public final class TrainingGrounds implements ModInitializer {
	public static final String MOD_ID = "minigamebots-training";
	public static final Logger LOGGER = LoggerFactory.getLogger("MinigameBots/TrainingGrounds");

	private static final List<String> FEATURES = List.of(Protocol.Features.HANDSHAKE, Protocol.Features.EVENTS,
			Protocol.Features.STATUS, Protocol.Features.PUPPET);
	private static TrainingGrounds instance;

	private final LinkedClients linkedClients = new LinkedClients();
	private final GroundTruth truth = new GroundTruth(linkedClients);
	private final ArenaManager arenas = new ArenaManager();
	private final DummyManager dummies = new DummyManager(truth);
	private final AimTrainer aim = new AimTrainer(truth);
	private final io.github.mudklip.minigamebots.training.scenario.ScenarioManager scenarios =
			new io.github.mudklip.minigamebots.training.scenario.ScenarioManager(arenas, dummies, truth);
	private final TgMenus menus = new TgMenus(this);

	public static TrainingGrounds instance() {
		return instance;
	}

	public GroundTruth truth() {
		return truth;
	}

	public ArenaManager arenas() {
		return arenas;
	}

	public DummyManager dummies() {
		return dummies;
	}

	public AimTrainer aim() {
		return aim;
	}

	public io.github.mudklip.minigamebots.training.scenario.ScenarioManager scenarios() {
		return scenarios;
	}

	public TgMenus menus() {
		return menus;
	}

	/** Arenas and dummies change the world: operators, or the owner of a singleplayer world. */
	public boolean canManage(CommandSourceStack source) {
		if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			return true;
		}
		ServerPlayer p = source.getPlayer();
		return p != null && source.getServer().isSingleplayerOwner(p.nameAndId());
	}

	/** Resets an arena and tells linked clients inside it. */
	public List<ServerPlayer> resetArena(Arena a) throws ArenaManager.ArenaException {
		List<ServerPlayer> players = arenas.reset(a);
		JsonObject j = new JsonObject();
		j.addProperty("arena", a.name);
		for (ServerPlayer p : players) {
			truth.send(p, "arena_reset", j);
		}
		return players;
	}

	/** True while the player is playing in a match round (not knocked out). */
	public static boolean inMatch(ServerPlayer player) {
		return instance != null && instance.scenarios.playing(player);
	}

	/** Called by the mixin for every left click a player makes. */
	public static void onClick(ServerPlayer player) {
		if (instance != null) {
			instance.aim.onClick(player);
		}
	}

	@Override
	public void onInitialize() {
		instance = this;
		MinigameBotsCommon.init();

		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		HelloPayload hello = HelloPayload.local(version, FEATURES);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			// Only players running the MinigameBots client mod can receive this; others are left alone.
			if (ServerPlayNetworking.canSend(handler.getPlayer(), HelloPayload.TYPE)) {
				sender.sendPacket(hello);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Compatibility.Result result = Compatibility.check(HelloPayload.localVersion(), payload.version());
			linkedClients.put(player.getUUID(), new LinkedClients.Link(payload, result));
			if (result == Compatibility.Result.COMPATIBLE) {
				LOGGER.info("Linked MinigameBots client {} (mod {}, protocol v{})",
						player.getPlainTextName(), payload.modVersion(), payload.protocol());
			} else {
				LOGGER.warn("MinigameBots client {} uses incompatible protocol v{} ({}); link disabled",
						player.getPlainTextName(), payload.protocol(), result);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(io.github.mudklip.minigamebots.common.net.TgStatusPayload.TYPE,
				(payload, context) -> scenarios.onStatus(context.player(), payload.aiActive(), payload.sustainableTps()));

		ServerPlayNetworking.registerGlobalReceiver(io.github.mudklip.minigamebots.common.net.TgPuppetPayload.TYPE,
				(payload, context) -> dummies.control(context.player(), payload));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			linkedClients.remove(handler.getPlayer().getUUID());
			aim.stop(handler.getPlayer(), false);
			scenarios.onDisconnect(handler.getPlayer());
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			arenas.load(server);
			dummies.load(server);
			scenarios.load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			scenarios.shutdown();
			aim.clear();
			dummies.unload();
		});
		// Leftover targets and damage numbers (e.g. after a crash) are removed when they load.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (DamageNumbers.isLeftover(entity)) {
				entity.discard();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			dummies.tick();
			aim.tick(server.getPlayerList().getPlayers());
			scenarios.tick();
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> dummies.allowDamage(entity, source));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
				scenarios.allowDeath(entity, source) && dummies.allowDeath(entity));
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			truth.onDamage(entity, source, taken, blocked);
			dummies.afterDamage(entity, source, taken, blocked);
			scenarios.onDamage(entity, source, blocked ? 0 : taken);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			truth.onDeath(entity, source);
			scenarios.afterDeath(entity, source);
			dummies.afterDeath(entity);
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer sp && aim.onAttack(sp, entity)) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer sp && player.isShiftKeyDown()
					&& entity instanceof Mannequin m && dummies.isDummy(m) && canManage(sp.createCommandSourceStack())) {
				menus.dummy(sp, m);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> TgCommands.register(dispatcher, this));

		LOGGER.info("Training Grounds {} loaded", version);
	}
}
