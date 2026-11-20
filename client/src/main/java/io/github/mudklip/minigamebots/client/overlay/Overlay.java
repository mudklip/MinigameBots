package io.github.mudklip.minigamebots.client.overlay;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Everything this mod draws over the game (A3): popups, the REC badge, the AI-control line and
 * its toasts. They are drawn in a second, small GUI pass right after the frame has been handed
 * to {@link io.github.mudklip.minigamebots.client.observe.FrameCapture}, so the AI's view and the
 * recordings never contain them, while the vanilla HUD and other mods' HUD (AppleSkin...) are
 * still in the captured frame.
 *
 * <p>The second pass reuses the game's own GUI renderer, found by type at the first frame. If
 * that isn't possible on this version (or it ever throws), everything falls back to the old way:
 * vanilla toasts and action bar, the REC badge as a normal HUD element.
 */
public final class Overlay {
	/** Something this mod draws on top; returns true if it drew anything this frame. */
	@FunctionalInterface
	public interface Piece {
		boolean draw(GuiGraphicsExtractor g, Minecraft mc, int width, int height);
	}

	private static final List<Piece> PIECES = new CopyOnWriteArrayList<>();
	private static final long TOAST_MS = 5000;
	private static final int TOAST_W = 160;
	private static final int MAX_TOASTS = 5;
	private static final long MESSAGE_MS = 3000;

	private enum Mode {
		UNKNOWN,
		DEFERRED,
		FALLBACK
	}

	private static volatile Mode mode = Mode.UNKNOWN;
	private static @Nullable Object guiState;
	private static @Nullable Method reset;
	private static @Nullable Object guiRenderer;
	private static @Nullable Method render;
	private static @Nullable Constructor<?> extractor;
	private static boolean extractorTakesMouse;

	private record Toast(SystemToast.SystemToastId id, Component title, Component body, long since) {
	}

	private static final Deque<Toast> TOASTS = new ArrayDeque<>();
	private static @Nullable Component message;
	private static long messageAt;

	static {
		PIECES.add(Overlay::drawMessage);
		PIECES.add(Overlay::drawToasts);
	}

	private Overlay() {
	}

	/** True when this mod's drawing happens after the AI's snapshot (normal case). */
	public static boolean deferred() {
		return mode == Mode.DEFERRED;
	}

	/** Adds something to draw after the snapshot (popups, badges...). */
	public static void add(Piece piece) {
		PIECES.add(piece);
	}

