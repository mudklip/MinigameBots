package io.github.mudklip.minigamebots.client.control;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mudklip.minigamebots.client.mixin.KeyMappingAccessor;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

/**
 * Turns a {@link GameInput} into the state of Minecraft's own key bindings. Vanilla then does
 * everything else (movement, cooldowns, packets) exactly as for a human.
 */
public final class VirtualInput {
	private static final int SHIFT_MODIFIER = 1;

	private final Set<VirtualKey> previous = EnumSet.noneOf(VirtualKey.class);
	private final GuiCursor cursor;

	public VirtualInput(GuiCursor cursor) {
		this.cursor = cursor;
	}

	public void apply(Minecraft client, GameInput input) {
		Screen screen = client.gui.screen();
		if (screen == null) {
			applyInWorld(client.options, input);
		} else {
			releaseBindings(client.options);
			applyInGui(client, screen, input);
		}
		previous.clear();
		previous.addAll(input.held());
	}

	/** Releases every key the AI was holding. */
	public void releaseAll(Minecraft client) {
		releaseBindings(client.options);
		cursor.releaseButtons(client);
		previous.clear();
	}

	private void applyInWorld(Options options, GameInput input) {
		cursor.releaseButtons(Minecraft.getInstance());
		for (VirtualKey key : VirtualKey.values()) {
			KeyMappingAccessor mapping = (KeyMappingAccessor) binding(options, key);
			boolean held = input.holds(key);
			mapping.minigamebots$setDownRaw(held);
			int clicks = switch (key) {
				case ATTACK -> input.attackClicks();
				case USE -> input.useClicks();
				default -> key.clicks() && held && !previous.contains(key) ? 1 : 0;
			};
			if (clicks > 0) {
				mapping.minigamebots$setClickCount(mapping.minigamebots$getClickCount() + clicks);
			}
		}
	}

	private void applyInGui(Minecraft client, Screen screen, GameInput input) {
		int modifiers = input.holds(VirtualKey.SNEAK) ? SHIFT_MODIFIER : 0;
		cursor.buttons(client, screen, input, modifiers);
		// Keyboard keys that do something in screens: inventory closes, hotbar keys swap, drop drops.
		for (VirtualKey key : VirtualKey.values()) {
			if (key.kind() != VirtualKey.Kind.CLICK || !input.holds(key) || previous.contains(key)) {
				continue;
			}
			InputConstants.Key bound = ((KeyMappingAccessor) binding(client.options, key)).minigamebots$getKey();
			if (bound.getType() != InputConstants.Type.KEYBOARD) {
				continue;
			}
			KeyEvent event = new KeyEvent(bound.getValue(), 0, modifiers);
			if (client.gui.screen() == screen) {
				screen.keyPressed(event);
			}
			if (client.gui.screen() == screen) {
				screen.keyReleased(event);
			}
		}
	}

	private static void releaseBindings(Options options) {
		for (VirtualKey key : VirtualKey.values()) {
			((KeyMappingAccessor) binding(options, key)).minigamebots$setDownRaw(false);
		}
	}

	static KeyMapping binding(Options options, VirtualKey key) {
		int slot = key.hotbarSlot();
		if (slot >= 0) {
			return options.keyHotbarSlots[slot];
		}
		return switch (key) {
			case FORWARD -> options.keyUp;
			case BACK -> options.keyDown;
			case LEFT -> options.keyLeft;
			case RIGHT -> options.keyRight;
			case JUMP -> options.keyJump;
			case SNEAK -> options.keyShift;
			case SPRINT -> options.keySprint;
			case ATTACK -> options.keyAttack;
			case USE -> options.keyUse;
			case PICK -> options.keyPickItem;
			case DROP -> options.keyDrop;
			case SWAP_OFFHAND -> options.keySwapOffhand;
			case INVENTORY -> options.keyInventory;
			default -> throw new IllegalStateException("unmapped key " + key);
		};
	}

	/** For debugging: the key binding each virtual key currently uses. */
	public static Map<VirtualKey, KeyMapping> bindings(Options options) {
		Map<VirtualKey, KeyMapping> map = new EnumMap<>(VirtualKey.class);
		for (VirtualKey key : VirtualKey.values()) {
			map.put(key, binding(options, key));
		}
		return map;
	}
}
