package io.github.mudklip.minigamebots.training.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The mannequin's label line under its name (vanilla shows "NPC"); dummies show their stats there. */
@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Invoker("setDescription")
	void minigamebots$setDescription(Component description);
}
