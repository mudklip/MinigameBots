package io.github.mudklip.minigamebots.training;

import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.common.Protocol;
import io.github.mudklip.minigamebots.common.net.TgEventPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Sends exact game events to linked MinigameBots clients (see docs/formats/training-events.md).
 * Players without the client mod never receive anything.
 */
public final class GroundTruth {
	private final LinkedClients clients;

	public GroundTruth(LinkedClients clients) {
		this.clients = clients;
	}

	public boolean wants(ServerPlayer player) {
		return clients.get(player.getUUID())
				.filter(l -> l.usable() && l.hello().features().contains(Protocol.Features.EVENTS))
				.isPresent() && ServerPlayNetworking.canSend(player, TgEventPayload.TYPE);
	}

	/** Whether the player's linked client advertised a protocol feature (e.g. "puppet"). */
	public boolean supports(ServerPlayer player, String feature) {
		return clients.get(player.getUUID()).filter(l -> l.usable() && l.hello().features().contains(feature)).isPresent();
	}

	public void send(ServerPlayer player, String kind, JsonObject data) {
		if (!wants(player)) {
			return;
		}
		String json = data.toString();
		if (json.length() <= TgEventPayload.MAX_JSON) {
			ServerPlayNetworking.send(player, new TgEventPayload(kind, json));
		}
	}

	/** Who to credit for a hit: the causing entity, or the direct one if nothing caused it (dummy hits on Peaceful). */
	public static @org.jspecify.annotations.Nullable Entity attacker(DamageSource source) {
		return source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
	}

	/** After any living entity was damaged: tells the victim and the attacker (if they are players). */
	public void onDamage(LivingEntity victim, DamageSource source, float taken, boolean blocked) {
		Entity attacker = attacker(source);
		if (victim instanceof ServerPlayer p) {
			JsonObject d = describe(source, taken, blocked);
			d.addProperty("health", victim.getHealth());
			if (attacker != null) {
				add(d, "attacker", attacker);
			}
			send(p, "damage_taken", d);
		}
		if (attacker instanceof ServerPlayer p && attacker != victim) {
			JsonObject d = describe(source, taken, blocked);
			add(d, "victim", victim);
			d.addProperty("victim_health", victim.getHealth());
			d.addProperty("distance", p.distanceTo(victim));
			if (source.getDirectEntity() == p) {
				AttackDetails.describe(d, p, victim, blocked);
			}
			send(p, "damage_dealt", d);
		}
	}

	public void onDeath(LivingEntity victim, DamageSource source) {
		Entity killerEntity = attacker(source);
		if (killerEntity instanceof ServerPlayer killer && killer != victim) {
			JsonObject d = new JsonObject();
			add(d, "victim", victim);
			d.addProperty("source", source.type().msgId());
			d.addProperty("weapon", weapon(killer.getMainHandItem()));
			send(killer, "kill", d);
		}
		if (victim instanceof ServerPlayer p) {
			JsonObject d = new JsonObject();
			d.addProperty("source", source.type().msgId());
			if (killerEntity != null) {
				add(d, "killer", killerEntity);
			}
			send(p, "death", d);
		}
	}

	private static JsonObject describe(DamageSource source, float amount, boolean blocked) {
		JsonObject d = new JsonObject();
		d.addProperty("amount", amount);
		d.addProperty("blocked", blocked);
		d.addProperty("source", source.type().msgId());
		d.addProperty("projectile", source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile);
		if (attacker(source) instanceof LivingEntity l) {
			d.addProperty("weapon", weapon(l.getMainHandItem()));
		}
		return d;
	}

	private static void add(JsonObject d, String prefix, Entity e) {
		d.addProperty(prefix + "_type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
		d.addProperty(prefix + "_name", e.getPlainTextName());
		d.addProperty(prefix + "_player", e instanceof ServerPlayer);
	}

	static String weapon(ItemStack stack) {
		return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
