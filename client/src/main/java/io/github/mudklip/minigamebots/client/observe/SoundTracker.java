package io.github.mudklip.minigamebots.client.observe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;

/** Remembers recently played sounds the player could hear, like the subtitles option does. */
public final class SoundTracker implements SoundEventListener {
	private static final int KEEP_TICKS = 40;
	private static final int MAX_SOUNDS = 32;

	private record Heard(String id, String source, double x, double y, double z, boolean relative, long tick) {
	}

	private final Deque<Heard> heard = new ArrayDeque<>();
	private long tick;

	public void setTick(long tick) {
		this.tick = tick;
		while (!heard.isEmpty() && heard.peekFirst().tick() < tick - KEEP_TICKS) {
			heard.pollFirst();
		}
	}

	@Override
	public void onPlaySound(SoundInstance sound, WeighedSoundEvents soundEvent, float range) {
		synchronized (heard) {
			heard.addLast(new Heard(sound.getIdentifier().toString(), sound.getSource().getName(), sound.getX(),
					sound.getY(), sound.getZ(), sound.isRelative(), tick));
			while (heard.size() > MAX_SOUNDS) {
				heard.pollFirst();
			}
		}
	}

	public List<Observation.SoundInfo> recent(Minecraft client) {
		Vec3 ear = client.player == null ? Vec3.ZERO : client.player.getEyePosition();
		List<Observation.SoundInfo> out = new ArrayList<>();
		synchronized (heard) {
			for (Heard h : heard) {
				double dx = h.relative() ? h.x() : h.x() - ear.x;
				double dy = h.relative() ? h.y() : h.y() - ear.y;
				double dz = h.relative() ? h.z() : h.z() - ear.z;
				out.add(new Observation.SoundInfo(h.id(), h.source(), dx, dy, dz, (int) (tick - h.tick())));
			}
		}
		return out;
	}
}
