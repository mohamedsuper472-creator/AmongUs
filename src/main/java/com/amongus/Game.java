package com.amongus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** All Among Us game logic. Everything runs on the server thread. */
public final class Game {
	public enum Phase { LOBBY, PLAYING, DISCUSS, VOTE }

	// ---- settings (change with /among set <key> <value>) ----
	static int impostors = 0;        // 0 = automatic
	static int shapeshifters = 1;
	static int engineers = 1;
	static int scientists = 1;
	static int taskCount = 4;
	static int killCd = 25;          // seconds
	static int discuss = 20;         // seconds
	static int voteTime = 30;        // seconds
	static int meetings = 1;         // emergency meetings per player
	static int shiftDuration = 30;   // seconds
	static int ventDuration = 7;     // engineer: seconds allowed inside the vents
	static int ventCooldown = 30;    // engineer: seconds to wait before venting again
	static int buttonCd = 15;        // emergency button cooldown (seconds), at game start and after every meeting
	static boolean redSword = true;

	// ---- state ----
	static MinecraftServer server;
	static MapData map = MapData.load();
	static Phase phase = Phase.LOBBY;
	static final Map<UUID, PState> players = new LinkedHashMap<>();
	static final Set<String> dirtyNames = new HashSet<>();
	static final List<Body> bodies = new ArrayList<>();
	static UUID host;
	static boolean testMode;
	static boolean lightsOut;
	static int reactorTicks;
	static int buttonLeft;
	static int phaseTicks;
	static int tickN;
	private static final Random RNG = new Random();

	static final class TaskRef {
		final int idx;
		boolean done;

		TaskRef(int idx) {
			this.idx = idx;
		}
	}

	static final class TaskRun {
		final TaskRef ref;
		final String type;
		int timer;
		int clicks;
		int progress;
		int code;

		TaskRun(TaskRef ref, String type) {
			this.ref = ref;
			this.type = type;
		}
	}

	static final class Body {
		final int color;
		final double x;
		final double y;
		final double z;

		Body(int color, double x, double y, double z) {
			this.color = color;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	static final class PState {
		final UUID id;
		final String name;
		int color = -1;
		Role role = Role.CREWMATE;
		boolean alive = true;
		int killCd;
		int sabCd;
		int shiftLeft;
		int shiftCd;
		int shiftColor = -1;
		int ventLeft;
		int ventCd;
		int hopCd;
		int meetingsLeft;
		int vitalsLeft;
		int votedFor = -2; // -2 = not voted, -1 = skip, else color index
		final List<TaskRef> tasks = new ArrayList<>();
		TaskRun run;

		PState(UUID id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	// =====================================================================
	// helpers
	// =====================================================================

	static void cmd(String c) {
		if (server == null) return;
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), c);
		} catch (Exception e) {
			AmongUsMod.LOGGER.warn("Command failed: {}", c, e);
		}
	}

	static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	static String txt(String s, String color) {
		return "{\"text\":\"" + esc(s) + "\",\"color\":\"" + color + "\"}";
	}

	static String btn(String label, String color, String command) {
		return "{\"text\":\"[" + esc(label) + "] \",\"color\":\"" + color
			+ "\",\"click_event\":{\"action\":\"run_command\",\"command\":\"" + esc(command) + "\"}}";
	}

	static void raw(String name, String json) {
		cmd("tellraw " + name + " " + json);
	}

	static void tell(String name, String s, String color) {
		raw(name, txt(s, color));
	}

	static void tellAll(String s, String color) {
		for (PState p : players.values()) tell(p.name, s, color);
	}

	static void bar(String name, String s, String color) {
		cmd("title " + name + " actionbar " + txt(s, color));
	}

	static void title(String name, String main, String sub, String color) {
		cmd("title " + name + " times 10 70 20");
		cmd("title " + name + " subtitle " + txt(sub, "white"));
		cmd("title " + name + " title " + txt(main, color));
	}

	static String f(double d) {
		return String.format(Locale.ROOT, "%.2f", d);
	}

	static void tp(String name, double x, double y, double z) {
		cmd("tp " + name + " " + f(x) + " " + f(y) + " " + f(z));
	}

	static String label(PState p) {
		return p.color >= 0 ? AmongColor.label(p.color) : p.name;
	}

