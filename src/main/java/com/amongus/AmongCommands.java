package com.amongus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Arrays;

public final class AmongCommands {
	private AmongCommands() {}

	private static final SuggestionProvider<CommandSourceStack> COLORS =
		(ctx, b) -> SharedSuggestionProvider.suggest(AmongColor.names(), b);
	private static final SuggestionProvider<CommandSourceStack> VOTES =
		(ctx, b) -> SharedSuggestionProvider.suggest(
			Arrays.asList("skip", "red", "blue", "green", "pink", "orange", "yellow", "black", "white", "purple", "brown", "cyan", "lime"), b);
	private static final SuggestionProvider<CommandSourceStack> KEYS =
		(ctx, b) -> SharedSuggestionProvider.suggest(
			Arrays.asList("impostors", "shapeshifters", "engineers", "scientists", "tasks", "killcd", "discuss",
				"vote", "meetings", "shiftduration", "ventduration", "ventcooldown", "buttoncd", "redsword"), b);
	private static final SuggestionProvider<CommandSourceStack> TYPES =
		(ctx, b) -> SharedSuggestionProvider.suggest(Arrays.asList("click", "hold", "code"), b);

	private static ServerPlayer pl(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Game.server = c.getSource().getServer();
		return c.getSource().getPlayerOrException();
	}

