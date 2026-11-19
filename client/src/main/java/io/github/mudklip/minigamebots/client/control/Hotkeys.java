package io.github.mudklip.minigamebots.client.control;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mudklip.minigamebots.client.MainScreen;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
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

	public static final KeyMapping RECORD = register("record", InputConstants.KEY_F7);
	public static final KeyMapping TOGGLE_CONTROL = register("toggle_control", InputConstants.KEY_F8);
	public static final KeyMapping EMERGENCY_STOP = register("emergency_stop", InputConstants.KEY_F9);
	public static final KeyMapping OPEN_MENU = register("open_menu", InputConstants.KEY_F10);
	public static final KeyMapping MARK_GOOD = register("mark_good", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping MARK_MISTAKE = register("mark_mistake", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping MARK_BASE = register("mark_base", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping PUSH_TO_TALK = register("push_to_talk", InputConstants.KEY_B);

	/**
	 * F8 with nothing to resume: let the selected AI play in free play (it fights what attacks
	 * it, eats, explores, follows its goals). Returns false (and says why) if it can't.
	 */
	private static boolean playSelectedAi(net.minecraft.client.Minecraft client) {
		var mgb = io.github.mudklip.minigamebots.client.MinigameBotsClient.instance();
		var profile = mgb.profiles().active();
		String problem = !mgb.brain().runtime().installed() ? "Set up the brain first: F10 > Brain > Install"
				: profile == null ? "Pick an AI first: F10 > AI Profiles > Use this AI" : null;
		if (problem != null) {
			client.gui.hud.setOverlayMessage(Component.literal(problem), false);
			return true;
		}
		mgb.brain().playManager(client, profile, new com.google.gson.JsonArray(), true, new com.google.gson.JsonObject());
		return true;
	}

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
		// While you're typing in a text box (chat, signs, search), keys belong to the text box.
		var screen = client.gui.screen();
		if (screen != null && !control.active() && screen.getFocused() instanceof EditBox) {
			return false;
		}
		if (TOGGLE_CONTROL.matches(event)) {
			if (!control.active() && control.source() == ActionSource.IDLE && playSelectedAi(client)) {
				return true;
			}
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
		var recorder = MinigameBotsClient.instance().recorder();
		if (RECORD.matches(event)) {
			recorder.toggle(client);
			return true;
		}
		if (!MARK_GOOD.isUnbound() && MARK_GOOD.matches(event)) {
			recorder.mark(client, "good");
			return true;
		}
		if (!MARK_MISTAKE.isUnbound() && MARK_MISTAKE.matches(event)) {
			recorder.mark(client, "mistake");
			return true;
		}
		if (!MARK_BASE.isUnbound() && MARK_BASE.matches(event)) {
			// Remember it as a base for the active AI; in a recording it's also a lesson ("this is
			// where a base goes") that the AI learns to repeat.
			var w = io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().memory().mark(client, "base", false);
			if (recorder.recording()) {
				recorder.mark(client, "base");
			} else if (w != null) {
				client.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.translatable("minigamebots.memory.base_marked"), false);
			}
			return true;
		}
		return false;
	}

	private Hotkeys() {
	}
}