	static ServerPlayer find(UUID id) {
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	static boolean hasArmor(ServerPlayer sp) {
		return !sp.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
			|| !sp.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
			|| !sp.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
			|| !sp.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}

	static List<PState> alive() {
		List<PState> l = new ArrayList<>();
		for (PState p : players.values()) if (p.alive) l.add(p);
		return l;
	}

	static PState byColor(int c) {
		for (PState p : players.values()) if (p.color == c) return p;
		return null;
	}

	static void dress(String name, int rgb) {
		String c = "[dyed_color=" + rgb + ",unbreakable={}]";
		cmd("item replace entity " + name + " armor.head with minecraft:leather_helmet" + c);
		cmd("item replace entity " + name + " armor.chest with minecraft:leather_chestplate" + c);
		cmd("item replace entity " + name + " armor.legs with minecraft:leather_leggings" + c);
		cmd("item replace entity " + name + " armor.feet with minecraft:leather_boots" + c);
	}

	static int shownColor(PState p) {
		return p.shiftLeft > 0 && p.shiftColor >= 0 ? p.shiftColor : p.color;
	}

	static void cleanupPlayer(String name, boolean adventure) {
		cmd("effect clear " + name);
		cmd("clear " + name + " minecraft:netherite_sword");
		cmd("item replace entity " + name + " armor.head with minecraft:air");
		cmd("item replace entity " + name + " armor.chest with minecraft:air");
		cmd("item replace entity " + name + " armor.legs with minecraft:air");
		cmd("item replace entity " + name + " armor.feet with minecraft:air");
		cmd("team leave " + name);
		cmd("gamemode " + (adventure ? "adventure " : "survival ") + name);
		if (!adventure) cmd("tag " + name + " remove amongus_in");
	}

	static void resetWorldState() {
		bodies.clear();
		cmd("kill @e[type=minecraft:armor_stand,tag=amongus_body]");
		cmd("bossbar remove amongus:tasks");
		lightsOut = false;
		reactorTicks = 0;
		phase = Phase.LOBBY;
		testMode = false;
	}

	// =====================================================================
	// lobby: join / leave / menu
	// =====================================================================

	static void menu(ServerPlayer sp) {
		String n = sp.getName().getString();
		raw(n, txt("=== AMONG US ===", "gold"));
		raw(n, "[" + btn("JOIN", "green", "/among join") + "," + btn("LEAVE", "red", "/among leave") + ","
			+ btn("START", "yellow", "/among start") + "," + btn("STATUS", "aqua", "/among status") + ","
			+ btn("HELP", "white", "/among help") + "]");
	}

	static void help(ServerPlayer sp) {
		String n = sp.getName().getString();
		String[] lines = {
			"/among join | leave | start [force] | stop | status | tasks",
			"/among report - report a nearby body (or right-click it)",
			"/among vote <color|skip> - during voting (buttons appear in chat)",
			"/among code <number> - for 'code' tasks",
			"/among sabotage lights|reactor - impostors",
			"/among shift <color> - shapeshifter | /among vitals - scientist",
			"/among set <key> <value> - settings (see /among status)",
			"/among map buildmap - builds the whole map where you stand",
			"Emergency meeting = press the red button in the middle of the Cafeteria.",
			"Tasks = lecterns. Vents = iron trapdoors on the floor: just right-click them (sneak+click = go back)."
		};
		for (String l : lines) tell(n, l, "gray");
	}

	static void status(ServerPlayer sp) {
		String n = sp.getName().getString();
		tell(n, "Phase: " + phase + " | Players: " + players.size() + "/" + AmongColor.NAMES.length
			+ " | Map ready: " + map.ready(), "yellow");
		tell(n, "impostors=" + impostors + " (0=auto) shapeshifters=" + shapeshifters + " engineers=" + engineers
			+ " scientists=" + scientists + " tasks=" + taskCount, "gray");
		tell(n, "killcd=" + killCd + " discuss=" + discuss + " vote=" + voteTime + " meetings=" + meetings
			+ " shiftduration=" + shiftDuration + " ventduration=" + ventDuration + " ventcooldown=" + ventCooldown + " buttoncd=" + buttonCd + " redsword=" + (redSword ? 1 : 0), "gray");
	}

	static void join(ServerPlayer sp) {
		String name = sp.getName().getString();
		if (phase != Phase.LOBBY) {
			tell(name, "A game is running. Wait for it to end.", "red");
			return;
		}
		if (players.containsKey(sp.getUUID())) {
			tell(name, "You already joined.", "yellow");
			return;
		}
		if (players.size() >= AmongColor.NAMES.length) {
			tell(name, "The game is full (" + AmongColor.NAMES.length + ").", "red");
			return;
		}
		if (map.lobby == null) {
			tell(name, "No map yet. Stand in an open flat area and run /among map buildmap", "red");
			return;
		}
		if (hasArmor(sp)) {
			tell(name, "Take off your armor first (it would be replaced during the game).", "red");
			return;
		}
		PState p = new PState(sp.getUUID(), name);
		players.put(sp.getUUID(), p);
		if (host == null) host = p.id;
		cmd("tag " + name + " add amongus_in");
		cmd("gamemode adventure " + name);
		tp(name, map.lobby.cx(), map.lobby.y, map.lobby.cz());
		tellAll(name + " joined (" + players.size() + "/" + AmongColor.NAMES.length + ")", "green");
		if (p.id.equals(host)) {
			tell(name, "You are the host. Start the game with /among start when everyone joined.", "gold");
		}
	}

	static void leave(UUID id, boolean disconnected) {
		PState p = players.get(id);
		if (p == null) return;
		if (phase != Phase.LOBBY && p.alive) {
			p.alive = false;
			tellAll(label(p) + " left the game.", "gray");
		}
		players.remove(id);
		if (disconnected) dirtyNames.add(p.name);
		else cleanupPlayer(p.name, false);
		if (p.id.equals(host)) {
			host = players.isEmpty() ? null : players.keySet().iterator().next();
			if (host != null) {
				PState h = players.get(host);
				tellAll(h.name + " is now the host.", "gold");
			}
		}
		if (players.isEmpty()) {
			resetWorldState();
			return;
		}
		if (phase != Phase.LOBBY) {
			updateBar();
			checkWin();
		}
	}

	/** Called when any player joins the server. */
	static void onServerJoin(ServerPlayer sp) {
		String name = sp.getName().getString();
		if (dirtyNames.remove(name)) {
			cleanupPlayer(name, false);
		}
		if (map.ready()) {
			raw(name, "[" + txt("Among Us is on this server!  ", "gold") + "," + btn("CLICK TO JOIN", "green", "/among join") + "]");
		}
	}

	// =====================================================================
	// start / stop
	// =====================================================================

	static void startCmd(ServerPlayer sp, boolean force) {
		String name = sp.getName().getString();
		if (host != null && !sp.getUUID().equals(host)) {
			tell(name, "Only the host (" + players.get(host).name + ") can start the game.", "red");
			return;
		}
		String err = start(force);
		if (err != null) tell(name, err, "red");
	}

	static String start(boolean force) {
		if (phase != Phase.LOBBY) return "A game is already running.";
		if (!map.ready()) return "No map yet. Run /among map buildmap first.";
		int n = players.size();
		if (n < 1) return "Nobody joined. Use /among join.";
		if (n < 3 && !force) return "Need at least 3 players. (/among start force = solo test mode)";
		for (PState p : players.values()) {
			ServerPlayer sp = find(p.id);
			if (sp == null) return p.name + " is offline.";
			if (hasArmor(sp)) return p.name + " must take off their armor first.";
		}

		testMode = n < 3;
		List<PState> list = new ArrayList<>(players.values());
		Collections.shuffle(list, RNG);

		List<Integer> cols = new ArrayList<>();
		for (int i = 0; i < AmongColor.NAMES.length; i++) cols.add(i);
		Collections.shuffle(cols, RNG);

		for (int i = 0; i < n; i++) {
			PState p = list.get(i);
			p.color = cols.get(i);
			p.role = Role.CREWMATE;
			p.alive = true;
			p.tasks.clear();
			p.run = null;
			p.killCd = 10 * 20;
			p.sabCd = 15 * 20;
			p.shiftLeft = 0;
			p.shiftCd = 0;
			p.shiftColor = -1;
			p.ventLeft = 0;
			p.ventCd = 0;
			p.hopCd = 0;
			p.meetingsLeft = meetings;
			p.vitalsLeft = 3;
			p.votedFor = -2;
		}

		int nImp = impostors > 0 ? impostors : (n >= 7 ? 2 : 1);
		if (n < 3) nImp = n == 2 ? 1 : 0;
		else nImp = Math.min(nImp, (n - 1) / 2);
		int nShape = Math.min(shapeshifters, nImp);
		int idx = 0;
		for (int i = 0; i < nImp; i++) {
			list.get(idx++).role = i < nShape ? Role.SHAPESHIFTER : Role.IMPOSTOR;
		}
		int nEng = Math.min(engineers, n - idx);
		for (int i = 0; i < nEng; i++) {
			PState p = list.get(idx++);
			p.role = Role.ENGINEER;
		}
		int nSci = Math.min(scientists, n - idx);
		for (int i = 0; i < nSci; i++) list.get(idx++).role = Role.SCIENTIST;

		// tasks for crew
		for (PState p : list) {
			if (p.role.impostor) continue;
			List<Integer> all = new ArrayList<>();
			for (int i = 0; i < map.tasks.size(); i++) all.add(i);
			Collections.shuffle(all, RNG);
			int take = Math.min(taskCount, all.size());
			for (int i = 0; i < take; i++) p.tasks.add(new TaskRef(all.get(i)));
		}

		// world setup
		bodies.clear();
		cmd("kill @e[type=minecraft:armor_stand,tag=amongus_body]");
		lightsOut = false;
		reactorTicks = 0;
		cmd("team add among_hide");
		cmd("team modify among_hide nametagVisibility never");
		cmd("team modify among_hide collisionRule never");

		for (PState p : list) {
			cmd("effect clear " + p.name);
			cmd("tag " + p.name + " add amongus_in");
			cmd("team join among_hide " + p.name);
			cmd("gamemode adventure " + p.name);
			cmd("effect give " + p.name + " minecraft:invisibility infinite 0 true");
			cmd("effect give " + p.name + " minecraft:saturation infinite 0 true");
			cmd("effect give " + p.name + " minecraft:resistance infinite 4 true");
			dress(p.name, AmongColor.RGB[p.color]);
			if (p.role.impostor) giveSword(p.name);
		}
		gather(5.0);

		cmd("bossbar remove amongus:tasks");
		cmd("bossbar add amongus:tasks " + txt("Total Tasks Completed", "green"));
		cmd("bossbar set amongus:tasks color green");
		cmd("bossbar set amongus:tasks style notched_10");
		cmd("bossbar set amongus:tasks max 100");
		cmd("bossbar set amongus:tasks value 0");
		cmd("bossbar set amongus:tasks players @a[tag=amongus_in]");

		phase = Phase.PLAYING;
		buttonLeft = buttonCd * 20;

		for (PState p : list) {
			title(p.name, p.role.display.toUpperCase(Locale.ROOT), "You are " + AmongColor.label(p.color), p.role.color);
			tell(p.name, "You are the " + p.role.display + " (" + AmongColor.label(p.color) + "). " + p.role.desc, p.role.color);
			if (p.role.impostor) {
				StringBuilder sb = new StringBuilder();
				for (PState o : list) {
					if (o != p && o.role.impostor) {
						if (sb.length() > 0) sb.append(", ");
						sb.append(AmongColor.label(o.color)).append(" (").append(o.name).append(")");
					}
				}
				tell(p.name, sb.length() == 0 ? "You are the only impostor." : "Fellow impostors: " + sb, "red");
			} else {
				tell(p.name, "Your tasks: /among tasks", "gray");
			}
		}
		if (testMode) tellAll("TEST MODE: fewer than 3 players, the game will not end by itself. Use /among stop.", "yellow");
		return null;
	}

	static void giveSword(String name) {
		String model = redSword ? ",item_model=\"amongus:red_sword\"" : "";
		cmd("give " + name + " minecraft:netherite_sword[item_name={text:\"Impostor Blade\",color:\"red\"},"
			+ "unbreakable={},enchantment_glint_override=true" + model + "]");
	}

	static void stopCmd(ServerPlayer sp) {
		String name = sp.getName().getString();
		if (host != null && !sp.getUUID().equals(host)) {
			tell(name, "Only the host can stop the game.", "red");
			return;
		}
		if (phase == Phase.LOBBY) {
			tell(name, "No game is running.", "yellow");
			return;
		}
		endGame(null, "The host stopped the game.");
	}

	/** Teleport all alive players in a circle around the emergency button. */
	static void gather(double radius) {
		List<PState> al = alive();
		int n = al.size();
		MapData.P c = map.button;
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / Math.max(n, 1);
			double x = c.cx() + radius * Math.cos(a);
			double z = c.cz() + radius * Math.sin(a);
			double floorY = map.lobby != null ? map.lobby.y : c.y;
			cmd("tp " + al.get(i).name + " " + f(x) + " " + f(floorY) + " " + f(z)
				+ " facing " + f(c.cx()) + " " + f(c.y) + " " + f(c.cz()));
		}
	}

