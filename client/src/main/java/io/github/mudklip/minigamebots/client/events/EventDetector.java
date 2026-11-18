package io.github.mudklip.minigamebots.client.events;

import static io.github.mudklip.minigamebots.client.events.EventTypes.*;

import io.github.mudklip.minigamebots.client.observe.Observation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Turns changes in the game into named {@link GameEvent}s for the reward system, using only what
 * the client can see. Some events (kills, damage dealt) are inferred and can occasionally be
 * wrong; Training Grounds replaces them with exact ones when present.
 */
public final class EventDetector {
	/** A target that dies within this many ticks of our last hit counts as our kill. */
	private static final int KILL_CREDIT_TICKS = 60;
	private static final int PLACE_CHECK_TICKS = 3;
	private static final int RECENT = 200;

	private record Target(LivingEntity entity, long lastHitTick, float health) {
	}

	private record Placement(BlockPos pos, String item, long checkTick) {
	}

	private final List<Consumer<GameEvent>> listeners = new ArrayList<>();
	private final Deque<GameEvent> recent = new ArrayDeque<>();
	private final Map<Integer, Target> targets = new HashMap<>();
	private final List<Placement> placements = new ArrayList<>();

	private @Nullable Observation previous;
	private long tick;

	public void addListener(Consumer<GameEvent> listener) {
		listeners.add(listener);
	}

	public synchronized List<GameEvent> recent(int n) {
		List<GameEvent> all = new ArrayList<>(recent);
		return all.subList(Math.max(0, all.size() - n), all.size());
	}

	public void reset() {
		previous = null;
		targets.clear();
		placements.clear();
	}

	public void emit(String type, Map<String, Object> data) {
		publish(GameEvent.client(tick, type, data));
	}

	/** An event reported by Training Grounds (exact, from the server). */
	public void emitServer(String type, Map<String, Object> data) {
		publish(new GameEvent(tick, type, data, "server"));
	}

	private void publish(GameEvent event) {
		synchronized (this) {
			recent.addLast(event);
			while (recent.size() > RECENT) {
				recent.pollFirst();
			}
		}
		for (Consumer<GameEvent> listener : listeners) {
			listener.accept(event);
		}
	}

	// --- Hooks called from game events ---

	public void onAttack(LocalPlayer player, Entity target) {
		emit(ATTACK, Map.of("target", typeOf(target), "weapon", itemId(player)));
		if (target instanceof LivingEntity living) {
			targets.put(target.getId(), new Target(living, tick, living.getHealth()));
		}
	}

