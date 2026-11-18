package io.github.mudklip.minigamebots.client.observe;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * One downscaled game frame as the AI sees it: {@code size x size} pixels, RGB, rows top to
 * bottom. The whole window (HUD included) is squeezed into a square, as OpenAI's VPT does.
 */
public record Frame(long tick, int size, byte[] rgb) {
	public Frame {
		if (rgb.length != size * size * 3) {
			throw new IllegalArgumentException("expected " + size * size * 3 + " bytes, got " + rgb.length);
		}
	}

	public void savePng(Path file) throws IOException {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int i = (y * size + x) * 3;
				image.setRGB(x, y, (rgb[i] & 0xFF) << 16 | (rgb[i + 1] & 0xFF) << 8 | rgb[i + 2] & 0xFF);
			}
		}
		Files.createDirectories(file.getParent());
		ImageIO.write(image, "png", file.toFile());
	}
}