	// =====================================================================
	// main tick
	// =====================================================================

	static void tick(MinecraftServer s) {
		server = s;
		if (players.isEmpty()) return;
		tickN++;

		if (phase == Phase.LOBBY) {
			if (tickN % 100 == 0 && host != null) {
				PState h = players.get(host);
				for (PState p : players.values()) {
					bar(p.name, "Lobby: " + players.size() + " player(s). Host (" + (h == null ? "?" : h.name) + ") starts with /among start", "yellow");
				}
			}
			return;
		}

		if (phase == Phase.DISCUSS) {
			if (--phaseTicks <= 0) beginVoting();
			else if (tickN % 20 == 0) {
				for (PState p : alive()) bar(p.name, "Discussion: " + (phaseTicks / 20) + "s - talk in chat!", "yellow");
			}
			return;
		}
		if (phase == Phase.VOTE) {
			phaseTicks--;
			boolean all = true;
			for (PState p : alive()) if (p.votedFor == -2) all = false;
			if (all || phaseTicks <= 0) {
				tally();
			} else if (tickN % 20 == 0) {
				for (PState p : alive()) {
					if (p.votedFor == -2) bar(p.name, "Vote now! " + (phaseTicks / 20) + "s left", "gold");
				}
			}
			return;
		}

		// PLAYING
		if (buttonLeft > 0) buttonLeft--;
		for (PState p : new ArrayList<>(players.values())) {
			if (phase != Phase.PLAYING) break;
			if (!p.alive) continue;
			ServerPlayer sp = find(p.id);
			if (sp == null) continue;

			if (p.killCd > 0) p.killCd--;
			if (p.sabCd > 0) p.sabCd--;
			if (p.hopCd > 0) p.hopCd--;
			if (p.ventLeft > 0) {
				p.ventLeft--;
				if (p.ventLeft == 0) endVent(p);
				else if (tickN % 10 == 0) bar(p.name, "In vent: " + (p.ventLeft / 20 + 1) + "s left (click a vent to hop)", "gray");
			} else if (p.ventCd > 0) {
				p.ventCd--;
			}
			if (p.shiftLeft > 0) {
				p.shiftLeft--;
				if (p.shiftLeft == 0) unshift(p);
			} else if (p.shiftCd > 0) {
				p.shiftCd--;
			}

			if (p.run != null) runTask(p, sp);

			if (tickN % 40 == 0) {
				if (sp.getItemBySlot(EquipmentSlot.HEAD).isEmpty() || sp.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
					|| sp.getItemBySlot(EquipmentSlot.LEGS).isEmpty() || sp.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
					dress(p.name, AmongColor.RGB[shownColor(p)]);
				}
			}
			if (tickN % 10 == 0 && p.run == null && p.ventLeft == 0) hud(p);
		}

		if (reactorTicks > 0 && phase == Phase.PLAYING) {
			reactorTicks--;
			if (tickN % 10 == 0) {
				for (PState p : alive()) {
					if (!p.role.impostor) bar(p.name, "REACTOR MELTDOWN in " + (reactorTicks / 20) + "s! Click the reactor panel (Reactor room)", "red");
				}
			}
			if (reactorTicks == 0) {
				if (!testMode) endGame(false, "The reactor melted down!");
				else tellAll("Reactor meltdown (test mode, game continues).", "red");
			}
		}
	}

