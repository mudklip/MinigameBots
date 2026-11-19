package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.Backups;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Backups of the whole AI: make one, go back to one completely, or tick only the parts to
 * bring back (e.g. just the combat network, or just its memory).
 */
public final class BackupsScreen extends Screen {
	private static final int CARD_H = 30;
	private final @Nullable Screen parent;
	private List<Backups.Info> list = List.of();
	private int selected;
	private int scroll;
	private final Set<String> chosen = new HashSet<>();
	private @Nullable String chosenFor;
	private @Nullable String message;
	private final List<int[]> partChips = new ArrayList<>();
	private int listW;

	public BackupsScreen(@Nullable Screen parent) {
		super(Component.literal("Backups"));
		this.parent = parent;
	}

	private @Nullable Path profileDir() {
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		return p == null ? null : MinigameBotsClient.instance().profiles().store().dir(p.id);
	}

	private Backups.@Nullable Info current() {
		return selected >= 0 && selected < list.size() ? list.get(selected) : null;
	}

	@Override
	protected void init() {
		Path dir = profileDir();
		list = dir == null ? List.of() : Backups.list(dir);
		selected = Math.max(0, Math.min(selected, list.size() - 1));
		listW = Math.min(150, width / 2 - 40);
		Backups.Info b = current();
		if (b != null && !b.id.equals(chosenFor)) {
			chosen.clear();
			chosen.addAll(b.parts.keySet());
			chosenFor = b.id;
		}
		addRenderableWidget(Button.builder(Component.literal("§a+ Back up now"), x -> {
			if (dir == null) {
				return;
			}
			try {
				Backups.create(dir, "Made by you", false, Backups.modVersion());
				message = "§aBacked up";
				selected = 0;
				chosenFor = null;
			} catch (Exception e) {
				message = "Couldn't: " + e.getMessage();
			}
			rebuildWidgets();
		}).pos(width - 166, 6).width(100).build()).active = dir != null;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), x -> onClose()).pos(width - 60, 6).width(52).build());
		if (b == null || dir == null) {
			return;
		}
		int rx = 8 + listW + 8;
		int rw = width - rx - 8;
		int by = height - 50;
		addRenderableWidget(Button.builder(Component.literal("Bring back ticked"), x -> restore(dir, b, new ArrayList<>(chosen)))
				.pos(rx + 6, by).width(Math.min(150, rw / 2 - 8)).tooltip(Tooltip.create(Component.literal("Only the ticked parts go back to "
						+ "how they were; everything else stays as it is now"))).build()).active = !chosen.isEmpty();
		addRenderableWidget(Button.builder(Component.literal("Bring back all"), x -> restore(dir, b, new ArrayList<>(b.parts.keySet())))
				.pos(rx + 10 + Math.min(150, rw / 2 - 8), by).width(Math.min(150, rw / 2 - 8)).build());
		addRenderableWidget(Button.builder(Component.literal(b.pinned ? "★ Kept" : "Keep"), x -> {
			try {
				Backups.setPinned(dir, b.id, !b.pinned);
			} catch (Exception e) {
				message = "Couldn't: " + e.getMessage();
			}
			rebuildWidgets();
		}).pos(rx + 6, by + 24).width(60).tooltip(Tooltip.create(Component.literal("Kept backups are never removed automatically"))).build());
		addRenderableWidget(Button.builder(Component.literal("§cDelete"), x -> minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				try {
					Backups.delete(dir, b.id);
				} catch (Exception e) {
					message = "Couldn't: " + e.getMessage();
				}
			}
			minecraft.gui.setScreen(this);
		}, Component.literal("Delete the backup from " + b.date + "?"), Component.literal("It can't be brought back."))))
				.pos(rx + 70, by + 24).width(60).build());
	}

	private void restore(Path dir, Backups.Info b, List<String> parts) {
		if (MinigameBotsClient.instance().training().busy()) {
			message = "Wait for training to finish first";
			return;
		}
		Runnable go = () -> {
			try {
				Backups.restore(dir, b.id, parts);
				message = "§aBrought back " + parts.size() + " part(s)";
				io.github.mudklip.minigamebots.client.coach.Progress.forget();
			} catch (Exception e) {
				message = "Couldn't: " + e.getMessage();
			}
			chosenFor = null;
			minecraft.gui.setScreen(this);
		};
		boolean networks = parts.stream().anyMatch(p -> p.startsWith("network:"));
		if (networks && Backups.lastLearned(dir) > b.created) {
			minecraft.gui.setScreen(new DowngradeScreen(this, "This backup is from " + b.date + ", older than what the AI knows now.", () -> {
				try {
					Backups.create(dir, "Before going back to " + b.date, false, Backups.modVersion());
				} catch (Exception e) {
					message = "Backup failed: " + e.getMessage();
				}
			}, go));
		} else {
			go.run();
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int vis = Math.max(1, (height - 44) / (CARD_H + 3));
		scroll = Math.max(0, Math.min(Math.max(0, list.size() - vis), scroll - (int) Math.signum(dy)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = scroll; i < list.size(); i++) {
			int y = 30 + (i - scroll) * (CARD_H + 3);
			if (y + CARD_H > height - 8) {
				break;
			}
			if (Ui.inside(event.x(), event.y(), 8, y, listW, CARD_H)) {
				selected = i;
				rebuildWidgets();
				return true;
			}
		}
		for (int[] c : partChips) {
			if (Ui.inside(event.x(), event.y(), c[0], c[1], c[2], 13)) {
				String part = new ArrayList<>(current().parts.keySet()).get(c[3]);
				if (!chosen.remove(part)) {
					chosen.add(part);
				}
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private static String size(long b) {
		return b > 1 << 20 ? String.format("%.0f MB", b / 1048576.0) : String.format("%.0f KB", b / 1024.0);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int rx = 8 + listW + 8;
		int rw = width - rx - 8;
		if (current() != null) {
			Ui.panel(g, rx, 30, rw, height - 38);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		ProfileManifest p = MinigameBotsClient.instance().profiles().active();
		g.text(font, Component.literal("§lBackups§r  " + (p == null ? "§cno AI selected" : "§7" + p.name)), 8, 10, Ui.TEXT, false);
		if (list.isEmpty()) {
			int ly = 34;
			for (var line : font.split(Component.literal("§7No backups yet. Press §f+ Back up now§7. One is also made by itself before "
					+ "coaching and practising (at most every few hours)."), width - 16)) {
				g.text(font, line, 8, ly, Ui.MUTED, false);
				ly += 10;
			}
		}
		for (int i = scroll; i < list.size(); i++) {
			int y = 30 + (i - scroll) * (CARD_H + 3);
			if (y + CARD_H > height - 8) {
				break;
			}
			Backups.Info b = list.get(i);
			boolean hover = Ui.inside(mouseX, mouseY, 8, y, listW, CARD_H);
			Ui.card(g, 8, y, listW, CARD_H, i == selected ? Ui.CARD_SELECTED : hover ? Ui.CARD_HOVER : Ui.CARD,
					i == selected ? Ui.ACCENT : b.auto ? Ui.BORDER : Ui.GOOD);
			g.text(font, Component.literal("§f" + b.date + (b.pinned ? " §e★" : "")), 14, y + 5, Ui.TEXT, false);
			String tag = b.auto ? "§8auto" : "§ayours";
			g.text(font, Component.literal(tag), 8 + listW - 6 - font.width(b.auto ? "auto" : "yours"), y + 5, Ui.TEXT, false);
			g.text(font, Component.literal("§7" + font.plainSubstrByWidth(b.label, listW - 60) + " §8· " + size(b.bytes)), 14, y + 17, Ui.MUTED, false);
		}
		Backups.Info b = current();
		if (b == null) {
			return;
		}
		Ui.header(g, font, "From " + b.date, rx + 6, 36);
		g.text(font, Component.literal("§7" + font.plainSubstrByWidth(b.label + (b.modVersion.isEmpty() ? "" : " · mod " + b.modVersion), rw - 12)),
				rx + 6, 50, Ui.MUTED, false);
		g.text(font, Component.literal("§fTick what to bring back:"), rx + 6, 66, Ui.TEXT, false);
		partChips.clear();
		int y = 80;
		int k = 0;
		for (var e : b.parts.entrySet()) {
			boolean on = chosen.contains(e.getKey());
			String text = (on ? "☑ " : "☐ ") + Backups.label(e.getKey());
			int w = Math.min(rw - 52, font.width(text) + 10);
			if (y + 13 > height - 56) {
				break;
			}
			Ui.chip(g, font, text, rx + 6, y, w, on, Ui.inside(mouseX, mouseY, rx + 6, y, w, 13), Ui.GOOD);
			String sz = size(e.getValue());
			g.text(font, Component.literal("§8" + sz), rx + rw - 8 - font.width(sz), y + 3, Ui.MUTED, false);
			partChips.add(new int[] {rx + 6, y, w, k++});
			y += 15;
		}
		if (message != null) {
			g.text(font, Component.literal(font.plainSubstrByWidth(message, rw - 12)), rx + 6, height - 62, Ui.WARN, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
