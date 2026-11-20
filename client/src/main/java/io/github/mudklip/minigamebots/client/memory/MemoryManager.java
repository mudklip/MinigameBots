package io.github.mudklip.minigamebots.client.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.events.EventDetector;
import io.github.mudklip.minigamebots.client.observe.BlockSense;
import io.github.mudklip.minigamebots.client.observe.Observation;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * The active AI's memory of the current world: waypoints, a coarse map and who's friend or
 * enemy. Stored in {@code <profile>/memories/<world>/}, so memories never mix between worlds
 * (and exports leave them out unless you ask).
 */
public final class MemoryManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final double MERGE_DISTANCE = 8;
	private static final double VISIT_DISTANCE = 4;
	private static final int MARK_COOLDOWN_TICKS = 100;
	private static final int SAVE_EVERY_TICKS = 20 * 60;

	private final EventDetector events;
	private @Nullable WorldMemory memory;
	private @Nullable Path dir;
	private @Nullable String loadedKey;
	private final Map<String, CoarseMap> maps = new HashMap<>();
	private final Set<String> inside = new HashSet<>();
	private long tick;
	private long lastAiMark = -MARK_COOLDOWN_TICKS;
	private boolean dirty;
	private String myInsignia = "";

	public MemoryManager(EventDetector events) {
		this.events = events;
	}

	/** "sp-<folder>" in singleplayer, "mp-<address>" on a server, sanitised for a folder name. */
	public static @Nullable String worldKey(Minecraft client) {
		if (client.level == null) {
			return null;
		}
		String key;
		if (client.getSingleplayerServer() != null) {
			key = "sp-" + client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
					.toAbsolutePath().normalize().getFileName();
		} else if (client.getCurrentServer() != null) {
			key = "mp-" + client.getCurrentServer().ip;
		} else {
			key = "unknown";
		}
		return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
	}

	public @Nullable WorldMemory memory() {
		return memory;
	}

	public String myInsignia() {
		return myInsignia;
	}

	public @Nullable CoarseMap map(String dimension) {
		return maps.get(dimension);
	}

	/** Called every client tick. */
	public void tick(Minecraft client) {
		if (client.player != null && client.player.tickCount % 20 == 0) {
			notePeople(client);
		}
		tick++;
		ProfileManifest profile = MinigameBotsClient.instance().profiles().active();
		String world = worldKey(client);
		String key = profile == null || world == null ? null : profile.id + "/" + world;
		if (key == null || !key.equals(loadedKey)) {
			save();
			unload();
			if (key != null) {
				load(MinigameBotsClient.instance().profiles().store().dir(profile.id).resolve("memories").resolve(world), world);
				loadedKey = key;
			}
		}
		WorldMemory m = memory;
		if (m == null || client.player == null || client.level == null) {
			return;
		}
		if (tick % 20 == 0) {
			myInsignia = Insignia.of(client.player).id();
			checkVisits(client, m);
		}
		if (tick % SAVE_EVERY_TICKS == 0 && dirty) {
			save();
		}
	}

	/** Called once a second with the latest observation (when one is being made). */
	public void observe(Minecraft client, Observation obs) {
		if (memory == null || client.level == null || client.player == null || tick % 20 != 0) {
			return;
		}
		BlockPos pos = client.player.blockPosition();
		String dim = client.level.dimension().identifier().toString();
		boolean ore = false;
		boolean water = false;
		boolean interactive = false;
		for (byte c : obs.blocks().categories()) {
			ore |= c == BlockSense.Category.ORE.ordinal();
			water |= c == BlockSense.Category.WATER.ordinal();
			interactive |= c == BlockSense.Category.INTERACTIVE.ordinal();
		}
		int hostiles = 0;
		for (Observation.EntityInfo e : obs.entities()) {
			if (e.hostile() && e.visible()) {
				hostiles++;
			}
		}
		int danger = hostiles == 0 ? 0 : hostiles < 3 ? 1 : hostiles < 6 ? 2 : 3;
		boolean underground = !client.level.canSeeSky(pos.above()) && pos.getY() < client.level.getSeaLevel() - 8;
		maps.computeIfAbsent(dim, d -> new CoarseMap()).visit(pos.getX() >> 4, pos.getZ() >> 4,
				underground ? 11 : biomeGroup(client.level.getBiome(pos)), danger, ore, water, interactive);
		dirty = true;
	}

	static int biomeGroup(Holder<Biome> b) {
		if (b.is(BiomeTags.IS_NETHER)) {
			return 9;
		}
		if (b.is(BiomeTags.IS_END)) {
			return 10;
		}
		if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER) || b.is(BiomeTags.IS_BEACH)) {
			return 6;
		}
		if (b.is(BiomeTags.IS_JUNGLE)) {
			return 8;
		}
		if (b.is(BiomeTags.IS_MOUNTAIN)) {
			return 5;
		}
		if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA)) {
			return 2;
		}
		if (b.is(BiomeTags.IS_BADLANDS) || b.is(BiomeTags.IS_SAVANNA)) {
			return 3;
		}
		String id = b.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		if (id.contains("snow") || id.contains("frozen") || id.contains("ice")) {
			return 4;
		}
		if (id.contains("desert")) {
			return 3;
		}
		if (id.contains("swamp")) {
			return 7;
		}
		if (id.contains("plains") || id.contains("meadow")) {
			return 1;
		}
		return 0;
	}

	private void checkVisits(Minecraft client, WorldMemory m) {
		String dim = client.level.dimension().identifier().toString();
		var p = client.player.position();
		for (Waypoint w : m.waypoints) {
			boolean near = w.dimension.equals(dim) && w.distanceSq(p.x, p.y, p.z) <= VISIT_DISTANCE * VISIT_DISTANCE;
			if (near && inside.add(w.id)) {
				w.lastVisited = System.currentTimeMillis();
				w.confidence = 1;
				dirty = true;
				events.emit("waypoint_reached", Map.of("type", w.type, "name", w.name, "pinned", w.pinned));
			} else if (!near) {
				inside.remove(w.id);
			}
		}
	}

	/**
	 * Marks a place where the player stands. A mark of the same type within 8 blocks moves that
	 * waypoint instead of adding one (unless it's pinned). The AI can mark at most every 5 s.
	 */
	public @Nullable Waypoint mark(Minecraft client, String type, boolean byAi) {
		WorldMemory m = memory;
		if (m == null || client.player == null || client.level == null || !Waypoint.TYPES.contains(type)) {
			return null;
		}
		if (byAi) {
			if (tick - lastAiMark < MARK_COOLDOWN_TICKS) {
				return null;
			}
			lastAiMark = tick;
		}
		BlockPos pos = client.player.blockPosition();
		String dim = client.level.dimension().identifier().toString();
		Waypoint w = m.waypoints.stream()
				.filter(o -> o.type.equals(type) && o.dimension.equals(dim)
						&& o.distanceSq(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) <= MERGE_DISTANCE * MERGE_DISTANCE)
				.findFirst().orElse(null);
		if (w != null && w.pinned) {
			return w;
		}
		if (w == null) {
			if (m.waypoints.size() >= WorldMemory.MAX_WAYPOINTS && !forgetOne(m)) {
				return null;
			}
			w = new Waypoint();
			w.id = UUID.randomUUID().toString().substring(0, 8);
			w.created = System.currentTimeMillis();
			w.name = type;
			m.waypoints.add(w);
		}
		w.type = type;
		w.dimension = dim;
		w.x = pos.getX();
		w.y = pos.getY();
		w.z = pos.getZ();
		w.byAi = byAi;
		w.confidence = 1;
		w.lastVisited = System.currentTimeMillis();
		inside.add(w.id);
		dirty = true;
		events.emit("waypoint_marked", Map.of("type", type, "by_ai", byAi));
		return w;
	}

	/** Drops the least useful unpinned waypoint (lowest confidence, then oldest visit). */
	private boolean forgetOne(WorldMemory m) {
		return m.waypoints.stream().filter(w -> !w.pinned)
				.min(Comparator.comparingDouble((Waypoint w) -> w.confidence).thenComparingLong(w -> w.lastVisited))
				.map(m.waypoints::remove).orElse(false);
	}

	public void remove(Waypoint w) {
		if (memory != null && memory.waypoints.remove(w)) {
			dirty = true;
		}
	}

	public void changed() {
		dirty = true;
	}

	/** Recent hits on the AI per attacker (ms), to tell a joke punch from a real attack. */
	private final Map<String, java.util.ArrayDeque<Long>> recentHits = new java.util.HashMap<>();
	private @Nullable String lastRealAttacker;
	private long lastRealAttackAt;

	/**
	 * The AI (or the person it protects) was hit by this entity. A real attack (a weapon in hand,
	 * several hits in a row, or an enemy) makes them an enemy and is remembered with a bad feeling;
	 * a lone punch is only counted as a joke. A real attack under a friendly insignia, or by a
	 * friend, is a betrayal: that insignia loses trust.
	 */
	public void onAttackedBy(@Nullable Entity attacker) {
		WorldMemory m = memory;
		if (m == null || attacker == null || !(attacker instanceof LivingEntity living) || attacker instanceof Enemy) {
			return;
		}
		long now = System.currentTimeMillis();
		Insignia.Info ins = Insignia.of(living);
		String name = attacker instanceof Player p ? p.getGameProfile().name() : null;
		String key = name != null ? name : attacker.getStringUUID();
		var hits = recentHits.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
		hits.addLast(now);
		while (!hits.isEmpty() && now - hits.peekFirst() > 10_000) {
			hits.removeFirst();
		}
		String held = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(living.getMainHandItem().getItem()).getPath();
		boolean armed = held.endsWith("_sword") || held.endsWith("_axe") || held.equals("trident") || held.equals("mace")
				|| held.equals("bow") || held.equals("crossbow");
		Relation known = name == null ? null : m.players.get(name);
		Mind.Person who = name == null ? null : m.mind.people.computeIfAbsent(name, k -> new Mind.Person());
		boolean grudge = who != null && (who.betrayals > 0 || who.like() < -0.3);
		boolean real = armed || hits.size() >= 2 || known == Relation.ENEMY || grudge;
		if (!real) {
			if (who != null) {
				who.jokeHits++;
			}
			dirty = true;
			return; // A joke punch: noted, not held against them.
		}
		boolean friendInsignia = ins.present() && (m.insignias.get(ins.id()) == Relation.FRIEND || ins.id().equals(myInsignia));
		boolean betrayal = known == Relation.FRIEND || friendInsignia;
		if (who != null) {
			who.realAttacks++;
			who.weapons.merge(held, 1, Integer::sum);
			if (betrayal) {
				who.betrayals++;
			}
		}
		if (friendInsignia) {
			Mind.Trust t = m.mind.trust.computeIfAbsent(ins.id(), k -> new Mind.Trust());
			t.trust = t.now(now) - (t.betrayers.add(key) ? 0.35 : 0.2); // Several different betrayers: worse.
			t.betrayals++;
			t.lastIncident = now;
			m.insigniaLooks.putIfAbsent(ins.id(), ins.description());
		}
		remember(betrayal ? "betrayed_me" : "attacked_me", name != null ? name : attacker.getType().toShortString(), ins.present() ? ins.id() : "",
				held, betrayal ? -0.9 : -0.5, betrayal ? 0.9 : 0.5);
		lastRealAttacker = name;
		lastRealAttackAt = now;
		if (m.autoMarkAttackers) {
			if (ins.present() && !ins.id().equals(myInsignia) && m.insignias.get(ins.id()) != Relation.FRIEND) {
				if (m.insignias.put(ins.id(), Relation.ENEMY) != Relation.ENEMY) {
					m.insigniaLooks.put(ins.id(), ins.description());
					events.emit("insignia_marked_enemy", Map.of("insignia", ins.id()));
				}
			}
			if (name != null && (known != Relation.FRIEND || betrayal && who.betrayals >= 2)
					&& m.players.put(name, Relation.ENEMY) != Relation.ENEMY) {
				events.emit("player_marked_enemy", Map.of("player", name));
			}
		}
		dirty = true;
	}

	/** The AI was knocked out: blame the last real attacker (within 10 s). */
	public void onMyDeath() {
		WorldMemory m = memory;
		if (m == null || lastRealAttacker == null || System.currentTimeMillis() - lastRealAttackAt > 10_000) {
			return;
		}
		m.mind.people.computeIfAbsent(lastRealAttacker, k -> new Mind.Person()).knockedMeOut++;
		remember("knocked_me_out", lastRealAttacker, "", "", -1.0, 1.0);
		dirty = true;
	}

	/** The AI knocked someone out. */
	public void onIKnockedOut(String name) {
		WorldMemory m = memory;
		if (m == null || name == null) {
			return;
		}
		m.mind.people.computeIfAbsent(name, k -> new Mind.Person()).iKnockedOut++;
		remember("i_knocked_out", name, "", "", 0.7, 0.6);
		dirty = true;
	}

	private void remember(String what, String who, String insignia, String weapon, double feeling, double strength) {
		WorldMemory m = memory;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (m == null || mc.player == null || mc.level == null) {
			return;
		}
		Mind.Episode e = new Mind.Episode();
		e.time = System.currentTimeMillis();
		e.what = what;
		e.who = who;
		e.insignia = insignia;
		e.weapon = weapon;
		e.feeling = feeling;
		e.strength = strength;
		e.x = mc.player.blockPosition().getX();
		e.y = mc.player.blockPosition().getY();
		e.z = mc.player.blockPosition().getZ();
		e.dimension = mc.level.dimension().identifier().toString();
		var near = nearest(e.dimension, e.x, e.y, e.z, 1);
		if (!near.isEmpty() && near.get(0).distanceSq(e.x, e.y, e.z) < 48 * 48) {
			e.place = near.get(0).name.isEmpty() ? near.get(0).type : near.get(0).name;
		}
		m.mind.episodes.add(e);
		m.mind.forget(e.time);
		// How it feels about this place from now on.
		Mind.Place place = m.mind.places.computeIfAbsent(Mind.placeKey(e.dimension, e.x, e.z), k -> new Mind.Place());
		if (feeling < 0) {
			place.bad += strength >= 0.9 ? 2 : 1;
		} else {
			place.good++;
		}
		place.last = e.time;
	}

	/** Every second: who is around (profiles: seen, where, what they carry). */
	private void notePeople(net.minecraft.client.Minecraft client) {
		WorldMemory m = memory;
		if (m == null || client.level == null || client.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Player p : client.level.players()) {
			if (p == client.player || p.isSpectator() || p.distanceTo(client.player) > 48 || !client.player.hasLineOfSight(p)) {
				continue;
			}
			Mind.Person who = m.mind.people.computeIfAbsent(p.getGameProfile().name(), k -> new Mind.Person());
			if (now - who.lastSeen > 60_000) {
				who.seen++; // Counted once per meeting.
			}
			who.lastSeen = now;
			var near = nearest(client.level.dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), 1);
			who.lastPlace = near.isEmpty() ? "" : near.get(0).name.isEmpty() ? near.get(0).type : near.get(0).name;
			dirty = true;
		}
	}

	/** Feelings about the places around it (the 3 x 3 areas around the player), for the brain. */
	public com.google.gson.JsonArray recallPlaces(net.minecraft.client.Minecraft client) {
		com.google.gson.JsonArray out = new com.google.gson.JsonArray();
		WorldMemory m = memory;
		if (m == null || client.player == null || client.level == null || m.mind.places.isEmpty()) {
			return out;
		}
		String dim = client.level.dimension().identifier().toString();
		int cx = Math.floorDiv(client.player.blockPosition().getX(), 16);
		int cz = Math.floorDiv(client.player.blockPosition().getZ(), 16);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				Mind.Place pl = m.mind.places.get(dim + ":" + (cx + dx) + ":" + (cz + dz));
				if (pl != null) {
					var o = new com.google.gson.JsonObject();
					o.addProperty("dx", (cx + dx) * 16 + 8 - client.player.getX());
					o.addProperty("dz", (cz + dz) * 16 + 8 - client.player.getZ());
					o.addProperty("feeling", Math.round(pl.feeling() * 100) / 100.0);
					o.addProperty("here", dx == 0 && dz == 0);
					out.add(o);
				}
			}
		}
		return out;
	}

	/** What it recalls about the players in view, for the brain (cheap lookups). */
	public com.google.gson.JsonArray recall(net.minecraft.client.Minecraft client) {
		com.google.gson.JsonArray out = new com.google.gson.JsonArray();
		WorldMemory m = memory;
		if (m == null || client.level == null || client.player == null) {
			return out;
		}
		long now = System.currentTimeMillis();
		for (Player p : client.level.players()) {
			if (p == client.player || p.distanceTo(client.player) > 48) {
				continue;
			}
			Mind.Person who = m.mind.people.get(p.getGameProfile().name());
			if (who == null) {
				continue;
			}
			var o = new com.google.gson.JsonObject();
			o.addProperty("id", p.getId());
			o.addProperty("name", p.getGameProfile().name());
			o.addProperty("danger", Math.round(who.danger() * 100) / 100.0);
			o.addProperty("like", Math.round(who.like() * 100) / 100.0);
			o.addProperty("betrayals", who.betrayals);
			o.addProperty("knockedMeOut", who.knockedMeOut);
			o.addProperty("iKnockedOut", who.iKnockedOut);
			o.addProperty("realAttacks", who.realAttacks);
			o.addProperty("weapon", who.usualWeapon());
			Insignia.Info ins = Insignia.of(p);
			if (ins.present() && m.mind.trust.containsKey(ins.id())) {
				o.addProperty("insigniaTrust", Math.round(m.mind.trust.get(ins.id()).now(now) * 100) / 100.0);
			}
			out.add(o);
		}
		return out;
	}

	/** Remembers what an insignia looks like (for the Memory screen) when it's seen. */
	public void noteInsignia(Insignia.Info ins) {
		WorldMemory m = memory;
		if (m != null && ins.present() && !m.insigniaLooks.containsKey(ins.id())) {
			m.insigniaLooks.put(ins.id(), ins.description());
			dirty = true;
		}
	}

	/** Nearest waypoints in this dimension (for the observation). */
	public List<Waypoint> nearest(String dimension, double x, double y, double z, int n) {
		WorldMemory m = memory;
		if (m == null) {
			return List.of();
		}
		List<Waypoint> out = new ArrayList<>(m.waypoints.stream().filter(w -> w.dimension.equals(dimension)).toList());
		out.sort(Comparator.comparingDouble(w -> w.distanceSq(x, y, z)));
		return out.subList(0, Math.min(n, out.size()));
	}

	// --- Files ---

	private void load(Path dir, String world) {
		this.dir = dir;
		WorldMemory m = null;
		Path f = dir.resolve("memory.json");
		if (Files.isRegularFile(f)) {
			try {
				m = GSON.fromJson(Files.readString(f), WorldMemory.class);
			} catch (IOException | RuntimeException e) {
				MinigameBotsClient.LOGGER.warn("Couldn't read memory {}", f, e);
			}
		}
		if (m == null) {
			m = new WorldMemory();
		}
		m.world = world;
		memory = m;
		maps.clear();
		if (Files.isDirectory(dir)) {
			try (var files = Files.newDirectoryStream(dir, "map-*.bin.gz")) {
				for (Path p : files) {
					String dim = p.getFileName().toString().replace("map-", "").replace(".bin.gz", "").replace('~', ':').replace('+', '/');
					try (InputStream in = Files.newInputStream(p)) {
						maps.put(dim, CoarseMap.read(in));
					} catch (IOException e) {
						MinigameBotsClient.LOGGER.warn("Couldn't read map {}", p, e);
					}
				}
			} catch (IOException ignored) {
				// No maps yet.
			}
		}
		dirty = false;
	}

	private void unload() {
		memory = null;
		dir = null;
		loadedKey = null;
		maps.clear();
		inside.clear();
	}

	/** Writes everything (called periodically, on world/profile change and when leaving). */
	public void save() {
		WorldMemory m = memory;
		Path d = dir;
		if (m == null || d == null || !dirty) {
			return;
		}
		try {
			Files.createDirectories(d);
			Path tmp = d.resolve("memory.json.tmp");
			Files.writeString(tmp, GSON.toJson(m));
			Files.move(tmp, d.resolve("memory.json"), StandardCopyOption.REPLACE_EXISTING);
			for (var e : maps.entrySet()) {
				String name = "map-" + e.getKey().replace(':', '~').replace('/', '+') + ".bin.gz";
				Path mt = d.resolve(name + ".tmp");
				try (OutputStream out = Files.newOutputStream(mt)) {
					e.getValue().write(out);
				}
				Files.move(mt, d.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.warn("Couldn't save memory", e);
		}
	}
}
