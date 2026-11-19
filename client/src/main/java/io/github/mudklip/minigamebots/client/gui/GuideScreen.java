package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The in-game Guide: a "Getting started" checklist that ticks itself off, and short pages for
 * every feature. Opened from the main menu, or with F1 on any MinigameBots screen (which opens
 * the page about that screen). Pages live in assets/minigamebots/guide/en_us.txt:
 * "=== id | Title" starts a page; "# " heading, "- " bullet, "1. " step, "> " tip, "! " warning,
 * [Key] highlights a key or button, blank line = gap.
 */
public final class GuideScreen extends Screen {
	private record Page(String id, String title, List<String> lines) {
	}

	/** Which page F1 opens on each screen. */
	public static final Map<String, String> PAGE_FOR = Map.ofEntries(
			Map.entry("MainScreen", "start"), Map.entry("ProfilesScreen", "profiles"), Map.entry("EditProfileScreen", "profiles"),
			Map.entry("MergeScreen", "profiles"), Map.entry("TrainingScreen", "training"), Map.entry("VersionsScreen", "training"),
			Map.entry("TeacherScreen", "teachers"), Map.entry("PracticeScreen", "practice"), Map.entry("ExamsScreen", "practice"),
			Map.entry("ObjectiveEditorScreen", "practice"), Map.entry("GoalsScreen", "goals"), Map.entry("MemoryScreen", "memory"),
			Map.entry("RecorderScreen", "recording"), Map.entry("RecordingViewerScreen", "recording"),
			Map.entry("EditRecordingScreen", "recording"), Map.entry("BrainScreen", "brain"), Map.entry("FoundationsScreen", "brain"),
			Map.entry("RealismScreen", "realism"), Map.entry("CompareScreen", "realism"), Map.entry("VideoLabScreen", "video"),
			Map.entry("QuestionScreen", "video"), Map.entry("GuideScreen", "start"),
			Map.entry("SkillsScreen", "skills"), Map.entry("SkillEditorScreen", "skills"), Map.entry("AddSkillScreen", "skills"),
			Map.entry("RulesScreen", "skills"), Map.entry("PickRuleScreen", "skills"), Map.entry("SkillAdvancedScreen", "skills"),
			Map.entry("ItemPickerScreen", "skills"), Map.entry("HubScreen", "hub"),
			Map.entry("BackupsScreen", "backups"), Map.entry("DowngradeScreen", "backups"));

	private static final int LIST_W = 110;
	private static final int LINE = 11;
	private static @Nullable List<Page> pages;

	private final @Nullable Screen parent;
	private int page;
	private int scroll;
	private int listScroll;
	private List<Row> rows = List.of();

	private record Row(FormattedCharSequence text, int indent, int color, int box) {
	}

