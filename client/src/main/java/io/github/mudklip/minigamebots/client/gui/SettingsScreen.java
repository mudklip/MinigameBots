package io.github.mudklip.minigamebots.client.gui;

import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleSlider;
import dev.isxander.yacl3.config.v2.api.autogen.FloatSlider;
import dev.isxander.yacl3.config.v2.api.autogen.IntSlider;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Settings, the friendly way: a tab per topic, a card per setting with what it does, switches and
 * sliders that save at once. Built from the settings themselves (MgbConfig), so new ones show up.
 * Internal values stay hidden; "All settings" opens the full classic list.
 */
public final class SettingsScreen extends Screen {
	private static final String[][] TABS = {{"humanizer", "How it plays"}, {"perception", "What it knows"},
			{"background", "Running"}, {"recording", "Recording & popups"}, {"autonomy", "Safety"}};
	private static final Set<String> HIDDEN = Set.of("guiCursorPixelsPerDegree");
	private static final int ROW = 30;

	private final @Nullable Screen parent;
	private int tab;
	private int scroll;
	private final List<Field> fields = new ArrayList<>();
	private final int[] tabX = new int[TABS.length];
	private final int[] tabW = new int[TABS.length];
	private @Nullable Field dragging;

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.literal("Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		fields.clear();
		for (Field f : MgbConfig.class.getFields()) {
			AutoGen ag = f.getAnnotation(AutoGen.class);
			if (ag != null && ag.category().equals(TABS[tab][0]) && !HIDDEN.contains(f.getName())) {
				fields.add(f);
			}
		}
		scroll = Math.max(0, Math.min(scroll, fields.size() - visible()));
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
		addRenderableWidget(Button.builder(Component.literal("All settings..."), b -> minecraft.gui.setScreen(MgbConfig.screen(this)))
				.pos(width - 150, 6).width(86).build());
	}

	private int visible() {
		return Math.max(1, (height - 50) / ROW);
	}

	private static String name(Field f) {
		return Component.translatable("yacl3.config.minigamebots:config." + f.getName()).getString();
	}

	private static String desc(Field f) {
		String key = "yacl3.config.minigamebots:config." + f.getName() + ".desc";
		String s = Component.translatable(key).getString();
		return s.equals(key) ? "" : s;
	}

	/** {min, max, step} for a slider field, or null if it's not a slider. */
	private static double @Nullable [] range(Field f) {
		IntSlider i = f.getAnnotation(IntSlider.class);
		if (i != null) {
			return new double[] {i.min(), i.max(), i.step()};
		}
		FloatSlider fl = f.getAnnotation(FloatSlider.class);
		if (fl != null) {
			return new double[] {fl.min(), fl.max(), fl.step()};
		}
		DoubleSlider d = f.getAnnotation(DoubleSlider.class);
		if (d != null) {
			return new double[] {d.min(), d.max(), d.step()};
		}
		return null;
	}

	private static String format(Field f, double v) {
		String fmt = "%s";
		IntSlider i = f.getAnnotation(IntSlider.class);
		FloatSlider fl = f.getAnnotation(FloatSlider.class);
		DoubleSlider d = f.getAnnotation(DoubleSlider.class);
		try {
			if (i != null) {
				return String.format(i.format(), (int) Math.round(v));
			}
			if (fl != null) {
				return String.format(fl.format(), (float) v);
			}
			if (d != null) {
				return String.format(d.format(), v);
			}
		} catch (RuntimeException e) {
			return fmt.formatted(v);
		}
		return String.valueOf(v);
	}

	private static double get(Field f) {
		try {
			Object o = f.get(MgbConfig.get());
			return o instanceof Number n ? n.doubleValue() : 0;
		} catch (IllegalAccessException e) {
			return 0;
		}
	}

