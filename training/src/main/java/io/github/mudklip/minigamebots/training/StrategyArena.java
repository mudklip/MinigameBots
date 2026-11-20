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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Strategy practice: a free-for-all arena built around the player, with cover to use, and bots of
 * mixed styles that fight everyone (each other too). Nobody really dies: a knocked-out bot comes
 * back somewhere else after a few seconds, and so does the player (with full health and food,
 * "death" is reported to the AI). Long sessions here teach the strategist when to hunt, when to
 * pick only winnable fights, when to back off and heal, and when to wait in ambush. Everything is
 * put back as it was when it stops. No setup needed.
 */
public final class StrategyArena {
	private static final int HALF = 18;
	private static final String[] STYLES = {"L2", "L3", "L4", "L5", "rusher", "blocker", "critter", "w_tapper", "kiter"};

	private final DummyManager dummies;
	private final GroundTruth truth;
	private final List<Map.Entry<BlockPos, BlockState>> replaced = new ArrayList<>();
	private final List<Bot> bots = new ArrayList<>();
	private @Nullable UUID owner;
	private @Nullable ServerLevel level;
	private BlockPos centre = BlockPos.ZERO;
	private int kills;
	private int deaths;

	private static final class Bot {
		final int index;
		@Nullable UUID id;
		int respawnIn = -1;

		Bot(int index) {
			this.index = index;
		}
	}

	public StrategyArena(DummyManager dummies, GroundTruth truth) {
		this.dummies = dummies;
		this.truth = truth;
	}

	public boolean running() {
		return owner != null;
	}

	public String start(ServerPlayer p, int count) {
		stop(p.level().getServer());
		ServerLevel lv = (ServerLevel) p.level();
		level = lv;
		owner = p.getUUID();
		centre = PracticeAreas.ground(p);
		kills = 0;
		deaths = 0;
		build(lv);
		for (int i = 0; i < Math.max(1, Math.min(8, count)); i++) {
			Bot b = new Bot(i);
			bots.add(b);
			spawn(b);
		}
		kit(p);
		p.setGameMode(GameType.SURVIVAL);
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Free-for-all")));
		return "Free-for-all arena with " + bots.size() + " bots around you (everyone fights everyone; /tg ffa stop ends it)";
	}

	private void build(ServerLevel lv) {
		var stone = Blocks.SMOOTH_STONE.defaultBlockState();
		var glass = Blocks.GLASS.defaultBlockState();
		var cover = Blocks.STONE_BRICKS.defaultBlockState();
		var air = Blocks.AIR.defaultBlockState();
		for (BlockPos q : BlockPos.betweenClosed(centre.offset(-HALF, -1, -HALF), centre.offset(HALF, 4, HALF))) {
			if (lv.getBlockEntity(q) != null) {
				continue;
			}
			int dx = q.getX() - centre.getX();
			int dz = q.getZ() - centre.getZ();
			int dy = q.getY() - centre.getY();
			boolean wall = Math.abs(dx) == HALF || Math.abs(dz) == HALF;
			// Cover: 2x2 pillars and short walls on a grid, to hide behind, ambush from and break line of sight.
			int gx = Math.floorMod(dx + 2, 9);
			int gz = Math.floorMod(dz + 2, 9);
			boolean pillar = !wall && dy >= 0 && dy <= 2 && Math.abs(dx) > 3 && Math.abs(dz) > 3 && gx < 2 && gz < 2;
			boolean lowWall = !wall && dy == 0 && Math.abs(dx) > 3 && gz == 6 && gx >= 3 && gx <= 5;
			BlockState want = dy == -1 ? stone : wall && dy <= 2 ? glass : pillar || lowWall ? cover : air;
			BlockState had = lv.getBlockState(q);
			if (had != want) {
				replaced.add(Map.entry(q.immutable(), had));
				lv.setBlock(q, want, Block.UPDATE_CLIENTS);
			}
		}
	}

	private Vec3 randomSpot(ServerLevel lv) {
		for (int tries = 0; tries < 40; tries++) {
			int x = centre.getX() + lv.getRandom().nextInt(2 * HALF - 3) - (HALF - 2);
			int z = centre.getZ() + lv.getRandom().nextInt(2 * HALF - 3) - (HALF - 2);
			BlockPos at = new BlockPos(x, centre.getY(), z);
			if (lv.getBlockState(at).isAir() && lv.getBlockState(at.above()).isAir()) {
				return Vec3.atBottomCenterOf(at);
			}
		}
		return Vec3.atBottomCenterOf(centre);
	}

