package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.memory.CoarseMap;
import io.github.mudklip.minigamebots.client.memory.Insignia;
import io.github.mudklip.minigamebots.client.memory.MemoryManager;
import io.github.mudklip.minigamebots.client.memory.Relation;
import io.github.mudklip.minigamebots.client.memory.Waypoint;
import io.github.mudklip.minigamebots.client.memory.WorldMemory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * What the active AI remembers about this world: places on a mini-map (mark, rename, pin,
 * delete) and who counts as friend or enemy, by name and by insignia.
 */
public final class MemoryScreen extends Screen {
	private static final int ROW = 12;
	private static final int[] BIOME_COLORS = {0xFF5A5A5A, 0xFF7CB342, 0xFF2E7D32, 0xFFD4B26A, 0xFFE0F0FF, 0xFF8D8D8D,
			0xFF1E5AA8, 0xFF4E6B3A, 0xFF1B8C3A, 0xFF8E2B2B, 0xFFD9D99A, 0xFF3B3B3B};
	private static final int[] TYPE_COLORS = {0xFF42A5F5, 0xFFFFB300, 0xFFB0BEC5, 0xFFAB47BC, 0xFFFF5252, 0xFF9CCC65, 0xFFFFFFFF};

	private final @Nullable Screen parent;
	private boolean factions;
	private int scale = 4; // Pixels per chunk.
	private @Nullable Waypoint selected;
	private String markType = "base";
	private int mapX;
	private int mapY;
	private int mapSize;
	private int listX;
	private int listY;
	private int listW;

	public MemoryScreen(@Nullable Screen parent) {
		super(Component.translatable("minigamebots.memory.screen"));
		this.parent = parent;
	}

