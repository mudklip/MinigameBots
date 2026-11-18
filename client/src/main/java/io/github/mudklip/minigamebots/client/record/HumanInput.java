package io.github.mudklip.minigamebots.client.record;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.GameInput;
import io.github.mudklip.minigamebots.client.control.VirtualInput;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import java.util.EnumSet;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Watches the real keyboard and mouse and turns what the human does into the same
 * {@link GameInput} format the AI uses, one per tick. That's what makes recordings directly
 * usable as training examples.
 *
 * <p>Keys are matched through the player's own key bindings, so rebinding doesn't matter. The
 * camera is measured from how the player's view actually turned; in menus, cursor movement is
 * converted to degrees the same way the AI's virtual cursor works.
 */
public final class HumanInput {
	private static final int PRESS = 1;
	private static final int RELEASE = 0;
	/** Larger turns in one tick are teleports or respawns, not mouse movement. */
	private static final float MAX_PLAUSIBLE_TURN = 90f;

	private final EnumSet<VirtualKey> held = EnumSet.noneOf(VirtualKey.class);
	private final EnumSet<VirtualKey> pressed = EnumSet.noneOf(VirtualKey.class);
	private int attackPresses;
	private int usePresses;

	private boolean haveRotation;
	private float lastYaw;
	private float lastPitch;
	private boolean wasInGui;
	private double lastCursorX;
	private double lastCursorY;
	private boolean cameraJump;

	public synchronized void onKey(Minecraft client, int action, KeyEvent event) {
		if (action == PRESS && !keysAreGameInput(client.gui.screen())) {
			// Typing into chat, a sign or a search box isn't playing: don't record it as key presses.
			return;
		}
		for (Map.Entry<VirtualKey, KeyMapping> e : VirtualInput.bindings(client.options).entrySet()) {
			if (e.getValue().matches(event)) {
				update(e.getKey(), action);
			}
		}
	}

	public synchronized void onMouseButton(Minecraft client, MouseButtonInfo info, int action) {
		MouseButtonEvent event = new MouseButtonEvent(0, 0, info);
		for (Map.Entry<VirtualKey, KeyMapping> e : VirtualInput.bindings(client.options).entrySet()) {
			if (e.getValue().matchesMouse(event)) {
				update(e.getKey(), action);
			}
		}
	}

	/**
	 * In the world every key is game input. In inventories and containers, keys like inventory,
	 * hotbar numbers and drop still act on the game, unless a text box (e.g. creative search)
	 * has focus. Other screens (chat, signs, menus) are typing, not playing.
	 */
	static boolean keysAreGameInput(@Nullable Screen screen) {
		if (screen == null) {
			return true;
		}
		if (screen instanceof CreativeModeInventoryScreen) {
			// Its search box takes typing without being reported as focused.
			return false;
		}
		return screen instanceof AbstractContainerScreen<?> && !(screen.getFocused() instanceof EditBox);
	}

	private void update(VirtualKey key, int action) {
		if (action == PRESS) {
			if (!held.contains(key)) {
				pressed.add(key);
				if (key == VirtualKey.ATTACK) {
					attackPresses++;
				} else if (key == VirtualKey.USE) {
					usePresses++;
				}
			}
			held.add(key);
		} else if (action == RELEASE) {
			held.remove(key);
		}
	}

	/** Forget everything, e.g. when a recording starts. */
	public synchronized void reset(Minecraft client) {
		pressed.clear();
		attackPresses = usePresses = 0;
		haveRotation = false;
		wasInGui = client.gui.screen() != null;
		lastCursorX = client.mouseHandler.xpos();
		lastCursorY = client.mouseHandler.ypos();
	}

	/** True if the last snapshot's camera movement was a jump (teleport, respawn) and was zeroed. */
	public boolean lastWasCameraJump() {
		return cameraJump;
	}

	/** What the human did since the previous call, as one tick of input. */
	public synchronized GameInput snapshot(Minecraft client) {
		EnumSet<VirtualKey> keys = EnumSet.copyOf(held.isEmpty() ? EnumSet.noneOf(VirtualKey.class) : held);
		// A tap that was pressed and released within one tick still counts as held for that tick.
		for (VirtualKey key : pressed) {
			if (key.kind() == VirtualKey.Kind.CLICK) {
				keys.add(key);
			}
		}
		int attacks = attackPresses;
		int uses = usePresses;
		pressed.clear();
		attackPresses = usePresses = 0;

		float yaw = 0;
		float pitch = 0;
		cameraJump = false;
		boolean inGui = client.gui.screen() != null;
		LocalPlayer player = client.player;
		if (inGui) {
			if (wasInGui) {
				var window = client.getWindow();
				double scale = (double) window.getScreenWidth() / window.getGuiScaledWidth();
				double perDegree = MgbConfig.get().guiCursorPixelsPerDegree * scale;
				yaw = (float) ((client.mouseHandler.xpos() - lastCursorX) / perDegree);
				pitch = (float) ((client.mouseHandler.ypos() - lastCursorY) / perDegree);
			}
			lastCursorX = client.mouseHandler.xpos();
			lastCursorY = client.mouseHandler.ypos();
		} else if (player != null) {
			if (haveRotation && !wasInGui) {
				yaw = Mth.wrapDegrees(player.getYRot() - lastYaw);
				pitch = player.getXRot() - lastPitch;
				if (Math.abs(yaw) > MAX_PLAUSIBLE_TURN || Math.abs(pitch) > MAX_PLAUSIBLE_TURN) {
					yaw = pitch = 0;
					cameraJump = true;
				}
			}
			lastYaw = player.getYRot();
			lastPitch = player.getXRot();
			haveRotation = true;
		}
		wasInGui = inGui;
		return new GameInput(keys, attacks, uses, yaw, pitch);
	}
}
