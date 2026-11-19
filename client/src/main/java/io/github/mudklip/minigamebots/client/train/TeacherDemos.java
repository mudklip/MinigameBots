package io.github.mudklip.minigamebots.client.train;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Teacher demos: a scripted expert plays through the AI's own virtual input (humanizer on) while
 * the recorder runs, so every fight becomes a demonstration to train on, with no skill needed
 * from you. The recording stops when the teacher stops (F8/F9, or the time is up).
 */
public final class TeacherDemos {
	/** A teacher: what it's called, the brain policy and its arguments. */
	public record Teacher(String label, String help, String policy, JsonObject args) {
	}

	public static final List<Teacher> TEACHERS = List.of(
			new Teacher("Fighter (mixed)", "Crits, W-taps, shield and axe-on-shield, strafing", "teacher", args("style", "mixed")),
			new Teacher("Fighter (crits)", "Jump-crit on every hit", "teacher", args("style", "crits")),
			new Teacher("Fighter (W-tap)", "Sprint hits with a W-tap after each one", "teacher", args("style", "wtap")),
			new Teacher("Aim and chase", "Walks to the nearest target and hits it with steady aim", "follow", args("target", "any")));

	/** The teachers to offer: the scripted ones, plus Baritone's when it's installed. */
	public static List<Teacher> available() {
		List<Teacher> out = new java.util.ArrayList<>(TEACHERS);
		if (BaritoneTeacher.installed()) {
			for (BaritoneTeacher.Task t : BaritoneTeacher.Task.values()) {
				out.add(new Teacher(t.label, t.help, "baritone:" + t.name(), new JsonObject()));
			}
		}
		return out;
	}

	private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId TOAST =
			new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId();
	private static boolean active;
	private static long endTick;
	private static long ticks;

	private TeacherDemos() {
	}

	private static JsonObject args(String k, String v) {
		JsonObject o = new JsonObject();
		o.addProperty(k, v);
		return o;
	}

	public static boolean active() {
		return active;
	}

	/** Starts recording and hands control to the teacher for {@code minutes} (0 = until stopped). */
	public static void start(Minecraft client, Teacher t, int minutes) {
		var mgb = MinigameBotsClient.instance();
		if (t.policy().startsWith("baritone:")) {
			String err = BaritoneTeacher.start(client, BaritoneTeacher.Task.valueOf(t.policy().substring(9)));
			if (err != null) {
				net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(client.gui.toastManager(), TOAST,
						net.minecraft.network.chat.Component.literal("Teacher"), net.minecraft.network.chat.Component.literal(err));
				return;
			}
		}
		if (!mgb.recorder().recording()) {
			mgb.recorder().start(client);
		}
		mgb.recorder().label("Teacher: " + t.label(), "teacher");
		if (!t.policy().startsWith("baritone:")) {
			mgb.brain().play(client, t.policy(), t.args().deepCopy());
		}
		active = true;
		ticks = 0;
		endTick = minutes <= 0 ? Long.MAX_VALUE : minutes * 60L * 20L;
	}

	/** Called every client tick: stops the recording once the teacher has stopped or time is up. */
	public static void tick(Minecraft client) {
		if (!active) {
			return;
		}
		ticks++;
		// Give the brain a few seconds to take control before checking it still has it.
		boolean running = BaritoneTeacher.active() ? BaritoneTeacher.busy() : AgentControl.aiActive();
		if (ticks > 100 && !running || ticks >= endTick) {
			active = false;
			BaritoneTeacher.stop();
			if (AgentControl.aiActive()) {
				AgentControl.get().stop(client, net.minecraft.network.chat.Component.literal("Teacher demo finished"));
			}
			MinigameBotsClient.instance().recorder().stop(client);
		}
	}
}
