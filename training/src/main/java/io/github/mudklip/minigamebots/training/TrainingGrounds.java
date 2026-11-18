package io.github.mudklip.minigamebots.training;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import io.github.mudklip.minigamebots.common.Protocol;
import io.github.mudklip.minigamebots.common.net.Compatibility;
import io.github.mudklip.minigamebots.common.net.HelloPayload;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MinigameBots Training Grounds: server-side training rooms. Also runs inside singleplayer. */
public final class TrainingGrounds implements ModInitializer {
	public static final String MOD_ID = "minigamebots-training";
	public static final Logger LOGGER = LoggerFactory.getLogger("MinigameBots/TrainingGrounds");

	private static final List<String> FEATURES = List.of(Protocol.Features.HANDSHAKE);

	private final LinkedClients linkedClients = new LinkedClients();

	@Override
	public void onInitialize() {
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

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				linkedClients.remove(handler.getPlayer().getUUID()));

		LOGGER.info("Training Grounds {} loaded", version);
	}
}
