package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileFaces;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.ProfileStore;
import io.github.mudklip.minigamebots.client.profile.SkinFetcher;
import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Create or edit an AI: name, description, skin (from any Minecraft username) and its own human
 * limits. Changes are saved with Done.
 */
public final class EditProfileScreen extends Screen {
	private final ProfilesScreen parent;
	private final ProfileStore store;
	private final ProfileFaces faces = new ProfileFaces();
	private final ProfileManifest working;
	private final boolean creating;

	private EditBox name;
	private EditBox description;
	private EditBox skinName;
	private Button fetch;
	private String skinStatus = "";
	private boolean fetching;
	private int previewX;
	private int previewY;

	/** {@code profile} null = make a new AI. */
	public EditProfileScreen(ProfilesScreen parent, @Nullable ProfileManifest profile) {
		super(Component.translatable(profile == null ? "minigamebots.profiles.create_title" : "minigamebots.profiles.edit_title"));
		this.parent = parent;
		this.store = MinigameBotsClient.instance().profiles().store();
		this.creating = profile == null;
		if (profile == null) {
			try {
				String version = FabricLoader.getInstance().getModContainer("minigamebots")
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
				profile = store.create("New AI", version);
				ProfileManifest.Humanizer defaults = ProfileManifest.Humanizer.from(
						io.github.mudklip.minigamebots.client.config.MgbConfig.get().humanizer().enabled()
								? io.github.mudklip.minigamebots.client.config.MgbConfig.get().humanizer()
								: io.github.mudklip.minigamebots.client.control.HumanizerSettings.DEFAULT);
				profile.humanizer = defaults;
			} catch (IOException e) {
				throw new IllegalStateException("Couldn't create a profile", e);
			}
		}
		this.working = profile;
	}