	private MemoryManager mm() {
		return MinigameBotsClient.instance().memory();
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.memory.places"), b -> {
			factions = false;
			rebuildWidgets();
		}).pos(width - 214, 6).width(70).build()).active = factions;
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.memory.factions"), b -> {
			factions = true;
			rebuildWidgets();
		}).pos(width - 140, 6).width(130).build()).active = !factions;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 70, height - 26).width(60).build());
		var wm = io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().memory().memory();
		int zones = wm == null ? 0 : wm.zones.size();
		addRenderableWidget(Button.builder(Component.literal("Protect area here (" + zones + " zones)"), b -> {
			io.github.mudklip.minigamebots.client.control.Autonomy.protectHere(minecraft);
			rebuildWidgets();
		}).pos(10, height - 26).width(150).tooltip(net.minecraft.client.gui.components.Tooltip.create(
				Component.translatable("minigamebots.memory.protect_help"))).build());
		addRenderableWidget(Button.builder(Component.literal("Clear zones"), b -> {
			if (wm != null) {
				wm.zones.clear();
				io.github.mudklip.minigamebots.client.MinigameBotsClient.instance().memory().changed();
			}
			rebuildWidgets();
		}).pos(164, height - 26).width(70).build()).active = zones > 0;
		WorldMemory m = mm().memory();
		if (m == null) {
			return;
		}
		if (factions) {
			initFactions(m);
		} else {
			initPlaces(m);
		}
	}

	// --- Places ---

	private void initPlaces(WorldMemory m) {
		mapSize = Math.max(80, Math.min(height - 70, width / 2 - 20));
		mapX = 10;
		mapY = 32;
		listX = mapX + mapSize + 12;
		listY = 32;
		listW = width - listX - 10;
		addRenderableWidget(Button.builder(Component.literal("+"), b -> scale = Math.min(16, scale * 2)).pos(mapX, mapY + mapSize + 4).width(20).build());
		addRenderableWidget(Button.builder(Component.literal("-"), b -> scale = Math.max(1, scale / 2)).pos(mapX + 24, mapY + mapSize + 4).width(20).build());
		int by = height - 26;
		addRenderableWidget(Button.builder(Component.literal("Mark: " + markType), b -> {
			markType = Waypoint.TYPES.get((Waypoint.TYPES.indexOf(markType) + 1) % Waypoint.TYPES.size());
			b.setMessage(Component.literal("Mark: " + markType));
		}).pos(listX, by).width(80).tooltip(Tooltip.create(Component.translatable("minigamebots.memory.mark_type_help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("minigamebots.memory.mark_here"), b -> {
			selected = mm().mark(minecraft, markType, false);
			rebuildWidgets();
		}).pos(listX + 84, by).width(70).build());
		Waypoint w = selected;
		if (w != null && m.waypoints.contains(w)) {
			int sy = height - 50;
			EditBox name = addRenderableWidget(new EditBox(font, listX, sy, Math.max(60, listW - 166), 18, Component.literal("name")));
			name.setMaxLength(32);
			name.setValue(w.name);
			name.setResponder(v -> {
				w.name = v.trim();
				mm().changed();
			});
			int bx = listX + Math.max(60, listW - 166) + 4;
			addRenderableWidget(Button.builder(Component.literal(w.type), b -> {
				w.type = Waypoint.TYPES.get((Waypoint.TYPES.indexOf(w.type) + 1) % Waypoint.TYPES.size());
				mm().changed();
				b.setMessage(Component.literal(w.type));
			}).pos(bx, sy).width(50).build());
			addRenderableWidget(Button.builder(Component.literal(w.pinned ? "Pinned" : "Pin"), b -> {
				w.pinned = !w.pinned;
				mm().changed();
				b.setMessage(Component.literal(w.pinned ? "Pinned" : "Pin"));
			}).pos(bx + 54, sy).width(50).tooltip(Tooltip.create(Component.translatable("minigamebots.memory.pin_help"))).build());
			addRenderableWidget(Button.builder(Component.translatable("minigamebots.memory.forget"), b -> {
				mm().remove(w);
				selected = null;
				rebuildWidgets();
			}).pos(bx + 108, sy).width(50).build());
		}
	}

	private List<Waypoint> sortedWaypoints(WorldMemory m) {
		var p = minecraft.player;
		List<Waypoint> list = new ArrayList<>(m.waypoints);
		if (p != null) {
			String dim = minecraft.level.dimension().identifier().toString();
			list.sort(Comparator.comparing((Waypoint w) -> !w.dimension.equals(dim)).thenComparingDouble(w -> w.distanceSq(p.getX(), p.getY(), p.getZ())));
		}
		return list;
	}

	private void renderPlaces(GuiGraphicsExtractor g, WorldMemory m, int mouseX, int mouseY) {
		var p = minecraft.player;
		if (p == null || minecraft.level == null) {
			return;
		}
		String dim = minecraft.level.dimension().identifier().toString();
		g.fill(mapX - 1, mapY - 1, mapX + mapSize + 1, mapY + mapSize + 1, 0xFF404040);
		g.fill(mapX, mapY, mapX + mapSize, mapY + mapSize, 0xFF101010);
		CoarseMap map = mm().map(dim);
		int pcx = p.getBlockX() >> 4;
		int pcz = p.getBlockZ() >> 4;
		int half = mapSize / scale / 2 + 1;
		int cx0 = mapX + mapSize / 2;
		int cy0 = mapY + mapSize / 2;
		if (map != null) {
			g.enableScissor(mapX, mapY, mapX + mapSize, mapY + mapSize);
			for (int dz = -half; dz <= half; dz++) {
				for (int dx = -half; dx <= half; dx++) {
					Integer v = map.get(pcx + dx, pcz + dz);
					if (v == null) {
						continue;
					}
					int x = cx0 + dx * scale - scale / 2;
					int y = cy0 + dz * scale - scale / 2;
					g.fill(x, y, x + scale, y + scale, BIOME_COLORS[Math.min(BIOME_COLORS.length - 1, CoarseMap.biome(v))]);
					int danger = CoarseMap.danger(v);
					if (danger > 0) {
						g.fill(x, y, x + scale, y + scale, (0x30 * danger) << 24 | 0xFF0000);
					}
					if (CoarseMap.ore(v) && scale >= 4) {
						g.fill(x + scale / 2 - 1, y + scale / 2 - 1, x + scale / 2 + 1, y + scale / 2 + 1, 0xFFFFFFFF);
					}
				}
			}
			g.disableScissor();
		}
		// Waypoints.
		for (Waypoint w : m.waypoints) {
			if (!w.dimension.equals(dim)) {
				continue;
			}
			double fx = (w.x - p.getX()) / 16.0 * scale;
			double fz = (w.z - p.getZ()) / 16.0 * scale;
			int x = (int) Math.max(mapX + 2, Math.min(mapX + mapSize - 3, cx0 + fx));
			int y = (int) Math.max(mapY + 2, Math.min(mapY + mapSize - 3, cy0 + fz));
			int c = TYPE_COLORS[Math.max(0, Waypoint.TYPES.indexOf(w.type))];
			g.fill(x - 2, y - 2, x + 3, y + 3, w == selected ? 0xFFFFFFFF : 0xFF000000);
			g.fill(x - 1, y - 1, x + 2, y + 2, c);
		}
		// The player: a dot and a line showing where they face.
		g.fill(cx0 - 1, cy0 - 1, cx0 + 2, cy0 + 2, 0xFFFFFFFF);
		double yaw = Math.toRadians(p.getYRot());
		for (int i = 2; i < 8; i++) {
			int x = cx0 + (int) Math.round(-Math.sin(yaw) * i);
			int y = cy0 + (int) Math.round(Math.cos(yaw) * i);
			g.fill(x, y, x + 1, y + 1, 0xFFFFFFFF);
		}
		g.text(font, String.format("%d chunks explored here", map == null ? 0 : map.size()), mapX + 48, mapY + mapSize + 9, 0xFF909090, false);

		// The list.
		List<Waypoint> list = sortedWaypoints(m);
		g.text(font, list.isEmpty() ? "No places remembered yet." : "Places (" + list.size() + "/" + WorldMemory.MAX_WAYPOINTS + ")",
				listX, listY, 0xFFB0B0B0, false);
		int y = listY + 12;
		int maxRows = Math.max(1, (height - 58 - y) / ROW);
		for (int i = 0; i < Math.min(maxRows, list.size()); i++) {
			Waypoint w = list.get(i);
			boolean here = w.dimension.equals(dim);
			String dist = here ? String.format("%.0fm", Math.sqrt(w.distanceSq(p.getX(), p.getY(), p.getZ()))) : w.dimension.replace("minecraft:", "");
			if (w == selected) {
				g.fill(listX - 2, y - 1, listX + listW, y + ROW - 2, 0x40FFFFFF);
			}
			g.fill(listX, y + 2, listX + 5, y + 7, TYPE_COLORS[Math.max(0, Waypoint.TYPES.indexOf(w.type))]);
			String label = (w.pinned ? "📌 " : "") + (w.name.isBlank() ? w.type : w.name) + (w.byAi ? " (AI)" : "");
			g.text(font, font.plainSubstrByWidth(label, listW - 60), listX + 8, y, 0xFFFFFFFF, false);
			g.text(font, dist, listX + listW - font.width(dist) - 2, y, 0xFF909090, false);
			y += ROW;
		}
	}

	// --- Allies and enemies ---

	private record Entry(String key, boolean insignia, Relation relation) {
	}

	private List<Entry> entries(WorldMemory m) {
		List<Entry> out = new ArrayList<>();
		m.players.forEach((k, v) -> out.add(new Entry(k, false, v)));
		m.insignias.forEach((k, v) -> out.add(new Entry(k, true, v)));
		return out;
	}

	private void initFactions(WorldMemory m) {
		int x = 10;
		int y = 30;
		addRenderableWidget(Button.builder(Component.literal("Attackers become enemies: " + (m.autoMarkAttackers ? "on" : "off")), b -> {
			m.autoMarkAttackers = !m.autoMarkAttackers;
			mm().changed();
			b.setMessage(Component.literal("Attackers become enemies: " + (m.autoMarkAttackers ? "on" : "off")));
		}).pos(x, y).width(190).tooltip(Tooltip.create(Component.translatable("minigamebots.memory.auto_help"))).build());
		addRenderableWidget(Button.builder(Component.literal("Other teams are enemies: " + (m.otherTeamsAreEnemies ? "yes" : "no")), b -> {
			m.otherTeamsAreEnemies = !m.otherTeamsAreEnemies;
			mm().changed();
			b.setMessage(Component.literal("Other teams are enemies: " + (m.otherTeamsAreEnemies ? "yes" : "no")));
		}).pos(x + 194, y).width(170).build());
		addRenderableWidget(Button.builder(Component.literal("Trust: " + m.trust.label), b -> {
			m.trust = WorldMemory.Trust.values()[(m.trust.ordinal() + 1) % WorldMemory.Trust.values().length];
			mm().changed();
			b.setMessage(Component.literal("Trust: " + m.trust.label));
		}).pos(x, y + 24).width(190).tooltip(Tooltip.create(Component.translatable("minigamebots.memory.trust_help"))).build());
		int ry = y + 66;
		int rows = Math.max(1, (height - 34 - ry) / 22);
		List<Entry> list = entries(m);
		for (int i = 0; i < Math.min(rows, list.size()); i++) {
			Entry e = list.get(i);
			int yy = ry + i * 22;
			addRenderableWidget(Button.builder(Component.literal(e.relation().label), b -> {
				Map<String, Relation> map = e.insignia() ? m.insignias : m.players;
				map.put(e.key(), map.get(e.key()).next());
				mm().changed();
				rebuildWidgets();
			}).pos(width - 120, yy).width(70).build());
			addRenderableWidget(Button.builder(Component.literal("x"), b -> {
				(e.insignia() ? m.insignias : m.players).remove(e.key());
				mm().changed();
				rebuildWidgets();
			}).pos(width - 46, yy).width(20).tooltip(Tooltip.create(Component.translatable("minigamebots.memory.forget"))).build());
		}
	}

	private void renderFactions(GuiGraphicsExtractor g, WorldMemory m) {
		String mine = minecraft.player == null ? "" : Insignia.of(minecraft.player).description();
		g.text(font, font.plainSubstrByWidth("Your insignia: " + (mine.isEmpty() ? "none (wear a patterned shield, banner or dyed armour)" : mine),
				width - 20), 10, 84, 0xFFB0B0B0, false);
		List<Entry> list = entries(m);
		if (list.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("minigamebots.memory.factions_empty"), 10, 100, width - 20, 0xFF909090);
			return;
		}
		int ry = 96;
		int rows = Math.max(1, (height - 34 - ry) / 22);
		for (int i = 0; i < Math.min(rows, list.size()); i++) {
			Entry e = list.get(i);
			String label = e.insignia() ? "Insignia: " + m.insigniaLooks.getOrDefault(e.key(), e.key()) : "Player: " + e.key();
			g.text(font, font.plainSubstrByWidth(label, width - 140), 10, ry + i * 22 + 6, e.relation().color, false);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		var profile = MinigameBotsClient.instance().profiles().active();
		WorldMemory m = mm().memory();
		g.text(font, font.plainSubstrByWidth(title.getString() + (profile == null ? "" : ": " + profile.name)
				+ (m == null ? "" : " in " + m.world), width - 230), 10, 12, 0xFFFFFFFF, false);
		if (m == null) {
			g.textWithWordWrap(font, Component.translatable("minigamebots.memory.none"), 10, 40, width - 20, 0xFFFFC060);
			return;
		}
		if (factions) {
			renderFactions(g, m);
		} else {
			renderPlaces(g, m, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		WorldMemory m = mm().memory();
		if (!factions && m != null && event.x() >= listX && event.x() < listX + listW && event.y() >= listY + 12) {
			int i = (int) ((event.y() - listY - 12) / ROW);
			List<Waypoint> list = sortedWaypoints(m);
			if (i >= 0 && i < list.size() && event.y() < height - 58) {
				selected = list.get(i);
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		mm().save();
		minecraft.gui.setScreen(parent);
	}
}
