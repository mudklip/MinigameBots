package io.github.mudklip.minigamebots.client;

import io.github.mudklip.minigamebots.common.net.Compatibility;
import io.github.mudklip.minigamebots.common.net.HelloPayload;
import java.util.Optional;

/** Whether the server we're connected to runs a compatible Training Grounds. */
public final class TrainingLink {
	public record State(HelloPayload server, Compatibility.Result result) {
		public boolean usable() {
			return result == Compatibility.Result.COMPATIBLE;
		}
	}

	private static volatile State state;

	public static Optional<State> get() {
		return Optional.ofNullable(state);
	}

	/** True when connected to a server with a compatible Training Grounds. */
	public static boolean active() {
		State s = state;
		return s != null && s.usable();
	}

	static void set(State newState) {
		state = newState;
	}

	static void clear() {
		state = null;
	}

	private TrainingLink() {
	}
}