	/** A toast of this mod (replaces the one with the same id), kept out of the AI's view. */
	public static void toast(SystemToast.SystemToastId id, Component title, Component body) {
		Minecraft mc = Minecraft.getInstance();
		if (!deferred()) {
			mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(), id, title, body));
			return;
		}
		synchronized (TOASTS) {
			TOASTS.removeIf(t -> t.id() == id);
			TOASTS.addLast(new Toast(id, title, body, System.currentTimeMillis()));
			while (TOASTS.size() > MAX_TOASTS) {
				TOASTS.removeFirst();
			}
		}
	}

	/**
	 * A short line above the hotbar (where the action bar goes), e.g. "AI is playing". Not the
	 * vanilla action bar, so the AI doesn't read its own status line as game text.
	 */
	public static void message(Component text) {
		Minecraft mc = Minecraft.getInstance();
		if (!deferred()) {
			mc.execute(() -> {
				if (mc.player != null) {
					mc.player.sendOverlayMessage(text);
				}
			});
			return;
		}
		message = text;
		messageAt = System.currentTimeMillis();
	}

	/** Called on the render thread right after the frame was handed to the capture. */
	public static void afterCapture(Minecraft mc) {
		if (mode == Mode.UNKNOWN) {
			mode = probe(mc) ? Mode.DEFERRED : Mode.FALLBACK;
			MinigameBotsClient.LOGGER.info("MinigameBots overlays: {}", mode == Mode.DEFERRED
					? "drawn after the AI's snapshot" : "drawn with the HUD (couldn't find the GUI renderer)");
		}
		if (mode != Mode.DEFERRED) {
			return;
		}
		try {
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			reset.invoke(guiState);
			GuiGraphicsExtractor g = (GuiGraphicsExtractor) (extractorTakesMouse ? extractor.newInstance(mc, guiState, 0, 0)
					: extractor.newInstance(mc, guiState));
			boolean drew = false;
			for (Piece p : PIECES) {
				drew |= p.draw(g, mc, w, h);
			}
			if (drew) {
				render.invoke(guiRenderer);
			} else {
				reset.invoke(guiState);
			}
		} catch (Throwable e) {
			MinigameBotsClient.LOGGER.warn("MinigameBots overlay pass failed; drawing with the HUD from now on", e);
			fallBack(mc);
		}
	}

	/** Back to the vanilla way, handing over what's still showing. */
	private static void fallBack(Minecraft mc) {
		mode = Mode.FALLBACK;
		List<Toast> left;
		synchronized (TOASTS) {
			left = new ArrayList<>(TOASTS);
			TOASTS.clear();
		}
		for (Toast t : left) {
			toast(t.id(), t.title(), t.body());
		}
		Component m = message;
		message = null;
		if (m != null && System.currentTimeMillis() - messageAt < MESSAGE_MS) {
			message(m);
		}
	}

	// ---- Built-in pieces ----

	private static boolean drawMessage(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		Component m = message;
		if (m == null || mc.gui.screen() != null) {
			return false;
		}
		long age = System.currentTimeMillis() - messageAt;
		if (age > MESSAGE_MS) {
			message = null;
			return false;
		}
		// Fade out over the last half second, like the action bar.
		int alpha = age < MESSAGE_MS - 500 ? 255 : (int) Math.max(8, 255 * (MESSAGE_MS - age) / 500);
		Font font = mc.font;
		int tw = font.width(m);
		int x = (w - tw) / 2;
		int y = h - 68;
		g.fill(x - 3, y - 2, x + tw + 3, y + 10, (alpha * 0x60 / 255) << 24);
		g.text(font, m, x, y, (alpha << 24) | 0xFFFFFF, true);
		return true;
	}

	private static boolean drawToasts(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		List<Toast> showing;
		long now = System.currentTimeMillis();
		synchronized (TOASTS) {
			TOASTS.removeIf(t -> now - t.since() > TOAST_MS);
			if (TOASTS.isEmpty()) {
				return false;
			}
			showing = new ArrayList<>(TOASTS);
		}
		Font font = mc.font;
		int y = 0;
		for (Toast t : showing) {
			long age = now - t.since();
			// Slide in from the right for 0.25 s, out for the last 0.25 s.
			double shown = Math.min(1.0, Math.min(age, TOAST_MS - age) / 250.0);
			int x = w - (int) Math.round(TOAST_W * shown);
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(t.body(), TOAST_W - 14);
			int lineCount = Math.min(2, lines.size());
			int hgt = 20 + lineCount * 10 + (lineCount == 0 ? -8 : 0);
			g.fill(x, y, x + TOAST_W, y + hgt, 0xE0202020);
			g.fill(x, y, x + 2, y + hgt, 0xFF4F8EDB);
			g.text(font, t.title(), x + 8, y + 6, 0xFFFFFF00, false);
			for (int i = 0; i < lineCount; i++) {
				g.text(font, lines.get(i), x + 8, y + 17 + i * 10, 0xFFFFFFFF, false);
			}
			y += hgt + 2;
			if (y > h - 32) {
				break;
			}
		}
		return true;
	}

	// ---- Finding the game's GUI renderer (by type, so renamed fields don't matter) ----

	private static boolean probe(Minecraft mc) {
		try {
			List<Object> roots = List.of(mc.gui, mc.gameRenderer);
			guiState = find(roots, "GuiRenderState");
			guiRenderer = find(roots, "GuiRenderer");
			if (guiState == null || guiRenderer == null) {
				return false;
			}
			reset = guiState.getClass().getMethod("reset");
			render = guiRenderer.getClass().getMethod("render");
			for (Constructor<?> c : GuiGraphicsExtractor.class.getDeclaredConstructors()) {
				Class<?>[] p = c.getParameterTypes();
				boolean start = p.length >= 2 && p[0] == Minecraft.class && p[1].isInstance(guiState);
				if (start && p.length == 4 && p[2] == int.class && p[3] == int.class) {
					extractor = c;
					extractorTakesMouse = true;
					break;
				}
				if (start && p.length == 2 && extractor == null) {
					extractor = c;
				}
			}
			if (extractor == null) {
				return false;
			}
			extractor.setAccessible(true);
			reset.setAccessible(true);
			render.setAccessible(true);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			MinigameBotsClient.LOGGER.debug("No separate overlay pass", e);
			return false;
		}
	}

	/** The first object of a class named {@code simpleName} reachable in two steps from {@code roots}. */
	private static @Nullable Object find(List<Object> roots, String simpleName) {
		Map<Object, Boolean> seen = new IdentityHashMap<>();
		List<Object> level = new ArrayList<>(roots);
		for (int depth = 0; depth < 3 && !level.isEmpty(); depth++) {
			List<Object> next = new ArrayList<>();
			for (Object o : level) {
				if (o == null || seen.put(o, Boolean.TRUE) != null) {
					continue;
				}
				if (o.getClass().getSimpleName().equals(simpleName)) {
					return o;
				}
				for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
					for (Field f : k.getDeclaredFields()) {
						if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()
								|| !f.getType().getName().startsWith("net.minecraft.")) {
							continue;
						}
						try {
							f.setAccessible(true);
							Object v = f.get(o);
							if (v != null) {
								if (f.getType().getSimpleName().equals(simpleName) || v.getClass().getSimpleName().equals(simpleName)) {
									return v;
								}
								next.add(v);
							}
						} catch (ReflectiveOperationException | RuntimeException ignored) {
							// Not readable: skip.
						}
					}
				}
			}
			level = next;
		}
		return null;
	}
}
