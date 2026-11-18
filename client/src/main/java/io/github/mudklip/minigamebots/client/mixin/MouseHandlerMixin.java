package io.github.mudklip.minigamebots.client.mixin;

import io.github.mudklip.minigamebots.client.control.AgentControl;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the AI is in control: the real mouse is ignored, the OS cursor is never captured, and
 * the camera follows the AI's virtual mouse instead.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private double xpos;

	@Shadow
	private double ypos;

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void minigamebots$onButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void minigamebots$onScroll(long handle, double x, double y, CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void minigamebots$onMove(long handle, double x, double y, double xrel, double yrel, CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "onDrop", at = @At("HEAD"), cancellable = true)
	private void minigamebots$onDrop(long handle, List<String> paths, CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			ci.cancel();
		}
	}

	/** Runs every frame: apply the AI's camera movement instead of the real mouse's. */
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true)
	private void minigamebots$handleMovement(CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			AgentControl.get().camera().onFrame(minecraft);
			ci.cancel();
		}
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void minigamebots$grabMouse(CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			ci.cancel();
		}
	}

	/** The game must think the mouse is captured (needed for mining) without touching the OS cursor. */
	@Inject(method = "isMouseGrabbed", at = @At("HEAD"), cancellable = true)
	private void minigamebots$isMouseGrabbed(CallbackInfoReturnable<Boolean> cir) {
		if (AgentControl.aiActive() && minecraft.gui.screen() == null) {
			cir.setReturnValue(true);
		}
	}

	/** Opening a screen normally snaps to the real OS cursor; the AI's cursor starts centred instead. */
	@Inject(method = "resyncMousePosition", at = @At("HEAD"), cancellable = true)
	private void minigamebots$resync(CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			xpos = minecraft.getWindow().getScreenWidth() / 2.0;
			ypos = minecraft.getWindow().getScreenHeight() / 2.0;
			ci.cancel();
		}
	}
}
