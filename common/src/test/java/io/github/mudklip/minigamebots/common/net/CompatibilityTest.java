package io.github.mudklip.minigamebots.common.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mudklip.minigamebots.common.net.Compatibility.Result;
import io.github.mudklip.minigamebots.common.net.Compatibility.Version;
import org.junit.jupiter.api.Test;

class CompatibilityTest {
	@Test
	void sameVersionIsCompatible() {
		assertEquals(Result.COMPATIBLE, Compatibility.check(new Version(1, 1), new Version(1, 1)));
	}

	@Test
	void newerPeerStillAcceptingOldVersionsIsCompatible() {
		assertEquals(Result.COMPATIBLE, Compatibility.check(new Version(1, 1), new Version(3, 1)));
		assertEquals(Result.COMPATIBLE, Compatibility.check(new Version(3, 1), new Version(1, 1)));
	}

	@Test
	void remoteRequiringNewerVersionMeansLocalTooOld() {
		assertEquals(Result.LOCAL_TOO_OLD, Compatibility.check(new Version(1, 1), new Version(3, 2)));
	}

	@Test
	void remoteBelowLocalMinimumMeansRemoteTooOld() {
		assertEquals(Result.REMOTE_TOO_OLD, Compatibility.check(new Version(3, 2), new Version(1, 1)));
	}

	@Test
	void rejectsInvalidVersions() {
		assertThrows(IllegalArgumentException.class, () -> new Version(0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Version(2, 3));
	}
}
