package io.github.mudklip.minigamebots.client.mixin;

import io.github.mudklip.minigamebots.client.gui.StyledButtons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mod's button look, inside its own screens only (StyledButtons). */
@Mixin(Button.Plain.class)
public abstract class ButtonStyleMixin {
	@Inject(method = "extractContents", at = @At("HEAD"), cancellable = true)
	private void minigamebots$style(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (StyledButtons.active()) {
			StyledButtons.draw((Button) (Object) this, graphics);
			ci.cancel();
		}
	}
}
