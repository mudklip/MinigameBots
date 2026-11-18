package io.github.mudklip.minigamebots.common.net;

/** Decides whether two peers can talk. Pure logic so it can be unit tested without Minecraft. */
public final class Compatibility {
	public enum Result {
		/** Both sides understand each other. */
		COMPATIBLE,
		/** This side is too old for the remote peer: update this mod. */
		LOCAL_TOO_OLD,
		/** The remote peer is too old for this side: update the other mod. */
		REMOTE_TOO_OLD
	}

	public record Version(int protocol, int minCompatible) {
		public Version {
			if (protocol < 1 || minCompatible < 1 || minCompatible > protocol) {
				throw new IllegalArgumentException("invalid protocol version " + protocol + "/" + minCompatible);
			}
		}
	}

	public static Result check(Version local, Version remote) {
		if (local.protocol() < remote.minCompatible()) {
			return Result.LOCAL_TOO_OLD;
		}
		if (remote.protocol() < local.minCompatible()) {
			return Result.REMOTE_TOO_OLD;
		}
		return Result.COMPATIBLE;
	}

	private Compatibility() {
	}
}