	static void hud(PState p) {
		if (p.role.impostor) {
			String s = "Kill: " + (p.killCd > 0 ? (p.killCd / 20 + 1) + "s" : "READY")
				+ "  |  Sabotage: " + (p.sabCd > 0 ? (p.sabCd / 20 + 1) + "s" : "READY");
			if (p.role == Role.SHAPESHIFTER) {
				s += "  |  Shift: " + (p.shiftLeft > 0 ? "ACTIVE " + (p.shiftLeft / 20) + "s"
					: p.shiftCd > 0 ? (p.shiftCd / 20 + 1) + "s" : "READY");
			}
			bar(p.name, s, "red");
		} else {
			int done = 0;
			for (TaskRef t : p.tasks) if (t.done) done++;
			String s = "Tasks: " + done + "/" + p.tasks.size();
			if (p.role == Role.ENGINEER) s += "  |  Vent: " + (p.ventCd > 0 ? (p.ventCd / 20 + 1) + "s" : "READY");
			if (p.role == Role.SCIENTIST) s += "  |  Vitals left: " + p.vitalsLeft;
			bar(p.name, s, "green");
		}
	}

	// =====================================================================
	// tasks
	// =====================================================================

	static boolean taskClick(PState p, BlockPos pos) {
		for (int i = 0; i < map.tasks.size(); i++) {
			MapData.TaskDef def = map.tasks.get(i);
			if (!def.pos.is(pos)) continue;
			if (p.role.impostor) {
				bar(p.name, "Fake task: " + def.name + " (impostors can't finish tasks)", "gray");
				return true;
			}
			TaskRef ref = null;
			for (TaskRef t : p.tasks) if (t.idx == i) ref = t;
			if (ref == null) {
				bar(p.name, "That is not one of your tasks.", "gray");
				return true;
			}
			if (ref.done) {
				bar(p.name, "You already finished: " + def.name, "gray");
				return true;
			}
			if (p.run != null && p.run.ref == ref) {
				if ("click".equals(def.type)) {
					p.run.clicks++;
					bar(p.name, def.name + ": " + p.run.clicks + "/5 clicks", "aqua");
					if (p.run.clicks >= 5) completeTask(p, ref, def);
				}
				return true;
			}
			TaskRun run = new TaskRun(ref, def.type);
			p.run = run;
			switch (def.type) {
				case "click" -> {
					run.clicks = 1;
					run.timer = 200;
					bar(p.name, def.name + ": click the station 5 times in 10 seconds (1/5)", "aqua");
				}
				case "hold" -> {
					run.timer = 100;
					bar(p.name, def.name + ": stay close to the station for 5 seconds...", "aqua");
				}
				default -> {
					run.code = 1000 + RNG.nextInt(9000);
					run.timer = 400;
					tell(p.name, def.name + ": type /among code " + run.code + " within 20 seconds", "aqua");
					title(p.name, "CODE: " + run.code, "/among code " + run.code, "aqua");
				}
			}
			return true;
		}
		return false;
	}

