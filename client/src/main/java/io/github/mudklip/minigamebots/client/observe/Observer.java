package io.github.mudklip.minigamebots.client.observe;

import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.mixin.BossHealthOverlayAccessor;
import io.github.mudklip.minigamebots.client.mixin.HudAccessor;
import io.github.mudklip.minigamebots.client.mixin.PlayerTabOverlayAccessor;
import io.github.mudklip.minigamebots.client.observe.BlockSense.Category;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

/** Builds the AI's {@link Observation} each tick from what the client knows. */
public final class Observer {
	private static final double ENTITY_RANGE = 48;
	private static final int MAX_ENTITIES = 32;
	/** How long a hidden entity is remembered at its last seen position (fair perception). */
	private static final int REMEMBER_TICKS = 60;

	private record Seen(long tick, Vec3 pos, Vec3 vel) {
	}

	private final SoundTracker sounds;
	private final TextTracker text;
	private final Map<Integer, Seen> lastSeen = new HashMap<>();

	public Observer(SoundTracker sounds, TextTracker text) {
		this.sounds = sounds;
		this.text = text;
	}

	public @Nullable Observation observe(Minecraft client, long tick) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return null;
		}
		boolean fair = MgbConfig.get().fairPerception;
		return new Observation(tick, self(player, level), inventory(player), player.getInventory().getSelectedSlot(),
				entities(player, level, tick, fair), blocks(player, level, fair), sounds.recent(client), text(client, level),
				gui(client));
	}

	private static Observation.Self self(LocalPlayer p, ClientLevel level) {
		List<String> effects = new ArrayList<>();
		for (MobEffectInstance e : p.getActiveEffects()) {
			effects.add(e.getEffect().getRegisteredName() + ":" + e.getAmplifier());
		}
		Vec3 v = p.getDeltaMovement();
		return new Observation.Self(p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel(),
				p.getFoodData().getSaturationLevel(), p.getArmorValue(), p.experienceLevel, p.experienceProgress,
				p.getAirSupply(), p.getMaxAirSupply(), p.onGround(), p.isInWater(), p.isInLava(), p.isShiftKeyDown(),
				p.isSprinting(), p.isUsingItem(), p.getYRot(), p.getXRot(), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z,
				level.dimension().identifier().toString(), effects, p.isDeadOrDying());
	}

	private static List<Observation.ItemInfo> inventory(LocalPlayer player) {
		List<Observation.ItemInfo> items = new ArrayList<>();
		var inv = player.getInventory();
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (!stack.isEmpty()) {
				items.add(item(slot, stack));
			}
		}
		return items;
	}

	static Observation.ItemInfo item(int slot, ItemStack stack) {
		Component custom = stack.getCustomName();
		return new Observation.ItemInfo(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
				stack.getCount(), stack.getDamageValue(), stack.getMaxDamage(), stack.isEnchanted(),
				custom == null ? null : custom.getString());
	}

	private List<Observation.EntityInfo> entities(LocalPlayer player, ClientLevel level, long tick, boolean fair) {
		Vec3 eye = player.getEyePosition();
		List<Entity> nearby = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (e != player && e.isAlive() && e.position().distanceTo(eye) <= ENTITY_RANGE) {
				nearby.add(e);
			}
		}
		nearby.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(eye)));

		List<Observation.EntityInfo> out = new ArrayList<>();
		Map<Integer, Seen> stillSeen = new HashMap<>();
		for (Entity e : nearby) {
			if (out.size() >= MAX_ENTITIES) {
				break;
			}
			boolean visible = !fair || player.hasLineOfSight(e);
			Vec3 pos = e.position();
			Vec3 vel = e.getDeltaMovement();
			int since = 0;
			if (visible) {
				stillSeen.put(e.getId(), new Seen(tick, pos, vel));
			} else {
				Seen seen = lastSeen.get(e.getId());
				if (seen == null || tick - seen.tick() > REMEMBER_TICKS) {
					continue;
				}
				stillSeen.put(e.getId(), seen);
				pos = seen.pos();
				vel = seen.vel();
				since = (int) (tick - seen.tick());
			}
			float health = 0;
			float maxHealth = 0;
			String held = "minecraft:air";
			int armor = 0;
			if (e instanceof LivingEntity living) {
				health = living.getHealth();
				maxHealth = living.getMaxHealth();
				held = BuiltInRegistries.ITEM.getKey(living.getMainHandItem().getItem()).toString();
				armor = living.getArmorValue();
			}
			PlayerTeam team = e.getTeam();
			boolean isPlayer = e instanceof Player;
			out.add(new Observation.EntityInfo(e.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
					isPlayer, isPlayer || e.hasCustomName() ? e.getName().getString() : null,
					team == null ? null : team.getName(), pos.x - eye.x, pos.y - eye.y, pos.z - eye.z, vel.x, vel.y,
					vel.z, health, maxHealth, held, armor, visible, since));
		}
		lastSeen.clear();
		lastSeen.putAll(stillSeen);
		return out;
	}

	private static BlockSense blocks(LocalPlayer player, ClientLevel level, boolean fair) {
		BlockPos feet = player.blockPosition();
		byte[] cats = new byte[BlockSense.SIZE * BlockSense.SIZE * BlockSense.SIZE];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
		int r = BlockSense.RADIUS;
		for (int dy = -r; dy <= r; dy++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					pos.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					BlockState state = level.getBlockState(pos);
					Category cat = categorize(level, pos, state);
					if (fair && cat != Category.AIR && !exposed(level, pos, side)) {
						cat = Category.UNKNOWN;
					}
					cats[BlockSense.index(dx, dy, dz)] = (byte) cat.ordinal();
				}
			}
		}
		return new BlockSense(cats);
	}

	/**
	 * A block counts as seeable when at least one side touches something you can see through.
	 * This isn't true line of sight, but it stops the AI "seeing" ores buried inside stone.
	 */
	private static boolean exposed(ClientLevel level, BlockPos pos, BlockPos.MutableBlockPos side) {
		for (Direction d : Direction.values()) {
			side.setWithOffset(pos, d);
			if (!level.getBlockState(side).canOcclude()) {
				return true;
			}
		}
		return false;
	}

	static Category categorize(ClientLevel level, BlockPos pos, BlockState state) {
		if (state.isAir()) {
			return Category.AIR;
		}
		if (state.getFluidState().is(FluidTags.LAVA)) {
			return Category.LAVA;
		}
		if (state.getFluidState().is(FluidTags.WATER) && state.getCollisionShape(level, pos).isEmpty()) {
			return Category.WATER;
		}
		if (state.is(BlockTags.FIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE)
				|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
			return Category.DANGER;
		}
		if (state.is(ConventionalBlockTags.ORES)) {
			return Category.ORE;
		}
		if (state.is(BlockTags.CLIMBABLE)) {
			return Category.CLIMBABLE;
		}
		if (state.hasBlockEntity() || state.is(Blocks.CRAFTING_TABLE) || state.is(BlockTags.DOORS)
				|| state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.BUTTONS)
				|| state.is(Blocks.LEVER)) {
			return Category.INTERACTIVE;
		}
		if (state.getCollisionShape(level, pos).isEmpty()) {
			return Category.PASSABLE;
		}
		return Category.SOLID;
	}

	private Observation.Text text(Minecraft client, ClientLevel level) {
		HudAccessor hud = (HudAccessor) client.gui.hud;
		String title = null;
		String subtitle = null;
		if (hud.minigamebots$titleTime() > 0) {
			title = string(hud.minigamebots$title());
			subtitle = string(hud.minigamebots$subtitle());
		}
		String actionbar = hud.minigamebots$actionbarTime() > 0 ? string(hud.minigamebots$actionbar()) : null;

		Map<String, Integer> sidebar = new LinkedHashMap<>();
		String sidebarTitle = null;
		Objective objective = level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective != null) {
			sidebarTitle = objective.getDisplayName().getString();
			List<PlayerScoreEntry> scores = new ArrayList<>(level.getScoreboard().listPlayerScores(objective));
			scores.removeIf(PlayerScoreEntry::isHidden);
			scores.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
			for (PlayerScoreEntry entry : scores.subList(0, Math.min(15, scores.size()))) {
				String name = entry.display() != null ? entry.display().getString() : entry.owner();
				sidebar.put(name, entry.value());
			}
		}

		List<String> bossBars = new ArrayList<>();
		for (LerpingBossEvent bar : ((BossHealthOverlayAccessor) ((HudAccessor) client.gui.hud).minigamebots$bossOverlay())
				.minigamebots$events().values()) {
			bossBars.add(bar.getName().getString() + " " + Math.round(bar.getProgress() * 100) + "%");
		}

		PlayerTabOverlayAccessor tab = (PlayerTabOverlayAccessor) client.gui.hud.getTabList();
		return new Observation.Text(text.recent(), title, subtitle, actionbar, sidebarTitle, sidebar, bossBars,
				string(tab.minigamebots$header()), string(tab.minigamebots$footer()));
	}

	private static Observation.@Nullable Gui gui(Minecraft client) {
		Screen screen = client.gui.screen();
		if (screen == null) {
			return null;
		}
		List<Observation.ItemInfo> slots = new ArrayList<>();
		if (screen instanceof AbstractContainerScreen<?> container) {
			List<Slot> menuSlots = container.getMenu().slots;
			for (int i = 0; i < menuSlots.size(); i++) {
				ItemStack stack = menuSlots.get(i).getItem();
				if (!stack.isEmpty()) {
					slots.add(item(i, stack));
				}
			}
		}
		var window = client.getWindow();
		return new Observation.Gui(screen.getClass().getSimpleName(), screen.getTitle().getString(), slots,
				client.mouseHandler.getScaledXPos(window), client.mouseHandler.getScaledYPos(window));
	}

	private static @Nullable String string(@Nullable Component component) {
		return component == null ? null : component.getString();
	}
}
