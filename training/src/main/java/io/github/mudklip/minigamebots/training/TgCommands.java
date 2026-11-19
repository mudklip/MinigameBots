package io.github.mudklip.minigamebots.training;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.mudklip.minigamebots.training.aim.AimTrainer;
import io.github.mudklip.minigamebots.training.arena.Arena;
import io.github.mudklip.minigamebots.training.arena.ArenaManager;
import io.github.mudklip.minigamebots.training.dummy.DummySettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /tg: opens the menu; subcommands do the same things for scripts and keyboards. */
final class TgCommands {
	private TgCommands() {
	}

	static void register(CommandDispatcher<CommandSourceStack> d, TrainingGrounds tg) {
		SuggestionProvider<CommandSourceStack> arenaNames = (ctx, b) ->
				SharedSuggestionProvider.suggest(tg.arenas().list().stream().map(a -> a.name), b);

		d.register(Commands.literal("tg")
				.executes(ctx -> {
					tg.menus().main(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("aim")
						.then(Commands.literal("stop").executes(ctx -> {
							tg.aim().stop(ctx.getSource().getPlayerOrException(), true);
							return 1;
						}))
						.then(aimMode("flick", AimTrainer.Mode.FLICK, tg))
						.then(aimMode("track", AimTrainer.Mode.TRACK, tg))
						.then(aimMode("switch", AimTrainer.Mode.SWITCH, tg)))
				.then(Commands.literal("arena").requires(tg::canManage)
						.then(Commands.literal("quick").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
							try {
								Arena a = tg.arenas().quick(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name"));
								ok(ctx, "Built and saved practice arena " + a.name + " here");
								return 1;
							} catch (ArenaManager.ArenaException e) {
								fail(ctx, e.getMessage());
								return 0;
							}
						})))
						.then(Commands.literal("pos1").executes(ctx -> corner(ctx, tg, 0)))
						.then(Commands.literal("pos2").executes(ctx -> corner(ctx, tg, 1)))
						.then(Commands.literal("list").executes(ctx -> {
							var list = tg.arenas().list();
							ok(ctx, list.isEmpty() ? "No arenas yet" : "Arenas: " + String.join(", ", list.stream().map(a -> a.name).toList()));
							return list.size();
						}))
						.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
							try {
								Arena a = tg.arenas().create(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name"));
								ok(ctx, String.format("Saved %s (%d x %d x %d)", a.name, a.sizeX, a.sizeY, a.sizeZ));
								return 1;
							} catch (ArenaManager.ArenaException e) {
								fail(ctx, e.getMessage());
								return 0;
							}
						})))
						.then(named("reset", arenaNames, (ctx, a) -> {
							ok(ctx, "Reset " + a.name + " (" + tg.resetArena(a).size() + " player(s))");
						}))
						.then(named("delete", arenaNames, (ctx, a) -> {
							tg.arenas().delete(a.name);
							ok(ctx, "Deleted " + a.name);
						}))
						.then(named("kit", arenaNames, (ctx, a) -> {
							ok(ctx, "Kit for " + a.name + ": " + tg.arenas().setKit(a, ctx.getSource().getPlayerOrException()) + " items");
						}))
						.then(named("nokit", arenaNames, (ctx, a) -> {
							tg.arenas().clearKit(a);
							ok(ctx, a.name + ": players keep their own items");
						}))
						.then(named("spawn", arenaNames, (ctx, a) -> {
							tg.arenas().addSpawn(a, ctx.getSource().getPlayerOrException());
							ok(ctx, "Added spawn " + a.spawns.size() + " to " + a.name);
						}))
						.then(named("clearspawns", arenaNames, (ctx, a) -> {
							tg.arenas().clearSpawns(a);
							ok(ctx, "Removed all spawns of " + a.name);
						})))
				.then(Commands.literal("area").requires(tg::canManage)
						.then(Commands.literal("clear").executes(ctx -> {
							tg.areas().clear();
							ok(ctx, "Practice area removed (the ground is as it was)");
							return 1;
						}))
						.then(Commands.argument("kind", StringArgumentType.word())
								.suggests((c, sb) -> net.minecraft.commands.SharedSuggestionProvider.suggest(PracticeAreas.KINDS, sb))
								.executes(ctx -> {
									ok(ctx, tg.areas().setup(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "kind")));
									return 1;
								})))
				.then(Commands.literal("multitrain").requires(tg::canManage)
						.then(Commands.literal("watch")
								.then(Commands.literal("off").executes(ctx -> {
									tg.multi().unwatch(ctx.getSource().getPlayerOrException());
									ok(ctx, "Back to your own view");
									return 1;
								}))
								.then(Commands.argument("world", IntegerArgumentType.integer(1, 32)).executes(ctx -> {
									ok(ctx, tg.multi().watch(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "world") - 1));
									return 1;
								})))
						.then(Commands.literal("here").then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
								.then(Commands.argument("opponent", StringArgumentType.word())
										.suggests((c, sb) -> net.minecraft.commands.SharedSuggestionProvider.suggest(DummySettings.STYLES, sb))
										.executes(ctx -> {
											String style = StringArgumentType.getString(ctx, "opponent");
											if (!DummySettings.STYLES.contains(style) || style.equals("self")) {
												fail(ctx, "Opponent styles: L1-L5, rusher, blocker, critter, w_tapper, kiter, godly");
												return 0;
											}
											ok(ctx, tg.multi().startAround(ctx.getSource().getPlayerOrException(),
													IntegerArgumentType.getInteger(ctx, "count"), style));
											return 1;
										}))))
						.then(Commands.literal("stop").executes(ctx -> {
							tg.multi().stop(ctx.getSource().getServer());
							ok(ctx, "Stopped multi-world training");
							return 1;
						}))
						.then(Commands.literal("start").then(Commands.argument("arena", StringArgumentType.word()).suggests(arenaNames)
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
										.then(Commands.argument("opponent", StringArgumentType.word())
												.suggests((c, sb) -> net.minecraft.commands.SharedSuggestionProvider.suggest(DummySettings.STYLES, sb))
												.executes(ctx -> {
													Arena a = tg.arenas().get(StringArgumentType.getString(ctx, "arena"));
													String style = StringArgumentType.getString(ctx, "opponent");
													if (a == null || !DummySettings.STYLES.contains(style) || style.equals("self")) {
														fail(ctx, a == null ? "No such arena" : "Opponent styles: L1-L5, rusher, blocker, critter, w_tapper, kiter, godly");
														return 0;
													}
													try {
														ok(ctx, tg.multi().start(ctx.getSource().getPlayerOrException(), a,
																IntegerArgumentType.getInteger(ctx, "count"), style));
													} catch (ArenaManager.ArenaException e) {
														fail(ctx, e.getMessage());
														return 0;
													}
													return 1;
												}))))))
				.then(Commands.literal("record")
						.then(Commands.literal("on").requires(tg::canManage).executes(ctx -> {
							tg.inputs().set(ctx.getSource().getServer(), true);
							ok(ctx, "Recording player inputs for linked AI clients (everyone was told)");
							return 1;
						}))
						.then(Commands.literal("off").requires(tg::canManage).executes(ctx -> {
							tg.inputs().set(ctx.getSource().getServer(), false);
							ok(ctx, "Stopped recording player inputs");
							return 1;
						}))
						.then(Commands.literal("optout").executes(ctx -> {
							tg.inputs().setOptedOut(ctx.getSource().getPlayerOrException(), true);
							ok(ctx, "Your inputs won't be recorded");
							return 1;
						}))
						.then(Commands.literal("optin").executes(ctx -> {
							tg.inputs().setOptedOut(ctx.getSource().getPlayerOrException(), false);
							ok(ctx, "Your inputs may be recorded while recording is on");
							return 1;
						})))
				.then(Commands.literal("minigame").requires(tg::canManage)
						.then(Commands.argument("template", StringArgumentType.word())
								.suggests((c, sb) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
										io.github.mudklip.minigamebots.training.scenario.Minigames.TEMPLATES, sb))
								.then(Commands.argument("arena", StringArgumentType.word()).suggests(arenaNames)
										.then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
											String template = StringArgumentType.getString(ctx, "template");
											if (!io.github.mudklip.minigamebots.training.scenario.Minigames.TEMPLATES.contains(template)) {
												fail(ctx, "Templates: " + String.join(", ",
														io.github.mudklip.minigamebots.training.scenario.Minigames.TEMPLATES));
												return 0;
											}
											Arena a = tg.arenas().get(StringArgumentType.getString(ctx, "arena"));
											if (a == null) {
												fail(ctx, "No such arena");
												return 0;
											}
											try {
												var sc = tg.scenarios().createMinigame(template, StringArgumentType.getString(ctx, "name"), a);
												ok(ctx, "Made " + template + " match " + sc.name + ": " + sc.teams.size() + " teams, "
														+ sc.rule.label + ", " + sc.timeLimit + " s. Join a team and start it from the menu"
														+ " (or /tg match join + /tg match start).");
											} catch (ArenaManager.ArenaException e) {
												fail(ctx, e.getMessage());
												return 0;
											}
											return 1;
										})))))
				.then(Commands.literal("match").requires(tg::canManage)
						.then(Commands.literal("list").executes(ctx -> {
							var list = tg.scenarios().list();
							ok(ctx, list.isEmpty() ? "No matches yet" : "Matches: " + String.join(", ", list.stream().map(m -> m.name).toList()));
							return list.size();
						}))
						.then(Commands.literal("stop").executes(ctx -> {
							tg.scenarios().stop();
							ok(ctx, "Stopped");
							return 1;
						}))
						.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("arena", StringArgumentType.word()).suggests(arenaNames).executes(ctx -> {
									Arena a = tg.arenas().get(StringArgumentType.getString(ctx, "arena"));
									if (a == null) {
										fail(ctx, "No such arena");
										return 0;
									}
									try {
										tg.scenarios().create(StringArgumentType.getString(ctx, "name"), a);
									} catch (ArenaManager.ArenaException e) {
										fail(ctx, e.getMessage());
										return 0;
									}
									ok(ctx, "Created red vs blue match " + StringArgumentType.getString(ctx, "name"));
									return 1;
								}))))
						.then(match("start", tg, (ctx, sc) -> {
							tg.scenarios().start(sc, ctx.getSource().getPlayerOrException());
							ok(ctx, "Started " + sc.name);
						}))
						.then(match("delete", tg, (ctx, sc) -> {
							tg.scenarios().delete(sc.name);
							ok(ctx, "Deleted " + sc.name);
						}))
						.then(Commands.literal("opponent").then(Commands.argument("name", StringArgumentType.word()).suggests(matchNames(tg))
								.then(Commands.argument("team", StringArgumentType.word())
										.then(Commands.argument("style", StringArgumentType.word())
												.suggests((c, sb) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
														java.util.stream.Stream.concat(DummySettings.STYLES.stream(),
																java.util.stream.Stream.of("league")), sb))
												.executes(ctx -> {
													var sc = tg.scenarios().get(StringArgumentType.getString(ctx, "name"));
													String color = StringArgumentType.getString(ctx, "team");
													String style = StringArgumentType.getString(ctx, "style");
													if (sc == null) {
														fail(ctx, "No such match");
														return 0;
													}
													if (!style.equals("league") && !DummySettings.STYLES.contains(style)) {
														fail(ctx, "Styles: " + String.join(", ", DummySettings.STYLES) + ", league");
														return 0;
													}
													for (var team : sc.teams) {
														if (team.color.equals(color)) {
															team.dummyStyle = style;
															if (team.dummies == 0) {
																team.dummies = 1;
															}
															tg.scenarios().save(sc);
															ok(ctx, team.label() + " dummies: " + style);
															return 1;
														}
													}
													fail(ctx, "No team " + color);
													return 0;
												})))))
						.then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.word()).suggests(matchNames(tg))
								.then(Commands.argument("team", StringArgumentType.word()).executes(ctx -> {
									var sc = tg.scenarios().get(StringArgumentType.getString(ctx, "name"));
									String color = StringArgumentType.getString(ctx, "team");
									if (sc == null) {
										fail(ctx, "No such match");
										return 0;
									}
									for (int t = 0; t < sc.teams.size(); t++) {
										if (sc.teams.get(t).color.equals(color)) {
											tg.scenarios().join(sc, ctx.getSource().getPlayerOrException(), t);
											ok(ctx, "Joined " + sc.teams.get(t).label());
											return 1;
										}
									}
									fail(ctx, "No team " + color);
									return 0;
								})))))
				.then(Commands.literal("dummy").requires(tg::canManage)
						.then(Commands.literal("spawn")
								.executes(ctx -> spawnDummy(ctx, tg, "L1"))
								.then(Commands.argument("style", com.mojang.brigadier.arguments.StringArgumentType.word())
										.suggests((c, sb) -> net.minecraft.commands.SharedSuggestionProvider.suggest(DummySettings.STYLES, sb))
										.executes(ctx -> spawnDummy(ctx, tg,
												com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "style")))))
						.then(Commands.literal("remove")
								.executes(ctx -> removeDummies(ctx, tg, 10))
								.then(Commands.argument("radius", DoubleArgumentType.doubleArg(0))
										.executes(ctx -> removeDummies(ctx, tg, DoubleArgumentType.getDouble(ctx, "radius")))))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> aimMode(String name, AimTrainer.Mode mode,
			TrainingGrounds tg) {
		return Commands.literal(name)
				.executes(ctx -> {
					tg.aim().start(ctx.getSource().getPlayerOrException(), mode, 30);
					return 1;
				})
				.then(Commands.argument("seconds", IntegerArgumentType.integer(5, 300)).executes(ctx -> {
					tg.aim().start(ctx.getSource().getPlayerOrException(), mode, IntegerArgumentType.getInteger(ctx, "seconds"));
					return 1;
				}));
	}

	private static SuggestionProvider<CommandSourceStack> matchNames(TrainingGrounds tg) {
		return (ctx, b) -> SharedSuggestionProvider.suggest(tg.scenarios().list().stream().map(m -> m.name), b);
	}

	private interface MatchAction {
		void run(CommandContext<CommandSourceStack> ctx, io.github.mudklip.minigamebots.training.scenario.Scenario sc)
				throws CommandSyntaxException, ArenaManager.ArenaException;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> match(String literal, TrainingGrounds tg,
			MatchAction action) {
		return Commands.literal(literal).then(Commands.argument("name", StringArgumentType.word()).suggests(matchNames(tg)).executes(ctx -> {
			var sc = tg.scenarios().get(StringArgumentType.getString(ctx, "name"));
			if (sc == null) {
				fail(ctx, "No match called " + StringArgumentType.getString(ctx, "name"));
				return 0;
			}
			try {
				action.run(ctx, sc);
			} catch (ArenaManager.ArenaException e) {
				fail(ctx, e.getMessage());
				return 0;
			}
			return 1;
		}));
	}

	private interface ArenaAction {
		void run(CommandContext<CommandSourceStack> ctx, Arena a) throws CommandSyntaxException, ArenaManager.ArenaException;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> named(String literal,
			SuggestionProvider<CommandSourceStack> names, ArenaAction action) {
		return Commands.literal(literal).then(Commands.argument("name", StringArgumentType.word()).suggests(names).executes(ctx -> {
			TrainingGrounds tg = TrainingGrounds.instance();
			Arena a = tg.arenas().get(StringArgumentType.getString(ctx, "name"));
			if (a == null) {
				fail(ctx, "No arena called " + StringArgumentType.getString(ctx, "name"));
				return 0;
			}
			try {
				action.run(ctx, a);
			} catch (ArenaManager.ArenaException e) {
				fail(ctx, e.getMessage());
				return 0;
			}
			return 1;
		}));
	}

	private static int corner(CommandContext<CommandSourceStack> ctx, TrainingGrounds tg, int i) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		tg.arenas().setCorner(p, i, p.blockPosition());
		ok(ctx, "Corner " + (i + 1) + " set to " + p.blockPosition().toShortString());
		return 1;
	}

	private static int spawnDummy(CommandContext<CommandSourceStack> ctx, TrainingGrounds tg, String style) throws CommandSyntaxException {
		String n = style.matches("[1-5]") ? "L" + style : style;
		DummySettings ds = DummySettings.style(n);
		var m = tg.dummies().spawn(ctx.getSource().getPlayerOrException(), ds);
		if (m == null) {
			fail(ctx, "Couldn't spawn a dummy here");
			return 0;
		}
		ok(ctx, "Spawned a " + ds.style + " dummy. Sneak + right-click it to change it.");
		return 1;
	}

	private static int removeDummies(CommandContext<CommandSourceStack> ctx, TrainingGrounds tg, double radius)
			throws CommandSyntaxException {
		int n = tg.dummies().removeNear(ctx.getSource().getPlayerOrException(), radius);
		ok(ctx, "Removed " + n + " dummies");
		return n;
	}

	private static void ok(CommandContext<CommandSourceStack> ctx, String text) {
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
	}

	private static void fail(CommandContext<CommandSourceStack> ctx, String text) {
		ctx.getSource().sendFailure(Component.literal(text));
	}
}
