package io.github.mudklip.minigamebots.training;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Training Grounds files live inside the world: {@code <world>/minigamebots-training/}. */
public final class TgStorage {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private TgStorage() {
	}

	public static Path dir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("minigamebots-training");
	}

	/** Writes through a temp file so a crash never leaves half a file. */
	public static void writeAtomic(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, text);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
