package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.config.MgbConfig;
import io.github.mudklip.minigamebots.client.control.AgentControl;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Chat in character (optional O1): while your AI plays and its character has chat on, it
 * answers when someone speaks to it (says its name, or yours). At most one reply every 8
 * seconds, one short filtered line, tagged [AI] if that setting is on. Never on its own.
 */
public final class ChatMind {
	private static final Pattern LINE = Pattern.compile("^<([A-Za-z0-9_]{1,16})> (.+)$");
	private static final ArrayDeque<String> recent = new ArrayDeque<>();
	private static long lastReply;
	private static boolean waiting;
	private static boolean toldToInstall;

	private ChatMind() {
	}

	public static void onEvent(GameEvent e) {
		if (!"chat".equals(e.type())) {
			return;
		}
		String text = String.valueOf(e.data().getOrDefault("text", ""));
		recent.addLast(text);
		while (recent.size() > 6) {
			recent.removeFirst();
		}
		Minecraft c = Minecraft.getInstance();
		var profile = MinigameBotsClient.instance().profiles().active();
		if (c.player == null || profile == null || !profile.character.chat || !AgentControl.aiActive() || waiting) {
			return;
		}
		Matcher m = LINE.matcher(text);
		String me = c.player.getGameProfile().name();
		if (!m.matches() || m.group(1).equalsIgnoreCase(me)) {
			return; // Not a player's message, or our own.
		}
		String msg = m.group(2).toLowerCase(Locale.ROOT);
		String name = profile.character.name.isBlank() ? profile.name : profile.character.name;
		boolean addressed = msg.contains(me.toLowerCase(Locale.ROOT)) || !name.isBlank() && msg.contains(name.toLowerCase(Locale.ROOT));
		if (!addressed || System.currentTimeMillis() - lastReply < 8000) {
			return;
		}
		JsonObject req = new JsonObject();
		req.addProperty("type", "chat");
		req.addProperty("text", text);
		req.addProperty("ai_name", name);
		req.add("character", com.google.gson.JsonParser.parseString(
				io.github.mudklip.minigamebots.client.record.RecordingCodec.GSON.toJson(profile.character)));
		JsonArray hist = new JsonArray();
		recent.forEach(hist::add);
		req.add("recent", hist);
		waiting = true;
		MinigameBotsClient.instance().brain().ask(req, r -> {
			waiting = false;
			if (r.has("error")) {
				if (!toldToInstall && c.player != null) {
					toldToInstall = true;
					c.player.sendSystemMessage(Component.literal("§b[AI]§r §7Chat is on, but: " + r.get("error").getAsString()));
				}
				return;
			}
			String reply = r.has("text") ? r.get("text").getAsString().replace('§', ' ').trim() : "";
			if (reply.isEmpty() || reply.startsWith("/") || c.player == null || !AgentControl.aiActive()) {
				return;
			}
			lastReply = System.currentTimeMillis();
			c.player.connection.sendChat((MgbConfig.get().tagChatAsAi ? "[AI] " : "") + reply);
		});
	}
}
