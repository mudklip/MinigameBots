package io.github.mudklip.minigamebots.common.net;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import io.github.mudklip.minigamebots.common.Protocol;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Handshake between the client mod and Training Grounds. The server sends one when a player
 * joins; a client with MinigameBots answers with its own.
 */
public record HelloPayload(int protocol, int minCompatible, String modVersion, List<String> features)
		implements CustomPacketPayload {
	private static final int MAX_FEATURES = 64;
	private static final int MAX_STRING = 64;

	public static final Type<HelloPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(MinigameBotsCommon.NAMESPACE, "tg/hello"));

	public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, HelloPayload::protocol,
			ByteBufCodecs.VAR_INT, HelloPayload::minCompatible,
			ByteBufCodecs.stringUtf8(MAX_STRING), HelloPayload::modVersion,
			ByteBufCodecs.stringUtf8(MAX_STRING).apply(ByteBufCodecs.list(MAX_FEATURES)), HelloPayload::features,
			HelloPayload::new);

	public HelloPayload {
		features = List.copyOf(features);
	}

	/** A hello describing this side, with the current protocol version. */
	public static HelloPayload local(String modVersion, List<String> features) {
		return new HelloPayload(Protocol.VERSION, Protocol.MIN_COMPATIBLE, modVersion, features);
	}

	public Compatibility.Version version() {
		return new Compatibility.Version(protocol, minCompatible);
	}

	public static Compatibility.Version localVersion() {
		return new Compatibility.Version(Protocol.VERSION, Protocol.MIN_COMPATIBLE);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
