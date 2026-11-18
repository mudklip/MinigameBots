package io.github.mudklip.minigamebots.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw access so toggle-mode keys (toggle sprint, toggle sneak) can't flip the AI's input. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("isDown")
	void minigamebots$setDownRaw(boolean down);

	@Accessor("clickCount")
	int minigamebots$getClickCount();

	@Accessor("clickCount")
	void minigamebots$setClickCount(int count);

	@Accessor("key")
	InputConstants.Key minigamebots$getKey();
}
