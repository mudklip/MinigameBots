package io.github.mudklip.minigamebots.client.profile;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;

/** Draws a profile's face (with hat layer) from its cached skin.png, or the default skin. */
public final class ProfileFaces {
	private record Loaded(Identifier id, DynamicTexture texture, long modified) {
	}

	private final Map<String, Loaded> loaded = new HashMap<>();

	public void draw(GuiGraphicsExtractor graphics, ProfileStore store, ProfileManifest profile, int x, int y, int size) {
		PlayerFaceExtractor.extractRenderState(graphics, texture(store, profile), x, y, size, true, false, -1);
	}

	private Identifier texture(ProfileStore store, ProfileManifest profile) {
		Path file = store.dir(profile.id).resolve(ProfileStore.SKIN);
		if (!Files.isRegularFile(file)) {
			return DefaultPlayerSkin.getDefaultTexture();
		}
		long modified;
		try {
			modified = Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			return DefaultPlayerSkin.getDefaultTexture();
		}
		Loaded l = loaded.get(profile.id);
		if (l != null && l.modified() == modified) {
			return l.id();
		}
		try (InputStream in = Files.newInputStream(file)) {
			NativeImage image = NativeImage.read(in);
			Identifier id = Identifier.fromNamespaceAndPath("minigamebots", "profile_skin/" + profile.id.replaceAll("[^a-z0-9_-]", "_"));
			DynamicTexture texture = new DynamicTexture(() -> "MinigameBots skin " + profile.id, image);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			if (l != null && !l.id().equals(id)) {
				Minecraft.getInstance().getTextureManager().release(l.id());
			}
			loaded.put(profile.id, new Loaded(id, texture, modified));
			return id;
		} catch (IOException | RuntimeException e) {
			MinigameBotsClient.LOGGER.warn("Bad skin for profile {}", profile.id, e);
			return DefaultPlayerSkin.getDefaultTexture();
		}
	}

	/** Frees all skin textures (when the screen closes). */
	public void close() {
		for (Loaded l : loaded.values()) {
			Minecraft.getInstance().getTextureManager().release(l.id());
		}
		loaded.clear();
	}
}
