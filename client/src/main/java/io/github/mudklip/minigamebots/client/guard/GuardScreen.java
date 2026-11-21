package io.github.mudklip.minigamebots.client.guard;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.gui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Who may give guard orders, and how to give them. */
public final class GuardScreen extends Screen {
	private final @Nullable Screen parent;
	private EditBox names;

	public GuardScreen(@Nullable Screen parent) {
		super(Component.literal("Guard orders"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		names = addRenderableWidget(new EditBox(font, 8, 52, width - 16, 18, Component.literal("Takes orders from")));
		names.setMaxLength(300);
		names.setHint(Component.literal("§7Player names, e.g. Steve, Alex (empty: nobody)"));
		names.setValue(MgbConfig.get().guardCommanders);
		names.setResponder(v -> {
			MgbConfig.get().guardCommanders = v;
			MgbConfig.HANDLER.save();
		});
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lGuard orders"), 8, 10, Ui.TEXT, false);
		g.text(font, font.plainSubstrByWidth("Players in this list can tell this AI to guard someone in formation, from chat or a whisper.",
				width - 76), 8, 22, Ui.MUTED, false);
		g.text(font, Component.literal("§lTakes orders from"), 8, 40, Ui.ACCENT, false);
		int y = 80;
		Ui.card(g, 8, y, width - 16, 104, Ui.CARD, Ui.ACCENT);
		String[] lines = {
				"§fguard Steve 3 ring with Bob Ann Cid§7: stand 3 blocks round Steve; each AI takes its spot",
				"§7   shapes: ring, line (behind), front, wedge, square. Leave out \"with\" for just this AI.",
				"§fstandby§7: hold the spots, don't fight   §ffollow Steve 2§7: walk behind   §fstop§7: stop",
				"§7Add names at the end to talk to some AIs only, e.g. §fstop Bob",
				"§7In guard mode, the AI nearest to anyone hitting the protected player fights them, then returns.",
				"§7Only real player chat or whispers count, never server messages. The protected player must be",
				"§7whoever gave the order or someone on this list. It never walks into lava or a drop for its spot."};
		int ly = y + 6;
		for (String l : lines) {
			g.text(font, font.plainSubstrByWidth(l, width - 30), 14, ly, Ui.TEXT, false);
			ly += 14;
		}
		var o = GuardOrders.current();
		if (o != null) {
			g.text(font, "Now: " + o.kind() + " " + o.target() + " " + (int) o.dist() + " " + o.shape(), 8, y + 112, Ui.GOOD, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
