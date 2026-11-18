package io.github.mudklip.minigamebots.client.events;

import java.util.Map;

/**
 * Something that happened in the game, named so reward rules can refer to it
 * (for example {@code kill}, {@code damage_taken}, {@code block_broken}).
 *
 * @param source where it was detected: {@code client} (inferred) or {@code server} (ground truth
 *               from Training Grounds)
 */
public record GameEvent(long tick, String type, Map<String, Object> data, String source) {
	public GameEvent {
		data = Map.copyOf(data);
	}

	public static GameEvent client(long tick, String type, Map<String, Object> data) {
		return new GameEvent(tick, type, data, "client");
	}

	@Override
	public String toString() {
		return type + (data.isEmpty() ? "" : " " + data);
	}
}
