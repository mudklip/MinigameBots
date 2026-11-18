package io.github.mudklip.minigamebots.client.gui;

import com.mojang.blaze3d.Blaze3D;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileFaces;
import io.github.mudklip.minigamebots.client.profile.ProfileManager;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.ProfileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Pick, create and manage AIs. Each AI is a card with its skin's face; the one in use is
 * marked. Double-click a card to use it.
 */
public final class ProfilesScreen extends Screen {
	private static final int CARD_W = 92;
	private static final int CARD_H = 100;
	private static final int GAP = 8;
	private static final int TOP = 36;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private final @Nullable Screen parent;
	private final ProfileManager profiles;
	private final ProfileFaces faces = new ProfileFaces();
	private List<ProfileManifest> all = List.of();
	private @Nullable String selected;
	private int page;
	private int columns;
	private int perPage;
	private long lastClick;
	private Button use;
	private Button edit;
	private Button duplicate;
	private Button delete;
	private Button export;
	private Button train;
	private Button prev;
	private Button next;

	public ProfilesScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.profiles.title"));
		this.parent = parent;
		this.profiles = MinigameBotsClient.instance().profiles();
	}

	@Override
	protected void init() {
		profiles.refreshMissingSkins();
		all = profiles.store().list();
		ProfileManifest active = profiles.active();
		if (selected == null && active != null) {
			selected = active.id;
		}
		columns = Math.max(1, (width - 20 + GAP) / (CARD_W + GAP));
		int rows = Math.max(1, (height - TOP - 64 + GAP) / (CARD_H + GAP));
		// The "New AI" card is treated as one more item after the last profile.
		perPage = columns * rows;
		page = Math.min(page, (all.size()) / perPage);

		int bw = 84;
		int y1 = height - 52;
		int x = width / 2 - (bw * 4 + 12) / 2;
		use = addRenderableWidget(Button.builder(Component.translatable("minigamebots.profiles.use"), b -> useSelected())
				.pos(x, y1).width(bw).build());
		edit = addRenderableWidget(Button.builder(Component.translatable("minigamebots.recorder.edit"), b -> {
			ProfileManifest p = selectedProfile();
			if (p != null) {
				minecraft.gui.setScreen(new EditProfileScreen(this, p));
			}
		}).pos(x + bw + 4, y1).width(bw).build());
		duplicate = addRenderableWidget(Button.builder(Component.translatable("minigamebots.profiles.duplicate"),
				b -> duplicateSelected()).pos(x + 2 * (bw + 4), y1).width(bw).build());
		delete = addRenderableWidget(Button.builder(Component.translatable("minigamebots.recorder.delete"), b -> deleteSelected())
				.pos(x + 3 * (bw + 4), y1).width(bw).build());

		// Second row: six equal buttons under the four above.
		int y2 = height - 28;
		int rowW = bw * 4 + 12;
		int sw = (rowW - 5 * 4) / 6;
		int[] col = new int[6];
		for (int i = 0; i < 6; i++) {
			col[i] = x + i * (sw + 4);
		}
		train = addRenderableWidget(Button.builder(Component.translatable("minigamebots.profiles.train"), b -> {
			ProfileManifest p = selectedProfile();
			if (p != null) {
				minecraft.gui.setScreen(new TrainingScreen(this, p));
			}
		}).pos(col[0], y2).width(sw).build());
		Button merge = addRenderableWidget(Button.builder(Component.translatable("minigamebots.profiles.merge"), b -> { })
				.pos(col[1], y2).width(sw).tooltip(Tooltip.create(Component.translatable("minigamebots.profiles.later", 10))).build());
		merge.active = false;
		export = addRenderableWidget(Button.builder(Component.translatable("minigamebots.recorder.export"), b -> exportSelected())
				.pos(col[2], y2).width(sw).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.recorder.import"), b -> importAll())
				.pos(col[3], y2).width(sw).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.profiles.folder"), b -> openFolder())
				.pos(col[4], y2).width(sw).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.pos(col[5], y2).width(sw).build());

		prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> page--).pos(4, height / 2 - 10).width(14).build());
		next = addRenderableWidget(Button.builder(Component.literal(">"), b -> page++).pos(width - 18, height / 2 - 10).width(14).build());
		updateButtons();
	}

	private void updateButtons() {
		boolean has = selectedProfile() != null;
		use.active = has;
		edit.active = has;
		duplicate.active = has;
		delete.active = has;
		export.active = has;
		train.active = has;
		prev.visible = page > 0;
		next.visible = (page + 1) * perPage < all.size() + 1;
		page = Math.min(page, all.size() / perPage);
	}

	private int ticks;

	@Override
	public void tick() {
		// Pick up skins that finished downloading in the background.
		if (++ticks % 20 == 0) {
			all = profiles.store().list();
		}
		updateButtons();
	}

	private @Nullable ProfileManifest selectedProfile() {
		for (ProfileManifest p : all) {
			if (p.id.equals(selected)) {
				return p;
			}
		}
		return null;
	}

	private void useSelected() {
		ProfileManifest p = selectedProfile();
		if (p != null) {
			profiles.setActive(p.id);
			toast(Component.translatable("minigamebots.profiles.now_using", p.name));
		}
	}

	private void duplicateSelected() {
		ProfileManifest p = selectedProfile();
		if (p == null) {
			return;
		}
		try {
			ProfileManifest copy = profiles.store().duplicate(p, p.name + " (copy)");
			selected = copy.id;
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.error("Couldn't duplicate {}", p.id, e);
			toast(Component.translatable("minigamebots.recorder.failed"));
		}
		rebuildWidgets();
	}

	private void deleteSelected() {
		ProfileManifest p = selectedProfile();
		if (p == null) {
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				try {
					profiles.store().delete(p.id);
					if (p.id.equals(io.github.mudklip.minigamebots.client.config.MgbConfig.get().activeProfile)) {
						profiles.setActive(null);
					}
					selected = null;
				} catch (IOException e) {
					MinigameBotsClient.LOGGER.error("Couldn't delete {}", p.id, e);
				}
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("minigamebots.profiles.delete_title"),
				Component.translatable("minigamebots.profiles.delete_message", p.name)));
	}

	private void exportSelected() {
		ProfileManifest p = selectedProfile();
		if (p == null) {
			return;
		}
		try {
			var file = profiles.store().export(p, false);
			toast(Component.translatable("minigamebots.recorder.exported", file.getFileName().toString()));
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.error("Couldn't export {}", p.id, e);
			toast(Component.translatable("minigamebots.recorder.failed"));
		}
	}

	private void importAll() {
		try {
			int n = profiles.store().importAll();
			if (n == 0) {
				toast(Component.translatable("minigamebots.profiles.import_none"));
				Blaze3D.openPath(profiles.store().importDir());
			} else {
				toast(Component.translatable("minigamebots.profiles.imported", n));
			}
		} catch (IOException | RuntimeException e) {
			MinigameBotsClient.LOGGER.error("Couldn't import profiles", e);
			toast(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage()));
		}
		rebuildWidgets();
	}

	private void openFolder() {
		try {
			Files.createDirectories(profiles.store().root());
		} catch (IOException ignored) {
			// Opening shows the problem.
		}
		Blaze3D.openPath(profiles.store().root());
	}

	private void toast(Component body) {
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, Component.translatable("minigamebots.profiles.title"), body);
	}

	// ---- Cards ----

	private int cardX(int index) {
		int gridW = columns * CARD_W + (columns - 1) * GAP;
		return (width - gridW) / 2 + (index % columns) * (CARD_W + GAP);
	}

	private int cardY(int index) {
		return TOP + (index / columns) * (CARD_H + GAP);
	}

	/** Position in the list (profiles, then the "New AI" card) of this page's first card. */
	private int pageStart() {
		return page * perPage;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		ProfileManifest active = profiles.active();
		g.centeredText(font, active == null ? Component.translatable("minigamebots.profiles.none_active")
				: Component.translatable("minigamebots.profiles.active", active.name), width / 2, 20, 0xFFA0A0A0);

		int start = pageStart();
		for (int i = 0; i < perPage && start + i <= all.size(); i++) {
			if (start + i < all.size()) {
				drawCard(g, all.get(start + i), cardX(i), cardY(i), mouseX, mouseY, active);
			} else {
				drawNewCard(g, cardX(i), cardY(i), mouseX, mouseY);
			}
		}
	}

	private boolean over(int x, int y, int mx, int my) {
		return mx >= x && mx < x + CARD_W && my >= y && my < y + CARD_H;
	}

	private void drawCard(GuiGraphicsExtractor g, ProfileManifest p, int x, int y, int mx, int my,
			@Nullable ProfileManifest active) {
		boolean isActive = active != null && active.id.equals(p.id);
		boolean isSelected = p.id.equals(selected);
		boolean hover = over(x, y, mx, my);
		int border = isSelected ? 0xFFFFFFFF : isActive ? 0xFF43A047 : hover ? 0xFF808080 : 0xFF404040;
		g.fill(x - 1, y - 1, x + CARD_W + 1, y + CARD_H + 1, border);
		g.fill(x, y, x + CARD_W, y + CARD_H, hover ? 0xE0282828 : 0xE0181818);
		if (isActive) {
			g.fill(x, y, x + CARD_W, y + 12, 0xFF2E7D32);
			g.centeredText(font, Component.translatable("minigamebots.profiles.in_use"), x + CARD_W / 2, y + 2, 0xFFFFFFFF);
		}
		int face = 40;
		int fx = x + (CARD_W - face) / 2;
		// A lighter backdrop so dark hair doesn't vanish into the card.
		g.fill(fx - 2, y + 14, fx + face + 2, y + 16 + face + 2, 0xFF6A6A6A);
		g.fill(fx - 1, y + 15, fx + face + 1, y + 16 + face + 1, 0xFF8B8B8B);
		faces.draw(g, profiles.store(), p, fx, y + 16, face);
		String name = font.plainSubstrByWidth(p.name, CARD_W - 6);
		g.centeredText(font, name, x + CARD_W / 2, y + 62, 0xFFFFFFFF);
		String trained = p.stats.trainingHours <= 0 ? Component.translatable("minigamebots.profiles.untrained").getString()
				: String.format("%.1f h trained", p.stats.trainingHours);
		g.centeredText(font, trained, x + CARD_W / 2, y + 74, 0xFFA0A0A0);
		String skills = p.stats.skills == 1 ? "1 skill" : p.stats.skills + " skills";
		g.centeredText(font, skills, x + CARD_W / 2, y + 85, 0xFF808080);
	}

	private void drawNewCard(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
		boolean hover = over(x, y, mx, my);
		g.fill(x - 1, y - 1, x + CARD_W + 1, y + CARD_H + 1, hover ? 0xFFA0A0A0 : 0xFF404040);
		g.fill(x, y, x + CARD_W, y + CARD_H, hover ? 0xC0303030 : 0xC0181818);
		g.centeredText(font, "+", x + CARD_W / 2, y + 34, 0xFFFFFFFF);
		g.centeredText(font, Component.translatable("minigamebots.profiles.new"), x + CARD_W / 2, y + 52, 0xFFE0E0E0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int mx = (int) event.x();
		int my = (int) event.y();
		int start = pageStart();
		for (int i = 0; i < perPage && start + i <= all.size(); i++) {
			if (!over(cardX(i), cardY(i), mx, my)) {
				continue;
			}
			if (start + i == all.size()) {
				minecraft.gui.setScreen(new EditProfileScreen(this, null));
				return true;
			}
			ProfileManifest p = all.get(start + i);
			boolean again = p.id.equals(selected) && System.currentTimeMillis() - lastClick < 300;
			selected = p.id;
			lastClick = System.currentTimeMillis();
			if (doubleClick || again) {
				useSelected();
			}
			updateButtons();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void removed() {
		faces.close();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	void select(String id) {
		selected = id;
	}

	static ProfileStore store() {
		return MinigameBotsClient.instance().profiles().store();
	}
}