	static void runTask(PState p, ServerPlayer sp) {
		TaskRun run = p.run;
		MapData.TaskDef def = map.tasks.get(run.ref.idx);
		if ("hold".equals(run.type)) {
			double dx = sp.getX() - def.pos.cx();
			double dy = sp.getY() - def.pos.y;
			double dz = sp.getZ() - def.pos.cz();
			if (dx * dx + dy * dy + dz * dz > 16.0) {
				p.run = null;
				bar(p.name, "Task cancelled - you moved away.", "red");
				return;
			}
			run.progress++;
			if (run.progress % 10 == 0) bar(p.name, def.name + ": " + (run.progress) + "%", "aqua");
			if (run.progress >= 100) completeTask(p, run.ref, def);
			return;
		}
		if (--run.timer <= 0) {
			p.run = null;
			bar(p.name, "Task failed (too slow). Try again.", "red");
		}
	}

	static void enterCode(ServerPlayer sp, int code) {
		PState p = players.get(sp.getUUID());
		if (p == null || phase != Phase.PLAYING || !p.alive) return;
		if (p.run == null || !"code".equals(p.run.type)) {
			tell(p.name, "You don't have a code task active. Click a task station first.", "gray");
			return;
		}
		if (p.run.code != code) {
			tell(p.name, "Wrong code!", "red");
			return;
		}
		completeTask(p, p.run.ref, map.tasks.get(p.run.ref.idx));
	}

	static void completeTask(PState p, TaskRef ref, MapData.TaskDef def) {
		ref.done = true;
		p.run = null;
		tell(p.name, "Task complete: " + def.name, "green");
		cmd("playsound minecraft:entity.experience_orb.pickup master " + p.name);
		updateBar();
		checkWin();
	}

	static void showTasks(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase == Phase.LOBBY) {
			tell(n, "No game running.", "gray");
			return;
		}
		if (p.role.impostor) {
			tell(n, "Impostors have no real tasks. Fake some tasks to blend in! Task stations are lecterns.", "red");
			return;
		}
		tell(n, "Your tasks:", "gold");
		for (TaskRef t : p.tasks) {
			MapData.TaskDef d = map.tasks.get(t.idx);
			tell(n, (t.done ? "[done] " : "[todo] ") + d.name + " (" + d.pos.x + ", " + d.pos.y + ", " + d.pos.z + ")",
				t.done ? "green" : "yellow");
		}
	}

	static void updateBar() {
		int total = 0;
		int done = 0;
		for (PState p : players.values()) {
			if (p.role.impostor || !p.alive) continue;
			for (TaskRef t : p.tasks) {
				total++;
				if (t.done) done++;
			}
		}
		int v = total == 0 ? 0 : done * 100 / total;
		cmd("bossbar set amongus:tasks value " + v);
	}

	// =====================================================================
	// kill / bodies / report
	// =====================================================================

	static InteractionResult onAttack(Player player, Level world, InteractionHand hand, Entity target) {
		if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
		PState a = players.get(sp.getUUID());
		if (a == null) return InteractionResult.PASS;
		if (phase == Phase.PLAYING && a.alive && a.role.impostor && target instanceof ServerPlayer tp
			&& sp.getMainHandItem().is(Items.NETHERITE_SWORD)) {
			PState v = players.get(tp.getUUID());
			if (v != null && v.alive && !v.role.impostor) {
				if (a.killCd > 0) {
					bar(a.name, "Kill cooldown: " + (a.killCd / 20 + 1) + "s", "red");
				} else if (sp.distanceToSqr(tp) > 16.0) {
					bar(a.name, "Too far away.", "red");
				} else {
					kill(a, v, tp);
				}
			}
		}
		return InteractionResult.FAIL;
	}

