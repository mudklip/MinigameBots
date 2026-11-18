package io.github.mudklip.minigamebots.common.net;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Ground truth from Training Grounds to a linked client: exact damage, kills, aim-trainer and
 * arena events. {@code kind} names the event; {@code json} is a small JSON object with its fields
 * (see docs/formats/training-events.md). Only sent to clients that advertised
 * {@link io.github.mudklip.minigamebots.common.Protocol.Features#EVENTS}.
 */
public record TgEventPayload(String kind, String json) implements CustomPacketPayload {
	public static final int MAX_KIND = 32;
	public static final int MAX_JSON = 4096;

	public static final Type<TgEventPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(MinigameBotsCommon.NAMESPACE, "tg/event"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TgEventPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_KIND), TgEventPayload::kind,
			ByteBufCodecs.stringUtf8(MAX_JSON), TgEventPayload::json,
			TgEventPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
