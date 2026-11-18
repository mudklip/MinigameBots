package io.github.mudklip.minigamebots.client.observe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

/**
 * Copies the finished frame off the GPU asynchronously (the game doesn't wait for it) and
 * shrinks it to the AI's resolution. At most one copy is in flight at a time.
 */
public final class FrameCapture {
	private static final FrameCapture INSTANCE = new FrameCapture();
	/** Sub-samples per output pixel side; 3x3 averaging is a cheap, good-enough area filter. */
	private static final int SAMPLES = 3;
	private static final int MAP_READ_COPY_DST = 9;

	private final AtomicReference<Frame> latest = new AtomicReference<>();
	private volatile boolean wanted;
	private volatile boolean inFlight;
	private long requestedTick;
	private GpuBuffer buffer;
	private long bufferSize;

	public static FrameCapture get() {
		return INSTANCE;
	}

	/** Asks for the next rendered frame to be captured. */
	public void request(long tick) {
		requestedTick = tick;
		wanted = true;
	}

	public Frame latest() {
		return latest.get();
	}

	/** Called on the render thread right after a frame has been drawn. */
	public void afterRender(Minecraft client) {
		if (!wanted || inFlight) {
			return;
		}
		GpuTexture texture = client.gameRenderer.mainRenderTarget().getColorTexture();
		if (texture == null) {
			return;
		}
		wanted = false;
		inFlight = true;
		int width = texture.getWidth(0);
		int height = texture.getHeight(0);
		int pixelBytes = texture.getFormat().blockSize();
		long size = (long) width * height * pixelBytes;
		if (buffer == null || bufferSize != size) {
			if (buffer != null) {
				buffer.close();
			}
			buffer = RenderSystem.getDevice().createBuffer(() -> "MinigameBots frame capture", MAP_READ_COPY_DST, size);
			bufferSize = size;
		}
		GpuBuffer target = buffer;
		long tick = requestedTick;
		int outSize = MgbConfig.get().frameSize;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, target, 0L, () -> {
			try (GpuBufferSlice.MappedView view = target.map(true, false)) {
				latest.set(new Frame(tick, outSize, downscale(view.data(), width, height, pixelBytes, outSize)));
			} catch (RuntimeException e) {
				MinigameBotsClient.LOGGER.warn("Frame capture failed", e);
			} finally {
				inFlight = false;
			}
		}, 0);
	}

	/** Area-averages an RGBA texture (rows bottom to top) into size x size RGB, rows top to bottom. */
	static byte[] downscale(ByteBuffer data, int width, int height, int pixelBytes, int size) {
		byte[] out = new byte[size * size * 3];
		int samples = SAMPLES * SAMPLES;
		for (int oy = 0; oy < size; oy++) {
			for (int ox = 0; ox < size; ox++) {
				int r = 0;
				int g = 0;
				int b = 0;
				for (int sy = 0; sy < SAMPLES; sy++) {
					// Flip vertically: GPU texture row 0 is the bottom of the screen.
					int y = height - 1 - (int) (((oy + (sy + 0.5) / SAMPLES) / size) * height);
					for (int sx = 0; sx < SAMPLES; sx++) {
						int x = (int) (((ox + (sx + 0.5) / SAMPLES) / size) * width);
						int i = (y * width + x) * pixelBytes;
						r += data.get(i) & 0xFF;
						g += data.get(i + 1) & 0xFF;
						b += data.get(i + 2) & 0xFF;
					}
				}
				int o = (oy * size + ox) * 3;
				out[o] = (byte) (r / samples);
				out[o + 1] = (byte) (g / samples);
				out[o + 2] = (byte) (b / samples);
			}
		}
		return out;
	}
}
