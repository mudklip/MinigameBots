package io.github.mudklip.minigamebots.client.mixin;

import io.github.mudklip.minigamebots.client.gui.StyledButtons;
import io.github.mudklip.minigamebots.client.gui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sliders in the mod's own screens: a card with the label, a thin track and a knob. */
@Mixin(AbstractSliderButton.class)
public abstract class SliderStyleMixin {
	@Shadow
	protected double value;

	@Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
	private void minigamebots$style(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (!StyledButtons.active()) {
			return;
		}
		AbstractWidget w = (AbstractWidget) (Object) this;
		var font = Minecraft.getInstance().font;
		int x = w.getX(), y = w.getY(), wd = w.getWidth(), h = w.getHeight();
		boolean hover = w.isHoveredOrFocused();
		g.fill(x, y, x + wd, y + h, hover ? Ui.CARD_HOVER : Ui.CARD);
		g.outline(x, y, wd, h, hover ? Ui.ACCENT : Ui.BORDER);
		Component msg = w.getMessage();
		g.text(font, font.plainSubstrByWidth(msg.getString(), wd - 8), x + 5, y + 3, Ui.TEXT, false);
		int ty = y + h - 5, tx = x + 5, tw = wd - 10;
		g.fill(tx, ty, tx + tw, ty + 2, 0xFF3A414D);
		g.fill(tx, ty, tx + (int) (tw * value), ty + 2, Ui.ACCENT);
		int kx = tx + (int) (tw * value);
		g.fill(kx - 2, ty - 3, kx + 2, ty + 5, hover ? Ui.TEXT : Ui.ACCENT);
		ci.cancel();
	}
}