	public GuideScreen(@Nullable Screen parent, String pageId) {
		super(Component.translatable("minigamebots.guide.screen"));
		this.parent = parent;
		List<Page> all = pages();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).id.equals(pageId)) {
				page = i;
			}
		}
	}

	private static List<Page> pages() {
		if (pages != null) {
			return pages;
		}
		List<Page> out = new ArrayList<>();
		var res = net.minecraft.client.Minecraft.getInstance().getResourceManager()
				.getResource(Identifier.fromNamespaceAndPath("minigamebots", "guide/en_us.txt"));
		if (res.isPresent()) {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(res.get().open(), StandardCharsets.UTF_8))) {
				Page cur = null;
				for (String line; (line = r.readLine()) != null; ) {
					if (line.startsWith("=== ")) {
						String[] p = line.substring(4).split("\\|", 2);
						cur = new Page(p[0].trim(), p.length > 1 ? p[1].trim() : p[0].trim(), new ArrayList<>());
						out.add(cur);
					} else if (cur != null) {
						cur.lines.add(line);
					}
				}
			} catch (Exception e) {
				MinigameBotsClient.LOGGER.warn("Couldn't read the guide", e);
			}
		}
		if (out.isEmpty()) {
			out.add(new Page("start", "Guide", List.of("The guide couldn't be loaded.")));
		}
		pages = out;
		return out;
	}

	@Override
	protected void init() {
		List<Page> all = pages();
		int visible = Math.max(1, (height - 60) / 18);
		listScroll = Math.max(0, Math.min(listScroll, all.size() - visible));
		for (int i = listScroll; i < Math.min(all.size(), listScroll + visible); i++) {
			int idx = i;
			Button b = addRenderableWidget(Button.builder(Component.literal(all.get(i).title), x -> {
				page = idx;
				scroll = 0;
				rebuildWidgets();
			}).pos(6, 26 + (i - listScroll) * 18).width(LIST_W).build());
			b.active = i != page;
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).pos(width - 56, height - 24).width(50).build());
		layout();
	}

	/** Turns the page's lines into wrapped, styled rows. */
	private void layout() {
		int x0 = LIST_W + 16;
		int w = width - x0 - 12;
		List<String> lines = new ArrayList<>(pages().get(page).lines);
		if (pages().get(page).id.equals("start")) {
			lines.addAll(0, checklist());
		}
		List<Row> out = new ArrayList<>();
		for (String raw : lines) {
			String line = raw.stripTrailing();
			int indent = 0;
			int color = 0xFFE0E0E0;
			int box = 0;
			String text = line;
			if (line.isBlank()) {
				out.add(new Row(FormattedCharSequence.EMPTY, 0, 0, 0));
				continue;
			} else if (line.startsWith("# ")) {
				text = "§l" + line.substring(2);
				color = 0xFFFFD866;
			} else if (line.startsWith("- ")) {
				text = "§7•§r " + line.substring(2);
				indent = 6;
			} else if (line.matches("\\d+\\. .*")) {
				int dot = line.indexOf('.');
				text = "§b" + line.substring(0, dot + 1) + "§r" + line.substring(dot + 1);
				indent = 4;
			} else if (line.startsWith("> ")) {
				text = "§a✔ Tip:§r " + line.substring(2);
				box = 0x3020A040;
				indent = 4;
			} else if (line.startsWith("! ")) {
				text = "§6⚠ Note:§r " + line.substring(2);
				box = 0x30C08020;
				indent = 4;
			} else if (line.startsWith("=> ")) {
				text = "§b➜ Next:§r " + line.substring(3);
				box = 0x302080C0;
				indent = 4;
			} else if (line.startsWith("[x] ") || line.startsWith("[ ] ")) {
				boolean done = line.startsWith("[x]");
				// Done steps: plain grey (no key colours), so they read as finished.
				text = done ? "§a☑ §7" + line.substring(4).replaceAll("\\[([^\\]]+)\\]", "$1") : "§e☐ §f" + line.substring(4);
				indent = 4;
			}
			text = text.replaceAll("\\[([^\\]]+)\\]", "§6$1§r");
			boolean first = true;
			int hang = line.startsWith("- ") || line.matches("\\d+\\. .*") || line.startsWith("[") ? 9 : 0;
			for (FormattedCharSequence part : font.split(Component.literal(text), w - indent - hang - (box != 0 ? 6 : 0))) {
				out.add(new Row(part, first ? indent : indent + hang, color, box));
				first = false;
			}
		}
		rows = out;
		int max = Math.max(0, rows.size() - (height - 50) / LINE);
		scroll = Math.max(0, Math.min(scroll, max));
	}

	/** The Getting started checklist, ticked from the real state of things. */
	private static List<String> checklist() {
		var mgb = MinigameBotsClient.instance();
		boolean brain = mgb.brain().runtime().installed();
		var profiles = mgb.profiles().store().list();
		var active = mgb.profiles().active();
		boolean recorded = mgb.recorder().store().list().stream().anyMatch(m -> "human".equals(m.source));
		boolean trained = active != null && Files.isRegularFile(mgb.profiles().store().dir(active.id).resolve("core/model.json"));
		boolean played = active != null && active.stats.decisions > 0;
		boolean goals = active != null && Files.isDirectory(mgb.profiles().store().dir(active.id).resolve("skills"))
				&& played;
		List<String> out = new ArrayList<>();
		out.add("# Your progress");
		out.add((brain ? "[x] " : "[ ] ") + "Set up the brain: [F10] > [Brain...] > [Install (GPU)]");
		out.add((!profiles.isEmpty() ? "[x] " : "[ ] ") + "Make an AI: [F10] > [AI Profiles...] > [New AI]");
		out.add((active != null ? "[x] " : "[ ] ") + "Pick it: select it and press [Use this AI]");
		out.add((recorded ? "[x] " : "[ ] ") + "Record yourself playing: press [F7], play a few minutes, press [F7] again");
		out.add((trained ? "[x] " : "[ ] ") + "Train it: [AI Profiles] > [Train] > tick recordings > [Start]");
		out.add((played ? "[x] " : "[ ] ") + "Let it play: [F8] (and [F8] again to take over)");
		out.add((goals ? "[x] " : "[ ] ") + "Give it goals: [F10] > [Goals...]");
		String next = !brain ? "set up the brain" : profiles.isEmpty() ? "make an AI" : active == null ? "pick your AI"
				: !recorded ? "record yourself (or use Teacher demos, no skill needed)" : !trained ? "train it"
				: !played ? "press F8 and watch it play" : "give it goals, then practice";
		out.add("=> " + next.substring(0, 1).toUpperCase() + next.substring(1) + ".");
		out.add("");
		return out;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (x < LIST_W + 10) {
			listScroll = Math.max(0, listScroll - (int) Math.signum(dy));
			rebuildWidgets();
		} else {
			scroll = Math.max(0, Math.min(Math.max(0, rows.size() - (height - 50) / LINE), scroll - (int) Math.signum(dy) * 3));
		}
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, Component.literal("§lGuide§r  §7(scroll to read more; F1 on any screen opens its page)"), 6, 8, 0xFFFFFFFF, false);
		int x0 = LIST_W + 16;
		g.fill(x0 - 6, 22, width - 6, height - 28, 0x50000000);
		g.text(font, Component.literal("§n" + pages().get(page).title), x0, 26, 0xFFFFFFFF, false);
		int y = 42;
		int bottom = height - 32;
		for (int i = scroll; i < rows.size() && y + LINE <= bottom; i++) {
			Row r = rows.get(i);
			if (r.box != 0) {
				g.fill(x0 - 2, y - 1, width - 10, y + LINE - 1, 0x40000000 | (r.box & 0xFFFFFF));
			}
			g.text(font, r.text, x0 + r.indent, y, r.color, false);
			y += LINE;
		}
		if (rows.size() - scroll > (bottom - 42) / LINE) {
			g.text(font, Component.literal("§7▼ scroll for more"), LIST_W + 16, height - 22, 0xFFFFFFFF, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
