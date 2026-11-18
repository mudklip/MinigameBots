package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Lets you build the brain from a Python you already have instead of downloading one. */
public final class PythonPathScreen extends Screen {
	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private EditBox path;

	public PythonPathScreen(Screen parent) {
		super(Component.translatable("minigamebots.brain.own_python_title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		LinearLayout body = layout.addToContents(LinearLayout.vertical().spacing(6));
		body.addChild(new MultiLineTextWidget(Component.translatable("minigamebots.brain.own_python_help"), font)
				.setMaxWidth(Math.min(360, width - 40)));
		path = body.addChild(new EditBox(font, Math.min(360, width - 40), 20, Component.translatable("minigamebots.brain.own_python")));
		path.setMaxLength(512);
		path.setValue(MgbConfig.get().customPythonPath);
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("gui.done"), b -> {
			MgbConfig.get().customPythonPath = path.getValue().trim();
			MgbConfig.HANDLER.save();
			onClose();
		}).width(100).build());
		footer.addChild(Button.builder(Component.translatable("minigamebots.brain.use_standalone"), b -> {
			MgbConfig.get().customPythonPath = "";
			MgbConfig.HANDLER.save();
			onClose();
		}).width(140).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		setInitialFocus(path);
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
