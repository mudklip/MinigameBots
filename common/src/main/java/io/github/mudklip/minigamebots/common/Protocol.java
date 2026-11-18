package io.github.mudklip.minigamebots.common;

/**
 * Version of the link between the client mod and Training Grounds.
 *
 * <p>Bump {@link #VERSION} whenever a payload changes. Raise {@link #MIN_COMPATIBLE} only when an
 * older peer can no longer be understood at all.
 */
public final class Protocol {
	public static final int VERSION = 1;
	public static final int MIN_COMPATIBLE = 1;

	/** Feature flags a peer can advertise. Peers ignore flags they don't know. */
	public static final class Features {
		public static final String HANDSHAKE = "handshake";

		private Features() {
		}
	}

	private Protocol() {
	}
}
