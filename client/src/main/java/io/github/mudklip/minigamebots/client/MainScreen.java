package io.github.mudklip.minigamebots.client;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The MinigameBots main menu (F10). Profiles, training and the rest arrive in later milestones. */
public final class MainScreen extends Screen {
	private final @Nullable Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	public MainScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.name"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		LinearLayout body = layout.addToContents(LinearLayout.vertical().spacing(8));
		boolean inWorld = minecraft.player != null;
		Button control = body.addChild(Button.builder(Component.translatable("minigamebots.menu.start_idle"), b -> {
			onClose();
			AgentControl.get().start(minecraft, io.github.mudklip.minigamebots.client.control.ActionSource.IDLE);
		}).width(200).build());
		control.active = inWorld;
		body.addChild(Button.builder(Component.translatable("minigamebots.menu.profiles"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.ProfilesScreen(this))).width(200).build());
		body.addChild(Button.builder(Component.translatable("minigamebots.menu.brain"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.BrainScreen(this))).width(200).build());
		body.addChild(Button.builder(Component.translatable("minigamebots.menu.recordings"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.RecorderScreen(this))).width(200).build());
		body.addChild(Button.builder(Component.translatable("minigamebots.menu.settings"),
				b -> minecraft.gui.setScreen(MgbConfig.screen(this))).width(200).build());
		layout.addToFooter(Button.builder(Component.translatable("gui.done"), b -> onClose()).width(200).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
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
