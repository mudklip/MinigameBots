package io.github.mudklip.minigamebots.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.GameInput;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.record.Recorder;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.record.RecordingReader;
import io.github.mudklip.minigamebots.client.record.RecordingStore;
import io.github.mudklip.minigamebots.client.record.TickRecord;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Plays back a recording exactly as the AI will see it: the downscaled frame, the input for each
 * tick and the detected events. Set the trim range and add marks here.
 */
public final class RecordingViewerScreen extends Screen {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("minigamebots", "recording_viewer");
	private static final int[] SPEEDS = {1, 2, 4, 8};
	private static final int KEY_SPACE = 44;
	private static final int KEY_RIGHT = 79;
	private static final int KEY_LEFT = 80;

	private final Screen parent;
	private final RecordingStore store;
	private final RecordingMeta meta;
	private final RecordingReader reader;

	private DynamicTexture texture;
	private int tick;
	private int shownTick = -1;
	private @Nullable TickRecord current;
	private boolean playing;
	private int speedIndex;
	private boolean dirty;
	private boolean hasFrame;
	private Button playButton;
	private Button speedButton;

	private int frameX;
	private int frameY;
	private int frameSize;
	private int barX;
	private int barY;
	private int barWidth;

	public RecordingViewerScreen(Screen parent, RecordingStore store, RecordingMeta meta) {
		super(Component.literal(meta.name));
		this.parent = parent;
		this.store = store;
		this.meta = meta;
		this.reader = new RecordingReader(store.dir(meta.id), meta);
		this.tick = meta.firstUsableTick();
	}

	@Override
	protected void init() {
		if (texture == null) {
			int size = Math.max(1, meta.frameSize);
			texture = new DynamicTexture(() -> "MinigameBots recording viewer", size, size, false);
			minecraft.getTextureManager().register(TEXTURE, texture);
		}
		frameSize = Math.min(height - 110, width / 2 - 20);
		frameX = 10;
		frameY = 26;
		barX = 10;
		barY = height - 74;
		barWidth = width - 20;

		int y = height - 58;
		int x = 10;
		x = add(x, y, 30, "⏮", b -> seek(tick - 100));
		x = add(x, y, 22, "◀", b -> step(-1));
		playButton = Button.builder(Component.literal("▶"), b -> togglePlay()).pos(x, y).width(30).build();
		addRenderableWidget(playButton);
		x += 34;
		x = add(x, y, 22, "▶|", b -> step(1));
		x = add(x, y, 30, "⏭", b -> seek(tick + 100));
		speedButton = Button.builder(speedLabel(), b -> {
			speedIndex = (speedIndex + 1) % SPEEDS.length;
			b.setMessage(speedLabel());
		}).pos(x, y).width(36).build();
		addRenderableWidget(speedButton);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.pos(width - 70, y).width(60).build());

