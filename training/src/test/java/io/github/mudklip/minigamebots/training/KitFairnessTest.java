package io.github.mudklip.minigamebots.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import io.github.mudklip.minigamebots.training.dummy.Loadout;
import io.github.mudklip.minigamebots.training.scenario.Minigames;
import org.junit.jupiter.api.Test;

/** The kits themselves are fair: shields only where named, no armour in the minigames. */
class KitFairnessTest {
	private static boolean has(JsonArray kit, int slot, String id) {
		for (var el : kit) {
			var o = el.getAsJsonObject();
			if (o.get("slot").getAsInt() == slot && o.getAsJsonObject("item").get("id").getAsString().equals("minecraft:" + id)) {
				return true;
			}
		}
		return false;
	}

	private static boolean anyIn(JsonArray kit, int from, int to) {
		for (var el : kit) {
			int s = el.getAsJsonObject().get("slot").getAsInt();
			if (s >= from && s <= to) {
				return true;
			}
		}
		return false;
	}

	@Test
	void shieldOnlyInTheShieldKit() {
		for (String name : Loadout.BUILT_IN.keySet()) {
			assertEquals("shield".equals(name), has(Loadout.builtIn(name), 40, "shield"), name);
		}
	}

	@Test
	void minigamesHaveNoArmourOrFood() {
		for (String kind : CoreGames.KINDS) {
			JsonArray k = Loadout.builtIn("core_" + kind);
			assertFalse(anyIn(k, 36, 40), kind + " has armour or an off hand");
			assertFalse(has(k, 8, "cooked_beef"), kind);
		}
		assertTrue(has(Loadout.builtIn("core_snowball"), 0, "snowball"));
		assertTrue(has(Loadout.builtIn("core_spleef"), 0, "diamond_shovel"));
	}

	@Test
	void styleGearOnlyHasAShieldWhenItBlocks() {
		assertFalse(has(Loadout.ofStyle(DummySettings.style("rusher")), 40, "shield"));
		assertTrue(has(Loadout.ofStyle(DummySettings.style("blocker")), 40, "shield"));
	}

	@Test
	void randomGamesLeaveOutWhatDummiesCantUse() {
		assertFalse(Loadout.dummyCanUse(new String[] {"mace,iron_sword", "wind_charge:8", "", ""}));
		assertTrue(Loadout.dummyCanUse(new String[] {"bow,stone_sword", "arrow:32", "", ""}));
		for (int i = 0; i < 200; i++) {
			var s = Minigames.make("random", "t", "a", new java.util.Random(i));
			for (var t : s.teams) {
				assertFalse(t.kit.toString().contains("mace") || t.kit.toString().contains("wind_charge"), "seed " + i);
			}
		}
	}

	@Test
	void kitNames() {
		assertTrue(Loadout.validName("my_kit-2"));
		assertFalse(Loadout.validName("core_snowball"));
		assertFalse(Loadout.validName("default"));
		assertFalse(Loadout.validName("../x"));
	}
}
