package io.github.mudklip.minigamebots.client.profile;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.HumanizerSettings;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The profile store plus which AI is currently selected. */
public final class ProfileManager {
	private final ProfileStore store;
	private final FoundationStore foundations;
	private final SkinFetcher skins = new SkinFetcher();
	/** Profiles whose skin lookup already ran this session (so we don't hammer Mojang). */
	private final Set<String> tried = new HashSet<>();

	public ProfileManager(Path minigamebotsDir) {
		this.store = new ProfileStore(minigamebotsDir.resolve("profiles"));
		this.foundations = new FoundationStore(minigamebotsDir.resolve("foundations"));
	}

	public ProfileStore store() {
		return store;
	}

	public FoundationStore foundations() {
		return foundations;
	}

	public SkinFetcher skins() {
		return skins;
	}

	/**
	 * Fetches the skin for a profile that has a username but no cached skin yet (for example one
	 * set while offline). Saves the result into the profile. At most once per session per profile.
	 */
	public void fetchSkinLater(ProfileManifest profile) {
		String user = profile.skin.username;
		if (user.isEmpty() || !tried.add(profile.id + "/" + user.toLowerCase(java.util.Locale.ROOT))) {
			return;
		}
		Path target = store.dir(profile.id).resolve(ProfileStore.SKIN);
		skins.fetch(user, target).thenAccept(result -> {
			if (result.outcome() != SkinFetcher.Outcome.OK) {
				// Offline or no such player: try again next session.
				if (result.outcome() == SkinFetcher.Outcome.OFFLINE) {
					tried.remove(profile.id + "/" + user.toLowerCase(java.util.Locale.ROOT));
				}
				return;
			}
			ProfileManifest current = store.get(profile.id);
			if (current == null || !user.equalsIgnoreCase(current.skin.username)) {
				return;
			}
			current.skin.uuid = result.uuid();
			current.skin.slim = result.slim();
			current.skin.fetched = OffsetDateTime.now().toString();
			try {
				store.save(current);
			} catch (IOException e) {
				MinigameBotsClient.LOGGER.warn("Couldn't save skin info for {}", profile.id, e);
			}
		});
	}

	/** Retries skins that couldn't be fetched before (called when the Profiles screen opens). */
	public void refreshMissingSkins() {
		for (ProfileManifest p : store.list()) {
			if (!p.skin.username.isEmpty() && !Files.exists(store.dir(p.id).resolve(ProfileStore.SKIN))) {
				fetchSkinLater(p);
			}
		}
	}

	/** The selected AI, or null if none is chosen (or it was deleted). */
	public @Nullable ProfileManifest active() {
		return store.get(MgbConfig.get().activeProfile);
	}

	public void setActive(@Nullable String id) {
		MgbConfig.get().activeProfile = id == null ? "" : id;
		MgbConfig.HANDLER.save();
	}

	/** Human limits to use: the active AI's own, or the global settings when no AI is selected. */
	public HumanizerSettings humanizer() {
		ProfileManifest p = active();
		return p == null ? MgbConfig.get().humanizer() : p.humanizer.toSettings();
	}

	/** Shown when control starts, e.g. "Steve Jr" or "AI". */
	public String displayName() {
		ProfileManifest p = active();
		return p == null ? "AI" : p.name;
	}
}
