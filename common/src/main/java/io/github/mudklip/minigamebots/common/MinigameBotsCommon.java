package io.github.mudklip.minigamebots.common;

import io.github.mudklip.minigamebots.common.net.HelloPayload;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared code used by both the MinigameBots client mod and the Training Grounds server mod.
 * It ships inside both jars; Fabric loads it once even when both mods are installed.
 *
 * <p>Fabric doesn't guarantee which mod initializes first, so instead of having its own
 * entrypoint, each mod calls {@link #init()} at the start of its initializer. Only the first
 * call does anything.
 */
public final class MinigameBotsCommon {
	public static final String NAMESPACE = "minigamebots";
	public static final Logger LOGGER = LoggerFactory.getLogger("MinigameBots/Common");

	private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

	public static void init() {
		if (!INITIALIZED.compareAndSet(false, true)) {
			return;
		}
		// The hello payload travels both ways: server -> client first, client answers.
		PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
		LOGGER.info("MinigameBots protocol v{} registered", Protocol.VERSION);
	}

	private MinigameBotsCommon() {
	}
}
