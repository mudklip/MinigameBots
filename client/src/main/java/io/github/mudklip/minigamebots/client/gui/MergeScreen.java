package io.github.mudklip.minigamebots.client.gui;

import io.github.mudklip.minigamebots.client.MinigameBotsClient;
import io.github.mudklip.minigamebots.client.profile.ProfileManifest;
import io.github.mudklip.minigamebots.client.profile.ProfileStore;
import io.github.mudklip.minigamebots.client.profile.Skills;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Makes a new AI from two: everything of the first, with any skill swapped for the other's.
 * The two originals stay as they are.
 */
public final class MergeScreen extends Screen {
	private static final int ROW = 22;

	private final @Nullable Screen parent;
	private final ProfileManifest base;
	private final List<ProfileManifest> others = new ArrayList<>();
	private int other;
	/** Skill name to the id it comes from (the base's id, the other's id, or "" for leave out). */
	private final Map<String, String> picks = new LinkedHashMap<>();
	private @Nullable String message;

	public MergeScreen(@Nullable Screen parent, ProfileManifest base) {
		super(Component.translatable("minigamebots.merge.screen", base.name));
		this.parent = parent;
		this.base = base;
		for (ProfileManifest p : store().list()) {
			if (!p.id.equals(base.id)) {
				others.add(p);
			}
		}
	}

	private static ProfileStore store() {
		return MinigameBotsClient.instance().profiles().store();
	}

	private @Nullable ProfileManifest otherProfile() {
		return others.isEmpty() ? null : others.get(other % others.size());
	}

	@Override
	protected void init() {
		ProfileManifest b = otherProfile();
		if (b == null) {
			message = "Make or import a second AI first.";
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), x -> onClose()).pos(width - 70, height - 28).width(60).build());
			return;
		}
		addRenderableWidget(Button.builder(Component.literal("With: " + b.name), x -> {
			other++;
			picks.clear();
			rebuildWidgets();
		}).pos(10, 26).width(Math.min(200, width - 20)).build());

		Path aDir = store().dir(base.id);
		Path bDir = store().dir(b.id);
		List<String> skills = new ArrayList<>();
		for (String s : Skills.all(aDir)) {
			if (Skills.trained(aDir, s) || Skills.trained(bDir, s)) {
				skills.add(s);
			}
		}
		for (String s : Skills.trainedOnes(bDir)) {
			if (!skills.contains(s)) {
				skills.add(s);
			}
		}
		int rows = Math.max(1, (height - 64 - 60) / ROW);
		for (int i = 0; i < Math.min(rows, skills.size()); i++) {
			String s = skills.get(i);
			boolean inA = Skills.trained(aDir, s);
			boolean inB = Skills.trained(bDir, s);
			// Default: keep the first AI's skill; take the second's where only it has one.
			picks.putIfAbsent(s, inA ? base.id : b.id);
			int y = 64 + i * ROW;
			addRenderableWidget(Button.builder(Component.literal(s + ": " + label(picks.get(s), b)), x -> {
				String now = picks.get(s);
				List<String> options = new ArrayList<>();
				if (inA) {
					options.add(base.id);
				}
				if (inB) {
					options.add(b.id);
				}
				if (!"general".equals(s)) {
					options.add("");
				}
				picks.put(s, options.get((options.indexOf(now) + 1) % options.size()));
				x.setMessage(Component.literal(s + ": " + label(picks.get(s), b)));
			}).pos(10, y).width(Math.min(240, width - 20)).build());
		}
		if (skills.isEmpty()) {
			message = "Neither AI has a trained skill yet; the merge would just copy " + base.name + ".";
		}

		addRenderableWidget(Button.builder(Component.translatable("minigamebots.merge.create"), x -> create(b))
				.pos(10, height - 28).width(100)
				.tooltip(Tooltip.create(Component.translatable("minigamebots.merge.help"))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), x -> onClose()).pos(width - 70, height - 28).width(60).build());
	}

	private String label(String id, ProfileManifest b) {
		return id.isEmpty() ? "leave out" : id.equals(base.id) ? "from " + base.name : "from " + b.name;
	}

	private void create(ProfileManifest b) {
		Map<String, String> take = new LinkedHashMap<>();
		List<String> drop = new ArrayList<>();
		picks.forEach((skill, id) -> {
			if (id.isEmpty()) {
				drop.add(skill);
			} else {
				take.put(skill, id);
			}
		});
		try {
			ProfileManifest m = store().merge(base, take, base.name + " + " + b.name);
			for (String s : drop) {
				store().removeSkill(m, s);
			}
			message = "Made " + m.name + ". Use it with the Goals screen.";
		} catch (IOException e) {
			MinigameBotsClient.LOGGER.error("Merge failed", e);
			message = "Couldn't merge: " + e.getMessage();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(font, title, 10, 10, 0xFFFFFFFF, false);
		g.text(font, font.plainSubstrByWidth("Skill: where it comes from (click to change)", width - 20), 10, 52, 0xFF909090, false);
		if (message != null) {
			g.text(font, font.plainSubstrByWidth(message, width - 20), 10, height - 44, 0xFF80E080, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
