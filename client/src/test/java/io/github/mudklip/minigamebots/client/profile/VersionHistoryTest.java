package io.github.mudklip.minigamebots.client.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionHistoryTest {
	@TempDir
	Path core;

	private void write(String weights, long step) throws Exception {
		Files.writeString(core.resolve("model.safetensors"), weights);
		Files.writeString(core.resolve("model.json"), "{\"step\": " + step + ", \"arch\": \"lite-v2\"}");
	}

	@Test
	void snapshotRevertAndUndo() throws Exception {
		assertNull(VersionHistory.snapshot(core, "nothing yet", ""));
		write("v1", 100);
		var s1 = VersionHistory.snapshot(core, "before training", "won 1/5 in m1");
		write("v2", 200);
		VersionHistory.revert(core, s1.id);
		assertEquals("v1", Files.readString(core.resolve("model.safetensors")));
		// The version we left (v2) was saved first, so the revert can be undone.
		var list = VersionHistory.list(core);
		assertEquals(2, list.size());
		var undo = list.stream().filter(s -> s.step == 200).findFirst().orElseThrow();
		VersionHistory.revert(core, undo.id);
		assertEquals("v2", Files.readString(core.resolve("model.safetensors")));
		assertEquals("won 1/5 in m1", list.stream().filter(s -> s.step == 100).findFirst().orElseThrow().exam);
	}

	@Test
	void keepsTheLastTenPlusPinned() throws Exception {
		write("v0", 0);
		var first = VersionHistory.snapshot(core, "first", "");
		VersionHistory.setPinned(core, first.id, true);
		for (int i = 1; i <= 14; i++) {
			write("v" + i, i);
			VersionHistory.snapshot(core, "s" + i, "");
		}
		var list = VersionHistory.list(core);
		assertEquals(VersionHistory.KEEP + 1, list.size());
		assertTrue(list.stream().anyMatch(s -> s.id.equals(first.id) && s.pinned));
		assertTrue(list.stream().noneMatch(s -> s.step == 1));
	}

	@Test
	void historyStaysOutOfExports() {
		assertTrue(ProfileStore.isTrainingOnly("core/history/20261118-120000-5/model.safetensors"));
	}
}