	@Override
	protected void init() {
		int w = Math.min(300, width - 120);
		int x = (width - w) / 2 + 30;
		int y = 30;
		name = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.translatable("minigamebots.recorder.name")));
		name.setMaxLength(40);
		name.setValue(working.name);
		description = addRenderableWidget(new EditBox(font, x, y + 30, w, 18, Component.translatable("minigamebots.profiles.description")));
		description.setMaxLength(200);
		description.setValue(working.description);
		skinName = addRenderableWidget(new EditBox(font, x, y + 60, w - 74, 18, Component.translatable("minigamebots.profiles.skin")));
		skinName.setMaxLength(16);
		skinName.setValue(working.skin.username);
		fetch = addRenderableWidget(Button.builder(Component.translatable("minigamebots.profiles.fetch_skin"), b -> fetchSkin())
				.pos(x + w - 70, y + 59).width(70).build());
		previewX = (width - w) / 2 - 30;
		previewY = y + 2;

		// This AI's own human limits.
		int sy = y + 104;
		int half = (w - 4) / 2;
		ProfileManifest.Humanizer h = working.humanizer;
		addRenderableWidget(Checkbox.builder(Component.translatable("minigamebots.profiles.humanizer_on"), font)
				.pos(x, sy).selected(h.enabled).onValueChange((c, on) -> h.enabled = on).build());
		addRenderableWidget(new ValueSlider(half, "Reaction", 0, 500, 10, h.reactionMs, v -> (int) v + " ms",
				v -> h.reactionMs = (int) v)).setPosition(x, sy + 24);
		addRenderableWidget(new ValueSlider(half, "Max CPS", 1, 20, 1, h.maxCps, v -> String.valueOf((int) v),
				v -> h.maxCps = (int) v)).setPosition(x + half + 4, sy + 24);
		addRenderableWidget(new ValueSlider(half, "Turn speed", 100, 3000, 50, h.maxTurnSpeed, v -> (int) v + "°/s",
				v -> h.maxTurnSpeed = (float) v)).setPosition(x, sy + 48);
		addRenderableWidget(new ValueSlider(half, "Aim wobble", 0, 1, 0.01, h.aimNoise, v -> String.format("%.2f°", v),
				v -> h.aimNoise = (float) v)).setPosition(x + half + 4, sy + 48);

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> save()).pos(width / 2 - 104, height - 28).width(100).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> cancel()).pos(width / 2 + 4, height - 28).width(100).build());
		setInitialFocus(name);
	}

	private void fetchSkin() {
		String user = skinName.getValue().trim();
		if (user.isEmpty()) {
			clearSkin();
			return;
		}
		fetching = true;
		fetch.active = false;
		skinStatus = Component.translatable("minigamebots.profiles.fetching").getString();
		MinigameBotsClient.instance().profiles().skins().fetch(user, store.dir(working.id).resolve(ProfileStore.SKIN))
				.thenAccept(result -> minecraft.execute(() -> {
					fetching = false;
					fetch.active = true;
					skinStatus = result.message();
					working.skin.username = user;
					if (result.outcome() == SkinFetcher.Outcome.OK) {
						working.skin.uuid = result.uuid();
						working.skin.slim = result.slim();
						working.skin.fetched = OffsetDateTime.now().toString();
					} else if (result.outcome() == SkinFetcher.Outcome.NO_SKIN || result.outcome() == SkinFetcher.Outcome.NO_SUCH_PLAYER) {
						deleteSkinFile();
					}
					// Offline: keep whatever skin was cached before.
				}));
	}

	private void clearSkin() {
		working.skin = new ProfileManifest.Skin();
		deleteSkinFile();
		skinStatus = Component.translatable("minigamebots.profiles.default_skin").getString();
	}

	private void deleteSkinFile() {
		try {
			Files.deleteIfExists(store.dir(working.id).resolve(ProfileStore.SKIN));
		} catch (IOException ignored) {
			// Shows the old skin until next time.
		}
	}

	private void save() {
		String n = name.getValue().trim();
		working.name = n.isEmpty() ? "New AI" : n;
		working.description = description.getValue().trim();
		String user = skinName.getValue().trim();
		boolean haveSkin = Files.exists(store.dir(working.id).resolve(ProfileStore.SKIN));
		if (user.isEmpty()) {
			if (!working.skin.username.isEmpty()) {
				clearSkin();
			}
		} else if (!user.equalsIgnoreCase(working.skin.username) || !haveSkin && !fetching) {
			// Remember the name now; the skin is fetched in the background (or later, when online).
			working.skin = new ProfileManifest.Skin();
			working.skin.username = user;
			deleteSkinFile();
			MinigameBotsClient.instance().profiles().fetchSkinLater(working);
		}
		try {
			store.save(working);
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.error("Couldn't save profile {}", working.id, e);
		}
		parent.select(working.id);
		if (creating && MinigameBotsClient.instance().profiles().active() == null) {
			MinigameBotsClient.instance().profiles().setActive(working.id);
		}
		minecraft.gui.setScreen(parent);
	}

	private void cancel() {
		if (creating) {
			try {
				store.delete(working.id);
			} catch (IOException ignored) {
				// Leaves an empty profile behind; harmless.
			}
		}
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		cancel();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		int labelX = name.getX();
		g.text(font, Component.translatable("minigamebots.recorder.name"), labelX, name.getY() - 10, 0xFFA0A0A0, false);
		g.text(font, Component.translatable("minigamebots.profiles.description"), labelX, description.getY() - 10, 0xFFA0A0A0, false);
		g.text(font, Component.translatable("minigamebots.profiles.skin"), labelX, skinName.getY() - 10, 0xFFA0A0A0, false);
		g.text(font, font.plainSubstrByWidth(skinStatus, width - labelX - 10), labelX, skinName.getY() + 22, 0xFFB0B0B0, false);
		g.text(font, Component.translatable("minigamebots.profiles.limits"), labelX, skinName.getY() + 34, 0xFFA0A0A0, false);
		g.fill(previewX - 2, previewY - 2, previewX + 50, previewY + 50, 0xFF6A6A6A);
		g.fill(previewX - 1, previewY - 1, previewX + 49, previewY + 49, 0xFF8B8B8B);
		faces.draw(g, store, working, previewX, previewY, 48);
	}

	@Override
	public void removed() {
		faces.close();
	}
}
