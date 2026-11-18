package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.record.RecordingStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Rename a recording and edit its tags. */
public final class EditRecordingScreen extends Screen {
	private final Screen parent;
	private final RecordingStore store;
	private final RecordingMeta meta;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private EditBox name;
	private EditBox tags;

	public EditRecordingScreen(Screen parent, RecordingStore store, RecordingMeta meta) {
		super(Component.translatable("minigamebots.recorder.edit_title"));
		this.parent = parent;
		this.store = store;
		this.meta = meta;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		LinearLayout body = layout.addToContents(LinearLayout.vertical().spacing(4));
		body.addChild(new StringWidget(Component.translatable("minigamebots.recorder.name"), font));
		name = body.addChild(new EditBox(font, 240, 20, Component.translatable("minigamebots.recorder.name")));
		name.setMaxLength(64);
		name.setValue(meta.name);
		body.addChild(new StringWidget(Component.translatable("minigamebots.recorder.tags"), font));
		tags = body.addChild(new EditBox(font, 240, 20, Component.translatable("minigamebots.recorder.tags")));
		tags.setMaxLength(200);
		tags.setValue(String.join(", ", meta.tags));

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("gui.done"), b -> save()).width(120).build());
		footer.addChild(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).width(120).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		setInitialFocus(name);
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	private void save() {
		String newName = name.getValue().trim();
		meta.name = newName.isEmpty() ? meta.id : newName;
		meta.tags = parseTags(tags.getValue());
		try {
			store.saveMeta(meta);
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.error("Couldn't save recording {}", meta.id, e);
		}
		onClose();
	}

	/** "PvP, bedwars,  pvp" becomes [pvp, bedwars]. */
	static List<String> parseTags(String text) {
		List<String> out = new ArrayList<>();
		for (String raw : text.split(",")) {
			String tag = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
			if (!tag.isEmpty() && !out.contains(tag)) {
				out.add(tag);
			}
		}
		return out;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
