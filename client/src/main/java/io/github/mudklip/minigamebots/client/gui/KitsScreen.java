package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The kit editor: your own inventory is the editor. "Edit" puts your things aside and loads the kit
 * (or an empty one), you arrange it like any inventory, then "Save" stores it and gives your things
 * back. Saved kits can be picked for training worlds, the same for both sides.
 */
public final class KitsScreen extends Screen {
	private final Screen parent;
	private EditBox name;
	private final List<int[]> chips = new ArrayList<>();

	public KitsScreen(Screen parent) {
		super(Component.literal("Kits"));
		this.parent = parent;
	}

	private static void cmd(String c) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand(c);
		}
	}

	private String kitName() {
		return name.getValue().trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
	}

	@Override
	protected void init() {
		int x = width / 2 - 150;
		name = addRenderableWidget(new EditBox(font, x, 60, 300, 18, Component.literal("Kit name")));
		name.setMaxLength(32);
		name.setHint(Component.literal("Kit name, e.g. my_pvp"));
		boolean inWorld = minecraft.player != null;
		Button edit = addRenderableWidget(Button.builder(Component.literal("✎ Edit in my inventory"), b -> {
			if (!kitName().isEmpty()) {
				cmd("tg kit edit " + kitName());
				minecraft.gui.setScreen(null);
			}
		}).pos(x, 84).width(148).tooltip(Tooltip.create(Component.literal(
				"Your things are put aside and the kit (or an empty one) goes in your inventory. Arrange it, then come back and Save.")))
				.build());
		Button save = addRenderableWidget(Button.builder(Component.literal("§a✔ Save my inventory as kit"), b -> {
			String n = kitName();
			if (!n.isEmpty()) {
				cmd("tg kit save " + n);
				MgbConfig c = MgbConfig.get();
				if (!c.customKits.contains(n)) {
					c.customKits.add(n);
					MgbConfig.HANDLER.save();
				}
			}
		}).pos(x + 152, 84).width(148).tooltip(Tooltip.create(Component.literal(
				"Saves what's in your inventory now (armour and off hand too), then gives your own things back."))).build());
		Button cancel = addRenderableWidget(Button.builder(Component.literal("Give my things back"), b -> cmd("tg kit cancel"))
				.pos(x, 108).width(148).tooltip(Tooltip.create(Component.literal("Stop editing without saving."))).build());
		Button delete = addRenderableWidget(Button.builder(Component.literal("§cDelete kit"), b -> {
			cmd("tg kit delete " + kitName());
			MgbConfig.get().customKits.remove(kitName());
			MgbConfig.HANDLER.save();
		}).pos(x + 152, 108).width(148).build());
		edit.active = save.active = cancel.active = delete.active = inWorld;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width / 2 - 50, height - 28).width(100).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int x = width / 2 - 150;
		g.text(font, Component.literal("§f§lKits"), 8, 8, Ui.TEXT, false);
		g.text(font, Component.literal("§7Your inventory is the editor. Training worlds give the same kit to both sides."), 8, 20, Ui.MUTED,
				false);
		g.text(font, Component.literal("§7Name (pick one below to edit it):"), x, 48, Ui.MUTED, false);
		chips.clear();
		int cx = x, cy = 152;
		g.text(font, Component.literal("§fYour kits:" + (MgbConfig.get().customKits.isEmpty() ? " §7none yet" : "")), x, cy - 12, Ui.TEXT, false);
		List<String> kits = MgbConfig.get().customKits;
		for (int i = 0; i < kits.size(); i++) {
			int w = font.width(kits.get(i)) + 14;
			if (cx + w > x + 300) {
				cx = x;
				cy += 17;
			}
			Ui.chip(g, font, kits.get(i), cx, cy, w, kits.get(i).equals(kitName()), Ui.inside(mouseX, mouseY, cx, cy, w, 13), Ui.ACCENT);
			chips.add(new int[] {cx, cy, w, i});
			cx += w + 3;
		}
		if (minecraft.player == null) {
			g.text(font, Component.literal("§eJoin a world to edit kits."), x, cy + 22, Ui.TEXT, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int[] c : chips) {
			if (Ui.inside(event.x(), event.y(), c[0], c[1], c[2], 13)) {
				name.setValue(MgbConfig.get().customKits.get(c[3]));
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
