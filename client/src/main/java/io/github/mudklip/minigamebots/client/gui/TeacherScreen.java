package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.TrainingLink;
import io.github.mudklip.minigamebots.client.train.TeacherDemos;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Records demos of a scripted teacher playing, to train on (Training screen: "Teacher demos..."). */
public final class TeacherScreen extends Screen {
	private static final int[] MINUTES = {2, 5, 10, 30, 0};

	private final @Nullable Screen parent;
	private int teacher;
	private int minutes = 1;

	public TeacherScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.teacher.screen"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 100;
		TeacherDemos.Teacher t = TeacherDemos.available().get(teacher);
		addRenderableWidget(Button.builder(Component.literal("Teacher: " + t.label()), b -> {
			teacher = (teacher + 1) % TeacherDemos.available().size();
			rebuildWidgets();
		}).pos(x, 60).width(200).tooltip(Tooltip.create(Component.literal(t.help()))).build());
		addRenderableWidget(Button.builder(Component.literal("Length: " + label(MINUTES[minutes])), b -> {
			minutes = (minutes + 1) % MINUTES.length;
			b.setMessage(Component.literal("Length: " + label(MINUTES[minutes])));
		}).pos(x, 84).width(200).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.teacher.start"), b -> {
			minecraft.gui.setScreen(null);
			TeacherDemos.start(minecraft, t, MINUTES[minutes]);
		}).pos(x, 112).width(98).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).pos(x + 102, 112).width(98).build());
	}

	private static String label(int m) {
		return m == 0 ? "until I press F9" : m + " minutes";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		g.textWithWordWrap(font, Component.translatable("minigamebots.teacher.help"), width / 2 - 150, 28, 300, 0xFFB0B0B0);
		boolean grounds = TrainingLink.get().map(l -> true).orElse(false);
		g.textWithWordWrap(font, Component.translatable(grounds ? "minigamebots.teacher.tip_grounds" : "minigamebots.teacher.tip_alone"),
				width / 2 - 150, 142, 300, 0xFF909090);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
