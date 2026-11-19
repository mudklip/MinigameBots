package io.github.mudklip.minigamebots.client.mixin;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Damage events name the exact attacker; feed them to the relation tracker. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void minigamebots$onDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		var player = Minecraft.getInstance().player;
		if (player != null) {
			MinigameBotsClient.instance().relations().onDamage(packet.entityId(), packet.sourceCauseId(),
					packet.sourceDirectId(), player.getId());
			if (player.level().getEntity(packet.entityId()) instanceof net.minecraft.world.entity.LivingEntity victim) {
				io.github.mudklip.minigamebots.client.observe.Observer.healthMemory.onDamage(player, victim, packet.sourceCauseId());
			}
		}
	}

	/** Entity event 35 = a totem of undying popped. */
	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void minigamebots$onEntityEvent(net.minecraft.network.protocol.game.ClientboundEntityEventPacket packet, CallbackInfo ci) {
		var mc = Minecraft.getInstance();
		if (packet.getEventId() == 35 && mc.level != null
				&& packet.getEntity(mc.level) instanceof net.minecraft.world.entity.LivingEntity who) {
			io.github.mudklip.minigamebots.client.observe.Observer.healthMemory.onTotem(who);
		}
	}
}
