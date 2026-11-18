package io.github.mudklip.minigamebots.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft drops to 10 FPS when minimized or idle. The AI needs steady frames to see, so while
 * it's in control the frame rate is fixed (and capped, to keep the PC quiet).
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void minigamebots$aiFrameRate(CallbackInfoReturnable<Integer> cir) {
		if (AgentControl.aiActive()) {
			cir.setReturnValue(MgbConfig.get().aiFrameRate);
		}
	}

	@Inject(method = "isHeavilyThrottled", at = @At("HEAD"), cancellable = true)
	private void minigamebots$notThrottled(CallbackInfoReturnable<Boolean> cir) {
		if (AgentControl.aiActive()) {
			cir.setReturnValue(false);
		}
	}
}
