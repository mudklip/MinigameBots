package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.TargetDataLine;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Voice (optional O3): hold the push-to-talk key (B), speak, let go. Your words become an /ai
 * command (only you can command your AI). Replies can also be spoken aloud (Settings).
 * Everything stays on your PC: audio goes to a file the local brain reads.
 */
public final class Voice {
	private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
	private static volatile boolean recording;
	private static Thread thread;

	private Voice() {
	}

	/** Every client tick: starts recording when the key goes down, sends it when it comes up. */
	public static void tick(Minecraft client, boolean keyDown) {
		if (keyDown && !recording && client.gui.screen() == null) {
			start(client);
		} else if (!keyDown && recording) {
			recording = false;
		}
		if (recording) {
			client.gui.hud.setOverlayMessage(Component.literal("§c● §fListening... (let go to send)"), false);
		}
	}

	private static void start(Minecraft client) {
		TargetDataLine line;
		try {
			line = AudioSystem.getTargetDataLine(FORMAT);
			line.open(FORMAT);
		} catch (Exception e) {
			client.gui.hud.setOverlayMessage(Component.literal("No microphone found: plug one in or check your sound settings"), false);
			MinigameBotsClient.LOGGER.info("No microphone: {}", e.toString());
			return;
		}
		recording = true;
		Path wav = MinigameBotsClient.instance().brain().runtime().root().resolve("run/voice.wav");
		thread = new Thread(() -> {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] chunk = new byte[3200];
			line.start();
			long started = System.currentTimeMillis();
			while (recording && System.currentTimeMillis() - started < 15_000) {
				int n = line.read(chunk, 0, chunk.length);
				if (n > 0) {
					buf.write(chunk, 0, n);
				}
			}
			recording = false;
			line.stop();
			line.close();
			byte[] pcm = buf.toByteArray();
			try {
				wav.getParent().toFile().mkdirs();
				AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT, pcm.length / 2),
						AudioFileFormat.Type.WAVE, wav.toFile());
			} catch (Exception e) {
				MinigameBotsClient.LOGGER.warn("Couldn't save voice", e);
				return;
			}
			client.execute(() -> MinigameBotsClient.instance().brain().voice(client, wav.toString()));
		}, "MinigameBots microphone");
		thread.setDaemon(true);
		thread.start();
	}

	/** Speaks a reply aloud if that setting is on (and the voice is installed). */
	public static void speak(String text) {
		if (!MgbConfig.get().speakReplies || text.isBlank()) {
			return;
		}
		JsonObject m = new JsonObject();
		m.addProperty("type", "speak");
		m.addProperty("text", text.replaceAll("§.", ""));
		MinigameBotsClient.instance().brain().sendIfRunning(m);
	}

	/** Plays the brain's spoken reply. */
	public static void play(String path) {
		Thread t = new Thread(() -> {
			try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(path))) {
				Clip clip = AudioSystem.getClip();
				clip.open(in);
				clip.start();
				Thread.sleep(clip.getMicrosecondLength() / 1000 + 100);
				clip.close();
			} catch (Exception e) {
				MinigameBotsClient.LOGGER.debug("Couldn't play speech", e);
			}
		}, "MinigameBots speech");
		t.setDaemon(true);
		t.start();
	}
}
