package io.github.mudklip.minigamebots.training;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.training.dummy.DummyManager;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;

/**
 * Instant minigames for the Core to learn from (no setup): built around the player, played in
 * rounds that restart by themselves, announced with titles and a sidebar scoreboard like a real
 * server, so the Core has to work out the rules from what happens. The ground is put back on stop.
 * Kinds: snowball (a snowball hit knocks out; a dummy's touch knocks you out), one_hit (any hit
 * knocks out), sumo (no damage; fall off the platform and you're out), king (stand on the gold to
 * score; first to 20 wins).
 */
public final class CoreGames {
	public static final List<String> KINDS = List.of("snowball", "one_hit", "sumo", "king", "spleef", "tnt_run");
	private static final Map<String, String> TITLES = Map.of("snowball", "Snowball Fight", "one_hit", "One Hit", "sumo", "Sumo",
			"king", "King of the Hill", "spleef", "Spleef", "tnt_run", "TNT Run");
	/** Floor blocks stepped on in TNT Run: removed a moment later (position -> tick to remove at). */
	private final Map<BlockPos, Integer> crumbling = new java.util.HashMap<>();
	private static final int HALF = 8;
	private static final int TO_WIN = 20;

	private final DummyManager dummies;
	private final GroundTruth truth;
	private final List<Map.Entry<BlockPos, BlockState>> replaced = new ArrayList<>();
	private final List<UUID> foes = new ArrayList<>();
	private final Map<UUID, Integer> points = new java.util.HashMap<>();
	private @Nullable UUID owner;
	private @Nullable ServerLevel level;
	private @Nullable String kind;
	private BlockPos centre = BlockPos.ZERO;
	private boolean ownerOut;
	private int restartIn = -1;
	private int age;
	private int myPoints;
	/** Free-for-all: the bots also fight each other (only when asked for; off by default). */
	private boolean ffa;

	public CoreGames(DummyManager dummies, GroundTruth truth) {
		this.dummies = dummies;
		this.truth = truth;
	}

	public boolean running() {
		return owner != null;
	}

	public String start(ServerPlayer p, String wanted) {
		return start(p, wanted, false);
	}

	public String start(ServerPlayer p, String wanted, boolean freeForAll) {
		stop(p.level().getServer());
		ffa = freeForAll;
		String k = wanted.equals("random") ? KINDS.get(p.getRandom().nextInt(KINDS.size())) : wanted;
		if (!KINDS.contains(k)) {
			return "Games: random, " + String.join(", ", KINDS);
		}
		ServerLevel lv = (ServerLevel) p.level();
		level = lv;
		owner = p.getUUID();
		kind = k;
		centre = PracticeAreas.ground(p);
		build(lv);
		newRound(p);
		return "Started " + TITLES.get(k) + " around you (rounds restart by themselves; /tg coregame stop ends it)";
	}

	private void build(ServerLevel lv) {
		var stone = Blocks.SMOOTH_STONE.defaultBlockState();
		var glass = Blocks.GLASS.defaultBlockState();
		var air = Blocks.AIR.defaultBlockState();
		boolean fall = fallGame();
		boolean sumo = fall;
		int half = "sumo".equals(kind) ? 5 : fall ? 7 : HALF;
		int outer = sumo ? half + 4 : half;
		for (BlockPos q : BlockPos.betweenClosed(centre.offset(-outer, -4, -outer), centre.offset(outer, 5, outer))) {
			int dx = Math.abs(q.getX() - centre.getX());
			int dz = Math.abs(q.getZ() - centre.getZ());
			int dy = q.getY() - centre.getY();
			BlockState want;
			if (sumo) {
				boolean inside = dx <= half && dz <= half;
				BlockState floor = "spleef".equals(kind) ? Blocks.SNOW_BLOCK.defaultBlockState()
						: "tnt_run".equals(kind) ? Blocks.TNT.defaultBlockState() : stone;
				if ("sumo".equals(kind)) {
					want = dy == -1 && inside ? floor : dy == -4 ? stone : dy <= -2 && !inside ? Blocks.WATER.defaultBlockState()
							: dy <= -2 ? stone : air;
				} else {
					// Spleef / TNT Run: the whole floor can go; a water pit below catches the fallen.
					boolean rim = dx == outer || dz == outer;
					want = dy == -1 && inside ? floor : dy == -4 ? stone : dy <= -2 && dy >= -3 ? Blocks.WATER.defaultBlockState()
							: rim && dy >= -1 && dy <= 2 ? glass : air;
				}
			} else {
				if (dy < -1) {
					continue;
				}
				boolean wall = dx == half || dz == half;
				want = dy == -1 ? stone : wall && dy <= 2 ? glass : air;
			}
			if ("king".equals(kind) && dy == -1 && dx <= 1 && dz <= 1) {
				want = Blocks.GOLD_BLOCK.defaultBlockState();
			}
			set(lv, q, want);
		}
	}

