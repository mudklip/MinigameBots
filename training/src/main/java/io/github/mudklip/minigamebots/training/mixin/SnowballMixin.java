package io.github.mudklip.minigamebots.training.mixin;

import io.github.mudklip.minigamebots.training.TrainingGrounds;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Snowball Fight: a snowball reaching a player counts even though it does no damage (CoreGames.onSnowball). */
@Mixin(Snowball.class)
public abstract class SnowballMixin {
	@Inject(method = "onHitEntity", at = @At("TAIL"))
	private void minigamebots$hit(EntityHitResult hit, CallbackInfo ci) {
		Snowball self = (Snowball) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		TrainingGrounds tg = TrainingGrounds.instance();
		if (tg != null && tg.coreGames() != null) {
			tg.coreGames().onSnowball(hit.getEntity(), self.getOwner());
		}
	}
}
