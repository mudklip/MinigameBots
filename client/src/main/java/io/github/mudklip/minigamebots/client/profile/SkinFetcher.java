package io.github.mudklip.minigamebots.client.profile;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/**
 * Looks up a Minecraft username's skin, like a player head does, and saves it in the profile so
 * it keeps showing when you're offline. Uses Mojang's public profile API:
 * name → UUID → textures → skin image (only from Mojang's texture server).
 */
public final class SkinFetcher {
	static final String NAME_API = "https://api.mojang.com/users/profiles/minecraft/";
	static final String PROFILE_API = "https://sessionserver.mojang.com/session/minecraft/profile/";
	static final String TEXTURE_HOST = "textures.minecraft.net";

	public enum Outcome {
		OK,
		NO_SUCH_PLAYER,
		NO_SKIN,
		OFFLINE,
		INVALID_NAME
	}

	public record Result(Outcome outcome, String uuid, boolean slim, String message) {
	}

	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.proxy(ProxySelector.getDefault())
			.connectTimeout(Duration.ofSeconds(8))
			.build();

	public static boolean validName(String name) {
		return name != null && name.matches("[A-Za-z0-9_]{1,16}");
	}

	/** Downloads the skin for {@code username} into {@code target} (as a 64x64 PNG) in the background. */
	public CompletableFuture<Result> fetch(String username, Path target) {
		return CompletableFuture.supplyAsync(() -> fetchBlocking(username, target));
	}

	Result fetchBlocking(String username, Path target) {
		if (!validName(username)) {
			return new Result(Outcome.INVALID_NAME, "", false, "Usernames are 1-16 letters, numbers or _");
		}
		try {
			HttpResponse<String> name = get(NAME_API + username);
			if (name.statusCode() == 204 || name.statusCode() == 404) {
				return new Result(Outcome.NO_SUCH_PLAYER, "", false, "No Minecraft player called " + username);
			}
			if (name.statusCode() != 200) {
				return new Result(Outcome.OFFLINE, "", false, "Mojang answered " + name.statusCode() + "; try again later");
			}
			String uuid = RecordingCodec.GSON.fromJson(name.body(), JsonObject.class).get("id").getAsString();

			HttpResponse<String> profile = get(PROFILE_API + uuid);
			if (profile.statusCode() != 200) {
				return new Result(Outcome.OFFLINE, uuid, false, "Mojang answered " + profile.statusCode() + "; try again later");
			}
			SkinInfo skin = parseTextures(RecordingCodec.GSON.fromJson(profile.body(), JsonObject.class));
			if (skin == null) {
				return new Result(Outcome.NO_SKIN, uuid, false, username + " uses a default skin");
			}
			HttpResponse<byte[]> image = http.send(request(skin.url()), HttpResponse.BodyHandlers.ofByteArray());
			if (image.statusCode() != 200) {
				return new Result(Outcome.OFFLINE, uuid, skin.slim(), "Couldn't download the skin (" + image.statusCode() + ")");
			}
			saveAs64x64(image.body(), target);
			return new Result(Outcome.OK, uuid, skin.slim(), "Got " + username + "'s skin");
		} catch (IOException e) {
			return new Result(Outcome.OFFLINE, "", false, "Can't reach Mojang (offline?); using the default skin for now");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(Outcome.OFFLINE, "", false, "Interrupted");
		} catch (RuntimeException e) {
			return new Result(Outcome.OFFLINE, "", false, "Unexpected answer from Mojang: " + e.getMessage());
		}
	}

	record SkinInfo(String url, boolean slim) {
	}

	/** Reads the base64 "textures" property; only accepts skins hosted by Mojang. */
	static SkinInfo parseTextures(JsonObject profile) {
		if (!profile.has("properties")) {
			return null;
		}
		for (var p : profile.getAsJsonArray("properties")) {
			JsonObject prop = p.getAsJsonObject();
			if (!"textures".equals(prop.get("name").getAsString())) {
				continue;
			}
			String json = new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8);
			JsonObject textures = RecordingCodec.GSON.fromJson(json, JsonObject.class).getAsJsonObject("textures");
			if (textures == null || !textures.has("SKIN")) {
				return null;
			}
			JsonObject skin = textures.getAsJsonObject("SKIN");
			String url = skin.get("url").getAsString();
			URI uri = URI.create(url);
			if (!TEXTURE_HOST.equals(uri.getHost())) {
				return null;
			}
			boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
					&& "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString().toLowerCase(Locale.ROOT));
			// Mojang serves skins over http; use https.
			return new SkinInfo("https://" + uri.getHost() + uri.getRawPath(), slim);
		}
		return null;
	}

	/** Checks the image is a real skin and stores it as 64x64 (old 64x32 skins are padded). */
	static void saveAs64x64(byte[] png, Path target) throws IOException {
		BufferedImage in = ImageIO.read(new ByteArrayInputStream(png));
		if (in == null || in.getWidth() != 64 || (in.getHeight() != 64 && in.getHeight() != 32)) {
			throw new IOException("Not a Minecraft skin image");
		}
		BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		out.getGraphics().drawImage(in, 0, 0, null);
		makeHeadOpaque(out);
		clearOpaqueHat(out);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bytes);
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(tmp, bytes.toByteArray());
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Like vanilla: the base head layer is always drawn fully opaque, whatever the file says. */
	static void makeHeadOpaque(BufferedImage skin) {
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 32; x++) {
				skin.setRGB(x, y, skin.getRGB(x, y) | 0xFF000000);
			}
		}
	}

	/**
	 * Very old skins (Notch's among them) have a solid hat layer, which would cover the face.
	 * Like vanilla, if the hat area has no transparent pixel at all, treat it as empty.
	 */
	static void clearOpaqueHat(BufferedImage skin) {
		for (int y = 0; y < 16; y++) {
			for (int x = 32; x < 64; x++) {
				if ((skin.getRGB(x, y) >>> 24) < 128) {
					return;
				}
			}
		}
		for (int y = 0; y < 16; y++) {
			for (int x = 32; x < 64; x++) {
				skin.setRGB(x, y, 0);
			}
		}
	}

	private HttpResponse<String> get(String url) throws IOException, InterruptedException {
		return http.send(request(url), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "MinigameBots").timeout(Duration.ofSeconds(15)).build();
	}
}
