package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import io.github.mudklip.minigamebots.client.record.RecordingMeta;
import io.github.mudklip.minigamebots.client.record.RecordingReader;
import io.github.mudklip.minigamebots.client.record.TickRecord;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * "Which was better?": two 2-second clips of the AI (or a teacher) loop side by side; you pick
 * the better or more human one. Picks go to the profile's preferences.jsonl, and the style
 * model (Realism > Analyze) learns your taste from them.
 */
public final class CompareScreen extends Screen {
	private static final int LENGTH = 40;
	private static final Identifier[] TEX = {Identifier.fromNamespaceAndPath("minigamebots", "compare_a"),
			Identifier.fromNamespaceAndPath("minigamebots", "compare_b")};

	private record Clip(List<TickRecord> ticks, String label) {
	}

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private final Random random = new Random();
	private final Clip[] clips = new Clip[2];
	private final DynamicTexture[] textures = new DynamicTexture[2];
	private int frame;
	private final int[] shown = {-1, -1};
	private int picks;
	private @Nullable String message;

	public CompareScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.translatable("minigamebots.compare.screen"));
		this.parent = parent;
		this.profile = profile;
	}

	@Override
	protected void init() {
		if (clips[0] == null) {
			next();
		}
		int bw = 80;
		int y = height - 28;
		int cx = width / 2;
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.compare.left"), b -> pick("a")).pos(cx - 2 * bw - 6, y).width(bw).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.compare.same"), b -> pick("same")).pos(cx - bw - 2, y).width(bw).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.compare.right"), b -> pick("b")).pos(cx + 2, y).width(bw).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.compare.skip"), b -> next()).pos(cx + bw + 6, y).width(bw).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 50, 6).width(44).build());
	}

	/** Two random clips from different moments of the AI's or teachers' recordings. */
	private void next() {
		var store = MinigameBotsClient.instance().recorder().store();
		List<RecordingMeta> pool = new ArrayList<>(store.list().stream()
				.filter(m -> !"human".equals(m.source) && m.ticks > LENGTH * 2 && m.frameSize > 0).toList());
		if (pool.isEmpty()) {
			message = "No AI or teacher recordings yet: record the AI playing (or a teacher demo) first.";
			clips[0] = clips[1] = null;
			return;
		}
		for (int i = 0; i < 2; i++) {
			clips[i] = null;
			for (int tries = 0; tries < 6 && clips[i] == null; tries++) {
				RecordingMeta m = pool.get(random.nextInt(pool.size()));
				clips[i] = load(store.dir(m.id), m);
			}
		}
		frame = 0;
		shown[0] = shown[1] = -1;
		message = clips[0] == null || clips[1] == null ? "Couldn't read clips from the recordings." : null;
	}

	private @Nullable Clip load(Path dir, RecordingMeta m) {
		try {
			RecordingReader r = new RecordingReader(dir, m);
			int start = m.firstUsableTick() + random.nextInt(Math.max(1, m.ticks - m.firstUsableTick() - LENGTH));
			List<TickRecord> out = new ArrayList<>();
			for (int t = start; t < start + LENGTH; t++) {
				TickRecord rec = r.get(t);
				if (rec == null) {
					return null;
				}
				out.add(rec);
			}
			return new Clip(out, m.name);
		} catch (Exception e) {
			return null;
		}
	}

	private void pick(String which) {
		if (clips[0] == null || clips[1] == null) {
			return;
		}
		JsonObject line = new JsonObject();
		line.add("a", inputs(clips[0]));
		line.add("b", inputs(clips[1]));
		line.addProperty("pick", which);
		Path file = MinigameBotsClient.instance().profiles().store().dir(profile.id).resolve("preferences.jsonl");
		try {
			Files.writeString(file, RecordingCodec.GSON.toJson(line) + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			picks++;
		} catch (Exception e) {
			message = "Couldn't save: " + e.getMessage();
		}
		next();
	}

	private static JsonArray inputs(Clip c) {
		JsonArray a = new JsonArray();
		c.ticks().forEach(t -> a.add(RecordingCodec.inputJson(t.input())));
		return a;
	}

	@Override
	public void tick() {
		frame = (frame + 1) % LENGTH;
	}

	private void show(int i, GuiGraphicsExtractor g, int x, int y, int size) {
		Clip c = clips[i];
		if (c == null) {
			return;
		}
		byte[] jpeg = null;
		for (int f = frame; f >= 0 && jpeg == null; f--) {
			jpeg = c.ticks().get(f).frameJpeg();
		}
		if (jpeg == null) {
			return;
		}
		if (shown[i] == frame && textures[i] != null) {
			NativeImage p = textures[i].getPixels();
			g.blit(RenderPipelines.GUI_TEXTURED, TEX[i], x, y, 0, 0, size, size, p.getWidth(), p.getHeight(), p.getWidth(), p.getHeight());
			return;
		}
		shown[i] = frame;
		BufferedImage img = RecordingCodec.decode(jpeg);
		if (img == null) {
			return;
		}
		if (textures[i] == null || textures[i].getPixels().getWidth() != img.getWidth()) {
			if (textures[i] != null) {
				minecraft.getTextureManager().release(TEX[i]);
			}
			textures[i] = new DynamicTexture(() -> "MinigameBots compare", img.getWidth(), img.getHeight(), false);
			minecraft.getTextureManager().register(TEX[i], textures[i]);
		}
		NativeImage px = textures[i].getPixels();
		for (int yy = 0; yy < img.getHeight(); yy++) {
			for (int xx = 0; xx < img.getWidth(); xx++) {
				px.setPixel(xx, yy, 0xFF000000 | img.getRGB(xx, yy));
			}
		}
		textures[i].upload();
		g.blit(RenderPipelines.GUI_TEXTURED, TEX[i], x, y, 0, 0, size, size, img.getWidth(), img.getHeight(), img.getWidth(), img.getHeight());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		g.centeredText(font, Component.translatable("minigamebots.compare.help"), width / 2, 20, 0xFFB0B0B0);
		int size = Math.min(width / 2 - 20, height - 70);
		show(0, g, width / 2 - size - 5, 34, size);
		show(1, g, width / 2 + 5, 34, size);
		if (message != null) {
			g.centeredText(font, Component.literal(message), width / 2, height / 2, 0xFFFF9090);
		}
		g.text(font, picks + " picked", 6, height - 22, 0xFF909090, false);
	}

	@Override
	public void onClose() {
		for (int i = 0; i < 2; i++) {
			if (textures[i] != null) {
				minecraft.getTextureManager().release(TEX[i]);
				textures[i] = null;
			}
		}
		minecraft.gui.setScreen(parent);
	}
}
