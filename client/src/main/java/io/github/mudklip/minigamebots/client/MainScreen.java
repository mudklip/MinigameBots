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
		LinearLayout body = layout.addToContents(LinearLayout.vertical().spacing(6));
		boolean inWorld = minecraft.player != null;
		body.addChild(Button.builder(Component.literal("§e★ Training Hub"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.HubScreen(this))).width(204)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
						"Plans, skills, training worlds and backups: everything for making your AI better."))).build());

		// Training stopped paying off: say so up front, with a way to see why.
		var active = MinigameBotsClient.instance().profiles().active();
		if (active != null) {
			var v = io.github.mudklip.minigamebots.client.coach.Watchdog.check(
					MinigameBotsClient.instance().profiles().store().dir(active.id));
			if (v.state() == io.github.mudklip.minigamebots.client.coach.Watchdog.State.FLAT
					|| v.state() == io.github.mudklip.minigamebots.client.coach.Watchdog.State.DROPPING) {
				body.addChild(Button.builder(Component.literal("§6⚠ " + v.state().label + ": see why"),
						b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.ProgressScreen(this, active))).width(204)
						.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(v.why()))).build());
			}
		}
		Button control = body.addChild(Button.builder(Component.translatable("minigamebots.menu.start_idle"), b -> {
			onClose();
			AgentControl.get().start(minecraft, io.github.mudklip.minigamebots.client.control.ActionSource.IDLE);
		}).width(204).build());
		control.active = inWorld;
		LinearLayout row1 = body.addChild(LinearLayout.horizontal().spacing(4));
		row1.addChild(Button.builder(Component.translatable("minigamebots.menu.profiles"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.ProfilesScreen(this))).width(100).build());
		row1.addChild(Button.builder(Component.translatable("minigamebots.menu.brain"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.BrainScreen(this))).width(100).build());
		LinearLayout row2 = body.addChild(LinearLayout.horizontal().spacing(4));
		Button goals = row2.addChild(Button.builder(Component.translatable("minigamebots.menu.goals"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.GoalsScreen(this))).width(100).build());
		goals.active = inWorld;
		Button memory = row2.addChild(Button.builder(Component.translatable("minigamebots.menu.memory"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.MemoryScreen(this))).width(100).build());
		memory.active = inWorld && MinigameBotsClient.instance().profiles().active() != null;
		LinearLayout row3 = body.addChild(LinearLayout.horizontal().spacing(4));
		row3.addChild(Button.builder(Component.translatable("minigamebots.menu.recordings"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.RecorderScreen(this))).width(65).build());
		row3.addChild(Button.builder(Component.literal("☾ Sleep..."),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.sleep.SleepScreen(this))).width(66)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Let it get better while you're away: "
						+ "replays the day, tidies its memories, keeps your PC free (can minimise the game)."))).build());
		row3.addChild(Button.builder(Component.translatable("minigamebots.menu.settings"),
				b -> minecraft.gui.setScreen(MgbConfig.screen(this))).width(65).build());
		LinearLayout row4 = body.addChild(LinearLayout.horizontal().spacing(4));
		row4.addChild(Button.builder(Component.translatable("minigamebots.menu.video"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.VideoLabScreen(this))).width(65).build());
		row4.addChild(Button.builder(Component.literal("Inventions..."),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.InventionsScreen(this))).width(66)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("It invents its own traps and shelters: "
						+ "combines and changes parts, tests every idea for real, keeps the best."))).build());
		row4.addChild(Button.builder(Component.translatable("minigamebots.menu.guide"),
				b -> minecraft.gui.setScreen(new io.github.mudklip.minigamebots.client.gui.GuideScreen(this, "start"))).width(65)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("minigamebots.menu.guide_help"))).build());
		if (!MgbConfig.get().guideHintShown) {
			// Once: point new players at the guide, then never again.
			MgbConfig.get().guideHintShown = true;
			MgbConfig.HANDLER.save();
			net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(minecraft.gui.toastManager(),
					new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(),
					Component.translatable("minigamebots.guide.hint_title"), Component.translatable("minigamebots.guide.hint"));
		}
		layout.addToFooter(Button.builder(Component.translatable("gui.done"), b -> onClose()).width(204).build());
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
