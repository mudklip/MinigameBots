package io.github.mudklip.minigamebots.client.mixin;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.observe.FrameCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** Don't open the pause menu when the window loses focus while the AI is playing. */
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void minigamebots$keepRunning(CallbackInfo ci) {
		if (AgentControl.aiActive() && MgbConfig.get().keepRunningUnfocused) {
			ci.cancel();
		}
	}

	/** Grab the AI's view of the frame right after it has been drawn (including the HUD). */
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
	private void minigamebots$afterRender(boolean advanceGameTime, CallbackInfo ci) {
		FrameCapture.get().afterRender((Minecraft) (Object) this);
	}
}