	private void spawn(Bot b) {
		ServerLevel lv = level;
		if (lv == null) {
			return;
		}
		var scoreboard = lv.getServer().getScoreboard();
		String teamName = "mgb_ffa_" + b.index;
		var team = scoreboard.getPlayerTeam(teamName);
		if (team == null) {
			team = scoreboard.addPlayerTeam(teamName); // Every bot alone on its own team: everyone is an enemy.
		}
		DummySettings s = DummySettings.style(STYLES[lv.getRandom().nextInt(STYLES.length)]);
		Mannequin m = dummies.spawnAt(lv, randomSpot(lv), lv.getRandom().nextFloat() * 360, s, false);
		if (m != null) {
			var d = dummies.byId(m.getUUID());
			if (d != null) {
				d.settings.infiniteHealth = false;
			}
			scoreboard.addPlayerToTeam(m.getScoreboardName(), team);
			b.id = m.getUUID();
		}
	}

	private static void kit(ServerPlayer p) {
		var inv = p.getInventory();
		if (inv.countItem(Items.IRON_SWORD) == 0 && inv.countItem(Items.DIAMOND_SWORD) == 0) {
			inv.add(new ItemStack(Items.IRON_SWORD));
		}
		if (inv.countItem(Items.COOKED_BEEF) < 8) {
			inv.add(new ItemStack(Items.COOKED_BEEF, 8 - inv.countItem(Items.COOKED_BEEF)));
		}
		if (p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
			p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
			p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		}
	}

	public void tick(MinecraftServer server) {
		if (owner == null || level == null) {
			return;
		}
		ServerPlayer p = server.getPlayerList().getPlayer(owner);
		if (p == null) {
			stop(server);
			return;
		}
		for (Bot b : bots) {
			if (b.respawnIn >= 0 && --b.respawnIn < 0) {
				spawn(b);
			} else if (b.respawnIn < 0 && (b.id == null || !(level.getEntity(b.id) instanceof LivingEntity e) || !e.isAlive())) {
				b.respawnIn = 100; // Knocked out (or lost): back in 5 seconds, somewhere else.
				b.id = null;
			}
		}
		if (p.getY() < centre.getY() - 4) {
			respawnPlayer(p); // Fell out somehow.
		}
	}

	/** The player is never really killed here: back at full health somewhere else in the arena. */
	public boolean allowDeath(LivingEntity e) {
		if (owner == null || !e.getUUID().equals(owner) || !(e instanceof ServerPlayer p)) {
			return true;
		}
		deaths++;
		truth.send(p, "death", new JsonObject());
		respawnPlayer(p);
		return false;
	}

	private void respawnPlayer(ServerPlayer p) {
		ServerLevel lv = level;
		if (lv == null) {
			return;
		}
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.clearFire();
		Vec3 at = randomSpot(lv);
		p.teleportTo(lv, at.x, at.y, at.z, java.util.Set.of(), p.getYRot(), 0, true);
		kit(p);
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Knocked out (" + kills + " / " + deaths + ")")));
	}

	/** A bot knocked out by the player: counted. */
	public void afterDeath(LivingEntity victim, @Nullable LivingEntity killer) {
		if (owner == null || killer == null || !killer.getUUID().equals(owner)) {
			return;
		}
		for (Bot b : bots) {
			if (victim.getUUID().equals(b.id)) {
				kills++;
				if (killer instanceof ServerPlayer p) {
					p.sendOverlayMessage(Component.literal("Knockouts " + kills + " / knocked out " + deaths));
				}
			}
		}
	}

	public void stop(@Nullable MinecraftServer server) {
		for (Bot b : bots) {
			if (b.id != null) {
				dummies.remove(b.id);
			}
		}
		if (server != null) {
			var sb = server.getScoreboard();
			for (Bot b : bots) {
				var t = sb.getPlayerTeam("mgb_ffa_" + b.index);
				if (t != null) {
					sb.removePlayerTeam(t);
				}
			}
		}
		bots.clear();
		ServerLevel lv = level;
		if (lv != null) {
			for (int k = replaced.size() - 1; k >= 0; k--) {
				lv.setBlock(replaced.get(k).getKey(), replaced.get(k).getValue(), Block.UPDATE_CLIENTS);
			}
		}
		replaced.clear();
		owner = null;
	}
}
