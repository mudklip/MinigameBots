package io.github.mudklip.minigamebots.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds a settings button for MinigameBots in Mod Menu (only loaded when Mod Menu is installed). */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return MgbConfig::screen;
	}
}
