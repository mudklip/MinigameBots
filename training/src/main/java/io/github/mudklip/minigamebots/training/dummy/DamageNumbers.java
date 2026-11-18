package io.github.mudklip.minigamebots.training.dummy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

/** Floating "-3.5" numbers above things that got hit. Vanilla text displays, so no client mod needed. */
public final class DamageNumbers {
	/** Tag on every temporary entity Training Grounds spawns; leftovers are removed on load. */
	public static final String TEMP_TAG = "minigamebots_temp";
	/**
	 * Also on every temporary entity, different each time the server starts: a temp entity
	 * without the current one is a leftover from an earlier run (crash, kill -9) and is removed.
	 */
	public static final String RUN_TAG = "minigamebots_run_" + Long.toHexString(System.nanoTime() ^ Double.doubleToLongBits(Math.random()));

	/** Marks an entity as temporary for this run. */
	public static void markTemp(net.minecraft.world.entity.Entity e) {
		e.addTag(TEMP_TAG);
		e.addTag(RUN_TAG);
	}

	public static boolean isLeftover(net.minecraft.world.entity.Entity e) {
		var tags = e.entityTags();
		return tags.contains(TEMP_TAG) && !tags.contains(RUN_TAG);
	}
	private static final int LIFE = 20;

	private record Number(Display.TextDisplay entity, Vec3 start, int[] age) {
	}

	private final List<Number> numbers = new ArrayList<>();

	public void spawn(ServerLevel level, Vec3 at, Component text) {
		Display.TextDisplay d = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
		Vec3 start = at.add((level.getRandom().nextDouble() - 0.5) * 0.6, 0, (level.getRandom().nextDouble() - 0.5) * 0.6);
		d.setPos(start);
		d.setText(text);
		d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		d.setBackgroundColor(0);
		markTemp(d);
		if (level.addFreshEntity(d)) {
			numbers.add(new Number(d, start, new int[1]));
		}
	}

	public void tick() {
		for (Iterator<Number> it = numbers.iterator(); it.hasNext(); ) {
			Number n = it.next();
			int age = ++n.age()[0];
			if (age >= LIFE || n.entity().isRemoved()) {
				n.entity().discard();
				it.remove();
			} else {
				n.entity().setPos(n.start().add(0, age * 0.025, 0));
			}
		}
	}

	public void clear() {
		numbers.forEach(n -> n.entity().discard());
		numbers.clear();
	}
}
