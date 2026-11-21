package io.github.mudklip.minigamebots.client.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Guard orders from chat. Only real player chat or whispers count (never server/system text, so a
 * plugin can't fake one), only from players in "Takes orders from" (empty: nobody), and each
 * name is tied to the first account that used it this session. The protected player must be the
 * one giving the order or on the same list. A 2-second cooldown per person.
 *
 *   guard <player> [distance] [ring|line|front|wedge|square] [with <names...>]
 *   standby [names...]   follow [player] [distance] [names...]   stop [names...]
 */
public final class GuardOrders {
	public record Order(String kind, String target, double dist, String shape, List<String> names) {
	}

	private static final List<String> SHAPES = List.of("ring", "line", "front", "wedge", "square");
	private static final Map<String, UUID> SEEN = new HashMap<>();
	private static final Map<String, Long> LAST = new HashMap<>();
	private static @Nullable Order current;

	private GuardOrders() {
	}

	/** The order in a chat line, if it's one and it's meant for `me`; null otherwise. */
	public static @Nullable Order parse(String text, String me) {
		String[] w = text.trim().split("\\s+");
		if (w.length == 0 || w[0].isEmpty()) {
			return null;
		}
		String kind = w[0].toLowerCase(Locale.ROOT);
		if (!List.of("guard", "standby", "follow", "stop").contains(kind)) {
			return null;
		}
		String target = "";
		double dist = kind.equals("follow") ? 2 : 3;
		String shape = kind.equals("follow") ? "line" : "ring";
		List<String> names = new ArrayList<>();
		int i = 1;
		if (kind.equals("guard") || kind.equals("follow")) {
			if (i < w.length && !w[i].equalsIgnoreCase("with")) {
				target = w[i++];
			}
			if (i < w.length && w[i].matches("\\d+(\\.\\d+)?")) {
				dist = Math.max(1, Math.min(12, Double.parseDouble(w[i++])));
			}
			if (i < w.length && SHAPES.contains(w[i].toLowerCase(Locale.ROOT))) {
				shape = w[i++].toLowerCase(Locale.ROOT);
			}
			if (i < w.length && w[i].equalsIgnoreCase("with")) {
				i++;
			}
		}
		for (; i < w.length; i++) {
			names.add(w[i].replaceAll("[,.;]", ""));
		}
		names.removeIf(String::isBlank);
		if (kind.equals("guard") && target.isEmpty()) {
			return null;
		}
		// Addressed to some AIs only: is this one of them?
		if (!names.isEmpty() && names.stream().noneMatch(n -> n.equalsIgnoreCase(me))) {
			return null;
		}
		if (names.isEmpty()) {
			names.add(me);
		}
		return new Order(kind, target, dist, shape, names);
	}

	/** Whether `sender` may give orders (and, for guard/follow, protect `target`). */
	public static boolean allowed(String sender, String target, List<String> commanders, String me) {
		if (sender.equalsIgnoreCase(me)) {
			return false; // Never its own messages.
		}
		boolean listed = commanders.stream().anyMatch(c -> c.equalsIgnoreCase(sender));
		boolean targetOk = target.isEmpty() || target.equalsIgnoreCase(sender)
				|| commanders.stream().anyMatch(c -> c.equalsIgnoreCase(target));
		return listed && targetOk;
	}

	public static List<String> commanders() {
		List<String> out = new ArrayList<>();
		for (String s : MgbConfig.get().guardCommanders.split("[,\\s]+")) {
			if (!s.isBlank()) {
				out.add(s.trim());
			}
		}
		return out;
	}

	/** A player chat message (from Fabric's CHAT event: real player chat or whispers only). */
	public static void onChat(Component message, String content, @Nullable String senderName, @Nullable UUID senderId) {
		Minecraft mc = Minecraft.getInstance();
		if (senderName == null || senderId == null || mc.player == null) {
			return;
		}
		String me = mc.player.getGameProfile().name();
		Order o = parse(content, me);
		if (o == null) {
			return;
		}
		if (!allowed(senderName, o.target(), commanders(), me)) {
			return;
		}
		// The name stays tied to the account that first used it this session.
		UUID known = SEEN.putIfAbsent(senderName.toLowerCase(Locale.ROOT), senderId);
		if (known != null && !known.equals(senderId)) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = LAST.get(senderName.toLowerCase(Locale.ROOT));
		if (last != null && now - last < 2000) {
			return;
		}
		LAST.put(senderName.toLowerCase(Locale.ROOT), now);
		mc.execute(() -> execute(mc, o, senderName));
	}

	private static void execute(Minecraft mc, Order o, String from) {
		var mgb = MinigameBotsClient.instance();
		if (o.kind().equals("stop")) {
			current = null;
			if (AgentControl.aiActive()) {
				AgentControl.get().stop(mc, Component.literal("Guard: stopped by " + from));
			}
			return;
		}
		String target = o.target().isEmpty() ? from : o.target();
		if (mc.level != null) {
			for (var pl : mc.level.players()) {
				if (pl.getGameProfile().name().equalsIgnoreCase(target)) {
					mgb.relations().setProtected(pl.getId());
				}
			}
		}
		JsonObject args = new JsonObject();
		args.addProperty("protectee", target);
		args.addProperty("distance", o.dist());
		args.addProperty("shape", o.shape());
		JsonArray names = new JsonArray();
		o.names().forEach(names::add);
		args.add("names", names);
		args.addProperty("me", mc.player == null ? "" : mc.player.getGameProfile().name());
		args.addProperty("mode", o.kind());
		args.addProperty("close_gaps", true);
		var p = mgb.profiles().active();
		if (p != null) {
			args.addProperty("profile", mgb.profiles().store().dir(p.id).toString());
		}
		current = o;
		mgb.brain().play(mc, "guard", args);
		io.github.mudklip.minigamebots.client.overlay.Overlay.toast(new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(),
				Component.literal("Guard order from " + from),
				Component.literal(o.kind() + " " + target + " " + (int) o.dist() + " " + o.shape() + " (" + o.names().size() + " guards)"));
	}

	/** Debug (/mgb guardtest ...): runs an order as if it came from the local player, skipping chat permissions. */
	public static String test(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "Join a world first";
		}
		Order o = parse(text, mc.player.getGameProfile().name());
		if (o == null) {
			return "Not an order for this AI: " + text;
		}
		execute(mc, o, mc.player.getGameProfile().name());
		return "Guard order: " + o;
	}

	public static @Nullable Order current() {
		return current;
	}
}
