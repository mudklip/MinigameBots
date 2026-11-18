package io.github.mudklip.minigamebots.client.control;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mudklip.minigamebots.client.MainScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * MinigameBots hotkeys. They are normal key bindings, so they show up (and can be rebound) in
 * Options > Controls > Key Binds under "MinigameBots".
 *
 * <p>They are handled straight from the keyboard event rather than in the tick, so they still
 * work while the AI blocks the rest of your keyboard, and while a screen is open.
 */
public final class Hotkeys {
	private static final int ACTION_PRESS = 1;
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minigamebots", "main"));
	private static final SystemToast.SystemToastId NOTICE = new SystemToast.SystemToastId();

	public static final KeyMapping RECORD = register("record", InputConstants.KEY_F7);
	public static final KeyMapping TOGGLE_CONTROL = register("toggle_control", InputConstants.KEY_F8);
	public static final KeyMapping EMERGENCY_STOP = register("emergency_stop", InputConstants.KEY_F9);
	public static final KeyMapping OPEN_MENU = register("open_menu", InputConstants.KEY_F10);

	private static KeyMapping register(String name, int key) {
		return KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.minigamebots." + name, InputConstants.Type.KEYBOARD, key, CATEGORY));
	}

	/** Loads the class so the bindings get registered. */
	public static void init() {
	}

	/** @return true if the key event was one of our hotkeys and has been handled */
	public static boolean onKey(Minecraft client, int action, KeyEvent event) {
		if (action != ACTION_PRESS) {
			return false;
		}
		AgentControl control = AgentControl.get();
		if (EMERGENCY_STOP.matches(event)) {
			control.emergencyStop(client);
			return true;
		}
		// With a screen open and the human in control, leave keys alone (text fields etc.).
		if (client.gui.screen() != null && !control.active()) {
			return false;
		}
		if (TOGGLE_CONTROL.matches(event)) {
			control.toggle(client);
			return true;
		}
		if (OPEN_MENU.matches(event)) {
			if (control.active()) {
				control.stop(client, Component.translatable("minigamebots.control.human_on"));
			}
			client.gui.setScreen(new MainScreen(client.gui.screen()));
			return true;
		}
		if (RECORD.matches(event)) {
			SystemToast.addOrUpdate(client.gui.toastManager(), NOTICE, Component.translatable("minigamebots.name"),
					Component.translatable("minigamebots.recorder.coming_soon"));
			return true;
		}
		return false;
	}

	private Hotkeys() {
	}
}