	private void rebuildFloor(ServerLevel lv) {
		int half = "sumo".equals(kind) ? 5 : 7;
		BlockState floor = "spleef".equals(kind) ? Blocks.SNOW_BLOCK.defaultBlockState()
				: "tnt_run".equals(kind) ? Blocks.TNT.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
		for (BlockPos q : BlockPos.betweenClosed(centre.offset(-half, -1, -half), centre.offset(half, -1, half))) {
			if (lv.getBlockState(q).isAir()) {
				lv.setBlock(q, floor, Block.UPDATE_CLIENTS); // Already recorded in "replaced" when first built.
			}
		}
	}

	private void set(ServerLevel lv, BlockPos q, BlockState want) {
		if (lv.getBlockEntity(q) != null) {
			return;
		}
		BlockState had = lv.getBlockState(q);
		if (had != want) {
			replaced.add(Map.entry(q.immutable(), had));
			lv.setBlock(q, want, Block.UPDATE_CLIENTS);
		}
	}

	private void newRound(ServerPlayer p) {
		ServerLevel lv = level;
		if (lv == null) {
			return;
		}
		clearFoes();
		if (fallGame() && !replaced.isEmpty()) {
			rebuildFloor(lv);
		}
		crumbling.clear();
		ownerOut = false;
		restartIn = -1;
		age = 0;
		myPoints = 0;
		points.clear();
		int r = "sumo".equals(kind) ? 3 : HALF - 2;
		for (int i = 0; i < 3; i++) {
			double a = Math.PI * 2 * i / 3;
			DummySettings s = DummySettings.style("L3");
			Mannequin m = dummies.spawnAt(lv, Vec3.atBottomCenterOf(centre).add(Math.cos(a) * r, 0, Math.sin(a) * r), 0, s, false);
			if (m != null) {
				var d = dummies.byId(m.getUUID());
				if (d != null) {
					d.settings.infiniteHealth = false;
					if ("spleef".equals(kind) || "tnt_run".equals(kind)) {
						d.settings.fightsBack = false; // Floor games: the floor is the danger, not fists.
						d.settings.cps = 0;
						d.settings.damage = 0;
					}
				}
				foes.add(m.getUUID());
				// A bot that plays this game properly (digs in Spleef, runs in TNT Run, pushes in Sumo...).
				final String game = kind;
				dummies.setBrain(m.getUUID(), io.github.mudklip.minigamebots.training.dummy.ModeBots.of(
						new io.github.mudklip.minigamebots.training.dummy.ModeBots.Field(centre,
								"sumo".equals(game) ? 5 : fallGame() ? 7 : HALF - 1, game,
								b -> crumbling.containsKey(b), ffa, this::contestants)));
			}
		}
		p.setGameMode(GameType.SURVIVAL);
		p.setHealth(p.getMaxHealth());
		p.teleportTo(lv, centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5, java.util.Set.of(), p.getYRot(), 0, true);
		var inv = p.getInventory();
		// The round's kit, only what's missing (no pile-up over rounds).
		if ("snowball".equals(kind) && inv.countItem(Items.SNOWBALL) < 16) {
			inv.add(new ItemStack(Items.SNOWBALL, 16 - inv.countItem(Items.SNOWBALL)));
		} else if ("spleef".equals(kind) && inv.countItem(Items.DIAMOND_SHOVEL) == 0) {
			inv.add(new ItemStack(Items.DIAMOND_SHOVEL));
		} else if (!fallGame() && !"snowball".equals(kind) && inv.countItem(Items.WOODEN_SWORD) == 0) {
			inv.add(new ItemStack(Items.WOODEN_SWORD));
		}
		scoreboard(p, 0);
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(TITLES.get(kind))));
		truth.send(p, "episode_start", new JsonObject());
	}

	private void scoreboard(ServerPlayer p, int value) {
		var sb = p.level().getServer().getScoreboard();
		Objective o = sb.getObjective("mgb_core");
		if (o == null) {
			o = sb.addObjective("mgb_core", ObjectiveCriteria.DUMMY, Component.literal(TITLES.get(kind)),
					ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		o.setDisplayName(Component.literal(TITLES.get(kind)));
		sb.setDisplayObjective(DisplaySlot.SIDEBAR, o);
		sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(p.getScoreboardName()), o).set(value);
	}

	public void stop(@Nullable MinecraftServer server) {
		clearFoes();
		crumbling.clear();
		ServerLevel lv = level;
		if (lv != null) {
			// Back to the start spot first, so nobody ends up inside the ground once it's put back.
			ServerPlayer back = owner == null ? null : lv.getServer().getPlayerList().getPlayer(owner);
			if (back != null) {
				back.teleportTo(lv, centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5, java.util.Set.of(), back.getYRot(), 0, true);
				back.setDeltaMovement(Vec3.ZERO);
			}
			for (int k = replaced.size() - 1; k >= 0; k--) {
				lv.setBlock(replaced.get(k).getKey(), replaced.get(k).getValue(), Block.UPDATE_CLIENTS);
			}
			var sb = lv.getServer().getScoreboard();
			Objective o = sb.getObjective("mgb_core");
			if (o != null) {
				sb.removeObjective(o);
			}
			ServerPlayer p = owner == null ? null : lv.getServer().getPlayerList().getPlayer(owner);
			if (p != null && p.gameMode() == GameType.SPECTATOR) {
				p.setGameMode(GameType.SURVIVAL);
			}
		}
		replaced.clear();
		owner = null;
		kind = null;
	}

	private void clearFoes() {
		for (UUID id : foes) {
			dummies.remove(id);
		}
		foes.clear();
	}

	private @Nullable ServerPlayer player(MinecraftServer server) {
		return owner == null ? null : server.getPlayerList().getPlayer(owner);
	}

	private boolean foe(LivingEntity e) {
		return foes.contains(e.getUUID());
	}

	/** Every server tick. */
	public void tick(MinecraftServer server) {
		ServerPlayer p = player(server);
		if (owner == null) {
			return;
		}
		if (p == null) {
			stop(server);
			return;
		}
		if (restartIn >= 0) {
			if (--restartIn < 0) {
				newRound(p);
			}
			return;
		}
		age++;
		ServerLevel lv = level;
		if (lv == null) {
			return;
		}
		if ("tnt_run".equals(kind) && age > 60) {
			crumble(lv, p); // 3 seconds' grace at the start, like on servers.
		}
		if (fallGame()) {
			if (!ownerOut && p.getY() < centre.getY() - 0.8) {
				out(p, p);
			}
			for (UUID id : List.copyOf(foes)) {
				if (lv.getEntity(id) instanceof LivingEntity e && e.getY() < centre.getY() - 0.8) {
					out(e, null);
				}
			}
		}
		if ("king".equals(kind) && age % 20 == 0) {
			if (!ownerOut && onGold(p)) {
				scoreboard(p, ++myPoints);
				if (myPoints >= TO_WIN) {
					end(p, true);
					return;
				}
			}
			for (UUID id : foes) {
				if (lv.getEntity(id) instanceof LivingEntity e && onGold(e) && points.merge(id, 1, Integer::sum) >= TO_WIN) {
					end(p, false);
					return;
				}
			}
		}
		if ("snowball".equals(kind) && age % 100 == 0 && p.getInventory().countItem(Items.SNOWBALL) < 8) {
			p.getInventory().add(new ItemStack(Items.SNOWBALL, 8));
		}
		if (ownerOut) {
			end(p, false);
		} else if (foes.isEmpty() && !"king".equals(kind)) {
			end(p, true);
		} else if (age > 20 * 120) {
			end(p, "king".equals(kind) && myPoints > points.values().stream().mapToInt(Integer::intValue).max().orElse(0));
		}
	}

	/** Everyone still in the round: you and the bots left. */
	private List<LivingEntity> contestants() {
		List<LivingEntity> out = new ArrayList<>();
		ServerLevel lv = level;
		if (lv == null) {
			return out;
		}
		ServerPlayer p = player(lv.getServer());
		if (p != null && !ownerOut) {
			out.add(p);
		}
		for (UUID id : foes) {
			if (lv.getEntity(id) instanceof LivingEntity e) {
				out.add(e);
			}
		}
		return out;
	}

	private boolean fallGame() {
		return "sumo".equals(kind) || "spleef".equals(kind) || "tnt_run".equals(kind);
	}

	/** TNT Run: the floor under everyone disappears 8 ticks after they step on it. */
	private void crumble(ServerLevel lv, ServerPlayer p) {
		List<LivingEntity> people = new ArrayList<>();
		if (!ownerOut) {
			people.add(p);
		}
		for (UUID id : foes) {
			if (lv.getEntity(id) instanceof LivingEntity e) {
				people.add(e);
			}
		}
		for (LivingEntity e : people) {
			BlockPos under = BlockPos.containing(e.getX(), centre.getY() - 1, e.getZ());
			if (lv.getBlockState(under).is(Blocks.TNT) && e.onGround()) {
				crumbling.putIfAbsent(under, age + 8);
			}
		}
		crumbling.entrySet().removeIf(en -> {
			if (en.getValue() <= age) {
				lv.setBlock(en.getKey(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				return true;
			}
			return false;
		});
	}

	private boolean onGold(LivingEntity e) {
		return Math.abs(e.getX() - (centre.getX() + 0.5)) <= 1.5 && Math.abs(e.getZ() - (centre.getZ() + 0.5)) <= 1.5
				&& Math.abs(e.getY() - centre.getY()) < 0.6;
	}

	/** Damage inside the game: the rules decide who is out. */
	public void onDamage(LivingEntity victim, DamageSource source, float taken) {
		ServerLevel lv = level;
		if (owner == null || lv == null || restartIn >= 0) {
			return;
		}
		ServerPlayer p = player(lv.getServer());
		if (p == null) {
			return;
		}
		boolean isOwner = victim == p;
		if (!isOwner && !foe(victim)) {
			return;
		}
		var direct = source.getDirectEntity();
		var cause = source.getEntity();
		boolean byOwner = cause == p;
		switch (kind == null ? "" : kind) {
			case "snowball" -> {
				if (direct instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball || isOwner && cause != null && foe((LivingEntity) cause)) {
					out(victim, byOwner ? p : null);
				}
			}
			case "one_hit" -> {
				if (cause != null && cause != victim) {
					out(victim, byOwner ? p : null);
				}
			}
			case "sumo", "king", "spleef", "tnt_run" -> victim.setHealth(victim.getMaxHealth()); // Knockback only.
			default -> {
			}
		}
	}

	private void out(LivingEntity victim, @Nullable ServerPlayer creditTo) {
		ServerLevel lv = level;
		ServerPlayer p = lv == null ? null : player(lv.getServer());
		if (p == null) {
			return;
		}
		if (victim == p) {
			ownerOut = true;
			p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Eliminated!")));
			return;
		}
		foes.remove(victim.getUUID());
		if (creditTo != null) {
			scoreboard(p, ++myPoints);
		}
		victim.kill(lv);
		dummies.remove(victim.getUUID());
	}

	private void end(ServerPlayer p, boolean won) {
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(won ? "You win!" : "You lost!")));
		JsonObject d = new JsonObject();
		d.addProperty("result", won ? "win" : "loss");
		truth.send(p, "episode_end", d);
		restartIn = 60;
		clearFoes();
	}
}
