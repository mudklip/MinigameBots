package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.coach.Progress;
import io.github.mudklip.minigamebots.client.coach.Watchdog;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Is training paying off? A status card (improving / not improving / getting worse, why, and what
 * to do), a chart of its exam results across trained versions with the recent stretch the verdict
 * looks at highlighted, and a few numbers at a glance. Everything comes from the exam history.
 */
public final class ProgressScreen extends Screen {
	private static final String[] SERIES = {"Score", "Win rate", "Damage share"};
	private static final String[] SERIES_HELP = {"wins + damage, 0-100", "rounds won, %", "damage it dealt, %"};

	private final @Nullable Screen parent;
	private final ProfileManifest profile;
	private Watchdog.Verdict verdict;
	private int series;
	private final int[] chipX = new int[SERIES.length];
	private final int[] chipW = new int[SERIES.length];
	private int chipY;

	public ProgressScreen(@Nullable Screen parent, ProfileManifest profile) {
		super(Component.literal("Progress"));
		this.parent = parent;
		this.profile = profile;
		this.verdict = Watchdog.check(MinigameBotsClient.instance().profiles().store().dir(profile.id));
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 60, 6).width(52).build());
	}

	private static double value(Watchdog.Point p, int series) {
		return switch (series) {
			case 1 -> p.winRate() * 100;
			case 2 -> p.damageShare() * 100;
			default -> p.score();
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		Watchdog.Verdict v = verdict;
		g.text(font, Component.literal("§f§lProgress§r§7  " + profile.name + (v.scenario().isEmpty() ? "" : "  ·  " + v.scenario())),
				8, 10, Ui.TEXT, false);

		// --- Status card ---
		int sx = 8, sy = 26, sw = width - 16;
		var fixLines = font.split(Component.literal("→ " + v.fix()), sw - 44);
		int shownFix = Math.min(2, fixLines.size());
		int sh = 32 + 11 * shownFix;
		Ui.card(g, sx, sy, sw, sh, Ui.CARD, v.state().color);
		badge(g, sx + 8, sy + (sh - 22) / 2, v.state());
		long diff = Math.round(Math.abs(v.change()));
		String change = switch (v.state()) {
			case IMPROVING -> "  +" + diff + " points on the versions before";
			case FLAT -> "  " + (v.change() >= 0 ? "+" : "-") + diff + " on the versions before: normal ups and downs";
			case DROPPING -> "  " + diff + " points below the versions before";
			default -> "";
		};
		g.text(font, Component.literal("§l" + v.state().label), sx + 34, sy + 6, v.state().color, false);
		g.text(font, font.plainSubstrByWidth(change, sw - 44 - font.width("§l" + v.state().label)),
				sx + 34 + font.width("§l" + v.state().label), sy + 6, Ui.MUTED, false);
		g.text(font, font.plainSubstrByWidth(v.why(), sw - 44), sx + 34, sy + 18, Ui.TEXT, false);
		for (int i = 0; i < shownFix; i++) {
			g.text(font, fixLines.get(i), sx + 34, sy + 30 + 11 * i, Ui.ACCENT, false);
		}

		// --- Chart ---
		int tilesW = Math.min(118, width / 4);
		int cx = 8, cy = sy + sh + 8, cw = width - 16 - tilesW - 8, ch = height - cy - 22;
		Ui.panel(g, cx, cy, cw, ch);
		// Which numbers: chips.
		chipY = cy + 5;
		int x = cx + 6;
		for (int i = 0; i < SERIES.length; i++) {
			boolean hover = Ui.inside(mouseX, mouseY, x, chipY, font.width(SERIES[i]) + 10, 12);
			chipX[i] = x;
			chipW[i] = Ui.chip(g, font, SERIES[i], x, chipY, i == series, hover, Ui.ACCENT);
			x += chipW[i] + 4;
		}
		if (font.width(SERIES_HELP[series]) <= cx + cw - x - 10) {
			g.text(font, SERIES_HELP[series], x + 4, chipY + 3, Ui.MUTED, false);
		}
		int px = cx + 26, py = cy + 24, pw = cw - 36, ph = ch - 44;
		chart(g, v.points(), px, py, pw, ph, mouseX, mouseY);

		// --- Tiles ---
		int tx = cx + cw + 8, ty = cy, th = 30;
		List<Watchdog.Point> pts = v.points();
		Watchdog.Point last = pts.isEmpty() ? null : pts.get(pts.size() - 1);
		double best = pts.stream().mapToDouble(Watchdog.Point::score).max().orElse(0);
		int exams = pts.stream().mapToInt(Watchdog.Point::exams).sum();
		tile(g, tx, ty, tilesW, th, "Latest score", last == null ? "-" : String.valueOf(Math.round(last.score())), Ui.TEXT);
		tile(g, tx, ty + th + 4, tilesW, th, "Best score", pts.isEmpty() ? "-" : String.valueOf(Math.round(best)), Ui.GOOD);
		tile(g, tx, ty + 2 * (th + 4), tilesW, th, "Recent change",
				v.state() == Watchdog.State.NO_DATA ? "-" : String.format("%+.0f", v.change()), v.state().color);
		tile(g, tx, ty + 3 * (th + 4), tilesW, th, "Exams run", String.valueOf(exams), Ui.TEXT);
		int by = ty + 4 * (th + 4) + 2;
		if (last != null && by + 40 < cy + ch) {
			g.text(font, "Latest version", tx, by, Ui.MUTED, false);
			g.text(font, "won " + Math.round(last.winRate() * 100) + "%", tx, by + 11, Ui.TEXT, false);
			Ui.bar(g, tx, by + 21, tilesW, 6, last.winRate(), Ui.GOOD);
			g.text(font, "dealt " + Math.round(last.damageShare() * 100) + "% of damage", tx, by + 30, Ui.TEXT, false);
			Ui.bar(g, tx, by + 40, tilesW, 6, last.damageShare(), Ui.ACCENT);
		}
		hoverCard(g, v.points());
		// Legend.
		int ly = height - 16;
		g.fill(8, ly + 3, 12, ly + 7, Ui.ACCENT);
		g.text(font, "a trained version", 15, ly, Ui.MUTED, false);
		int lx = 15 + font.width("a trained version") + 10;
		g.fill(lx, ly + 1, lx + 10, ly + 9, 0x303C78C8);
		g.text(font, "the stretch the verdict compares", lx + 13, ly, Ui.MUTED, false);
		lx += 13 + font.width("the stretch the verdict compares") + 10;
		dashed(g, lx, ly + 4, lx + 12, ly + 4, v.state().color);
		g.text(font, "trend", lx + 15, ly, Ui.MUTED, false);
		if (series == 1 && Progress.active() != null && Progress.active().human.containsKey("duel")) {
			lx += 15 + font.width("trend") + 10;
			dashed(g, lx, ly + 4, lx + 12, ly + 4, 0xFFE8ECF2);
			g.text(font, "you", lx + 15, ly, Ui.MUTED, false);
		}
	}

	/** A round-ish coloured badge with an arrow for the verdict. */
	private void badge(GuiGraphicsExtractor g, int x, int y, Watchdog.State s) {
		int c = s.color;
		g.fill(x + 2, y, x + 20, y + 22, c);
		g.fill(x, y + 2, x + 22, y + 20, c);
		String icon = switch (s) {
			case IMPROVING -> "▲";
			case FLAT -> "■";
			case DROPPING -> "▼";
			default -> "?";
		};
		g.text(font, Component.literal("§l" + icon), x + 11 - font.width("§l" + icon) / 2, y + 7, 0xFF101418, false);
	}

	private void tile(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, String value, int color) {
		Ui.card(g, x, y, w, h, Ui.CARD, color);
		g.text(font, label, x + 7, y + 4, Ui.MUTED, false);
		g.text(font, Component.literal("§l" + value), x + 7, y + 16, color, false);
	}

	private void chart(GuiGraphicsExtractor g, List<Watchdog.Point> pts, int x, int y, int w, int h, int mx, int my) {
		// Grid: 0, 25, 50, 75, 100.
		for (int i = 0; i <= 4; i++) {
			int gy = y + h - h * i / 4;
			g.fill(x, gy, x + w, gy + 1, i == 0 ? 0xFF3C4656 : 0x283C4656);
			String lab = String.valueOf(25 * i);
			g.text(font, lab, x - 4 - font.width(lab), gy - 4, Ui.MUTED, false);
		}
		if (pts.isEmpty()) {
			g.text(font, "No exams yet: Exams screen > Start exam, after each training.", x + 6, y + h / 2 - 4, Ui.MUTED, false);
			return;
		}
		int n = pts.size();
		int[] xs = new int[n];
		int[] ys = new int[n];
		for (int i = 0; i < n; i++) {
			xs[i] = n == 1 ? x + w / 2 : x + 6 + (w - 12) * i / (n - 1);
			ys[i] = y + h - (int) Math.round(h * Math.max(0, Math.min(100, value(pts.get(i), series))) / 100.0);
		}
		// The stretch the verdict looks at: "before" (faint) and "recent" (brighter).
		if (verdict.state() != Watchdog.State.NO_DATA) {
			int r0 = Math.max(0, n - 3);
			int b0 = Math.max(0, n - 6);
			int half = n > 1 ? (w - 12) / (n - 1) / 2 : 6;
			g.fill(xs[b0] - half, y, xs[r0] - half, y + h, 0x183C78C8);
			g.fill(xs[r0] - half, y, Math.min(x + w, xs[n - 1] + half), y + h, 0x303C78C8);
			// Trend through the recent versions (least squares), dashed in the verdict's colour.
			if (n - r0 >= 2) {
				double sx = 0, sy = 0, sxx = 0, sxy = 0;
				int m = n - r0;
				for (int i = r0; i < n; i++) {
					sx += xs[i];
					sy += ys[i];
					sxx += (double) xs[i] * xs[i];
					sxy += (double) xs[i] * ys[i];
				}
				double den = m * sxx - sx * sx;
				double k = den == 0 ? 0 : (m * sxy - sx * sy) / den;
				double b = (sy - k * sx) / m;
				dashed(g, xs[r0], (int) (k * xs[r0] + b), xs[n - 1], (int) (k * xs[n - 1] + b), verdict.state().color);
			}
		}
		// You (win rate only: the human floor from "Measure me").
		var pr = Progress.active();
		if (series == 1 && pr != null && pr.human.containsKey("duel")) {
			int hy = y + h - (int) Math.round(h * Math.max(0, Math.min(1, pr.human.get("duel"))));
			dashed(g, x, hy, x + w, hy, 0xC0E8ECF2);
			g.text(font, "you", x + w - font.width("you") - 2, hy - 10, Ui.TEXT, false);
		}
		// The line and the points.
		for (int i = 1; i < n; i++) {
			line(g, xs[i - 1], ys[i - 1], xs[i], ys[i], Ui.ACCENT);
		}
		int hover = -1;
		for (int i = 0; i < n; i++) {
			boolean on = Math.abs(mx - xs[i]) <= 4 && my >= y && my <= y + h;
			if (on) {
				hover = i;
			}
			int r = on ? 3 : 2;
			g.fill(xs[i] - r - 1, ys[i] - r - 1, xs[i] + r + 1, ys[i] + r + 1, 0xFF101418);
			g.fill(xs[i] - r, ys[i] - r, xs[i] + r, ys[i] + r, on ? 0xFFFFFFFF : Ui.ACCENT);
		}
		// First and last version under the axis.
		g.text(font, "v1", xs[0] - font.width("v1") / 2, y + h + 4, Ui.MUTED, false);
		if (n > 1) {
			String lab = "v" + n;
			g.text(font, lab, xs[n - 1] - font.width(lab) / 2, y + h + 4, Ui.MUTED, false);
		}
		hoverAt = hover < 0 ? null : new int[] {hover, xs[hover], ys[hover]};
	}

	/** The details card for the point under the mouse: drawn last, on top of everything. */
	private int @Nullable [] hoverAt;

	private void hoverCard(GuiGraphicsExtractor g, List<Watchdog.Point> pts) {
		int[] h = hoverAt;
		if (h == null || h[0] >= pts.size()) {
			return;
		}
		int hover = h[0];
		int[] xs = new int[hover + 1];
		int[] ys = new int[hover + 1];
		xs[hover] = h[1];
		ys[hover] = h[2];
		{
			Watchdog.Point p = pts.get(hover);
			String[] lines = {"Version " + (hover + 1) + "  (training step " + p.step() + ")",
					"Score " + Math.round(p.score()) + "  ·  won " + Math.round(p.winRate() * 100) + "%  ·  dealt "
							+ Math.round(p.damageShare() * 100) + "% of damage",
					p.exams() + " exam(s), last " + p.date()};
			int bw = 0;
			for (String l : lines) {
				bw = Math.max(bw, font.width(l));
			}
			bw += 12;
			int bh = lines.length * 11 + 8;
			int bx = Math.min(xs[hover] + 8, width - bw - 4);
			int byy = Math.max(4, ys[hover] - bh - 6);
			g.fill(bx, byy, bx + bw, byy + bh, 0xF0101418);
			g.outline(bx, byy, bw, bh, Ui.ACCENT);
			for (int i = 0; i < lines.length; i++) {
				g.text(font, lines[i], bx + 6, byy + 5 + 11 * i, i == 0 ? Ui.TEXT : Ui.MUTED, false);
			}
		}
	}

	private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= steps; i++) {
			int x = steps == 0 ? x0 : x0 + (x1 - x0) * i / steps;
			int y = steps == 0 ? y0 : y0 + (y1 - y0) * i / steps;
			g.fill(x, y, x + 2, y + 2, color);
		}
	}

	private static void dashed(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= steps; i++) {
			if ((i / 3) % 2 == 1) {
				continue;
			}
			int x = steps == 0 ? x0 : x0 + (x1 - x0) * i / steps;
			int y = steps == 0 ? y0 : y0 + (y1 - y0) * i / steps;
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < SERIES.length; i++) {
			if (Ui.inside(event.x(), event.y(), chipX[i], chipY, chipW[i], 12)) {
				series = i;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
