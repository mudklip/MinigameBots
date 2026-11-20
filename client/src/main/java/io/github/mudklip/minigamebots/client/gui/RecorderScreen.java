package io.github.mudklip.minigamebots.client.gui;

import com.mojang.blaze3d.Blaze3D;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.record.Recorder;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.record.RecordingStore;
import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Lists recordings: view, edit name and tags, delete, export, import. */
public final class RecorderScreen extends Screen {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private final @Nullable Screen parent;
	private final RecordingStore store;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
	private RecordingList list;
	private Button view;
	private Button edit;
	private Button delete;
	private Button export;
	private List<RecordingMeta> recordings = List.of();

	public RecorderScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.recorder.screen"));
		this.parent = parent;
		this.store = MinigameBotsClient.instance().recorder().store();
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		list = layout.addToContents(new RecordingList());
		reload();

		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		LinearLayout row1 = footer.addChild(LinearLayout.horizontal().spacing(4));
		view = row1.addChild(Button.builder(Component.translatable("minigamebots.recorder.view"), b -> viewSelected()).width(74).build());
		edit = row1.addChild(Button.builder(Component.translatable("minigamebots.recorder.edit"), b -> editSelected()).width(74).build());
		delete = row1.addChild(Button.builder(Component.translatable("minigamebots.recorder.delete"), b -> deleteSelected()).width(74).build());
		export = row1.addChild(Button.builder(Component.translatable("minigamebots.recorder.export"), b -> exportSelected()).width(74).build());
		LinearLayout row2 = footer.addChild(LinearLayout.horizontal().spacing(4));
		row2.addChild(Button.builder(Component.translatable("minigamebots.recorder.import"), b -> importAll()).width(98).build());
		row2.addChild(Button.builder(Component.translatable("minigamebots.recorder.open_folder"), b -> openFolder()).width(98).build());
		row2.addChild(Button.builder(Component.translatable("gui.done"), b -> onClose()).width(98).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		updateButtons();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (list != null) {
			list.updateSize(width, layout);
		}
	}

	private void reload() {
		Recorder recorder = MinigameBotsClient.instance().recorder();
		recordings = store.list(recorder.currentId());
		list.replaceEntries(recordings.stream().map(RecordingEntry::new).toList());
		updateButtons();
	}

	private void updateButtons() {
		boolean selected = list != null && list.getSelected() != null;
		boolean busy = MinigameBotsClient.instance().recorder().recording();
		if (view != null) {
			view.active = selected;
			edit.active = selected;
			delete.active = selected && !(busy && isCurrent(list.getSelected().meta));
			export.active = selected;
		}
	}

	private boolean isCurrent(RecordingMeta meta) {
		return !meta.complete && MinigameBotsClient.instance().recorder().recording();
	}

	private @Nullable RecordingMeta selected() {
		RecordingEntry e = list.getSelected();
		return e == null ? null : e.meta;
	}

	private void viewSelected() {
		RecordingMeta meta = selected();
		if (meta != null) {
			minecraft.gui.setScreen(new RecordingViewerScreen(this, store, meta));
		}
	}

	private void editSelected() {
		RecordingMeta meta = selected();
		if (meta != null) {
			minecraft.gui.setScreen(new EditRecordingScreen(this, store, meta));
		}
	}

	private void deleteSelected() {
		RecordingMeta meta = selected();
		if (meta == null) {
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				try {
					store.delete(meta.id);
				} catch (IOException e) {
					MinigameBotsClient.LOGGER.error("Couldn't delete recording {}", meta.id, e);
				}
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("minigamebots.recorder.delete_title"),
				Component.translatable("minigamebots.recorder.delete_message", meta.name)));
	}

	private void exportSelected() {
		RecordingMeta meta = selected();
		if (meta == null) {
			return;
		}
		try {
			var file = store.export(meta);
			toast(Component.translatable("minigamebots.recorder.exported", file.getFileName().toString()));
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.error("Couldn't export recording {}", meta.id, e);
			toast(Component.translatable("minigamebots.recorder.failed"));
		}
	}

	private void importAll() {
		try {
			int n = store.importAll();
			toast(n == 0 ? Component.translatable("minigamebots.recorder.import_none")
					: Component.translatable("minigamebots.recorder.imported", n));
			if (n == 0) {
				Blaze3D.openPath(store.importDir());
			}
		} catch (IOException | RuntimeException e) {
			MinigameBotsClient.LOGGER.error("Couldn't import recordings", e);
			toast(Component.translatable("minigamebots.recorder.failed"));
		}
		reload();
	}

	private void openFolder() {
		try {
			Files.createDirectories(store.root());
		} catch (IOException ignored) {
			// Opening will show the problem.
		}
		Blaze3D.openPath(store.root());
	}

	private void toast(Component body) {
		io.github.mudklip.minigamebots.client.overlay.Overlay.toast(TOAST, Component.translatable("minigamebots.recorder.title"), body);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	static String describe(RecordingMeta m) {
		String date;
		try {
			date = OffsetDateTime.parse(m.created).format(DATE);
		} catch (RuntimeException e) {
			date = m.created;
		}
		String size = String.format("%.1f MB", m.sizeBytes / 1_000_000.0);
		String tags = m.tags.isEmpty() ? "" : "  #" + String.join(" #", m.tags);
		String source = "ai".equals(m.source) ? "  [AI]" : "";
		String broken = m.complete ? "" : "  (unfinished)";
		return date + "  " + Recorder.time(m.ticks) + "  " + size + source + broken + tags;
	}

	private final class RecordingList extends ObjectSelectionList<RecordingEntry> {
		RecordingList() {
			super(RecorderScreen.this.minecraft, RecorderScreen.this.width, RecorderScreen.this.height - 93, 33, 24);
		}

		void replaceEntries(List<RecordingEntry> entries) {
			RecordingEntry old = getSelected();
			clearEntries();
			for (RecordingEntry e : entries) {
				addEntry(e);
				if (old != null && old.meta.id.equals(e.meta.id)) {
					setSelected(e);
				}
			}
		}

		@Override
		public int getRowWidth() {
			return Math.min(340, width - 20);
		}

		@Override
		public void setSelected(@Nullable RecordingEntry entry) {
			super.setSelected(entry);
			updateButtons();
		}
	}

	private final class RecordingEntry extends ObjectSelectionList.Entry<RecordingEntry> {
		private final RecordingMeta meta;

		RecordingEntry(RecordingMeta meta) {
			this.meta = meta;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			int x = getContentX() + 2;
			int y = getContentY() + 1;
			String marks = meta.marks.isEmpty() ? "" : "  " + meta.marks.size() + " marks";
			graphics.text(font, meta.name + marks, x, y, 0xFFFFFFFF, false);
			graphics.text(font, describe(meta), x, y + 11, 0xFFA0A0A0, false);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			list.setSelected(this);
			if (doubleClick) {
				viewSelected();
			}
			return true;
		}

		@Override
		public Component getNarration() {
			return Component.literal(meta.name).withStyle(ChatFormatting.WHITE);
		}
	}
}
