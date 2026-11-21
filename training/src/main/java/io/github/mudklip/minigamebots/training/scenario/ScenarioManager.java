package io.github.mudklip.minigamebots.training.scenario;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.training.GroundTruth;
import io.github.mudklip.minigamebots.training.TgStorage;
import io.github.mudklip.minigamebots.training.TrainingGrounds;
import io.github.mudklip.minigamebots.training.arena.Arena;
import io.github.mudklip.minigamebots.training.arena.ArenaManager;
import io.github.mudklip.minigamebots.training.dummy.DummyManager;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import org.jspecify.annotations.Nullable;

/**
 * Runs scenarios as episodes: reset the arena, put players and dummies on coloured scoreboard
 * teams with their kits and spawns, count down, play until the win rule decides, report the
 * result (titles for people, ground-truth events for AIs), clean up and, if looping, go again.
 * Knocked-out players watch as spectators instead of dying, so nothing drops.
 */
public final class ScenarioManager {
	private static final int COUNTDOWN = 60;
	private static final int SHOW_RESULT = 60;
	private static final int BETWEEN = 40;
	private static final int STATUS_FRESH_MS = 3000;

	/** Last status a linked client reported. */
	private record AiStatus(boolean active, float tps, long when) {
	}

	private static final class Stats {
		float dealt;
		float taken;
		int kills;
		int deaths;
	}

	private static final class Episode {
		final Scenario sc;
		final Map<UUID, Integer> players = new LinkedHashMap<>();
		final Map<UUID, GameType> previousMode = new HashMap<>();
		final Map<UUID, Integer> dummies = new LinkedHashMap<>();
		/** Style of each dummy that was picked by the league, to record the result against. */
		final Map<UUID, String> leagueStyles = new LinkedHashMap<>();
		/** Self-play: entity id of the dummy each player's client steers. */
		final Map<UUID, Integer> puppets = new LinkedHashMap<>();
		final Set<UUID> out = new HashSet<>();
		final Map<UUID, Stats> stats = new HashMap<>();
		final int[] started;
		final int[] kills;
		int countdown = COUNTDOWN;
		int age;
		WinRule.@Nullable Outcome outcome;
		int afterEnd;
		boolean stopRequested;
		float tps = 20;

		Episode(Scenario sc) {
			this.sc = sc;
			started = new int[sc.teams.size()];
			kills = new int[sc.teams.size()];
		}

		Stats stats(UUID id) {
			return this.stats.computeIfAbsent(id, k -> new Stats());
		}
	}

	private final ArenaManager arenas;
	private final DummyManager dummyManager;
	private final GroundTruth truth;
	private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
	/** Who joined which team, per scenario, for the next episodes. */
	private final Map<String, Map<UUID, Integer>> joined = new HashMap<>();
	private final Map<UUID, AiStatus> status = new ConcurrentHashMap<>();
	private @Nullable MinecraftServer server;
	private @Nullable Episode episode;
	private int restartIn = -1;

	public ScenarioManager(ArenaManager arenas, DummyManager dummies, GroundTruth truth) {
		this.arenas = arenas;
		this.dummyManager = dummies;
		this.truth = truth;
	}

	// --- Storage ---

	private final League league = new League();

	public League league() {
		return league;
	}