	static void kill(PState killer, PState victim, ServerPlayer victimPlayer) {
		double x = victimPlayer.getX();
		double y = victimPlayer.getY();
		double z = victimPlayer.getZ();
		victim.alive = false;
		victim.run = null;
		killer.killCd = killCd * 20;

		bodies.add(new Body(victim.color, x, y, z));
		int rgb = AmongColor.RGB[victim.color];
		String piece = ",count:1,components:{\"minecraft:dyed_color\":" + rgb + "}}";
		cmd("summon minecraft:armor_stand " + f(x) + " " + f(y) + " " + f(z)
			+ " {Tags:[\"amongus_body\"],ShowArms:1b,NoBasePlate:1b,"
			+ "Pose:{Head:[25f,0f,0f],Body:[10f,0f,0f],LeftArm:[-20f,0f,-10f],RightArm:[-20f,0f,10f],LeftLeg:[0f,0f,-5f],RightLeg:[0f,0f,5f]},"
			+ "equipment:{head:{id:\"minecraft:leather_helmet\"" + piece
			+ ",chest:{id:\"minecraft:leather_chestplate\"" + piece
			+ ",legs:{id:\"minecraft:leather_leggings\"" + piece
			+ ",feet:{id:\"minecraft:leather_boots\"" + piece + "}}");

		cmd("gamemode spectator " + victim.name);
		title(victim.name, "YOU DIED", "You were killed. You are a ghost now.", "red");
		tp(killer.name, x, y, z);
		updateBar();
		checkWin();
	}

