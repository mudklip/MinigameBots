package io.github.mudklip.minigamebots.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen sits, so the AI knows where each slot is (to move its cursor there). */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("leftPos")
	int minigamebots$leftPos();

	@Accessor("topPos")
	int minigamebots$topPos();
}
