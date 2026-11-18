package io.github.mudklip.minigamebots.client.mixin;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the event detector know when the local player attacks or uses an item on a block. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void minigamebots$onAttack(Player player, Entity target, CallbackInfo ci) {
		if (player instanceof LocalPlayer local) {
			MinigameBotsClient.events().onAttack(local, target);
		}
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void minigamebots$onUseOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (cir.getReturnValue().consumesAction()) {
			MinigameBotsClient.events().onUseOnBlock(player, hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()));
		}
	}
}