	static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.PASS;
		}
		PState p = players.get(sp.getUUID());
		if (p == null || phase != Phase.PLAYING || !p.alive) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		if (map.button != null && map.button.is(pos)) {
			emergency(p);
			return InteractionResult.PASS;
		}
		if (map.lights != null && map.lights.is(pos)) {
			fixLights(p);
			return InteractionResult.SUCCESS;
		}
		if (map.reactor != null && map.reactor.is(pos)) {
			fixReactor(p);
			return InteractionResult.SUCCESS;
		}
		for (int i = 0; i < map.vents.size(); i++) {
			if (map.vents.get(i).is(pos)) {
				ventUse(p, sp, i);
				return InteractionResult.SUCCESS;
			}
		}
		if (taskClick(p, pos)) return InteractionResult.SUCCESS;
		return InteractionResult.PASS;
	}

	static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity) {
		if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.PASS;
		}
		PState p = players.get(sp.getUUID());
		if (p == null) return InteractionResult.PASS;
		if (entity.getTags().contains("amongus_body")) {
			if (phase == Phase.PLAYING && p.alive) reportNear(p, sp);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	static void reportCmd(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		if (p == null || phase != Phase.PLAYING || !p.alive) {
			tell(sp.getName().getString(), "You can't report right now.", "red");
			return;
		}
		reportNear(p, sp);
	}

	static void reportNear(PState p, ServerPlayer sp) {
		Body best = null;
		double bd = 64.0; // 8 blocks squared
		for (Body b : bodies) {
			double dx = sp.getX() - b.x;
			double dy = sp.getY() - b.y;
			double dz = sp.getZ() - b.z;
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bd) {
				bd = d;
				best = b;
			}
		}
		if (best == null) {
			bar(p.name, "No body nearby.", "gray");
			return;
		}
		startMeeting(p, best.color);
	}

	// =====================================================================
	// vents / sabotage / abilities
	// =====================================================================

	static void ventUse(PState p, ServerPlayer sp, int i) {
		boolean imp = p.role.impostor;
		if (!imp && p.role != Role.ENGINEER) {
			bar(p.name, "Only impostors and engineers can use vents.", "red");
			return;
		}
		if (p.hopCd > 0) return;
		if (!imp && p.ventLeft <= 0) {
			if (p.ventCd > 0) {
				bar(p.name, "Vent cooldown: " + (p.ventCd / 20 + 1) + "s", "red");
				return;
			}
			p.ventLeft = ventDuration * 20; // start the vent session
			tell(p.name, "You entered the vents. You have " + ventDuration + " seconds - click vents to hop.", "gray");
		}
		int n = map.vents.size();
		int next = sp.isShiftKeyDown() ? (i - 1 + n) % n : (i + 1) % n;
		MapData.P dest = map.vents.get(next);
		p.hopCd = 20;
		tp(p.name, dest.cx(), dest.y + 0.1, dest.cz());
		if (imp) bar(p.name, "Vent " + (next + 1) + "/" + n + " (click = next, sneak+click = previous)", "gray");
	}

	/** Engineer leaves the vents (time ran out or a meeting started): cooldown begins. */
	static void endVent(PState p) {
		p.ventLeft = 0;
		p.ventCd = ventCooldown * 20;
		bar(p.name, "You left the vents. Vent cooldown: " + ventCooldown + "s", "gray");
	}

	static void sabotage(ServerPlayer sp, String kind) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.PLAYING || !p.alive || !p.role.impostor) {
			tell(n, "Only living impostors can sabotage during the game.", "red");
			return;
		}
		if (p.sabCd > 0) {
			tell(n, "Sabotage cooldown: " + (p.sabCd / 20 + 1) + "s", "red");
			return;
		}
		if (lightsOut || reactorTicks > 0) {
			tell(n, "A sabotage is already active.", "red");
			return;
		}
		if ("lights".equals(kind)) {
			if (map.lights == null) {
				tell(n, "This map has no lights panel.", "red");
				return;
			}
			lightsOut = true;
			for (PState c : alive()) {
				if (!c.role.impostor) cmd("effect give " + c.name + " minecraft:blindness infinite 0 true");
			}
			for (PState c : players.values()) {
				if (!c.role.impostor) title(c.name, "LIGHTS OUT", "Fix them at the Electrical panel", "red");
			}
		} else {
			if (map.reactor == null) {
				tell(n, "This map has no reactor panel.", "red");
				return;
			}
			reactorTicks = 45 * 20;
			for (PState c : players.values()) {
				if (!c.role.impostor) title(c.name, "REACTOR MELTDOWN", "Click the panel in the Reactor room!", "red");
			}
		}
		p.sabCd = 30 * 20;
	}

	static void fixLights(PState p) {
		if (!lightsOut) {
			bar(p.name, "The lights are fine.", "gray");
			return;
		}
		if (p.role.impostor) {
			bar(p.name, "Impostors can't fix sabotage.", "red");
			return;
		}
		lightsOut = false;
		clearBlind();
		tellAll("Lights fixed by " + label(p) + ".", "green");
	}

	static void clearBlind() {
		for (PState c : players.values()) cmd("effect clear " + c.name + " minecraft:blindness");
	}

	static void fixReactor(PState p) {
		if (reactorTicks <= 0) {
			bar(p.name, "The reactor is stable.", "gray");
			return;
		}
		if (p.role.impostor) {
			bar(p.name, "Impostors can't fix sabotage.", "red");
			return;
		}
		reactorTicks = 0;
		tellAll("Reactor stabilised by " + label(p) + ".", "green");
	}

	static void shift(ServerPlayer sp, String colorName) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.PLAYING || !p.alive || p.role != Role.SHAPESHIFTER) {
			tell(n, "Only a living Shapeshifter can do that during the game.", "red");
			return;
		}
		if (colorName == null) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (PState o : alive()) {
				if (o == p) continue;
				if (!first) sb.append(",");
				first = false;
				sb.append(btn(AmongColor.label(o.color), AmongColor.hex(o.color), "/among shift " + AmongColor.NAMES[o.color]));
			}
			sb.append("]");
			if (first) tell(n, "Nobody to copy.", "gray");
			else raw(n, sb.toString());
			return;
		}
		if (p.shiftLeft > 0) {
			tell(n, "You are already shifted.", "red");
			return;
		}
		if (p.shiftCd > 0) {
			tell(n, "Shift cooldown: " + (p.shiftCd / 20 + 1) + "s", "red");
			return;
		}
		int ci = AmongColor.indexOf(colorName);
		PState t = ci < 0 ? null : byColor(ci);
		if (t == null || !t.alive || t == p) {
			tell(n, "Pick a living player's color.", "red");
			return;
		}
		p.shiftColor = ci;
		p.shiftLeft = shiftDuration * 20;
		dress(p.name, AmongColor.RGB[ci]);
		tell(n, "You now look like " + AmongColor.label(ci) + " for " + shiftDuration + "s.", "gold");
	}

	static void unshift(PState p) {
		p.shiftLeft = 0;
		p.shiftCd = 20 * 20;
		p.shiftColor = -1;
		if (p.alive) {
			dress(p.name, AmongColor.RGB[p.color]);
			tell(p.name, "You returned to your own color.", "gray");
		}
	}

	static void vitals(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.PLAYING || !p.alive || p.role != Role.SCIENTIST) {
			tell(n, "Only a living Scientist can do that during the game.", "red");
			return;
		}
		if (p.vitalsLeft <= 0) {
			tell(n, "No vitals uses left.", "red");
			return;
		}
		p.vitalsLeft--;
		tell(n, "--- VITALS (" + p.vitalsLeft + " uses left) ---", "aqua");
		for (PState o : players.values()) {
			tell(n, AmongColor.label(o.color) + ": " + (o.alive ? "ALIVE" : "DEAD"), o.alive ? "green" : "red");
		}
	}

	// =====================================================================
	// meetings and voting
	// =====================================================================

	static void emergency(PState p) {
		if (buttonLeft > 0) {
			bar(p.name, "The emergency button is recharging: " + (buttonLeft / 20 + 1) + "s", "red");
			return;
		}
		if (reactorTicks > 0) {
			bar(p.name, "You can't call a meeting during a reactor meltdown!", "red");
			return;
		}
		if (p.meetingsLeft <= 0) {
			bar(p.name, "You have no emergency meetings left.", "red");
			return;
		}
		p.meetingsLeft--;
		startMeeting(p, -1);
	}

	static void startMeeting(PState caller, int bodyColor) {
		phase = Phase.DISCUSS;
		phaseTicks = discuss * 20;
		lightsOut = false;
		reactorTicks = 0;
		clearBlind();
		bodies.clear();
		cmd("kill @e[type=minecraft:armor_stand,tag=amongus_body]");
		for (PState p : players.values()) {
			p.run = null;
			p.votedFor = -2;
			if (p.shiftLeft > 0) unshift(p);
			if (p.ventLeft > 0) endVent(p);
		}
		gather(4.0);
		for (PState p : alive()) {
			cmd("effect give " + p.name + " minecraft:slowness infinite 10 true");
		}
		String head = bodyColor >= 0 ? "BODY REPORTED" : "EMERGENCY MEETING";
		String sub = bodyColor >= 0 ? label(caller) + " found the body of " + AmongColor.label(bodyColor)
			: label(caller) + " pressed the button";
		for (PState p : players.values()) {
			title(p.name, head, sub, "red");
			tell(p.name, head + ": " + sub + ". Discuss in chat! Voting starts in " + discuss + "s.", "gold");
		}
	}

	static void beginVoting() {
		phase = Phase.VOTE;
		phaseTicks = voteTime * 20;
		StringBuilder sb = new StringBuilder("[");
		for (PState o : alive()) {
			sb.append(btn(AmongColor.label(o.color), AmongColor.hex(o.color), "/among vote " + AmongColor.NAMES[o.color])).append(",");
		}
		sb.append(btn("SKIP", "gray", "/among vote skip")).append("]");
		for (PState p : players.values()) {
			tell(p.name, "=== VOTE === click a color (or SKIP). Type /among vote <color> if clicking doesn't work.", "gold");
			if (p.alive) raw(p.name, sb.toString());
		}
	}

	static void vote(ServerPlayer sp, String target) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.VOTE || !p.alive) {
			tell(n, "You can't vote right now.", "red");
			return;
		}
		if (p.votedFor != -2) {
			tell(n, "You already voted.", "red");
			return;
		}
		int v;
		if ("skip".equalsIgnoreCase(target)) {
			v = -1;
		} else {
			int ci = AmongColor.indexOf(target);
			PState t = ci < 0 ? null : byColor(ci);
			if (t == null || !t.alive) {
				tell(n, "Vote for the color of a living player, or skip.", "red");
				return;
			}
			v = ci;
		}
		p.votedFor = v;
		tell(n, "Vote registered.", "green");
		tellAll(label(p) + " voted.", "gray");
	}

	static void tally() {
		int[] counts = new int[AmongColor.NAMES.length];
		int skips = 0;
		for (PState p : alive()) {
			if (p.votedFor == -1 || p.votedFor == -2) skips++;
			else counts[p.votedFor]++;
		}
		StringBuilder res = new StringBuilder("Votes: ");
		int best = -1;
		int bestN = 0;
		boolean tie = false;
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] == 0) continue;
			res.append(AmongColor.label(i)).append("=").append(counts[i]).append(" ");
			if (counts[i] > bestN) {
				best = i;
				bestN = counts[i];
				tie = false;
			} else if (counts[i] == bestN) {
				tie = true;
			}
		}
		res.append("Skip=").append(skips);
		tellAll(res.toString(), "yellow");

		if (best < 0 || tie || skips >= bestN) {
			tellAll("Nobody was ejected.", "gray");
		} else {
			PState e = byColor(best);
			if (e != null) {
				e.alive = false;
				cmd("gamemode spectator " + e.name);
				cmd("effect clear " + e.name + " minecraft:slowness");
				tellAll(AmongColor.label(best) + " was ejected. " + AmongColor.label(best) + " was "
					+ (e.role.impostor ? "An Impostor." : "not An Impostor."), e.role.impostor ? "green" : "red");
			}
		}
		endMeeting();
	}

	static void endMeeting() {
		phase = Phase.PLAYING;
		buttonLeft = buttonCd * 20;
		for (PState p : alive()) {
			cmd("effect clear " + p.name + " minecraft:slowness");
			p.killCd = Math.max(p.killCd, killCd * 20);
		}
		gather(5.0);
		updateBar();
		checkWin();
	}

	// =====================================================================
	// win conditions and end of game
	// =====================================================================

	static void checkWin() {
		if (phase == Phase.LOBBY || testMode) return;
		int imp = 0;
		int crew = 0;
		for (PState p : players.values()) {
			if (!p.alive) continue;
			if (p.role.impostor) imp++;
			else crew++;
		}
		if (imp == 0) {
			endGame(true, "All impostors are gone!");
			return;
		}
		if (imp >= crew) {
			endGame(false, "The impostors outnumber the crew!");
			return;
		}
		int total = 0;
		int done = 0;
		for (PState p : players.values()) {
			if (!p.alive || p.role.impostor) continue;
			for (TaskRef t : p.tasks) {
				total++;
				if (t.done) done++;
			}
		}
		if (total > 0 && done == total) endGame(true, "All tasks were completed!");
	}

	/** crewWin: true = crew, false = impostors, null = stopped without winner. */
	static void endGame(Boolean crewWin, String reason) {
		String head = crewWin == null ? "GAME STOPPED" : crewWin ? "CREWMATES WIN" : "IMPOSTORS WIN";
		String color = crewWin == null ? "gray" : crewWin ? "green" : "red";
		for (PState p : players.values()) {
			title(p.name, head, reason, color);
			tell(p.name, head + " - " + reason, color);
		}
		if (crewWin != null) {
			tellAll("--- Roles ---", "gold");
			for (PState p : players.values()) {
				if (p.color < 0) continue;
				tellAll(AmongColor.label(p.color) + " = " + p.name + " (" + p.role.display + ")", p.role.color);
			}
		}
		for (PState p : players.values()) {
			cleanupPlayer(p.name, true);
			cmd("tag " + p.name + " add amongus_in");
			if (map.lobby != null) tp(p.name, map.lobby.cx(), map.lobby.y, map.lobby.cz());
			p.alive = true;
			p.color = -1;
			p.role = Role.CREWMATE;
			p.tasks.clear();
			p.run = null;
		}
		resetWorldState();
		tellAll("Back in the lobby. Host: /among start for another round.", "yellow");
	}
}
