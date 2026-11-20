package io.github.mudklip.minigamebots.client.coach;

import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The human floor: your own scores on the same tests the AI takes, so its progress can be read
 * against a real player (you). Duel: your win rate over the next 10 rounds of the Training
 * Grounds match you're in (as the AI's exam does). Aim: your aim-trainer score (60 s flick test).
 * Only counted while you're playing yourself (never while the AI has control).
 */
public final class HumanBench {
	public enum Mode {
		NONE, DUEL, AIM
	}

	public static final int ROUNDS = 10;
	private static Mode mode = Mode.NONE;
	private static int rounds;
	private static int wins;

	private HumanBench() {
	}

	public static Mode mode() {
		return mode;
	}

	public static String status() {
		return switch (mode) {
			case DUEL -> "Measuring you: round " + rounds + " / " + ROUNDS + " (" + wins + " won)";
			case AIM -> "Measuring you: aim test running (60 s)";
			default -> "";
		};
	}

	public static void startDuel() {
		mode = Mode.DUEL;
		rounds = 0;
		wins = 0;
	}

	public static void startAim(Minecraft mc) {
		mode = Mode.AIM;
		if (mc.player != null) {
			mc.player.connection.sendCommand("tg aim flick 60");
		}
	}

	public static void cancel() {
		mode = Mode.NONE;
	}

	public static void onEvent(GameEvent e) {
		if (mode == Mode.NONE || AgentControl.aiActive()) {
			return;
		}
		Progress pr = Progress.active();
		if (pr == null) {
			return;
		}
		if (mode == Mode.DUEL && "tg_episode_end".equals(e.type())) {
			rounds++;
			if ("win".equals(e.data().get("result"))) {
				wins++;
			}
			if (rounds >= ROUNDS) {
				pr.human.put("duel", wins / (double) ROUNDS);
				Progress.save();
				mode = Mode.NONE;
				toast("Your duel score: " + Math.round(100.0 * wins / ROUNDS) + "% of " + ROUNDS + " rounds won");
			}
		} else if (mode == Mode.AIM && "tg_aim_result".equals(e.type()) && e.data().get("score") instanceof Number n) {
			pr.human.merge("aim", n.doubleValue(), Math::max);
			Progress.save();
			mode = Mode.NONE;
			toast("Your aim score: " + Math.round(n.doubleValue()));
		}
	}

	private static void toast(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> io.github.mudklip.minigamebots.client.overlay.Overlay.toast(new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(), Component.literal("Human floor"),
				Component.literal(text)));
	}
}
