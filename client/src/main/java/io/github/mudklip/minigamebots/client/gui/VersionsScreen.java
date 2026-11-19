package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.VersionHistory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Earlier versions of an AI: go back to one, pin it so it's kept, or delete it. */
public final class VersionsScreen extends Screen {
	private static final int ROW = 24;

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private Path core;
	private String skill = "general";
	private @Nullable String message;

	public VersionsScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.translatable("minigamebots.versions.screen", profile.name));
		this.parent = parent;
		this.profile = profile;
		this.core = MinigameBotsClient.instance().profiles().store().dir(profile.id).resolve("core");
	}

	@Override
	protected void init() {
		Path profileDir = MinigameBotsClient.instance().profiles().store().dir(profile.id);
		core = io.github.mudklip.minigamebots.client.profile.Skills.dir(profileDir, skill).resolve("core");
		addRenderableWidget(Button.builder(Component.literal("Skill: " + skill), b -> {
			var all = io.github.mudklip.minigamebots.client.profile.Skills.all(profileDir);
			skill = all.get((all.indexOf(skill) + 1) % all.size());
			rebuildWidgets();
		}).pos(width - 120, 6).width(110).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.versions.save_now"), b -> {
			VersionHistory.before(profileDir, core, "Saved by hand");
			message = "Saved";
			rebuildWidgets();
		}).pos(10, height - 28).width(90).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 28).width(60).build());
		List<VersionHistory.Snapshot> list = VersionHistory.list(core);
		int rows = Math.max(1, (height - 40 - 34) / ROW);
		for (int i = 0; i < Math.min(rows, list.size()); i++) {
			VersionHistory.Snapshot s = list.get(i);
			int y = 34 + i * ROW;
			addRenderableWidget(Button.builder(Component.translatable("minigamebots.versions.revert"), b -> {
				if (MinigameBotsClient.instance().training().busy()) {
					message = "Wait for training to finish first";
					return;
				}
				minecraft.gui.setScreen(new DowngradeScreen(this, "This goes back to the " + skill + " network from " + s.date + ".", () -> {
					try {
						io.github.mudklip.minigamebots.client.profile.Backups.create(profileDir, "Before going back to " + s.date, false,
								io.github.mudklip.minigamebots.client.profile.Backups.modVersion());
					} catch (IOException e) {
						message = "Backup failed: " + e.getMessage();
					}
				}, () -> {
					try {
						VersionHistory.revert(core, s.id);
						message = "Went back to " + s.date + " (the version you had is saved too)";
					} catch (IOException e) {
						message = "Couldn't: " + e.getMessage();
					}
					minecraft.gui.setScreen(this);
				}));
			}).pos(width - 186, y).width(60).tooltip(Tooltip.create(Component.translatable("minigamebots.versions.revert_help"))).build());
			addRenderableWidget(Button.builder(Component.literal(s.pinned ? "Pinned" : "Pin"), b -> {
				try {
					VersionHistory.setPinned(core, s.id, !s.pinned);
				} catch (IOException e) {
					message = "Couldn't: " + e.getMessage();
				}
				rebuildWidgets();
			}).pos(width - 122, y).width(54).tooltip(Tooltip.create(Component.translatable("minigamebots.versions.pin_help"))).build());
			addRenderableWidget(Button.builder(Component.literal("x"), b -> {
				try {
					VersionHistory.delete(core, s.id);
				} catch (IOException e) {
					message = "Couldn't: " + e.getMessage();
				}
				rebuildWidgets();
			}).pos(width - 64, y).width(20).build());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		if (message != null) {
			g.text(font, font.plainSubstrByWidth(message, width - 20), 10, 22, 0xFF80E080, false);
		}
		List<VersionHistory.Snapshot> list = VersionHistory.list(core);
		if (list.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("minigamebots.versions.empty"), 10, 40, width - 20, 0xFF909090);
			return;
		}
		int rows = Math.max(1, (height - 40 - 34) / ROW);
		int w = width - 200;
		for (int i = 0; i < Math.min(rows, list.size()); i++) {
			VersionHistory.Snapshot s = list.get(i);
			int y = 34 + i * ROW;
			String exam = s.exam.replaceAll(" in .*", "");
			g.text(font, fit(s.date.substring(Math.min(5, s.date.length())) + "  step " + s.step + (s.phase.startsWith("practice") ? " +RL" : "")
					+ (exam.isEmpty() ? "" : "  " + exam), w), 10, y + 1, 0xFFFFFFFF, false);
			g.text(font, fit(s.label + String.format("  (%.0f MB)", s.bytes / 1e6), w), 10, y + 11, 0xFF909090, false);
		}
	}

	/** Shortened with "…" if it doesn't fit (hover tooltips aren't needed for these short notes). */
	private String fit(String text, int w) {
		return font.width(text) <= w ? text : font.plainSubstrByWidth(text, w - font.width("…")) + "…";
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
