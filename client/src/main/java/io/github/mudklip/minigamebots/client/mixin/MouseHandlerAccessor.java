package io.github.mudklip.minigamebots.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void minigamebots$setXpos(double x);

	@Accessor("ypos")
	void minigamebots$setYpos(double y);
}