		// Second row: five buttons sharing the full width.
		int y2 = height - 32;
		int w2 = Math.min(80, (width - 20 - 4 * 4) / 5);
		int x2 = 10;
		x2 = addT(x2, y2, w2, "minigamebots.viewer.trim_start", b -> setTrim(true));
		x2 = addT(x2, y2, w2, "minigamebots.viewer.trim_end", b -> setTrim(false));
		x2 = addT(x2, y2, w2, "minigamebots.viewer.trim_clear", b -> {
			meta.trimStart = meta.trimEnd = -1;
			dirty = true;
		});
		x2 = addT(x2, y2, w2, "minigamebots.viewer.mark_good", b -> addMark("good"));
		addT(x2, y2, w2, "minigamebots.viewer.mark_mistake", b -> addMark("mistake"));
		load();
	}

	private int add(int x, int y, int w, String label, Button.OnPress press) {
		addRenderableWidget(Button.builder(Component.literal(label), press).pos(x, y).width(w).build());
		return x + w + 4;
	}

	private int addT(int x, int y, int w, String key, Button.OnPress press) {
		addRenderableWidget(Button.builder(Component.translatable(key), press).pos(x, y).width(w).build());
		return x + w + 4;
	}

	private Component speedLabel() {
		return Component.literal(SPEEDS[speedIndex] + "x");
	}

	private void togglePlay() {
		playing = !playing;
		playButton.setMessage(Component.literal(playing ? "❚❚" : "▶"));
	}

	private void step(int delta) {
		if (playing) {
			togglePlay();
		}
		seek(tick + delta);
	}

	private void seek(int t) {
		tick = Math.max(0, Math.min(meta.ticks - 1, t));
		load();
	}

	private void setTrim(boolean start) {
		if (start) {
			meta.trimStart = tick;
			if (meta.trimEnd >= 0 && meta.trimEnd < tick) {
				meta.trimEnd = -1;
			}
		} else {
			meta.trimEnd = tick;
			if (meta.trimStart > tick) {
				meta.trimStart = -1;
			}
		}
		dirty = true;
	}

	private void addMark(String type) {
		meta.marks.add(new RecordingMeta.Mark(tick, type));
		dirty = true;
	}

	@Override
	public void tick() {
		if (playing) {
			int next = tick + SPEEDS[speedIndex];
			if (next >= meta.ticks) {
				next = meta.ticks - 1;
				togglePlay();
			}
			tick = next;
			load();
		}
	}

	private void load() {
		if (tick == shownTick || meta.ticks == 0) {
			return;
		}
		try {
			current = reader.get(tick);
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.warn("Couldn't read tick {} of {}", tick, meta.id, e);
			current = null;
		}
		shownTick = tick;
		hasFrame = current != null && current.frameJpeg() != null;
		if (hasFrame) {
			BufferedImage image = RecordingCodec.decode(current.frameJpeg());
			NativeImage pixels = texture.getPixels();
			if (pixels != null && image != null) {
				int w = Math.min(image.getWidth(), pixels.getWidth());
				int h = Math.min(image.getHeight(), pixels.getHeight());
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						pixels.setPixel(x, y, 0xFF000000 | image.getRGB(x, y));
					}
				}
				texture.upload();
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);

		graphics.fill(frameX - 1, frameY - 1, frameX + frameSize + 1, frameY + frameSize + 1, 0xFF404040);
		int size = Math.max(1, meta.frameSize);
		if (hasFrame) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, frameX, frameY, 0, 0, frameSize, frameSize, size, size, size, size);
		} else {
			graphics.centeredText(font, Component.translatable("minigamebots.viewer.no_frame"), frameX + frameSize / 2,
					frameY + frameSize / 2 - 4, 0xFFB0B0B0);
		}

		int infoX = frameX + frameSize + 14;
		int y = frameY;
		y = line(graphics, infoX, y, Recorder.time(tick) + " / " + Recorder.time(meta.ticks) + "   tick " + tick, 0xFFFFFFFF);
		if (current == null) {
			line(graphics, infoX, y, Component.translatable("minigamebots.viewer.missing").getString(), 0xFFFF8080);
		} else {
			GameInput in = current.input();
			List<String> keys = new ArrayList<>();
			for (VirtualKey k : VirtualKey.values()) {
				if (in.holds(k)) {
					keys.add(k.name().toLowerCase(Locale.ROOT));
				}
			}
			y = line(graphics, infoX, y + 4, "Keys: " + (keys.isEmpty() ? "-" : String.join(" ", keys)), 0xFFE0E0E0);
			y = line(graphics, infoX, y, String.format("Clicks: attack %d, use %d", in.attackClicks(), in.useClicks()), 0xFFE0E0E0);
			y = line(graphics, infoX, y, String.format("Camera: %+.1f° yaw, %+.1f° pitch%s", in.yaw(), in.pitch(),
					current.cameraJump() ? " (jump removed)" : ""), 0xFFE0E0E0);
			if (current.frameAge() > 0) {
				y = line(graphics, infoX, y, "Frame is " + current.frameAge() + " ticks old", 0xFFB0B0B0);
			}
			if (current.observation() != null) {
				var self = current.observation().self();
				y = line(graphics, infoX, y + 4, String.format("HP %.1f  food %d  entities %d", self.health(), self.food(),
						current.observation().entities().size()), 0xFFB0B0B0);
				if (current.observation().gui() != null) {
					y = line(graphics, infoX, y, "Screen: " + current.observation().gui().screen(), 0xFFB0B0B0);
				}
			}
			for (GameEvent e : current.events()) {
				if (!e.type().equals("sound_heard") && y < barY - 20) {
					y = line(graphics, infoX, y, "• " + e, 0xFFFFE080);
				}
			}
			for (String m : current.marks()) {
				y = line(graphics, infoX, y, "★ " + m, 0xFF80FF80);
			}
		}
		drawTimeline(graphics);
	}

	private int line(GuiGraphicsExtractor graphics, int x, int y, String text, int color) {
		String clipped = font.plainSubstrByWidth(text, width - x - 6);
		graphics.text(font, clipped, x, y, color, false);
		return y + 10;
	}

	private void drawTimeline(GuiGraphicsExtractor graphics) {
		int total = Math.max(1, meta.ticks - 1);
		graphics.fill(barX, barY, barX + barWidth, barY + 8, 0xFF303030);
		int start = meta.firstUsableTick();
		int end = meta.lastUsableTick();
		graphics.fill(barX + start * barWidth / total, barY, barX + end * barWidth / total + 1, barY + 8, 0xFF2E7D32);
		for (RecordingMeta.Mark m : meta.marks) {
			int mx = barX + m.tick * barWidth / total;
			int color = switch (m.type) {
				case "mistake" -> 0xFFE53935;
				case "base" -> 0xFF42A5F5;
				default -> 0xFFFFD54F;
			};
			graphics.fill(mx, barY - 3, mx + 2, barY + 11, color);
		}
		int px = barX + tick * barWidth / total;
		graphics.fill(px - 1, barY - 4, px + 2, barY + 12, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.y() >= barY - 4 && event.y() <= barY + 12 && event.x() >= barX && event.x() <= barX + barWidth) {
			seek((int) Math.round((event.x() - barX) / barWidth * (meta.ticks - 1)));
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case KEY_SPACE -> togglePlay();
			case KEY_RIGHT -> step(event.hasShiftDown() ? 20 : 1);
			case KEY_LEFT -> step(event.hasShiftDown() ? -20 : -1);
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	@Override
	public void removed() {
		if (dirty) {
			try {
				store.saveMeta(meta);
			} catch (IOException e) {
				MinigameBotsClient.LOGGER.error("Couldn't save recording {}", meta.id, e);
			}
			dirty = false;
		}
		minecraft.getTextureManager().release(TEXTURE);
		texture = null;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
