package io.github.mudklip.minigamebots.training;

import io.github.mudklip.minigamebots.common.net.Compatibility;
import io.github.mudklip.minigamebots.common.net.HelloPayload;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Players whose MinigameBots client completed the handshake, and whether the link is usable. */
public final class LinkedClients {
	public record Link(HelloPayload hello, Compatibility.Result result) {
		public boolean usable() {
			return result == Compatibility.Result.COMPATIBLE;
		}
	}

	private final Map<UUID, Link> links = new ConcurrentHashMap<>();

	public void put(UUID player, Link link) {
		links.put(player, link);
	}

	public void remove(UUID player) {
		links.remove(player);
	}

	public Optional<Link> get(UUID player) {
		return Optional.ofNullable(links.get(player));
	}
}
