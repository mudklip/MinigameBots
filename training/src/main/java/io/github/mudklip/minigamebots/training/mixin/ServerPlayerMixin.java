package io.github.mudklip.minigamebots.training.mixin;

import io.github.mudklip.minigamebots.training.TrainingGrounds;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Peaceful heals players by 1 every second, which cancels out most hits in a training match.
 * While a player is in a match round, that Peaceful-only healing is skipped.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "tickRegeneration", at = @At("HEAD"), cancellable = true)
	private void minigamebots$noPeacefulRegenInMatches(CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (self.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL && TrainingGrounds.inMatch(self)) {
			ci.cancel();
		}
	}
}
