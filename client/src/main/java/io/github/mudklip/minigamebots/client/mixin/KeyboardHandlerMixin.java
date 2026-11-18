package io.github.mudklip.minigamebots.client.mixin;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.control.Hotkeys;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hotkeys always work; every other real key is ignored while the AI is in control. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void minigamebots$keyPress(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (handle != minecraft.getWindow().handle()) {
			return;
		}
		if (!AgentControl.aiActive()) {
			MinigameBotsClient.instance().humanInput().onKey(minecraft, action, event);
		}
		if (Hotkeys.onKey(minecraft, action, event) || AgentControl.aiActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void minigamebots$charTyped(long handle, CharacterEvent event, CallbackInfo ci) {
		if (AgentControl.aiActive()) {
			ci.cancel();
		}
	}
}
