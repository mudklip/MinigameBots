package io.github.mudklip.minigamebots.client.profile;

import io.github.mudklip.minigamebots.client.record.RecordingCodec;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pretrained foundation models (the large shared base, hundreds of MB), stored once in
 * {@code .minecraft/minigamebots/foundations/<id>/} and referenced by id from profiles, so each AI
 * file stays small. Each folder has a {@code foundation.json}. Filled from milestone 6 on.
 */
public final class FoundationStore {
	public static final String META = "foundation.json";

	public static final class Foundation {
		public String id = "";
		public String name = "";
		public String description = "";
		public long sizeBytes;
	}

	private final Path root;

	public FoundationStore(Path root) {
		this.root = root;
	}

	public Path root() {
		return root;
	}

	public boolean has(String id) {
		return id != null && Files.isRegularFile(root.resolve(id).resolve(META));
	}

	public List<Foundation> list() {
		List<Foundation> out = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return out;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
			for (Path dir : dirs) {
				Path meta = dir.resolve(META);
				if (Files.isRegularFile(meta)) {
					out.add(RecordingCodec.GSON.fromJson(Files.readString(meta), Foundation.class));
				}
			}
		} catch (IOException | RuntimeException e) {
			// An unreadable entry just doesn't show.
		}
		return out;
	}
}
