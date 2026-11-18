package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.FoundationInstaller;
import io.github.mudklip.minigamebots.client.profile.FoundationStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Foundation models: big pretrained bases downloaded once and shared by every AI trained on them.
 * One row per model with Install / Remove and download progress.
 */
public final class FoundationsScreen extends Screen {
	private static final int ROW = 58;
	private static final int TOP = 48;

	private final @Nullable Screen parent;
	private final FoundationInstaller installer;
	private final FoundationStore store;
	private final List<Button> installs = new ArrayList<>();
	private final List<Button> removes = new ArrayList<>();
	private Button cancel;
	private @Nullable String error;

	public FoundationsScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.foundations.screen"));
		this.parent = parent;
		this.installer = MinigameBotsClient.instance().foundationInstaller();
		this.store = MinigameBotsClient.instance().profiles().foundations();
	}

	@Override
	protected void init() {
		installs.clear();
		removes.clear();
		int y = TOP;
		for (FoundationInstaller.Entry e : FoundationInstaller.CATALOG) {
			installs.add(addRenderableWidget(Button.builder(Component.translatable("minigamebots.foundations.install"), b -> {
				try {
					installer.install(e.id());
					error = null;
				} catch (IOException ex) {
					error = ex.getMessage();
				}
			}).pos(width - 140, y + 16).width(64).build()));
			removes.add(addRenderableWidget(Button.builder(Component.translatable("minigamebots.foundations.remove"), b -> {
				try {
					installer.remove(e.id());
					error = null;
				} catch (IOException ex) {
					error = ex.getMessage();
				}
			}).pos(width - 72, y + 16).width(62)
					.tooltip(Tooltip.create(Component.translatable("minigamebots.foundations.remove_help"))).build()));
			y += ROW;
		}
		cancel = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> installer.cancel())
				.pos(10, height - 28).width(70).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.pos(width - 70, height - 28).width(60).build());
		updateButtons();
	}

	@Override
	public void tick() {
		updateButtons();
	}

	private void updateButtons() {
		boolean ready = MinigameBotsClient.instance().brain().runtime().installed();
		for (int i = 0; i < FoundationInstaller.CATALOG.size(); i++) {
			String id = FoundationInstaller.CATALOG.get(i).id();
			boolean has = store.has(id);
			installs.get(i).visible = !has && !id.equals(installer.installing());
			installs.get(i).active = ready && !installer.busy();
			removes.get(i).visible = has;
			removes.get(i).active = !installer.busy();
		}
		cancel.visible = installer.busy();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		g.textWithWordWrap(font, Component.translatable("minigamebots.foundations.help"), 10, 20, width - 20, 0xFFA0A0A0);
		int y = TOP;
		int textW = width - 160;
		for (FoundationInstaller.Entry e : FoundationInstaller.CATALOG) {
			g.fill(8, y, width - 8, y + ROW - 4, 0x60000000);
			boolean has = store.has(e.id());
			g.text(font, e.name(), 14, y + 4, 0xFFFFFFFF, false);
			var lines = font.split(Component.literal(e.description()), textW);
			for (int l = 0; l < Math.min(2, lines.size()); l++) {
				g.text(font, lines.get(l), 14, y + 15 + l * 10, 0xFFA0A0A0, false);
			}
			String state;
			int color;
			if (e.id().equals(installer.installing())) {
				state = installer.message();
				color = 0xFFFFFFFF;
				drawProgress(g, 14, y + 47, textW, installer.progress());
			} else if (has) {
				state = "Installed";
				color = 0xFF80E080;
			} else {
				state = String.format("Not installed (%.0f MB download)", e.downloadBytes() / 1e6);
				color = 0xFFB0B0B0;
			}
			g.text(font, font.plainSubstrByWidth(state, textW), 14, y + 36, color, false);
			y += ROW;
		}
		if (!MinigameBotsClient.instance().brain().runtime().installed()) {
			g.text(font, Component.translatable("minigamebots.training.needs_brain"), 10, y + 4, 0xFFFFC060, false);
		} else if (error != null) {
			g.text(font, font.plainSubstrByWidth(error, width - 20), 10, y + 4, 0xFFFF8080, false);
		} else if (!installer.busy() && installer.failed()) {
			g.text(font, font.plainSubstrByWidth(installer.message(), width - 20), 10, y + 4, 0xFFFF8080, false);
		}
	}

	private void drawProgress(GuiGraphicsExtractor g, int x, int y, int w, double p) {
		g.fill(x, y, x + w, y + 4, 0xFF303030);
		if (p >= 0) {
			g.fill(x, y, x + (int) (w * Math.min(1, p)), y + 4, 0xFF43A047);
		} else {
			int block = w / 6;
			int pos = (int) ((System.currentTimeMillis() / 8) % (w + block)) - block;
			g.fill(x + Math.max(0, pos), y, x + Math.min(w, pos + block), y + 4, 0xFF43A047);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
