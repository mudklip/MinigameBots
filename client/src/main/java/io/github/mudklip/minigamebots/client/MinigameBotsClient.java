package io.github.mudklip.minigamebots.client;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import io.github.mudklip.minigamebots.common.Protocol;
import io.github.mudklip.minigamebots.common.net.Compatibility;
import io.github.mudklip.minigamebots.common.net.HelloPayload;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MinigameBots client mod: the AI and everything around it. */
public final class MinigameBotsClient implements ClientModInitializer {
	public static final String MOD_ID = "minigamebots";
	public static final Logger LOGGER = LoggerFactory.getLogger("MinigameBots");

	private static final List<String> FEATURES = List.of(Protocol.Features.HANDSHAKE);
	private static final SystemToast.SystemToastId LINK_TOAST = new SystemToast.SystemToastId();

	@Override
	public void onInitializeClient() {
		MinigameBotsCommon.init();

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
