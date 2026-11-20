package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Training worlds, customised: kit, arena size and type, opponent mix, round length, what each
 * world practises and the speed. Every row starts on the classic setup, so nothing needs touching.
 */
public final class WorldsCustomizeScreen extends Screen {
	private record Row(String label, String help, String[] names, String[] values) {
	}

	private static final Row[] ROWS = {
		new Row("Kit", "The same gear on both sides", new String[] {"Default", "Sword + shield", "Axe + shield", "Diamond", "Bare hands"},
				new String[] {"default", "shield", "axe", "diamond", "bare"}),
		new Row("Arena", "Size of each world", new String[] {"Small", "Normal", "Large"}, new String[] {"small", "normal", "large"}),
		new Row("Type", "Edge: a platform, off it and you're out", new String[] {"Flat", "Pillars", "Edge"},
				new String[] {"flat", "pillars", "edge"}),
		new Row("Opponents", "Mixed: a new style each round. Ladder: harder after a win, easier after a loss",
				new String[] {"One style", "Mixed", "Ladder"}, new String[] {"single", "mixed", "ladder"}),
		new Row("Round", "When time's up it's a draw", new String[] {"No limit", "30 s", "1 min", "2 min"},
				new String[] {"0", "30", "60", "120"}),
		new Row("Practice", "What the worlds reward. Mixed: each world its own", new String[] {"Duel", "Crits", "W-tap", "Shields",
				"Mixed"}, new String[] {"duel", "crits", "wtap", "shields", "mixed"}),
		new Row("Speed", "Singleplayer only: how fast the worlds run", new String[] {"1x", "2x", "3x"}, new String[] {"1", "2", "3"}),
	};

	private final Screen parent;
	private final List<int[]> chips = new ArrayList<>();

	public WorldsCustomizeScreen(Screen parent) {
		super(Component.literal("Customize training worlds"));
		this.parent = parent;
	}

	private static String get(int row) {
		MgbConfig c = MgbConfig.get();
		return switch (row) {
			case 0 -> c.worldKit;
			case 1 -> c.worldSize;
			case 2 -> c.worldType;
			case 3 -> c.worldMix;
			case 4 -> String.valueOf(c.worldRound);
			case 5 -> c.worldPractice;
			default -> String.valueOf(c.worldSpeed);
		};
	}

	private static void set(int row, String v) {
		MgbConfig c = MgbConfig.get();
		switch (row) {
			case 0 -> c.worldKit = v;
			case 1 -> c.worldSize = v;
			case 2 -> c.worldType = v;
			case 3 -> c.worldMix = v;
			case 4 -> c.worldRound = Integer.parseInt(v);
			case 5 -> c.worldPractice = v;
			default -> c.worldSpeed = Integer.parseInt(v);
		}
		MgbConfig.HANDLER.save();
	}

	/** The options for the start command (empty when everything is the default). */
	public static String options() {
		MgbConfig c = MgbConfig.get();
		String s = " kit=" + c.worldKit + " size=" + c.worldSize + " type=" + c.worldType + " mix=" + c.worldMix + " round="
				+ c.worldRound + " practice=" + c.worldPractice;
		return s.equals(" kit=default size=normal type=flat mix=single round=0 practice=duel") ? "" : s;
	}

	/** What's not the default, in words, for the Worlds tab. */
	public static String summary() {
		List<String> parts = new ArrayList<>();
		for (int r = 0; r < ROWS.length - 1; r++) {
			String v = get(r);
			if (!v.equals(ROWS[r].values()[r == 1 ? 1 : 0])) {
				for (int i = 0; i < ROWS[r].values().length; i++) {
					if (ROWS[r].values()[i].equals(v)) {
						parts.add(ROWS[r].label() + " " + ROWS[r].names()[i]);
					}
				}
			}
		}
		return String.join(", ", parts);
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Defaults"), b -> {
			for (int r = 0; r < ROWS.length; r++) {
				set(r, ROWS[r].values()[r == 1 || r == ROWS.length - 1 ? 1 : 0]);
			}
		}).pos(width / 2 - 104, height - 28).width(100).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width / 2 + 4, height - 28).width(100).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, Component.literal("§f§lCustomize training worlds"), 8, 8, Ui.TEXT, false);
		g.text(font, Component.literal("§7The defaults are the classic setup: change only what you want."), 8, 20, Ui.MUTED, false);
		chips.clear();
		int y = 36;
		String hover = null;
		for (int r = 0; r < ROWS.length; r++) {
			Row row = ROWS[r];
			g.text(font, Component.literal("§f" + row.label() + ":"), 8, y + 3, Ui.TEXT, false);
			int x = 72;
			String cur = get(r);
			for (int i = 0; i < row.names().length; i++) {
				int w = font.width(row.names()[i]) + 14;
				boolean h = Ui.inside(mouseX, mouseY, x, y, w, 13);
				Ui.chip(g, font, row.names()[i], x, y, w, row.values()[i].equals(cur), h, Ui.ACCENT);
				chips.add(new int[] {x, y, w, r, i});
				if (h || Ui.inside(mouseX, mouseY, 8, y, 60, 13)) {
					hover = row.help();
				}
				x += w + 3;
			}
			y += 18;
		}
		if (hover != null) {
			g.text(font, Component.literal("§7" + font.plainSubstrByWidth(hover, width - 16)), 8, y + 4, Ui.MUTED, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int[] c : chips) {
			if (Ui.inside(event.x(), event.y(), c[0], c[1], c[2], 13)) {
				set(c[3], ROWS[c[3]].values()[c[4]]);
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