	private static void set(Field f, double v) {
		try {
			Object cfg = MgbConfig.get();
			if (f.getType() == int.class) {
				f.setInt(cfg, (int) Math.round(v));
			} else if (f.getType() == float.class) {
				f.setFloat(cfg, (float) v);
			} else if (f.getType() == double.class) {
				f.setDouble(cfg, v);
			} else if (f.getType() == boolean.class) {
				f.setBoolean(cfg, v != 0);
			}
			MgbConfig.HANDLER.save();
		} catch (IllegalAccessException ignored) {
			// Public fields only.
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lSettings"), 10, 10, Ui.TEXT, false);
		int x = 10;
		for (int i = 0; i < TABS.length; i++) {
			tabX[i] = x;
			tabW[i] = Ui.chip(g, font, TABS[i][1], x, 24, i == tab, Ui.inside(mouseX, mouseY, x, 24, font.width(TABS[i][1]) + 10, 13), Ui.ACCENT);
			x += tabW[i] + 4;
		}
		int y = 42;
		int w = width - 20;
		for (int i = scroll; i < Math.min(fields.size(), scroll + visible()); i++) {
			Field f = fields.get(i);
			boolean hover = Ui.inside(mouseX, mouseY, 10, y, w, ROW - 4);
			Ui.card(g, 10, y, w, ROW - 4, hover ? Ui.CARD_HOVER : Ui.CARD, Ui.BORDER);
			g.text(font, Component.literal("§l" + name(f)), 16, y + 4, Ui.TEXT, false);
			int controlW = f.getType() == boolean.class ? 26 : 130;
			g.text(font, font.plainSubstrByWidth(desc(f), w - controlW - 24), 16, y + 15, Ui.MUTED, false);
			int cx = 10 + w - controlW - 6, cy = y + (ROW - 4) / 2;
			if (f.getType() == boolean.class) {
				boolean on = get(f) != 0 || isTrue(f);
				g.fill(cx, cy - 5, cx + 20, cy + 5, on ? 0xFF2F7D3B : 0xFF3A414D);
				g.outline(cx, cy - 5, 20, 10, on ? Ui.GOOD : Ui.BORDER);
				int kx = on ? cx + 11 : cx + 1;
				g.fill(kx, cy - 4, kx + 8, cy + 4, on ? Ui.TEXT : Ui.MUTED);
			} else {
				double[] r = range(f);
				if (r != null) {
					double v = get(f);
					double frac = (v - r[0]) / Math.max(1e-9, r[1] - r[0]);
					int tw = controlW - 46;
					g.fill(cx, cy - 1, cx + tw, cy + 1, 0xFF3A414D);
					g.fill(cx, cy - 1, cx + (int) (tw * frac), cy + 1, Ui.ACCENT);
					int kx = cx + (int) (tw * frac);
					g.fill(kx - 2, cy - 5, kx + 3, cy + 5, dragging == f ? Ui.TEXT : Ui.ACCENT);
					g.text(font, format(f, v), cx + tw + 6, cy - 4, Ui.TEXT, false);
				}
			}
			y += ROW;
		}
		// The full description of the setting under the mouse, at the bottom.
		int[] h = hit(mouseX, mouseY);
		if (h != null && !desc(fields.get(h[0])).isEmpty()) {
			var lines = font.split(Component.literal(desc(fields.get(h[0]))), width - 28);
			int n = Math.min(3, lines.size());
			int by = height - 6 - n * 10;
			g.fill(8, by - 4, width - 8, height - 2, 0xF0101418);
			g.outline(8, by - 4, width - 16, height - 2 - (by - 4), Ui.ACCENT);
			for (int i = 0; i < n; i++) {
				g.text(font, lines.get(i), 14, by + i * 10, Ui.TEXT, false);
			}
			return;
		}
		if (fields.size() > visible()) {
			g.text(font, "Scroll for more (" + (scroll + 1) + "-" + Math.min(fields.size(), scroll + visible()) + " of " + fields.size() + ")",
					10, height - 12, Ui.MUTED, false);
		}
	}

	private static boolean isTrue(Field f) {
		try {
			return f.getType() == boolean.class && f.getBoolean(MgbConfig.get());
		} catch (IllegalAccessException e) {
			return false;
		}
	}

	/** The field row and control under the mouse, if any: {index, controlX, width}. */
	private int @Nullable [] hit(double mx, double my) {
		int y = 42;
		int w = width - 20;
		for (int i = scroll; i < Math.min(fields.size(), scroll + visible()); i++) {
			if (my >= y && my < y + ROW - 4 && mx >= 10 && mx < 10 + w) {
				Field f = fields.get(i);
				int controlW = f.getType() == boolean.class ? 26 : 130;
				return new int[] {i, 10 + w - controlW - 6, controlW};
			}
			y += ROW;
		}
		return null;
	}

	private void slide(Field f, int cx, int controlW, double mx) {
		double[] r = range(f);
		if (r == null) {
			return;
		}
		int tw = controlW - 46;
		double frac = Math.max(0, Math.min(1, (mx - cx) / tw));
		double v = r[0] + frac * (r[1] - r[0]);
		if (r[2] > 0) {
			v = r[0] + Math.round((v - r[0]) / r[2]) * r[2];
		}
		set(f, v);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < TABS.length; i++) {
			if (Ui.inside(event.x(), event.y(), tabX[i], 24, tabW[i], 13)) {
				tab = i;
				scroll = 0;
				rebuildWidgets();
				return true;
			}
		}
		int[] h = hit(event.x(), event.y());
		if (h != null) {
			Field f = fields.get(h[0]);
			if (f.getType() == boolean.class) {
				set(f, isTrue(f) ? 0 : 1);
				return true;
			}
			if (event.x() >= h[1] - 4) {
				dragging = f;
				slide(f, h[1], h[2], event.x());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			int w = width - 20;
			slide(dragging, 10 + w - 130 - 6, 130, event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll = Math.max(0, Math.min(Math.max(0, fields.size() - visible()), scroll - (int) Math.signum(dy)));
		return true;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