	public void load(MinecraftServer server) {
		this.server = server;
		scenarios.clear();
		league.load(TgStorage.dir(server).resolve("league.json"));
		Path dir = dir();
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path f : files) {
				try {
					Scenario s = TgStorage.GSON.fromJson(Files.readString(f), Scenario.class);
					if (s != null && Arena.validName(s.name) && s.teams != null && !s.teams.isEmpty()) {
						if (s.rule == null) {
							s.rule = WinRule.LAST_TEAM_STANDING;
						}
						scenarios.put(s.name, s);
					}
				} catch (IOException | RuntimeException e) {
					TrainingGrounds.LOGGER.warn("Skipping unreadable scenario {}", f, e);
				}
			}
		} catch (IOException e) {
			TrainingGrounds.LOGGER.warn("Couldn't list scenarios", e);
		}
	}

	private Path dir() {
		return TgStorage.dir(server()).resolve("scenarios");
	}

	private MinecraftServer server() {
		if (server == null) {
			throw new IllegalStateException("No server");
		}
		return server;
	}

	public void save(Scenario s) {
		try {
			TgStorage.writeAtomic(dir().resolve(s.name + ".json"), TgStorage.GSON.toJson(s));
		} catch (IOException e) {
			TrainingGrounds.LOGGER.warn("Couldn't save scenario {}", s.name, e);
		}
	}

	public List<Scenario> list() {
		List<Scenario> out = new ArrayList<>(scenarios.values());
		out.sort(Comparator.comparing(s -> s.name));
		return out;
	}

	public @Nullable Scenario get(String name) {
		return scenarios.get(name);
	}

	public Scenario create(String name, Arena arena) throws ArenaManager.ArenaException {
		if (!Arena.validName(name)) {
			throw new ArenaManager.ArenaException("Names use letters, digits, - and _ (up to 32)");
		}
		Scenario s = Scenario.redVsBlue(name, arena.name);
		// Share the arena's spawns between the teams to start with.
		for (int i = 0; i < arena.spawns.size(); i++) {
			s.teams.get(i % 2).spawns.add(arena.spawns.get(i).clone());
		}
		scenarios.put(name, s);
		save(s);
		return s;
	}

	/** A match from a minigame template, or a random one ("random"). Re-rolls a random one each call. */
	public Scenario createMinigame(String template, String name, Arena arena) throws ArenaManager.ArenaException {
		if (!Arena.validName(name)) {
			throw new ArenaManager.ArenaException("Names use letters, digits, - and _ (up to 32)");
		}
		Scenario s = Minigames.make(template, name, arena.name, new java.util.Random());
		for (int i = 0; i < arena.spawns.size(); i++) {
			s.teams.get(i % s.teams.size()).spawns.add(arena.spawns.get(i).clone());
		}
		Scenario old = scenarios.get(name);
		if (old != null) {
			s.episodes = old.episodes;
		}
		scenarios.put(name, s);
		save(s);
		return s;
	}

	public void delete(String name) throws ArenaManager.ArenaException {
		if (episode != null && episode.sc.name.equals(name)) {
			throw new ArenaManager.ArenaException("Stop it first");
		}
		if (scenarios.remove(name) == null) {
			throw new ArenaManager.ArenaException("No scenario called " + name);
		}
		joined.remove(name);
		try {
			Files.deleteIfExists(dir().resolve(name + ".json"));
		} catch (IOException e) {
			throw new ArenaManager.ArenaException("Couldn't delete: " + e.getMessage());
		}
	}

	public String nextName() {
		for (int i = 1; ; i++) {
			if (!scenarios.containsKey("match-" + i)) {
				return "match-" + i;
			}
		}
	}

	// --- Teams ---

	public void join(Scenario s, ServerPlayer p, int team) {
		joined.computeIfAbsent(s.name, k -> new LinkedHashMap<>()).put(p.getUUID(), team);
	}

	public void leave(Scenario s, ServerPlayer p) {
		Map<UUID, Integer> j = joined.get(s.name);
		if (j != null) {
			j.remove(p.getUUID());
		}
	}

	/** Team index the player joined for this scenario, or -1. */
	public int teamOf(Scenario s, ServerPlayer p) {
		Map<UUID, Integer> j = joined.get(s.name);
		Integer t = j == null ? null : j.get(p.getUUID());
		return t == null ? -1 : t;
	}

	public int joinedCount(Scenario s, int team) {
		Map<UUID, Integer> j = joined.get(s.name);
		return j == null ? 0 : (int) j.values().stream().filter(t -> t == team).count();
	}

	public void onStatus(ServerPlayer p, boolean aiActive, float tps) {
		status.put(p.getUUID(), new AiStatus(aiActive, Math.max(1, Math.min(20, tps)), System.currentTimeMillis()));
	}

	// --- Episodes ---

	/** In a round that has started and not been knocked out. */
	public boolean playing(ServerPlayer p) {
		Episode e = episode;
		return e != null && e.outcome == null && e.players.containsKey(p.getUUID()) && !e.out.contains(p.getUUID());
	}

	public @Nullable Scenario running() {
		Episode e = episode;
		return e == null ? null : e.sc;
	}

	public void start(Scenario s, ServerPlayer starter) throws ArenaManager.ArenaException {
		if (episode != null) {
			throw new ArenaManager.ArenaException(episode.sc.name + " is already running");
		}
		Map<UUID, Integer> j = joined.computeIfAbsent(s.name, k -> new LinkedHashMap<>());
		if (j.isEmpty()) {
			j.put(starter.getUUID(), 0);
		}
		restartIn = -1;
		begin(s);
	}

	/** Ends the current episode now and doesn't start another. */
	public void stop() {
		Episode e = episode;
		restartIn = -1;
		if (e == null) {
			return;
		}
		e.stopRequested = true;
		if (e.outcome == null) {
			finish(e, new WinRule.Outcome(true, -1, "stopped"));
		}
		e.afterEnd = SHOW_RESULT; // Clean up on the next tick.
	}

	private void begin(Scenario s) throws ArenaManager.ArenaException {
		Arena arena = arenas.get(s.arena);
		if (arena == null) {
			throw new ArenaManager.ArenaException("Arena " + s.arena + " no longer exists");
		}
		ServerLevel level = arenas.level(arena);
		MinecraftServer srv = server();
		if (s.randomize && s.episodes > 0) {
			// Keep everyone on their team slot if it still exists; otherwise the first team.
			Minigames.reroll(s, new java.util.Random());
			for (var en : joined.getOrDefault(s.name, java.util.Map.of()).entrySet()) {
				if (en.getValue() >= s.teams.size()) {
					en.setValue(0);
				}
			}
			save(s);
		}
		Episode e = new Episode(s);
		for (Map.Entry<UUID, Integer> en : joined.getOrDefault(s.name, Map.of()).entrySet()) {
			ServerPlayer p = srv.getPlayerList().getPlayer(en.getKey());
			if (p != null && en.getValue() < s.teams.size()) {
				e.players.put(p.getUUID(), en.getValue());
			}
		}
		if (e.players.isEmpty()) {
			throw new ArenaManager.ArenaException("Nobody has joined a team");
		}
		arenas.reset(arena);
		List<PlayerTeam> teams = scoreboardTeams(s);
		int[] next = new int[s.teams.size()];
		for (Map.Entry<UUID, Integer> en : e.players.entrySet()) {
			ServerPlayer p = srv.getPlayerList().getPlayer(en.getKey());
			int t = en.getValue();
			Scenario.Team team = s.teams.get(t);
			e.previousMode.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
			if (p.level() != level) {
				p.teleportTo(level, p.getX(), p.getY(), p.getZ(), Set.of(), p.getYRot(), p.getXRot(), true);
			}
			p.setGameMode(GameType.SURVIVAL);
			ArenaManager.refresh(p);
			if (!team.kit.isEmpty()) {
				ArenaManager.applyKit(p, team.kit);
				io.github.mudklip.minigamebots.training.dummy.Loadout.check(s.name, p, team.kit);
			}
			double[] sp = spawn(arena, team, t, next[t]++);
			if (sp != null) {
				p.teleportTo(level, sp[0], sp[1], sp[2], Set.of(), (float) sp[3], (float) sp[4], true);
			}
			p.setDeltaMovement(Vec3.ZERO);
			srv.getScoreboard().addPlayerToTeam(p.getScoreboardName(), teams.get(t));
			e.started[t]++;
		}
		for (int t = 0; t < s.teams.size(); t++) {
			Scenario.Team team = s.teams.get(t);
			for (int i = 0; i < team.dummies; i++) {
				double[] sp = spawn(arena, team, t, next[t]++);
				Vec3 at = sp != null ? new Vec3(sp[0], sp[1], sp[2]) : arena.box().getCenter();
				String style = team.style();
				boolean picked = League.LEAGUE.equals(style);
				if (picked) {
					style = league.pick(e.players.keySet().iterator().next(), level.getRandom(), selfPlayer(e) != null);
				}
				DummySettings ds = DummySettings.style(style);
				ds.infiniteHealth = false;
				ds.fightsBack = true;
				Mannequin m = dummyManager.spawnAt(level, at, sp != null ? (float) sp[3] : 0, ds, false);
				if (m != null) {
					ServerPlayer owner = ds.puppet ? selfPlayer(e) : null;
					if (owner != null) {
						dummyManager.setPuppetOwner(m.getUUID(), owner.getUUID());
						e.puppets.put(owner.getUUID(), m.getId());
					}
					if (picked) {
						e.leagueStyles.put(m.getUUID(), ds.style);
					}
					if (!team.kit.isEmpty()) {
						// The team's kit, exactly as its players get it (not the style's own gear).
						io.github.mudklip.minigamebots.training.dummy.Loadout.apply(m, dummyManager.byId(m.getUUID()), team.kit);
						io.github.mudklip.minigamebots.training.dummy.Loadout.check(s.name, m, team.kit);
					}
					dummyManager.setPaused(m.getUUID(), true);
					srv.getScoreboard().addPlayerToTeam(m.getScoreboardName(), teams.get(t));
					e.dummies.put(m.getUUID(), t);
					e.started[t]++;
				}
			}
		}
		episode = e;
		s.episodes++;
		for (UUID id : e.players.keySet()) {
			ServerPlayer p = srv.getPlayerList().getPlayer(id);
			JsonObject j = new JsonObject();
			j.addProperty("scenario", s.name);
			j.addProperty("episode", s.episodes);
			j.addProperty("team", s.teams.get(e.players.get(id)).color);
			j.addProperty("teams", s.teams.size());
			j.addProperty("rule", s.rule.name().toLowerCase());
			j.addProperty("time_limit", s.timeLimit);
			j.addProperty("teammates", e.started[e.players.get(id)] - 1);
			j.addProperty("opponents", opponents(e, e.players.get(id)));
			if (e.puppets.containsKey(id)) {
				j.addProperty("puppet_id", e.puppets.get(id));
			}
			truth.send(p, "episode_start", j);
		}
	}

	/** The first player in the round whose client can steer a self-play dummy, or null. */
	private @Nullable ServerPlayer selfPlayer(Episode e) {
		for (UUID id : e.players.keySet()) {
			ServerPlayer p = server().getPlayerList().getPlayer(id);
			if (p != null && truth.supports(p, io.github.mudklip.minigamebots.common.Protocol.Features.PUPPET)
					&& !e.puppets.containsKey(id)) {
				return p;
			}
		}
		return null;
	}

	/** Styles of the dummies not on {@code team}, e.g. "godly, L3". */
	private String opponents(Episode e, int team) {
		List<String> out = new java.util.ArrayList<>();
		for (Map.Entry<UUID, Integer> d : e.dummies.entrySet()) {
			if (d.getValue() != team) {
				var dummy = dummyManager.byId(d.getKey());
				out.add(dummy == null ? "?" : dummy.settings.style);
			}
		}
		return String.join(", ", out);
	}

	/** Where member number {@code index} of a team starts: its own spawns, else the arena's. */
	private static double @Nullable [] spawn(Arena arena, Scenario.Team team, int teamIndex, int index) {
		if (!team.spawns.isEmpty()) {
			return team.spawns.get(index % team.spawns.size());
		}
		if (!arena.spawns.isEmpty()) {
			return arena.spawns.get((teamIndex + index * 2) % arena.spawns.size());
		}
		return null;
	}

	private List<PlayerTeam> scoreboardTeams(Scenario s) {
		var board = server().getScoreboard();
		List<PlayerTeam> out = new ArrayList<>();
		for (Scenario.Team t : s.teams) {
			String name = "tg_" + t.color;
			PlayerTeam team = board.getPlayerTeam(name);
			if (team == null) {
				team = board.addPlayerTeam(name);
			}
			TeamColor color = TeamColor.byName(t.color);
			team.setColor(Optional.ofNullable(color));
			team.setDisplayName(Component.literal(t.label()));
			team.setAllowFriendlyFire(false);
			team.setSeeFriendlyInvisibles(true);
			out.add(team);
		}
		return out;
	}

	public void tick() {
		if (restartIn > 0 && --restartIn == 0) {
			restartIn = -1;
			Scenario s = lastScenario;
			if (s != null && scenarios.containsKey(s.name)) {
				try {
					begin(s);
				} catch (ArenaManager.ArenaException ex) {
					broadcastLast(Component.literal("Couldn't start the next episode: " + ex.getMessage()).withStyle(ChatFormatting.RED));
				}
			}
		}
		Episode e = episode;
		if (e == null) {
			return;
		}
		MinecraftServer srv = server();
		if (e.outcome != null) {
			if (++e.afterEnd >= SHOW_RESULT) {
				cleanup(e);
			}
			return;
		}
		if (e.countdown > 0) {
			if (e.countdown % 20 == 0) {
				title(e, Component.literal(Integer.toString(e.countdown / 20)).withStyle(ChatFormatting.YELLOW),
						Component.literal(e.sc.rule.label));
			}
			if (--e.countdown == 0) {
				title(e, Component.literal("Go!").withStyle(ChatFormatting.GREEN), Component.empty());
				e.dummies.keySet().forEach(id -> dummyManager.setPaused(id, false));
			}
			return;
		}
		e.age++;
		for (UUID id : e.players.keySet()) {
			ServerPlayer p = srv.getPlayerList().getPlayer(id);
			if ((p == null || !p.isAlive()) && e.out.add(id)) {
				e.stats(id).deaths++;
			}
		}
		int[] alive = alive(e);
		WinRule.Outcome o = e.sc.rule.decide(alive, e.started, e.kills, e.age / 20, e.sc.timeLimit, e.sc.killTarget);
		if (e.sc.matchAiSpeed && e.age % 20 == 0) {
			matchAiSpeed(e);
		}
		if (e.age % 20 == 0) {
			actionBar(e, alive);
		}
		if (o.over()) {
			finish(e, o);
		}
	}

	private int[] alive(Episode e) {
		int[] alive = new int[e.sc.teams.size()];
		for (Map.Entry<UUID, Integer> en : e.players.entrySet()) {
			if (!e.out.contains(en.getKey())) {
				alive[en.getValue()]++;
			}
		}
		for (Map.Entry<UUID, Integer> en : e.dummies.entrySet()) {
			if (!e.out.contains(en.getKey())) {
				alive[en.getValue()]++;
			}
		}
		return alive;
	}

	private void actionBar(Episode e, int[] alive) {
		StringBuilder sb = new StringBuilder();
		for (int t = 0; t < e.sc.teams.size(); t++) {
			if (t > 0) {
				sb.append("  vs  ");
			}
			sb.append(e.sc.teams.get(t).label()).append(' ').append(alive[t]).append(" in, ").append(e.kills[t]).append(" kills");
		}
		if (e.sc.timeLimit > 0) {
			int left = Math.max(0, e.sc.timeLimit - e.age / 20);
			sb.append(String.format("   %d:%02d", left / 60, left % 60));
		}
		if (e.sc.matchAiSpeed && e.tps < 19.5f) {
			sb.append(String.format("   AI speed %.0f tps", e.tps));
		}
		Component msg = Component.literal(sb.toString()).withStyle(ChatFormatting.YELLOW);
		forEachPlayer(e, p -> p.sendOverlayMessage(msg));
	}

	/** Slows the game to the slowest AI's pace (never faster than normal). */
	private void matchAiSpeed(Episode e) {
		long now = System.currentTimeMillis();
		float target = 20;
		for (UUID id : e.players.keySet()) {
			AiStatus st = status.get(id);
			if (st != null && st.active() && now - st.when() < STATUS_FRESH_MS) {
				target = Math.min(target, st.tps());
			}
		}
		target = Math.max(4, target);
		float next = Math.round((e.tps + (target - e.tps) * 0.5f) * 2) / 2f;
		if (Math.abs(next - e.tps) >= 0.5f) {
			e.tps = next;
			server().tickRateManager().setTickRate(next);
		}
	}

	private void finish(Episode e, WinRule.Outcome o) {
		e.outcome = o;
		e.afterEnd = 0;
		e.dummies.keySet().forEach(id -> dummyManager.setPaused(id, true));
		Scenario s = e.sc;
		String winner = o.winner() >= 0 ? s.teams.get(o.winner()).color : "";
		if (!"stopped".equals(o.reason())) {
			if (o.winner() >= 0) {
				s.wins.merge(winner, 1, Integer::sum);
			} else {
				s.draws++;
			}
			save(s);
		}
		for (Map.Entry<UUID, Integer> en : e.players.entrySet()) {
			ServerPlayer p = server().getPlayerList().getPlayer(en.getKey());
			if (p == null) {
				continue;
			}
			String result = o.winner() < 0 ? "draw" : o.winner() == en.getValue() ? "win" : "loss";
			Component big = switch (result) {
				case "win" -> Component.literal("Victory").withStyle(ChatFormatting.GOLD);
				case "loss" -> Component.literal("Defeat").withStyle(ChatFormatting.RED);
				default -> Component.literal("Draw").withStyle(ChatFormatting.GRAY);
			};
			sendTitle(p, big, Component.literal(o.reason()));
			Stats st = e.stats(p.getUUID());
			JsonObject j = new JsonObject();
			j.addProperty("scenario", s.name);
			j.addProperty("result", result);
			j.addProperty("reason", o.reason());
			j.addProperty("winner", winner);
			j.addProperty("duration_s", e.age / 20.0);
			j.addProperty("kills", st.kills);
			j.addProperty("deaths", st.deaths);
			j.addProperty("damage_dealt", st.dealt);
			j.addProperty("damage_taken", st.taken);
			j.addProperty("team_kills", e.kills[en.getValue()]);
			j.addProperty("opponents", opponents(e, en.getValue()));
			if (!"stopped".equals(o.reason())) {
				for (Map.Entry<UUID, String> ls : e.leagueStyles.entrySet()) {
					if (!en.getValue().equals(e.dummies.get(ls.getKey()))) {
						league.result(p.getUUID(), ls.getValue(), "win".equals(result));
					}
				}
			}
			truth.send(p, "episode_end", j);
		}
	}

	private @Nullable Scenario lastScenario;

	private void cleanup(Episode e) {
		episode = null;
		lastScenario = e.sc;
		e.dummies.keySet().forEach(dummyManager::remove);
		for (Map.Entry<UUID, GameType> en : e.previousMode.entrySet()) {
			ServerPlayer p = server().getPlayerList().getPlayer(en.getKey());
			if (p != null) {
				p.setGameMode(en.getValue() == GameType.SPECTATOR ? GameType.SURVIVAL : en.getValue());
				ArenaManager.refresh(p);
			}
		}
		if (e.sc.matchAiSpeed) {
			server().tickRateManager().setTickRate(20);
		}
		if (e.sc.loop && !e.stopRequested) {
			restartIn = BETWEEN;
		}
	}

	private void broadcastLast(Component msg) {
		Scenario s = lastScenario;
		if (s == null) {
			return;
		}
		for (UUID id : joined.getOrDefault(s.name, Map.of()).keySet()) {
			ServerPlayer p = server().getPlayerList().getPlayer(id);
			if (p != null) {
				p.sendSystemMessage(msg);
			}
		}
	}

	// --- Hooks from game events ---

	private int teamOf(Episode e, @Nullable Entity entity) {
		if (entity == null) {
			return -1;
		}
		Integer t = e.players.get(entity.getUUID());
		if (t == null) {
			t = e.dummies.get(entity.getUUID());
		}
		return t == null ? -1 : t;
	}

	/** A player in an episode who would die is knocked out instead: spectator until the end. */
	public boolean allowDeath(LivingEntity victim, DamageSource source) {
		Episode e = episode;
		if (e == null || e.outcome != null || !(victim instanceof ServerPlayer p) || !e.players.containsKey(p.getUUID())
				|| e.out.contains(p.getUUID())) {
			return true;
		}
		knockOut(e, p, source);
		truth.onDeath(p, source);
		p.setHealth(p.getMaxHealth());
		p.setGameMode(GameType.SPECTATOR);
		p.sendSystemMessage(Component.literal("Knocked out. You'll be back next episode.").withStyle(ChatFormatting.GRAY));
		return false;
	}

	/** Dummies in an episode die for real (they don't respawn). */
	public void afterDeath(LivingEntity victim, DamageSource source) {
		Episode e = episode;
		if (e != null && e.outcome == null && e.dummies.containsKey(victim.getUUID()) && !e.out.contains(victim.getUUID())) {
			knockOut(e, victim, source);
		}
	}

	private void knockOut(Episode e, LivingEntity victim, DamageSource source) {
		e.out.add(victim.getUUID());
		e.stats(victim.getUUID()).deaths++;
		Entity killer = GroundTruth.attacker(source);
		int killerTeam = teamOf(e, killer);
		int victimTeam = teamOf(e, victim);
		if (killerTeam >= 0 && killerTeam != victimTeam) {
			e.kills[killerTeam]++;
			e.stats(killer.getUUID()).kills++;
		}
		Component msg = Component.literal(name(e, victim) + " was knocked out"
				+ (killer != null ? " by " + name(e, killer) : "")).withStyle(ChatFormatting.GRAY);
		forEachPlayer(e, p -> p.sendSystemMessage(msg));
	}

	/** "Player123", or "Blue L3 dummy" for dummies (their name tag also shows health). */
	private String name(Episode e, Entity entity) {
		var d = dummyManager.get(entity);
		if (d != null) {
			int t = teamOf(e, entity);
			return (t >= 0 ? e.sc.teams.get(t).label() + " " : "") + "L" + d.settings.level + " dummy";
		}
		return entity.getPlainTextName();
	}

	public void onDamage(LivingEntity victim, DamageSource source, float taken) {
		Episode e = episode;
		if (e == null || e.outcome != null) {
			return;
		}
		if (e.players.containsKey(victim.getUUID())) {
			e.stats(victim.getUUID()).taken += taken;
		}
		Entity attacker = GroundTruth.attacker(source);
		if (attacker != null && e.players.containsKey(attacker.getUUID())) {
			e.stats(attacker.getUUID()).dealt += taken;
		}
	}

	public void onDisconnect(ServerPlayer p) {
		Episode e = episode;
		if (e != null && e.players.containsKey(p.getUUID()) && e.out.add(p.getUUID())) {
			e.stats(p.getUUID()).deaths++;
		}
		status.remove(p.getUUID());
	}

	public void shutdown() {
		Episode e = episode;
		if (e != null) {
			e.stopRequested = true;
			cleanup(e);
		}
		restartIn = -1;
	}

	private void forEachPlayer(Episode e, java.util.function.Consumer<ServerPlayer> action) {
		for (UUID id : e.players.keySet()) {
			ServerPlayer p = server().getPlayerList().getPlayer(id);
			if (p != null) {
				action.accept(p);
			}
		}
	}

	private void title(Episode e, Component title, Component subtitle) {
		forEachPlayer(e, p -> sendTitle(p, title, subtitle));
	}

	private static void sendTitle(ServerPlayer p, Component title, Component subtitle) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 20, 5));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
