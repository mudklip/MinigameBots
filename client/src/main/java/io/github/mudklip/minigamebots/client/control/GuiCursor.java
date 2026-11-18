package io.github.mudklip.minigamebots.client.control;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * The AI's own mouse cursor inside screens (inventories, chests, menus). It never touches the
 * real OS cursor: it moves Minecraft's idea of where the mouse is and sends click events to the
 * open screen, the same events a real mouse produces.
 */
public final class GuiCursor {
	private static final int LEFT = 1;
	private static final int RIGHT = 3;

	private boolean leftDown;
	private boolean rightDown;
	private int modifiers;

	/** Moves the cursor by a camera-style delta in degrees. */
	public void move(Minecraft client, Screen screen, float yawDeg, float pitchDeg) {
		if (yawDeg == 0 && pitchDeg == 0) {
			return;
		}
		MouseHandler mouse = client.mouseHandler;
		var window = client.getWindow();
		double scale = (double) window.getScreenWidth() / window.getGuiScaledWidth();
		double perDegree = MgbConfig.get().guiCursorPixelsPerDegree * scale;
		double x = clamp(mouse.xpos() + yawDeg * perDegree, 0, window.getScreenWidth() - 1);
		double y = clamp(mouse.ypos() + pitchDeg * perDegree, 0, window.getScreenHeight() - 1);
		double dx = MouseHandler.getScaledXPos(window, x - mouse.xpos());
		double dy = MouseHandler.getScaledYPos(window, y - mouse.ypos());
		((MouseHandlerAccessor) mouse).minigamebots$setXpos(x);
		((MouseHandlerAccessor) mouse).minigamebots$setYpos(y);

		double gx = mouse.getScaledXPos(window);
		double gy = mouse.getScaledYPos(window);
		screen.mouseMoved(gx, gy);
		if (leftDown) {
			screen.mouseDragged(new MouseButtonEvent(gx, gy, new MouseButtonInfo(LEFT, modifiers)), dx, dy);
		} else if (rightDown) {
			screen.mouseDragged(new MouseButtonEvent(gx, gy, new MouseButtonInfo(RIGHT, modifiers)), dx, dy);
		}
		screen.afterMouseMove();
	}

	/** Attack acts as the left button and use as the right button while a screen is open. */
	void buttons(Minecraft client, Screen screen, GameInput input, int mods) {
		this.modifiers = mods;
		leftDown = button(client, screen, LEFT, leftDown, input.holds(VirtualKey.ATTACK), input.attackClicks());
		rightDown = button(client, screen, RIGHT, rightDown, input.holds(VirtualKey.USE), input.useClicks());
	}

	private boolean button(Minecraft client, Screen screen, int button, boolean down, boolean held, int clicks) {
		for (int i = 0; i < clicks && client.gui.screen() == screen; i++) {
			if (down) {
				send(client, screen, button, false);
			}
			send(client, screen, button, true);
			down = true;
			if (i < clicks - 1 || !held) {
				send(client, screen, button, false);
				down = false;
			}
		}
		if (down && !held) {
			send(client, screen, button, false);
			down = false;
		}
		return down;
	}

	void releaseButtons(Minecraft client) {
		Screen screen = client.gui.screen();
		if (screen != null) {
			if (leftDown) {
				send(client, screen, LEFT, false);
			}
			if (rightDown) {
				send(client, screen, RIGHT, false);
			}
		}
		leftDown = rightDown = false;
	}

	private void send(Minecraft client, Screen screen, int button, boolean press) {
		if (client.gui.screen() != screen) {
			return;
		}
		var window = client.getWindow();
		MouseButtonEvent event = new MouseButtonEvent(client.mouseHandler.getScaledXPos(window),
				client.mouseHandler.getScaledYPos(window), new MouseButtonInfo(button, modifiers));
		if (press) {
			screen.afterMouseAction();
			screen.mouseClicked(event, false);
		} else {
			screen.mouseReleased(event);
		}
	}

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}
}
