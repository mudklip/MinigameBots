package io.github.mudklip.minigamebots.client.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mudklip.minigamebots.client.practice.Objective;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryTest {
	@Test
	void relationsDefaultToNeutralAndFollowTrustOrder() {
		WorldMemory m = new WorldMemory();
		assertEquals(Relation.NEUTRAL, m.relation("Alex", 0, "", "mine"));
		// Same insignia as mine: ally. Marked insignia: whatever it was marked as.
		assertEquals(Relation.FRIEND, m.relation("Alex", 0, "mine", "mine"));
		m.insignias.put("redshield", Relation.ENEMY);
		assertEquals(Relation.ENEMY, m.relation("Alex", 0, "redshield", "mine"));
		// Teams: same team is a friend, another team an enemy (unless switched off).
		assertEquals(Relation.FRIEND, m.relation("Alex", 1, "", "mine"));
		assertEquals(Relation.ENEMY, m.relation("Alex", -1, "", "mine"));
		m.otherTeamsAreEnemies = false;
		assertEquals(Relation.NEUTRAL, m.relation("Alex", -1, "", "mine"));
		// Disagreements: name > team > insignia by default, configurable.
		m.players.put("Alex", Relation.FRIEND);
		assertEquals(Relation.FRIEND, m.relation("Alex", 0, "redshield", "mine"));
		m.trust = WorldMemory.Trust.INSIGNIA_NAME_TEAM;
		assertEquals(Relation.ENEMY, m.relation("Alex", 0, "redshield", "mine"));
	}

	@Test
	void coarseMapRemembersAndSaves() throws Exception {
		CoarseMap map = new CoarseMap();
		map.visit(3, -7, 2, 2, true, false, false);
		map.visit(3, -7, 2, 0, false, false, true); // Danger fades by one per visit; ore stays known.
		int v = map.get(3, -7);
		assertEquals(2, CoarseMap.biome(v));
		assertEquals(1, CoarseMap.danger(v));
		assertTrue(CoarseMap.ore(v));
		assertTrue(CoarseMap.interactive(v));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		map.write(out);
		CoarseMap back = CoarseMap.read(new ByteArrayInputStream(out.toByteArray()));
		assertEquals(v, (int) back.get(3, -7));
		assertEquals(1, back.size());
	}

	@Test
	void whereTextRoundTrips() {
		Map<String, Object> w = Objective.parseWhere("target=minecraft:pig, amount>=2, text~won, dead=false");
		assertEquals("minecraft:pig", w.get("target"));
		assertEquals(Map.of("min", 2.0), w.get("amount"));
		assertEquals(Map.of("contains", "won"), w.get("text"));
		assertEquals(false, w.get("dead"));
		assertEquals("target=minecraft:pig, amount>=2, text~won, dead=false", Objective.formatWhere(w));
	}

	@Test
	void objectiveWarnings() {
		Objective o = new Objective();
		assertFalse(o.warnings().isEmpty());
		Objective.Rule hurt = new Objective.Rule();
		hurt.event = "damage_taken";
		hurt.value = -1;
		o.rules.add(hurt);
		assertTrue(o.warnings().stream().anyMatch(s -> s.contains("avoid")));
		Objective.Rule kill = new Objective.Rule();
		kill.event = "kill";
		kill.value = 3;
		o.rules.add(kill);
		hurt.forgive = new Objective.Forgive();
		hurt.forgive.by.add("kill");
		assertTrue(o.warnings().isEmpty());
	}
}
