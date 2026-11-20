package io.github.mudklip.minigamebots.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mudklip.minigamebots.client.invent.InventSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Inventions: start an inventing session (traps or shelters), watch it work, and look through its
 * hall of fame: the best ideas per quality, each with a top-down picture and what it did in the test.
 */
public final class InventionsScreen extends Screen {
	private static final String[] KINDS = {"trap", "shelter"};
	private static final int[] MINUTES = {2, 5, 15};
	private static final Map<String, String> QUALITY = Map.of("overall", "Best overall", "deadliest", "Deadliest",
			"most_hidden", "Most hidden", "cheapest", "Cheapest", "holds", "Holds them longest", "cheapest_safe", "Cheapest safe",
			"roomiest_safe", "Roomiest safe", "best_lit", "Best lit");
	private static int kind;
	private static int minutes;

	private final Screen parent;
	private final List<int[]> chips = new ArrayList<>();
	private final List<Object[]> rows = new ArrayList<>(); // {x, y, w, design}
	private @Nullable JsonObject selected;
	private @Nullable String message;
	private boolean builtRunning;

	public InventionsScreen(Screen parent) {
		super(Component.literal("Inventions"));
		this.parent = parent;
		InventSession.refresh();
	}

	@Override
	protected void init() {
		boolean on = InventSession.running();
		builtRunning = on;
		addRenderableWidget(Button.builder(Component.literal(on ? "§cStop" : "§a▶ Invent"), b -> {
			if (InventSession.running()) {
				InventSession.stop();
			} else {
				message = InventSession.start(KINDS[kind], MINUTES[minutes]);
			}
			rebuildWidgets();
		}).pos(8, 48).width(70).tooltip(Tooltip.create(Component.literal("Tests every idea for real in a sandbox "
				+ "high above you (needs Training Grounds); you can keep playing meanwhile. The sky there is put back as it was."))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 62, 6).width(54).build());
		// The selected idea: get the blocks (Training Grounds) and let the AI build it by hand.
		addRenderableWidget(Button.builder(Component.literal("Give me the blocks"), b -> {
			if (selected != null) {
				message = io.github.mudklip.minigamebots.client.invent.BuildRunner.giveBlocks(selected);
			}
		}).pos(width - 196, height - 26).width(100).tooltip(Tooltip.create(Component.literal("Training Grounds puts the blocks this "
				+ "idea needs in your inventory (hotbar first)."))).build());
		addRenderableWidget(Button.builder(Component.literal("§aBuild it here"), b -> {
			if (selected != null) {
				message = io.github.mudklip.minigamebots.client.invent.BuildRunner.build(minecraft, selected);
				onClose();
			}
		}).pos(width - 92, height - 26).width(84).tooltip(Tooltip.create(Component.literal("The AI builds it a few blocks in front of "
				+ "you with its own hands (walking, picking blocks, looking, clicking), from what it has in its hotbar. It's recorded as "
				+ "a building demo, so its building skill can learn from it. F9 stops it."))).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (InventSession.running() != builtRunning) {
			rebuildWidgets();
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, Component.literal("§f§lInventions"), 8, 8, Ui.TEXT, false);
		g.text(font, Component.literal("§7Combines parts it knows, tests every idea for real, keeps the best."), 8, 20,
				Ui.MUTED, false);
		chips.clear();
		int x = 8;
		g.text(font, Component.literal("§fInvent:"), x, 35, Ui.TEXT, false);
		x += 42;
		for (int i = 0; i < KINDS.length; i++) {
			String t = i == 0 ? "Traps" : "Shelters";
			int w = font.width(t) + 14;
			Ui.chip(g, font, t, x, 32, w, i == kind, Ui.inside(mouseX, mouseY, x, 32, w, 13), Ui.ACCENT);
			chips.add(new int[] {x, 32, w, 0, i});
			x += w + 3;
		}
		x += 10;
		g.text(font, Component.literal("§fFor:"), x, 35, Ui.TEXT, false);
		x += 26;
		for (int i = 0; i < MINUTES.length; i++) {
			String t = MINUTES[i] + " min";
			int w = font.width(t) + 14;
			Ui.chip(g, font, t, x, 32, w, i == minutes, Ui.inside(mouseX, mouseY, x, 32, w, 13), Ui.ACCENT);
			chips.add(new int[] {x, 32, w, 1, i});
			x += w + 3;
		}
		String st = InventSession.status().isEmpty() ? (message == null ? "" : message) : InventSession.status();
		g.text(font, Component.literal((InventSession.running() ? "§a" : "§7") + font.plainSubstrByWidth(st, width - 92)), 84, 54,
				Ui.TEXT, false);
		// Hall of fame for this kind: one row per quality, its best idea.
		rows.clear();
		JsonObject all = InventSession.hall();
		int y = 72;
		int listW = Math.min(200, width / 2 - 12);
		JsonObject hall = all == null || !all.has("hall") ? null : all.getAsJsonObject("hall").has(KINDS[kind])
				? all.getAsJsonObject("hall").getAsJsonObject(KINDS[kind]) : null;
		if (hall == null || hall.isEmpty()) {
			g.text(font, Component.literal("§7No " + KINDS[kind] + "s kept yet: press Invent."), 8, y, Ui.MUTED, false);
		} else {
			if (all.has("tested")) {
				g.text(font, Component.literal("§7Hall of fame (" + all.get("tested").getAsInt() + " ideas tested, "
						+ all.get("failed").getAsInt() + " that didn't work remembered)"), 8, y, Ui.MUTED, false);
				y += 12;
			}
			for (var en : hall.entrySet()) {
				JsonArray lst = en.getValue().getAsJsonArray();
				if (lst.isEmpty()) {
					continue;
				}
				JsonObject best = lst.get(0).getAsJsonObject();
				if (selected == null) {
					selected = best;
				}
				boolean on = selected != null && best.get("id").getAsString().equals(selected.get("id").getAsString());
				boolean h = Ui.inside(mouseX, mouseY, 8, y, listW, 22);
				g.fill(8, y, 8 + listW, y + 22, on ? Ui.CARD_SELECTED : h ? Ui.CARD_HOVER : Ui.CARD);
				g.text(font, Component.literal("§f" + QUALITY.getOrDefault(en.getKey(), en.getKey())), 12, y + 3, Ui.TEXT, false);
				g.text(font, Component.literal("§7" + font.plainSubstrByWidth(best.get("name").getAsString(), listW - 8)), 12, y + 12,
						Ui.MUTED, false);
				rows.add(new Object[] {8, y, listW, best});
				y += 24;
			}
		}
		if (selected != null) {
			drawDesign(g, selected, 8 + listW + 12, 72);
		}
	}

	private void drawDesign(GuiGraphicsExtractor g, JsonObject d, int x0, int y0) {
		int cell = 6;
		int size = 15;
		g.fill(x0 - 1, y0 - 1, x0 + size * cell + 1, y0 + size * cell + 1, Ui.BORDER);
		g.fill(x0, y0, x0 + size * cell, y0 + size * cell, 0xFF55595E);
		JsonArray pal = d.getAsJsonArray("palette");
		Map<Long, int[]> top = new HashMap<>(); // (x,z) -> {y, palette index}
		for (var el : d.getAsJsonArray("blocks")) {
			JsonArray b = el.getAsJsonArray();
			int bx = b.get(0).getAsInt();
			int by = b.get(1).getAsInt();
			int bz = b.get(2).getAsInt();
			String id = pal.get(b.get(3).getAsInt()).getAsString();
			if (id.equals("minecraft:air") && by < -1) {
				continue;
			}
			long k = ((long) bx << 32) ^ (bz & 0xffffffffL);
			int[] cur = top.get(k);
			if (cur == null || by > cur[0]) {
				top.put(k, new int[] {by, b.get(3).getAsInt(), bx, bz});
			}
		}
		for (int[] t : top.values()) {
			int px = x0 + (t[2] + size / 2) * cell;
			int pz = y0 + (t[3] + size / 2) * cell;
			if (t[2] < -size / 2 || t[2] > size / 2 || t[3] < -size / 2 || t[3] > size / 2) {
				continue;
			}
			g.fill(px, pz, px + cell, pz + cell, colour(pal.get(t[1]).getAsString()));
		}
		if (d.has("path")) {
			JsonArray p = d.getAsJsonArray("path");
			int z = p.get(0).getAsJsonArray().get(1).getAsInt();
			g.fill(x0, y0 + (z + size / 2) * cell + cell / 2, x0 + size * cell, y0 + (z + size / 2) * cell + cell / 2 + 1, 0x80FFFF66);
		}
		int ty = y0 + size * cell + 6;
		List<String> lines = new ArrayList<>();
		lines.add("§f" + d.get("name").getAsString());
		lines.add("§7Cost " + d.get("cost").getAsDouble() + (d.has("visibility") ? " · shows " + d.get("visibility").getAsDouble() : ""));
		JsonObject r = d.has("result") ? d.getAsJsonObject("result") : new JsonObject();
		if ("trap".equals(d.get("kind").getAsString())) {
			lines.add("§7In the test: " + (r.has("killed") && r.get("killed").getAsBoolean() ? "§cknocked out§7"
					: "took " + (r.has("damage") ? String.format("%.0f", r.get("damage").getAsDouble()) : "0") + " damage")
					+ (r.has("caught") && r.get("caught").getAsBoolean() ? ", caught" : ""));
		} else {
			lines.add("§7In the test: " + (r.has("enclosed") && r.get("enclosed").getAsBoolean() ? "closed in" : "§copen§7") + ", room "
					+ (r.has("room") ? r.get("room").getAsInt() : 0) + ", light " + (r.has("light") ? r.get("light").getAsInt() : 0));
		}
		lines.add("§8Yellow line: where the victim walks. Black: holes.");
		for (String s : lines) {
			g.text(font, Component.literal(font.plainSubstrByWidth(s, width - x0 - 8)), x0, ty, Ui.TEXT, false);
			ty += 10;
		}
	}

	private static int colour(String id) {
		String b = id.split("\\[")[0].replace("minecraft:", "");
		return switch (b) {
			case "air" -> 0xFF101010;
			case "tnt" -> 0xFFD03030;
			case "dispenser" -> 0xFF707070;
			case "lava" -> 0xFFFF8020;
			case "cobweb" -> 0xFFF0F0F0;
			case "magma_block" -> 0xFF9A3A10;
			case "stone_pressure_plate" -> 0xFFB0B0B0;
			case "oak_pressure_plate", "oak_door", "oak_trapdoor" -> 0xFF9C7440;
			case "torch" -> 0xFFFFE060;
			case "glass" -> 0xFFB8E0F0;
			case "oak_planks" -> 0xFFC09858;
			case "cobblestone" -> 0xFF8A8A8A;
			case "dirt" -> 0xFF7A5534;
			case "stone_bricks" -> 0xFF9A9A9A;
			default -> 0xFF6A6E74;
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int[] c : chips) {
			if (Ui.inside(event.x(), event.y(), c[0], c[1], c[2], 13)) {
				if (c[3] == 0) {
					kind = c[4];
					selected = null;
				} else {
					minutes = c[4];
				}
				return true;
			}
		}
		for (Object[] r : rows) {
			if (Ui.inside(event.x(), event.y(), (int) r[0], (int) r[1], (int) r[2], 22)) {
				selected = (JsonObject) r[3];
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
