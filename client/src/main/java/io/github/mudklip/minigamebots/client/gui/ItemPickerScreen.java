package io.github.mudklip.minigamebots.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/** Pick an item (e.g. an icon), a block or a creature, with search. */
public final class ItemPickerScreen extends Screen {
	public enum Mode { ITEM, BLOCK, ENTITY }

	private static final int S = 20;
	private final Screen parent;
	private final Consumer<String> onPick;
	private final Mode mode;
	/** {returned id, icon item id, search text}. */
	private final List<String[]> all = new ArrayList<>();
	private List<String[]> shown = List.of();
	private EditBox search;
	private int scroll;

	public ItemPickerScreen(Screen parent, Consumer<String> onPick) {
		this(parent, Mode.ITEM, onPick);
	}

	public ItemPickerScreen(Screen parent, Mode mode, Consumer<String> onPick) {
		super(Component.literal(mode == Mode.ITEM ? "Pick an item" : mode == Mode.BLOCK ? "Pick a block" : "Pick a creature"));
		this.parent = parent;
		this.onPick = onPick;
		this.mode = mode;
		switch (mode) {
			case ITEM -> {
				for (var item : BuiltInRegistries.ITEM) {
					if (item != Items.AIR) {
						String id = BuiltInRegistries.ITEM.getKey(item).toString();
						all.add(new String[] {id, id, id + " " + item.getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT)});
					}
				}
			}
			case BLOCK -> {
				for (var item : BuiltInRegistries.ITEM) {
					if (item instanceof net.minecraft.world.item.BlockItem bi) {
						String id = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
						all.add(new String[] {id, BuiltInRegistries.ITEM.getKey(item).toString(),
								id + " " + item.getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT)});
					}
				}
			}
			case ENTITY -> {
				for (var type : BuiltInRegistries.ENTITY_TYPE) {
					var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
					String egg = key.getNamespace() + ":" + key.getPath() + "_spawn_egg";
					var eggKey = net.minecraft.resources.Identifier.tryParse(egg);
					boolean hasEgg = eggKey != null && BuiltInRegistries.ITEM.containsKey(eggKey);
					String icon = hasEgg ? egg : key.getPath().equals("player") ? "minecraft:player_head" : "minecraft:name_tag";
					all.add(new String[] {key.toString(), icon, key + " " + type.getDescription().getString().toLowerCase(Locale.ROOT)});
				}
			}
		}
	}

	@Override
	protected void init() {
		search = addRenderableWidget(new EditBox(font, 8, 24, Math.min(200, width - 80), 18, Component.literal("Search")));
		search.setHint(Component.literal("§7Search items..."));
		search.setResponder(t -> filter());
		setInitialFocus(search);
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).pos(width - 60, 6).width(52).build());
		filter();
	}

	private void filter() {
		String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).replace(' ', '_');
		String q2 = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
		shown = all.stream().filter(e -> e[2].contains(q) || e[2].contains(q2)).toList();
		scroll = 0;
	}

	private int cols() {
		return Math.max(1, (width - 16) / S);
	}

	private int rows() {
		return Math.max(1, (height - 56) / S);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int total = (shown.size() + cols() - 1) / cols();
		scroll = Math.max(0, Math.min(Math.max(0, total - rows()), scroll - (int) Math.signum(dy) * 2));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int i = index(event.x(), event.y());
		if (i >= 0) {
			onPick.accept(shown.get(i)[0]);
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int index(double mx, double my) {
		if (mx < 8 || my < 48) {
			return -1;
		}
		int c = (int) ((mx - 8) / S);
		int r = (int) ((my - 48) / S);
		if (c >= cols() || r >= rows()) {
			return -1;
		}
		int i = (r + scroll) * cols() + c;
		return i < shown.size() ? i : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§l" + title.getString() + "§r  §7" + shown.size()), 8, 10, Ui.TEXT, false);
		Ui.panel(g, 6, 46, cols() * S + 4, rows() * S + 4);
		int hover = index(mouseX, mouseY);
		for (int r = 0; r < rows(); r++) {
			for (int c = 0; c < cols(); c++) {
				int i = (r + scroll) * cols() + c;
				if (i >= shown.size()) {
					break;
				}
				int x = 8 + c * S;
				int y = 48 + r * S;
				if (i == hover) {
					g.fill(x, y, x + S, y + S, Ui.CARD_SELECTED);
				}
				g.item(Ui.stack(shown.get(i)[1]), x + 2, y + 2);
			}
		}
		if (hover >= 0) {
			String[] h = shown.get(hover);
			String name = h[2].substring(h[0].length()).trim();
			g.text(font, Component.literal("§f" + name + " §8" + h[0]), 8, height - 10, Ui.TEXT, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
