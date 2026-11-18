package io.github.mudklip.minigamebots.common.net;

import io.github.mudklip.minigamebots.common.MinigameBotsCommon;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Self-play: a linked client steering the Training Grounds dummy that plays a past version of
 * its AI. Sent every tick of a round: which keys the dummy holds and how far it turns.
 * The server only accepts it from the player the dummy was given to.
 */
public record TgPuppetPayload(int entityId, int keys, float yaw, float pitch) implements CustomPacketPayload {
	/** Bit order of {@link #keys}. */
	public static final List<String> KEYS = List.of("forward", "back", "left", "right", "jump", "sprint", "sneak", "attack", "use");

	public static final Type<TgPuppetPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(MinigameBotsCommon.NAMESPACE, "tg/puppet"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TgPuppetPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, TgPuppetPayload::entityId,
			ByteBufCodecs.VAR_INT, TgPuppetPayload::keys,
			ByteBufCodecs.FLOAT, TgPuppetPayload::yaw,
			ByteBufCodecs.FLOAT, TgPuppetPayload::pitch,
			TgPuppetPayload::new);

	public boolean has(String key) {
		int i = KEYS.indexOf(key);
		return i >= 0 && (keys & (1 << i)) != 0;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
