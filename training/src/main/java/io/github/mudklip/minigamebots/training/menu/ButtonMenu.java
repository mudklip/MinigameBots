package io.github.mudklip.minigamebots.training.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * A chest-style menu whose items are buttons. Works for every player, with or without the
 * MinigameBots client mod, because it's a plain vanilla chest screen.
 */
public final class ButtonMenu extends ChestMenu {
	/** What a click does. {@code shift} = shift-click, {@code right} = right mouse button. */
	public interface Action {
		void run(ServerPlayer player, boolean right, boolean shift);
	}

	/** Collects buttons for one menu. */
	public static final class Builder {
		final SimpleContainer items;
		final Map<Integer, Action> actions = new HashMap<>();

		Builder(int rows) {
			items = new SimpleContainer(rows * 9);
		}

		public Builder button(int slot, Item icon, String name, List<String> lore, Action action) {
			ItemStack stack = new ItemStack(icon);
			// Hide "+5 Armor" style lines: these items are buttons, not gear.
			stack.set(DataComponents.TOOLTIP_DISPLAY, new net.minecraft.world.item.component.TooltipDisplay(false,
					new java.util.LinkedHashSet<>(List.of(DataComponents.ATTRIBUTE_MODIFIERS))));
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(Style.EMPTY.withItalic(false)));
			if (!lore.isEmpty()) {
				stack.set(DataComponents.LORE, new ItemLore(lore.stream()
						.map(l -> (Component) Component.literal(l).withStyle(Style.EMPTY.withItalic(false).withColor(0xAAAAAA)))
						.toList()));
			}
			items.setItem(slot, stack);
			if (action != null) {
				actions.put(slot, action);
			}
			return this;
		}
	}

	private final Map<Integer, Action> actions;
	private final int buttons;

	private ButtonMenu(MenuType<?> type, int id, Inventory inventory, Builder b, int rows) {
		super(type, id, inventory, b.items, rows);
		this.actions = b.actions;
		this.buttons = rows * 9;
	}

	public static void open(ServerPlayer player, String title, int rows, java.util.function.Consumer<Builder> fill) {
		int r = Math.max(1, Math.min(6, rows));
		Builder b = new Builder(r);
		fill.accept(b);
		MenuType<?> type = switch (r) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ButtonMenu(type, id, inv, b, r), Component.literal(title)));
	}

	@Override
	public void clicked(int slot, int button, ContainerInput input, Player player) {
		// Nothing can be taken out or put in; clicks on our slots run their button.
		if (slot >= 0 && slot < buttons && player instanceof ServerPlayer sp
				&& (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
			Action a = actions.get(slot);
			if (a != null) {
				boolean shift = input == ContainerInput.QUICK_MOVE;
				boolean right = button == 1;
				// Run after this click finishes, so an action may open another menu.
				sp.level().getServer().execute(() -> a.run(sp, right, shift));
			}
		}
		setCarried(ItemStack.EMPTY);
		sendAllDataToRemote();
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
