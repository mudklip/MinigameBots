package io.github.mudklip.minigamebots.common.net;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A request from a linked client to Training Grounds that is too big for a command, e.g. a design
 * to test in the sandbox ({@code kind} "sandbox_test", {@code json} the design). Answers come back
 * as TgEventPayload events.
 */
public record TgRequestPayload(String kind, String json) implements CustomPacketPayload {
	public static final int MAX_JSON = 32000;

	public static final Type<TgRequestPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(MinigameBotsCommon.NAMESPACE, "tg/request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TgRequestPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(32), TgRequestPayload::kind,
			ByteBufCodecs.stringUtf8(MAX_JSON), TgRequestPayload::json,
			TgRequestPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
