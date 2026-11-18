package io.github.mudklip.minigamebots.client.memory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What it knows about explored chunks of one dimension, 1 int each: visited, biome group,
 * danger level and resources seen. A 2 MB cap (about 500,000 chunks) drops the least
 * recently visited first.
 */
public final class CoarseMap {
	public static final int MAX_CHUNKS = 500_000;
	public static final String[] BIOMES = {"other", "plains", "forest", "desert", "snow", "mountain", "ocean", "swamp",
			"jungle", "nether", "end", "cave"};

	/** bits 0-3 biome group, 4-5 danger 0-3, 6 ore seen, 7 water, 8 chest/interactive seen, 16-31 visit stamp. */
	private final Map<Long, Integer> chunks = new HashMap<>();
	private int stamp;

	public static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	public int size() {
		return chunks.size();
	}

	public Integer get(int cx, int cz) {
		return chunks.get(key(cx, cz));
	}

	public Map<Long, Integer> all() {
		return chunks;
	}

	public static int biome(int v) {
		return v & 0xF;
	}

	public static int danger(int v) {
		return (v >> 4) & 0x3;
	}

	public static boolean ore(int v) {
		return (v & (1 << 6)) != 0;
	}

	public static boolean interactive(int v) {
		return (v & (1 << 8)) != 0;
	}

	/** Records a visit: biome, how dangerous it looks now (decays toward the new value), what's there. */
	public void visit(int cx, int cz, int biome, int danger, boolean ore, boolean water, boolean interactive) {
		long k = key(cx, cz);
		Integer old = chunks.get(k);
		int d = danger;
		int flags = 0;
		if (old != null) {
			d = Math.max(danger, danger(old) - (danger < danger(old) ? 1 : 0));
			flags = old & ((1 << 6) | (1 << 7) | (1 << 8));
		}
		flags |= (ore ? 1 << 6 : 0) | (water ? 1 << 7 : 0) | (interactive ? 1 << 8 : 0);
		stamp = (stamp + 1) & 0xFFFF;
		chunks.put(k, (biome & 0xF) | (Math.min(3, d) << 4) | flags | (stamp << 16));
		if (chunks.size() > MAX_CHUNKS) {
			evictOldest();
		}
	}

	private void evictOldest() {
		long worst = 0;
		int worstAge = -1;
		for (var e : chunks.entrySet()) {
			int age = (stamp - (e.getValue() >>> 16)) & 0xFFFF;
			if (age > worstAge) {
				worstAge = age;
				worst = e.getKey();
			}
		}
		chunks.remove(worst);
	}

	public void write(OutputStream raw) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
			out.writeInt(1);
			out.writeInt(stamp);
			out.writeInt(chunks.size());
			for (var e : chunks.entrySet()) {
				out.writeLong(e.getKey());
				out.writeInt(e.getValue());
			}
		}
	}

	public static CoarseMap read(InputStream raw) throws IOException {
		CoarseMap m = new CoarseMap();
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
			if (in.readInt() != 1) {
				throw new IOException("Unknown map version");
			}
			m.stamp = in.readInt();
			int n = Math.min(in.readInt(), MAX_CHUNKS);
			for (int i = 0; i < n; i++) {
				m.chunks.put(in.readLong(), in.readInt());
			}
		}
		return m;
	}
}
