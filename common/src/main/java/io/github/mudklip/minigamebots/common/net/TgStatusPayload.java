package io.github.mudklip.minigamebots.common.net;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent by a linked client about once a second: whether its AI is in control and how many game
 * ticks per second its brain can keep up with. Training Grounds uses it for "match AI speed",
 * slowing the game down so a slow brain still gets to decide every tick.
 */
public record TgStatusPayload(boolean aiActive, float sustainableTps) implements CustomPacketPayload {
	public static final Type<TgStatusPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(MinigameBotsCommon.NAMESPACE, "tg/status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TgStatusPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, TgStatusPayload::aiActive,
			ByteBufCodecs.FLOAT, TgStatusPayload::sustainableTps,
			TgStatusPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
