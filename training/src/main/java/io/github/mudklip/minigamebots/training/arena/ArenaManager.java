package io.github.mudklip.minigamebots.training.arena;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.mudklip.minigamebots.training.TgStorage;
import io.github.mudklip.minigamebots.training.TrainingGrounds;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Arenas: mark two corners, save the blocks as a snapshot, and reset it instantly later
 * (blocks, dropped items, projectiles, and the players inside: health, hunger, effects, kit, spawn).
 */
public final class ArenaManager {
	/** Bigger areas take too long to reset to be useful for training. */
	public static final long MAX_VOLUME = 2_000_000;

	private final Map<UUID, BlockPos[]> corners = new ConcurrentHashMap<>();
	private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
	private final Map<String, StructureTemplate> snapshots = new HashMap<>();
	private @Nullable MinecraftServer server;

	public void load(MinecraftServer server) {
		this.server = server;
		arenas.clear();
		snapshots.clear();
		Path dir = dir();
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path f : files) {
				try {
					Arena a = TgStorage.GSON.fromJson(Files.readString(f), Arena.class);
					if (a != null && Arena.validName(a.name)) {
						arenas.put(a.name, a);
					}
				} catch (IOException | RuntimeException e) {
					TrainingGrounds.LOGGER.warn("Skipping unreadable arena {}", f, e);
				}
			}
		} catch (IOException e) {
			TrainingGrounds.LOGGER.warn("Couldn't list arenas", e);
		}
	}

	private Path dir() {
		return TgStorage.dir(server()).resolve("arenas");
	}

	private MinecraftServer server() {
		if (server == null) {
			throw new IllegalStateException("No server");
		}
		return server;
	}

	public void setCorner(ServerPlayer player, int index, BlockPos pos) {
		corners.computeIfAbsent(player.getUUID(), k -> new BlockPos[2])[index] = pos.immutable();
	}

	public BlockPos @Nullable [] corners(ServerPlayer player) {
		return corners.get(player.getUUID());
	}

	public List<Arena> list() {
		List<Arena> out = new ArrayList<>(arenas.values());
		out.sort(Comparator.comparing(a -> a.name));
		return out;
	}

	public @Nullable Arena get(String name) {
		return arenas.get(name);
	}

	/** The arena containing this position, if any. */
	public @Nullable Arena at(ServerLevel level, Vec3 pos) {
		String dim = level.dimension().identifier().toString();
		for (Arena a : arenas.values()) {
			if (a.dimension.equals(dim) && a.box().contains(pos)) {
				return a;
			}
		}
		return null;
	}

	/** A name not used yet: arena-1, arena-2, ... */
	public String nextName() {
		for (int i = 1; ; i++) {
			if (!arenas.containsKey("arena-" + i)) {
				return "arena-" + i;
			}
		}
	}

	/** Saves the area between the player's two corners as a new (or replaced) arena. */
	public Arena create(ServerPlayer player, String name) throws ArenaException {
		if (!Arena.validName(name)) {
			throw new ArenaException("Names use letters, digits, - and _ (up to 32)");
		}
		BlockPos[] c = corners.get(player.getUUID());
		if (c == null || c[0] == null || c[1] == null) {
			throw new ArenaException("Set both corners first");
		}
		BlockPos lo = BlockPos.min(c[0], c[1]);
		BlockPos hi = BlockPos.max(c[0], c[1]);
		Arena a = new Arena();
		a.name = name;
		a.dimension = player.level().dimension().identifier().toString();
		a.minX = lo.getX();
		a.minY = lo.getY();
		a.minZ = lo.getZ();
		a.sizeX = hi.getX() - lo.getX() + 1;
		a.sizeY = hi.getY() - lo.getY() + 1;
		a.sizeZ = hi.getZ() - lo.getZ() + 1;
		a.created = System.currentTimeMillis();
		if (a.volume() > MAX_VOLUME) {
			throw new ArenaException(String.format("Too big: %,d blocks (max %,d)", a.volume(), MAX_VOLUME));
		}
		if (a.box().contains(player.position())) {
			a.spawns.add(spawnOf(player));
		}
		ServerLevel level = player.level();
		StructureTemplate t = new StructureTemplate();
		t.fillFromWorld(level, lo, new BlockPos(a.sizeX, a.sizeY, a.sizeZ), false, List.of());
		try {
			Files.createDirectories(dir());
			NbtIo.writeCompressed(t.save(new CompoundTag()), dir().resolve(name + ".nbt"));
			save(a);
		} catch (IOException e) {
			throw new ArenaException("Couldn't save: " + e.getMessage());
		}
		arenas.put(name, a);
		snapshots.put(name, t);
		return a;
	}

	public void save(Arena a) throws IOException {
		TgStorage.writeAtomic(dir().resolve(a.name + ".json"), TgStorage.GSON.toJson(a));
	}

	public void delete(String name) throws ArenaException {
		if (arenas.remove(name) == null) {
			throw new ArenaException("No arena called " + name);
		}
		snapshots.remove(name);
		try {
			Files.deleteIfExists(dir().resolve(name + ".json"));
			Files.deleteIfExists(dir().resolve(name + ".nbt"));
		} catch (IOException e) {
			throw new ArenaException("Couldn't delete files: " + e.getMessage());
		}
	}

	public void addSpawn(Arena a, ServerPlayer player) throws ArenaException {
		if (!a.box().contains(player.position())) {
			throw new ArenaException("Stand inside the arena to add a spawn");
		}
		a.spawns.add(spawnOf(player));
		saveQuietly(a);
	}

	public void clearSpawns(Arena a) {
		a.spawns.clear();
		saveQuietly(a);
	}

	/** Uses the player's current inventory as the kit everyone gets on reset. */
	public int setKit(Arena a, ServerPlayer player) {
		a.kit = captureKit(player);
		saveQuietly(a);
		return a.kit.size();
	}

	/** The player's whole inventory (armour and offhand included) as a kit. */
	public static com.google.gson.JsonArray captureKit(ServerPlayer player) {
		RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, player.registryAccess());
		com.google.gson.JsonArray kit = new com.google.gson.JsonArray();
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			var encoded = ItemStack.CODEC.encodeStart(ops, s).result();
			if (encoded.isPresent()) {
				JsonObject o = new JsonObject();
				o.addProperty("slot", i);
				o.add("item", encoded.get());
				kit.add(o);
			}
		}
		return kit;
	}

	/** Replaces the player's inventory with a kit. */
	public static void applyKit(ServerPlayer p, com.google.gson.JsonArray kit) {
		var inv = p.getInventory();
		inv.clearContent();
		RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, p.registryAccess());
		for (var el : kit) {
			JsonObject o = el.getAsJsonObject();
			int slot = o.get("slot").getAsInt();
			ItemStack.CODEC.parse(ops, o.get("item")).result().ifPresent(s -> {
				if (slot >= 0 && slot < inv.getContainerSize()) {
					inv.setItem(slot, s);
				}
			});
		}
		p.containerMenu.broadcastChanges();
	}

	/** Full health and hunger, no effects, fire or fall damage pending. */
	public static void refresh(ServerPlayer p) {
		p.removeAllEffects();
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		p.clearFire();
		p.resetFallDistance();
		p.setAirSupply(p.getMaxAirSupply());
	}

	public void clearKit(Arena a) {
		a.kit = new com.google.gson.JsonArray();
		saveQuietly(a);
	}

	private void saveQuietly(Arena a) {
		try {
			save(a);
		} catch (IOException e) {
			TrainingGrounds.LOGGER.warn("Couldn't save arena {}", a.name, e);
		}
	}

	/**
	 * Puts the arena back to its snapshot and resets everyone inside it. Returns the players
	 * that were reset.
	 */
	public List<ServerPlayer> reset(Arena a) throws ArenaException {
		ServerLevel level = level(a);
		StructureTemplate t = snapshot(a, level);
		long start = System.nanoTime();
		var box = a.box();
		// Clear loose things first so nothing drops into the restored blocks.
		for (Entity e : level.getEntities((Entity) null, box.inflate(1), ArenaManager::isDebris)) {
			e.discard();
		}
		t.placeInWorld(level, a.min(), a.min(), new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
		for (Entity e : level.getEntities((Entity) null, box.inflate(1), ArenaManager::isDebris)) {
			e.discard();
		}
		List<ServerPlayer> players = new ArrayList<>(level.getEntitiesOfClass(ServerPlayer.class, box,
				p -> !p.isSpectator()));
		int next = 0;
		for (ServerPlayer p : players) {
			resetPlayer(a, p, a.spawns.isEmpty() ? null : a.spawns.get(next++ % a.spawns.size()));
		}
		TrainingGrounds.LOGGER.debug("Reset arena {} in {} ms", a.name, (System.nanoTime() - start) / 1_000_000);
		return players;
	}

	/**
	 * Builds a simple 21 x 21 practice arena where the player stands (stone floor, glass walls,
	 * air above), saves it with two spawns facing each other, and returns it. Used by the Coach.
	 */
	public Arena quick(ServerPlayer player, String name) throws ArenaException {
		ServerLevel level = (ServerLevel) player.level();
		BlockPos c = player.blockPosition();
		int r = 10;
		BlockPos min = c.offset(-r, -1, -r);
		BlockPos max = c.offset(r, 6, r);
		var stone = net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState();
		var glass = net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState();
		var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			boolean floor = p.getY() == min.getY();
			boolean wall = p.getX() == min.getX() || p.getX() == max.getX() || p.getZ() == min.getZ() || p.getZ() == max.getZ();
			level.setBlock(p, floor ? stone : wall && p.getY() <= c.getY() + 3 ? glass : air, Block.UPDATE_CLIENTS);
		}
		setCorner(player, 0, min);
		setCorner(player, 1, max);
		if (get(name) != null) {
			delete(name);
		}
		Arena a = create(player, name);
		a.spawns.add(new double[] {c.getX() - 6 + 0.5, c.getY(), c.getZ() + 0.5, -90, 0});
		a.spawns.add(new double[] {c.getX() + 6 + 0.5, c.getY(), c.getZ() + 0.5, 90, 0});
		saveQuietly(a);
		return a;
	}

	/** Places a copy of the arena with its lowest corner at {@code at} (multi-world training). */
	public void placeCopy(Arena a, BlockPos at) throws ArenaException {
		ServerLevel level = level(a);
		snapshot(a, level).placeInWorld(level, at, at, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
	}

	private static boolean isDebris(Entity e) {
		return e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof Projectile
				|| e instanceof PrimedTnt || e instanceof FallingBlockEntity;
	}

	private void resetPlayer(Arena a, ServerPlayer p, double @Nullable [] spawn) {
		refresh(p);
		if (!a.kit.isEmpty()) {
			applyKit(p, a.kit);
		}
		if (spawn != null) {
			p.teleportTo(p.level(), spawn[0], spawn[1], spawn[2], Set.of(), (float) spawn[3], (float) spawn[4], true);
		}
		p.setDeltaMovement(Vec3.ZERO);
	}

	public ServerLevel level(Arena a) throws ArenaException {
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(a.dimension));
		ServerLevel level = server().getLevel(key);
		if (level == null) {
			throw new ArenaException("Dimension " + a.dimension + " isn't loaded");
		}
		return level;
	}

	private StructureTemplate snapshot(Arena a, ServerLevel level) throws ArenaException {
		StructureTemplate t = snapshots.get(a.name);
		if (t != null) {
			return t;
		}
		Path file = dir().resolve(a.name + ".nbt");
		try {
			CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			t = new StructureTemplate();
			t.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		} catch (IOException e) {
			throw new ArenaException("Snapshot missing or unreadable: " + file.getFileName());
		}
		snapshots.put(a.name, t);
		return t;
	}

	private static double[] spawnOf(ServerPlayer p) {
		return new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
	}

	public static final class ArenaException extends Exception {
		public ArenaException(String message) {
			super(message);
		}
	}
}
