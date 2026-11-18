package io.github.mudklip.minigamebots.training.mixin;

import io.github.mudklip.minigamebots.training.AttackDetails;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes how a player's melee attack was made (charge, sprinting, critical) before the game
 * resets those, so the damage event that follows can report the technique used.
 */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void minigamebots$beforeAttack(Entity target, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer p) {
			AttackDetails.begin(p, p.getAttackStrengthScale(0.5f), p.isSprinting());
		}
	}

	@Inject(method = "canCriticalAttack", at = @At("RETURN"))
	private void minigamebots$crit(Entity target, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer p && Boolean.TRUE.equals(cir.getReturnValue())) {
			AttackDetails.crit(p);
		}
	}
}