	/** The block the player is looking at (within 6 blocks), or null. */
	private static BlockPos lookedAt(ServerPlayer p) {
		try {
			HitResult hr = p.pick(6.0, 1.0f, false);
			if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) return bhr.getBlockPos();
		} catch (Exception ignored) {
			// fall through
		}
		return null;
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		LiteralCommandNode<CommandSourceStack> node = d.register(Commands.literal("among")
			.executes(c -> {
				Game.menu(pl(c));
				return 1;
			})
			.then(Commands.literal("help").executes(c -> {
				Game.help(pl(c));
				return 1;
			}))
			.then(Commands.literal("status").executes(c -> {
				Game.status(pl(c));
				return 1;
			}))
			.then(Commands.literal("join").executes(c -> {
				Game.join(pl(c));
				return 1;
			}))
			.then(Commands.literal("leave").executes(c -> {
				ServerPlayer p = pl(c);
				Game.leave(p.getUUID(), false);
				return 1;
			}))
			.then(Commands.literal("start")
				.executes(c -> {
					Game.startCmd(pl(c), false);
					return 1;
				})
				.then(Commands.literal("force").executes(c -> {
					Game.startCmd(pl(c), true);
					return 1;
				})))
			.then(Commands.literal("stop").executes(c -> {
				Game.stopCmd(pl(c));
				return 1;
			}))
			.then(Commands.literal("tasks").executes(c -> {
				Game.showTasks(pl(c));
				return 1;
			}))
			.then(Commands.literal("report").executes(c -> {
				Game.reportCmd(pl(c));
				return 1;
			}))
			.then(Commands.literal("vitals").executes(c -> {
				Game.vitals(pl(c));
				return 1;
			}))
			.then(Commands.literal("code")
				.then(Commands.argument("code", IntegerArgumentType.integer(0, 9999)).executes(c -> {
					Game.enterCode(pl(c), IntegerArgumentType.getInteger(c, "code"));
					return 1;
				})))
			.then(Commands.literal("vote")
				.then(Commands.argument("target", StringArgumentType.word()).suggests(VOTES).executes(c -> {
					Game.vote(pl(c), StringArgumentType.getString(c, "target"));
					return 1;
				})))
			.then(Commands.literal("sabotage")
				.then(Commands.literal("lights").executes(c -> {
					Game.sabotage(pl(c), "lights");
					return 1;
				}))
				.then(Commands.literal("reactor").executes(c -> {
					Game.sabotage(pl(c), "reactor");
					return 1;
				})))
			.then(Commands.literal("shift")
				.executes(c -> {
					Game.shift(pl(c), null);
					return 1;
				})
				.then(Commands.argument("color", StringArgumentType.word()).suggests(COLORS).executes(c -> {
					Game.shift(pl(c), StringArgumentType.getString(c, "color"));
					return 1;
				})))
			.then(Commands.literal("set")
				.then(Commands.argument("key", StringArgumentType.word()).suggests(KEYS)
					.then(Commands.argument("value", IntegerArgumentType.integer(0, 1000)).executes(c -> {
						ServerPlayer p = pl(c);
						String msg = setting(StringArgumentType.getString(c, "key").toLowerCase(), IntegerArgumentType.getInteger(c, "value"));
						Game.tell(p.getName().getString(), msg, msg.startsWith("Unknown") || msg.startsWith("Can't") ? "red" : "green");
						return 1;
					}))))
			.then(Commands.literal("map")
				.then(Commands.literal("buildmap").executes(c -> {
					ServerPlayer p = pl(c);
					String n = p.getName().getString();
					if (Game.phase != Game.Phase.LOBBY) {
						Game.tell(n, "Can't build during a game.", "red");
						return 0;
					}
					BlockPos pos = p.blockPosition();
					Game.tell(n, "Building the map around you (70x70 blocks, overworld)... stay still.", "yellow");
					Game.map = MapBuilder.build(pos.getX(), pos.getY(), pos.getZ());
					Game.tell(n, "Map built! Everyone can now /among join. (Lobby = Cafeteria, red button = emergency meeting, iron trapdoors = vents)", "green");
					return 1;
				}))
				.then(Commands.literal("info").executes(c -> {
					ServerPlayer p = pl(c);
					Game.tell(p.getName().getString(), "Map ready=" + Game.map.ready() + " tasks=" + Game.map.tasks.size()
						+ " vents=" + Game.map.vents.size() + " lights=" + (Game.map.lights != null)
						+ " reactor=" + (Game.map.reactor != null), "yellow");
					return 1;
				}))
				.then(Commands.literal("clear").executes(c -> {
					ServerPlayer p = pl(c);
					if (Game.phase != Game.Phase.LOBBY) return 0;
					Game.map = new MapData();
					Game.map.save();
					Game.tell(p.getName().getString(), "Map data cleared (blocks stay in the world).", "yellow");
					return 1;
				}))
				.then(Commands.literal("addvent").executes(c -> {
					ServerPlayer p = pl(c);
					BlockPos b = lookedAt(p);
					if (b == null) {
						Game.tell(p.getName().getString(), "Look at a block first (within 6 blocks).", "red");
						return 0;
					}
					Game.map.vents.add(new MapData.P(b.getX(), b.getY(), b.getZ()));
					Game.map.save();
					Game.tell(p.getName().getString(), "Vent #" + Game.map.vents.size() + " added.", "green");
					return 1;
				}))
				.then(Commands.literal("addtask")
					.then(Commands.argument("type", StringArgumentType.word()).suggests(TYPES)
						.then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
							ServerPlayer p = pl(c);
							String type = StringArgumentType.getString(c, "type").toLowerCase();
							BlockPos b = lookedAt(p);
							if (b == null || !(type.equals("click") || type.equals("hold") || type.equals("code"))) {
								Game.tell(p.getName().getString(), "Look at a block, and use type click, hold or code.", "red");
								return 0;
							}
							Game.map.tasks.add(new MapData.TaskDef(StringArgumentType.getString(c, "name"), type,
								new MapData.P(b.getX(), b.getY(), b.getZ())));
							Game.map.save();
							Game.tell(p.getName().getString(), "Task added (" + Game.map.tasks.size() + " total).", "green");
							return 1;
						}))))
				.then(Commands.literal("set")
					.then(Commands.literal("lobby").executes(c -> {
						ServerPlayer p = pl(c);
						BlockPos b = p.blockPosition();
						Game.map.lobby = new MapData.P(b.getX(), b.getY(), b.getZ());
						Game.map.save();
						Game.tell(p.getName().getString(), "Lobby set to where you stand.", "green");
						return 1;
					}))
					.then(Commands.literal("button").executes(c -> setLooked(c, "button")))
					.then(Commands.literal("lights").executes(c -> setLooked(c, "lights")))
					.then(Commands.literal("reactor").executes(c -> setLooked(c, "reactor")))))
		);
		d.register(Commands.literal("au").redirect(node));
	}

	private static int setLooked(CommandContext<CommandSourceStack> c, String what) throws CommandSyntaxException {
		ServerPlayer p = pl(c);
		BlockPos b = lookedAt(p);
		if (b == null) {
			Game.tell(p.getName().getString(), "Look at a block first (within 6 blocks).", "red");
			return 0;
		}
		MapData.P pos = new MapData.P(b.getX(), b.getY(), b.getZ());
		switch (what) {
			case "button" -> Game.map.button = pos;
			case "lights" -> Game.map.lights = pos;
			default -> Game.map.reactor = pos;
		}
		Game.map.save();
		Game.tell(p.getName().getString(), "Map " + what + " set.", "green");
		return 1;
	}

	private static String setting(String key, int v) {
		if (Game.phase != Game.Phase.LOBBY) return "Can't change settings during a game.";
		switch (key) {
			case "impostors" -> Game.impostors = Math.min(v, 3);
			case "shapeshifters" -> Game.shapeshifters = Math.min(v, 3);
			case "engineers" -> Game.engineers = Math.min(v, 4);
			case "scientists" -> Game.scientists = Math.min(v, 4);
			case "tasks" -> Game.taskCount = Math.max(1, Math.min(v, 10));
			case "killcd" -> Game.killCd = Math.max(5, v);
			case "discuss" -> Game.discuss = Math.max(5, v);
			case "vote" -> Game.voteTime = Math.max(5, v);
			case "meetings" -> Game.meetings = Math.min(v, 9);
			case "shiftduration" -> Game.shiftDuration = Math.max(5, v);
			case "ventduration" -> Game.ventDuration = Math.max(1, v);
			case "ventcooldown" -> Game.ventCooldown = v;
			case "buttoncd" -> Game.buttonCd = v;
			case "redsword" -> Game.redSword = v != 0;
			default -> {
				return "Unknown setting '" + key + "'. See /among status";
			}
		}
		return "Set " + key + " = " + v;
	}
}
