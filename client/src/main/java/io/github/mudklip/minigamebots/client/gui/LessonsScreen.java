package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.memory.Mind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** The lessons it wrote while sleeping, with how far each is trusted; click × to forget one. */
public final class LessonsScreen extends Screen {
	private final Screen parent;
	private final List<Object[]> crosses = new ArrayList<>();

	public LessonsScreen(Screen parent) {
		super(Component.literal("Lessons"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, height - 26).width(52).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, Component.literal("§f§lLessons"), 8, 8, Ui.TEXT, false);
		g.text(font, Component.literal("§7Written while it sleeps, from what its memories show. A lesson is only used once what "
				+ "happens next backs it up (trusted)."), 8, 20, Ui.MUTED, false);
		crosses.clear();
		var wm = MinigameBotsClient.instance().memory().memory();
		if (wm == null || wm.mind.lessons.isEmpty()) {
			g.text(font, Component.literal("§7No lessons yet: let it sleep after a day of playing (main menu > Sleep)."), 8, 44, Ui.MUTED, false);
			return;
		}
		int y = 40;
		for (Mind.Lesson l : List.copyOf(wm.mind.lessons).reversed()) {
			if (y > height - 40) {
				break;
			}
			String tag = "idea".equals(l.type) ? "§didea" : l.trusted() ? "§atrusted" : "§etesting";
			int bar = (int) (40 * l.trust);
			g.fill(8, y + 2, 48, y + 7, 0xFF303030);
			if (!"idea".equals(l.type)) {
				g.fill(8, y + 2, 8 + bar, y + 7, l.trusted() ? 0xFF43A047 : 0xFFE0B040);
			}
			g.text(font, Component.literal(tag), 54, y, Ui.TEXT, false);
			g.text(font, Component.literal("§f" + font.plainSubstrByWidth(l.text, width - 130)), 100, y, Ui.TEXT, false);
			boolean h = Ui.inside(mouseX, mouseY, width - 20, y - 1, 10, 10);
			g.text(font, Component.literal(h ? "§c×" : "§7×"), width - 18, y, Ui.TEXT, false);
			crosses.add(new Object[] {y, l});
			y += 13;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (Object[] c : crosses) {
			int y = (int) c[0];
			if (Ui.inside(event.x(), event.y(), width - 20, y - 1, 10, 10)) {
				var wm = MinigameBotsClient.instance().memory().memory();
				if (wm != null) {
					wm.mind.lessons.remove((Mind.Lesson) c[1]); // You disagree: forgotten.
					MinigameBotsClient.instance().memory().save();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
