package io.github.mudklip.minigamebots.client.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudAccessor {
	@Accessor("bossOverlay")
	BossHealthOverlay minigamebots$bossOverlay();

	@Accessor("title")
	Component minigamebots$title();

	@Accessor("subtitle")
	Component minigamebots$subtitle();

	@Accessor("overlayMessageString")
	Component minigamebots$actionbar();

	@Accessor("overlayMessageTime")
	int minigamebots$actionbarTime();

	@Accessor("titleTime")
	int minigamebots$titleTime();
}
