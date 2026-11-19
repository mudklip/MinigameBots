package io.github.mudklip.minigamebots.client.video;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.brain.BrainProcess;
import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Video Lab files, under {@code .minecraft/minigamebots/videolab/}: {@code videos/} (drop videos
 * here), {@code idm/} (the input guesser), {@code atlas/} (item icons from your game),
 * {@code books/<tag>.json} (Knowledge Books), {@code questions/} (the Question Queue),
 * {@code exports/} and {@code import/} ({@code .mgbkb} books to share).
 */
public final class VideoLab {
	public static final List<String> BEHAVIORS = List.of("weapon", "tool", "food", "block", "revive", "potion",
			"projectile", "armor", "cosmetic", "other");

	private VideoLab() {
	}

	public static Path root() {
		return MinigameBotsClient.instance().recorder().store().root().getParent().resolve("videolab");
	}

	public static List<Path> videos() {
		Path d = root().resolve("videos");
		try {
			Files.createDirectories(d);
			try (Stream<Path> s = Files.list(d)) {
				return s.filter(p -> p.getFileName().toString().toLowerCase().matches(".*\\.(mp4|mkv|webm|mov|avi)")).sorted().toList();
			}
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Runs `python -m minigamebots_brain <args>` (e.g. "video", "import", ...); returns the final JSON line ("done" or "error"). */
	public static JsonObject run(List<String> args, Consumer<String> progress) throws IOException, InterruptedException {
		var runtime = MinigameBotsClient.instance().brain().runtime();
		if (!runtime.installed()) {
			throw new IOException("Set up the brain first (Brain > Install)");
		}
		var code = runtime.prepareCode();
		List<String> cmd = new ArrayList<>(List.of(runtime.venvPython().toString(), "-m", "minigamebots_brain"));
		cmd.addAll(args);
		ArrayDeque<String> out = new ArrayDeque<>();
		JsonObject[] last = {new JsonObject()};
		BrainProcess.run(cmd, runtime.root(), Map.of("PYTHONPATH", code.toString()), runtime.logsDir().resolve("videolab.log"), out,
				line -> {
					if (!line.startsWith("{")) {
						return;
					}
					try {
						JsonObject o = JsonParser.parseString(line).getAsJsonObject();
						last[0] = o;
						if (o.has("message")) {
							progress.accept(o.get("message").getAsString());
						}
					} catch (RuntimeException ignored) {
						// Not JSON.
					}
				});
		return last[0];
	}

	/**
	 * The reference atlas: every item's icon as your game draws it (resource packs included),
	 * from its item texture, or its block texture for block items. Returns how many were saved.
	 */
	public static int buildAtlas(Minecraft mc) throws IOException {
		Path dir = root().resolve("atlas");
		Files.createDirectories(dir);
		int n = 0;
		for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
			for (String kind : new String[] {"item", "block"}) {
				var res = mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath(id.getNamespace(),
						"textures/" + kind + "/" + id.getPath() + ".png"));
				if (res.isPresent()) {
					try (InputStream in = res.get().open()) {
						Files.write(dir.resolve(id.getNamespace() + "__" + id.getPath() + ".png"), in.readAllBytes());
						n++;
					}
					break;
				}
			}
		}
		return n;
	}

	public static JsonArray questions() {
		try {
			return JsonParser.parseString(Files.readString(root().resolve("questions/queue.json"))).getAsJsonArray();
		} catch (Exception e) {
			return new JsonArray();
		}
	}

	/**
	 * Answers the first question: stores it in the Knowledge Book of each of the video's tags
	 * (or "untagged") and removes it from the queue. {@code answer} null = ignore it.
	 */
	public static void answer(JsonObject q, String answer, String kind, String behavior) throws IOException {
		if (answer != null) {
			JsonArray tags = q.has("tags") ? q.getAsJsonArray("tags") : new JsonArray();
			List<String> names = new ArrayList<>();
			tags.forEach(t -> names.add(t.getAsString()));
			if (names.isEmpty()) {
				names.add("untagged");
			}
			for (String tag : names) {
				Path book = root().resolve("books").resolve(tag.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
				Files.createDirectories(book.getParent());
				JsonObject b = Files.isRegularFile(book) ? JsonParser.parseString(Files.readString(book)).getAsJsonObject() : new JsonObject();
				if (!b.has("items")) {
					b.add("items", new JsonObject());
				}
				JsonObject e = new JsonObject();
				e.addProperty("answer", answer);
				e.addProperty("kind", kind);
				e.addProperty("behavior", behavior);
				b.getAsJsonObject("items").add(q.get("hash").getAsString(), e);
				Files.writeString(book, RecordingCodec.PRETTY.toJson(b));
			}
		}
		JsonArray all = questions();
		JsonArray rest = new JsonArray();
		all.forEach(x -> {
			if (!x.getAsJsonObject().get("id").getAsString().equals(q.get("id").getAsString())) {
				rest.add(x);
			}
		});
		Files.writeString(root().resolve("questions/queue.json"), RecordingCodec.PRETTY.toJson(rest));
	}

	/** Copies every Knowledge Book to exports/<tag>.mgbkb and merges any .mgbkb found in import/. */
	public static String shareBooks() throws IOException {
		Path books = root().resolve("books");
		Path ex = root().resolve("exports");
		Path im = root().resolve("import");
		Files.createDirectories(books);
		Files.createDirectories(ex);
		Files.createDirectories(im);
		int out = 0;
		int in = 0;
		try (Stream<Path> s = Files.list(books)) {
			for (Path p : s.filter(p -> p.toString().endsWith(".json")).toList()) {
				Files.copy(p, ex.resolve(p.getFileName().toString().replace(".json", ".mgbkb")),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				out++;
			}
		}
		try (Stream<Path> s = Files.list(im)) {
			for (Path p : s.filter(p -> p.toString().endsWith(".mgbkb")).toList()) {
				JsonObject incoming = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
				Path target = books.resolve(p.getFileName().toString().replace(".mgbkb", ".json"));
				JsonObject b = Files.isRegularFile(target) ? JsonParser.parseString(Files.readString(target)).getAsJsonObject()
						: new JsonObject();
				if (!b.has("items")) {
					b.add("items", new JsonObject());
				}
				if (incoming.has("items")) {
					// Your own answers win over imported ones.
					incoming.getAsJsonObject("items").entrySet().forEach(e -> {
						if (!b.getAsJsonObject("items").has(e.getKey())) {
							b.getAsJsonObject("items").add(e.getKey(), e.getValue());
						}
					});
				}
				Files.writeString(target, RecordingCodec.PRETTY.toJson(b));
				in++;
			}
		}
		return "Exported " + out + " book(s) to videolab/exports, imported " + in + " from videolab/import";
	}
}