	public void onBlockBroken(BlockPos pos, BlockState state) {
		emit(BLOCK_BROKEN, Map.of("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
				"x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
	}

	/** After using an item on a block: if it was a block item, check shortly whether a block appeared. */
	public void onUseOnBlock(LocalPlayer player, BlockPos clicked, BlockPos adjacent) {
		var stack = player.getMainHandItem();
		if (stack.getItem() instanceof BlockItem) {
			String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			placements.add(new Placement(clicked, item, tick + PLACE_CHECK_TICKS));
			placements.add(new Placement(adjacent, item, tick + PLACE_CHECK_TICKS));
		}
	}

	public void onChat(String text, boolean actionbar) {
		emit(actionbar ? ACTIONBAR : CHAT, Map.of("text", text));
	}

	public void onTitle(String text, boolean subtitle) {
		emit(subtitle ? SUBTITLE : TITLE, Map.of("text", text));
	}

	// --- Per-tick comparison ---

	public void onTick(long now, Observation obs, ClientLevel level) {
		tick = now;
		Observation prev = previous;
		previous = obs;
		checkPlacements(level);
		checkTargets();
		if (prev == null) {
			return;
		}
		Observation.Self a = prev.self();
		Observation.Self b = obs.self();

		if (b.dead() && !a.dead()) {
			emit(DEATH, Map.of());
		} else if (!b.dead() && a.dead()) {
			emit(RESPAWN, Map.of());
		}
		if (!b.dead() && !a.dead()) {
			float dh = b.health() - a.health();
			if (dh < -0.01f) {
				emit(DAMAGE_TAKEN, Map.of("amount", -dh));
			} else if (dh > 0.01f) {
				emit(HEALED, Map.of("amount", dh));
			}
		}
		if (a.food() != b.food()) {
			emit(HUNGER_CHANGED, Map.of("from", a.food(), "to", b.food()));
		}
		int xpBefore = a.xpLevel() * 1000 + Math.round(a.xpProgress() * 1000);
		int xpAfter = b.xpLevel() * 1000 + Math.round(b.xpProgress() * 1000);
		if (xpAfter > xpBefore && !b.dead()) {
			emit(XP_GAINED, Map.of("level", b.xpLevel()));
		}
		if (!a.dimension().equals(b.dimension())) {
			emit(DIMENSION_CHANGED, Map.of("from", a.dimension(), "to", b.dimension()));
		}
		diffInventory(prev, obs);
		diffScoreboard(prev.text().sidebar(), obs.text().sidebar());
		diffScreen(prev.gui(), obs.gui());
		for (Observation.SoundInfo sound : obs.sounds()) {
			if (sound.ageTicks() == 0) {
				emit(SOUND_HEARD, Map.of("sound", sound.id(), "distance",
						Math.sqrt(sound.dx() * sound.dx() + sound.dy() * sound.dy() + sound.dz() * sound.dz())));
			}
		}
	}

	private void diffInventory(Observation prev, Observation obs) {
		// Opening a screen doesn't change the inventory, so compare totals per item id.
		Map<String, Integer> before = totals(prev.inventory());
		Map<String, Integer> after = totals(obs.inventory());
		for (var e : after.entrySet()) {
			int diff = e.getValue() - before.getOrDefault(e.getKey(), 0);
			if (diff > 0) {
				emit(ITEM_GAINED, Map.of("item", e.getKey(), "count", diff));
			}
		}
		for (var e : before.entrySet()) {
			int diff = e.getValue() - after.getOrDefault(e.getKey(), 0);
			if (diff > 0) {
				emit(ITEM_LOST, Map.of("item", e.getKey(), "count", diff));
			}
		}
	}

	private static Map<String, Integer> totals(List<Observation.ItemInfo> items) {
		Map<String, Integer> totals = new HashMap<>();
		for (Observation.ItemInfo item : items) {
			totals.merge(item.id(), item.count(), Integer::sum);
		}
		return totals;
	}

	private void diffScoreboard(Map<String, Integer> before, Map<String, Integer> after) {
		for (var e : after.entrySet()) {
			Integer old = before.get(e.getKey());
			if (!Objects.equals(old, e.getValue())) {
				emit(SCOREBOARD_CHANGED, Map.of("name", e.getKey(), "from", old == null ? 0 : old, "to", e.getValue()));
			}
		}
	}

	private void diffScreen(Observation.@Nullable Gui before, Observation.@Nullable Gui after) {
		String a = before == null ? null : before.screen();
		String b = after == null ? null : after.screen();
		if (Objects.equals(a, b)) {
			return;
		}
		if (a != null) {
			emit(SCREEN_CLOSED, Map.of("screen", a));
		}
		if (b != null) {
			emit(SCREEN_OPENED, Map.of("screen", b, "title", after.title()));
		}
	}

	private void checkTargets() {
		Iterator<Map.Entry<Integer, Target>> it = targets.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Target> entry = it.next();
			Target t = entry.getValue();
			LivingEntity e = t.entity();
			boolean dead = e.isDeadOrDying();
			float health = dead ? 0 : e.getHealth();
			if (!e.isRemoved() || dead) {
				if (health < t.health() - 0.01f) {
					emit(DAMAGE_DEALT, Map.of("target", typeOf(e), "amount", t.health() - health));
				}
			}
			if (dead) {
				emit(KILL, Map.of("target", typeOf(e)));
				it.remove();
			} else if (e.isRemoved() || tick - t.lastHitTick() > KILL_CREDIT_TICKS) {
				// Despawned, out of range, or not hit for a while: no longer ours to credit.
				it.remove();
			} else {
				entry.setValue(new Target(e, t.lastHitTick(), health));
			}
		}
	}

	private void checkPlacements(ClientLevel level) {
		Iterator<Placement> it = placements.iterator();
		while (it.hasNext()) {
			Placement p = it.next();
			if (tick < p.checkTick()) {
				continue;
			}
			it.remove();
			BlockState state = level.getBlockState(p.pos());
			String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			String placedItem = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
			if (!state.isAir() && placedItem.equals(p.item())) {
				emit(BLOCK_PLACED, Map.of("block", block, "x", p.pos().getX(), "y", p.pos().getY(), "z", p.pos().getZ()));
				// Don't count the same placement twice (clicked + adjacent position).
				placements.removeIf(other -> other.item().equals(p.item()) && other.checkTick() == p.checkTick());
				return;
			}
		}
	}

	private static String typeOf(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	private static String itemId(LocalPlayer player) {
		return BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
	}
}
