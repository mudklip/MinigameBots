package io.github.mudklip.minigamebots.client.memory;

import java.util.ArrayList;
import java.util.List;

/** A remembered place in one world. */
public final class Waypoint {
	/** Kinds of places. Index order is what the brain sees (keep in sync with features.WAYPOINT_TYPES). */
	public static final List<String> TYPES = List.of("base", "chest", "mine", "portal", "danger", "farm", "custom");

	public String id = "";
	public String type = "custom";
	public String name = "";
	public String dimension = "minecraft:overworld";
	public int x;
	public int y;
	public int z;
	public List<String> notes = new ArrayList<>();
	public long created;
	public long lastVisited;
	/** 0-1: how sure it is this is still right. Drops slowly over time, refreshed on visits. */
	public double confidence = 1;
	/** Pinned waypoints are never forgotten or moved by the AI. */
	public boolean pinned;
	/** True if the AI marked it itself (a learned meta-action), false if a person did. */
	public boolean byAi;

	public double distanceSq(double px, double py, double pz) {
		double dx = x + 0.5 - px;
		double dy = y - py;
		double dz = z + 0.5 - pz;
		return dx * dx + dy * dy + dz * dz;
	}
}
