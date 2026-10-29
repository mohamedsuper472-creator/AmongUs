package com.amongus;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen box (scoreboard sidebar, right side) with a mini map, plus an optional location bar at the top.
 * Every player has their own team with a unique colour and their own objective, shown only to them through
 * "sidebar.team.<colour>". Vanilla has only 16 team colours, so players 17-20 get the top location bar only.
 */
final class Hud {
	private Hud() {}

	static final String[] TEAM_COLORS = {
		"red", "blue", "green", "light_purple", "gold", "yellow",
		"dark_gray", "white", "dark_purple", "dark_red", "aqua", "dark_green",
		"dark_aqua", "dark_blue", "gray", "black"
	};
	private static final String SHARED_TEAM = "au_tx";

	// ---- mini map geometry (local map coordinates) ----
	private static final int GX0 = -124;
	private static final int GZ0 = -52;
	private static final int CELL = 15;
	private static final int COLS = 17;
	private static final int ROWS = 8;
	private static int[][] grid; // room index, -2 corridor, -1 empty

	static boolean hasSidebar(Game.PState p) {
		return p.slot < TEAM_COLORS.length;
	}

	static String obj(Game.PState p) {
		return "au_s" + p.slot;
	}

	static String team(Game.PState p) {
		return hasSidebar(p) ? "au_t" + p.slot : SHARED_TEAM;
	}

	/** Create the player's team (names visible in the lobby) and put them in it. */
	static void setup(Game.PState p) {
		if (hasSidebar(p)) {
			Game.cmd("team remove " + team(p));
			Game.cmd("team add " + team(p));
			Game.cmd("team modify " + team(p) + " color " + TEAM_COLORS[p.slot]);
		} else {
			Game.cmd("team add " + SHARED_TEAM);
			Game.cmd("team modify " + SHARED_TEAM + " color white");
		}
		Game.cmd("team modify " + team(p) + " nametagVisibility always");
		Game.cmd("team modify " + team(p) + " collisionRule never");
		Game.cmd("team join " + team(p) + " " + p.name);
		p.hudLines = null;
		refresh(p, true);
	}

	static void hideNames(Game.PState p, boolean hide) {
		Game.cmd("team modify " + team(p) + " nametagVisibility " + (hide ? "never" : "always"));
	}

	static void teardown(Game.PState p) {
		clearLoc(p);
		if (hasSidebar(p)) {
			Game.cmd("scoreboard objectives remove " + obj(p));
			Game.cmd("team remove " + team(p));
		} else {
			Game.cmd("team leave " + p.name);
		}
		p.hudLines = null;
	}

	static void refreshAll(boolean force) {
		for (Game.PState p : Game.players.values()) refresh(p, force);
	}

	// ---------------------------------------------------------------- location

	/** Room name where the player stands ("Hallway" in corridors). */
	static String locationOf(net.minecraft.server.level.ServerPlayer sp) {
		int[] c = cellOrigin();
		int lx = (int) Math.floor(sp.getX()) - c[0];
		int lz = (int) Math.floor(sp.getZ()) - c[1];
		for (int i = 0; i < Layout.ROOMS.length; i++) {
			int[] r = Layout.ROOMS[i];
			if (lx >= r[0] - 1 && lx <= r[2] + 1 && lz >= r[1] - 1 && lz <= r[3] + 1) return Layout.ROOM_NAMES[i];
		}
		for (int[] r : Layout.CORRIDORS) {
			if (lx >= r[0] && lx <= r[2] && lz >= r[1] && lz <= r[3]) return "Hallway";
		}
		return "Outside";
	}

	private static int[] cellOrigin() {
		MapData.P l = Game.map.lobby;
		if (l == null) return new int[] {0, 0};
		return new int[] {l.x - Layout.LOBBY_X, l.z - Layout.LOBBY_Z};
	}

	private static void buildGrid() {
		grid = new int[ROWS][COLS];
		for (int gz = 0; gz < ROWS; gz++) {
			for (int gx = 0; gx < COLS; gx++) {
				int x1 = GX0 + gx * CELL;
				int x2 = x1 + CELL - 1;
				int z1 = GZ0 + gz * CELL;
				int z2 = z1 + CELL - 1;
				int best = -1;
				int bestArea = 0;
				for (int i = 0; i < Layout.ROOMS.length; i++) {
					int[] r = Layout.ROOMS[i];
					int w = Math.min(x2, r[2]) - Math.max(x1, r[0]) + 1;
					int h = Math.min(z2, r[3]) - Math.max(z1, r[1]) + 1;
					if (w > 0 && h > 0 && w * h > bestArea) {
						bestArea = w * h;
						best = i;
					}
				}
				if (best < 0) {
					for (int[] c : Layout.CORRIDORS) {
						if (!(x2 < c[0] || c[2] < x1 || z2 < c[1] || c[3] < z1)) {
							best = -2;
							break;
						}
					}
				}
				grid[gz][gx] = best;
			}
		}
	}

	private static int roomIndex(String name) {
		for (int i = 0; i < Layout.ROOM_NAMES.length; i++) if (Layout.ROOM_NAMES[i].equals(name)) return i;
		return -1;
	}

	private static String seg(String text, String color) {
		return "{\"text\":\"" + text + "\",\"color\":\"" + color + "\"}";
	}

	/** The mini map as JSON lines (ROWS lines). Gold = room with one of your tasks, red = sabotage, aqua dot = you. */
	static List<String> mapRows(Game.PState p, net.minecraft.server.level.ServerPlayer sp) {
		if (grid == null) buildGrid();
		boolean[] task = new boolean[Layout.ROOM_NAMES.length];
		for (Game.TaskRef t : p.tasks) {
			if (t.done) continue;
			MapData.TaskDef d = Game.map.tasks.get(t.idx);
			MapData.Step s = d.steps.get(Math.min(t.step, d.steps.size() - 1));
			int ri = roomIndex(s.room);
			if (ri >= 0) task[ri] = true;
		}
		boolean[] sab = new boolean[Layout.ROOM_NAMES.length];
		if (Game.lightsOut) sab[Math.max(0, roomIndex("Electrical"))] = true;
		if (Game.o2Ticks > 0) sab[Math.max(0, roomIndex("O2"))] = true;
		if (Game.reactorTicks > 0) sab[Math.max(0, roomIndex("Reactor"))] = true;
		int pgx = -1;
		int pgz = -1;
		if (sp != null) {
			int[] o = cellOrigin();
			pgx = (int) Math.floor((Math.floor(sp.getX()) - o[0] - GX0) / (double) CELL);
			pgz = (int) Math.floor((Math.floor(sp.getZ()) - o[1] - GZ0) / (double) CELL);
		}
		List<String> rows = new ArrayList<>();
		for (int gz = 0; gz < ROWS; gz++) {
			StringBuilder sb = new StringBuilder("[");
			String runColor = null;
			StringBuilder run = new StringBuilder();
			boolean first = true;
			for (int gx = 0; gx < COLS; gx++) {
				int g = grid[gz][gx];
				String ch = "■";
				String col;
				if (gx == pgx && gz == pgz) {
					ch = "●";
					col = "aqua";
				} else if (g >= 0 && sab[g]) {
					col = "red";
				} else if (g >= 0 && task[g]) {
					col = "gold";
				} else if (g >= 0) {
					col = "gray";
				} else if (g == -2) {
					col = "dark_gray";
				} else {
					col = "#15151d";
				}
				if (runColor != null && !runColor.equals(col)) {
					if (!first) sb.append(",");
					sb.append(seg(run.toString(), runColor));
					first = false;
					run.setLength(0);
				}
				runColor = col;
				run.append(ch);
			}
			if (!first) sb.append(",");
			sb.append(seg(run.toString(), runColor)).append("]");
			rows.add(sb.toString());
		}
		return rows;
	}

	// ---------------------------------------------------------------- top location bar

	static void clearLoc(Game.PState p) {
		if (p.locBar) {
			Game.cmd("bossbar remove amongus:loc" + p.slot);
			p.locBar = false;
		}
	}

	private static void updateLocBar(Game.PState p, String text) {
		String id = "amongus:loc" + p.slot;
		if (!p.locBar) {
			Game.cmd("bossbar add " + id + " " + Game.txt(text, "yellow"));
			Game.cmd("bossbar set " + id + " color yellow");
			Game.cmd("bossbar set " + id + " max 1");
			Game.cmd("bossbar set " + id + " value 1");
			Game.cmd("bossbar set " + id + " players " + p.name);
			p.locBar = true;
			p.lastLoc = text;
		} else if (!text.equals(p.lastLoc)) {
			Game.cmd("bossbar set " + id + " name " + Game.txt(text, "yellow"));
			p.lastLoc = text;
		}
	}

	// ---------------------------------------------------------------- sidebar

	static List<String> lines(Game.PState p, String loc, List<String> map) {
		List<String> l = new ArrayList<>();
		Stats.S st = Stats.get(p.id);
		if (Game.phase == Game.Phase.LOBBY) {
			l.add(Game.txt(" ", "white"));
			l.add(Game.txt("Game: " + (Game.gameName == null ? "-" : Game.gameName), "yellow"));
			l.add(Game.txt("Players: " + Game.players.size() + "/" + (Game.lobbyTarget() > 0 ? Game.lobbyTarget() : Game.MAX_PLAYERS), "white"));
			l.add(Game.txt(Game.gamePublic ? "Public" : "Private", "gray"));
			l.add(Game.txt(" ", "white"));
			l.add(Game.txt("Coins: " + st.coins, "gold"));
			l.add(Game.txt("Wins: " + st.wins + "  Games: " + st.games, "gray"));
			l.add(Game.txt("Kills: " + st.kills + "  Tasks: " + st.tasks, "gray"));
			return l;
		}
		int done = 0;
		for (Game.TaskRef t : p.tasks) if (t.done) done++;
		boolean withMap = map != null;
		if (withMap) {
			l.add("[" + seg("Location: ", "yellow") + "," + seg(loc, "white") + "]");
			l.addAll(map);
		} else {
			l.add(Game.txt(" ", "white"));
		}
		l.add(Game.txt(p.role.display + (p.alive ? "" : " (ghost)") + " | " + st.coins + "$", p.role.color));
		if (p.role.impostor) {
			l.add(Game.txt("Fake Tasks: " + done + "/" + p.tasks.size() + (p.alive ? "  Kill: " + (p.killCd > 0 ? (p.killCd / 20 + 1) + "s" : "READY") : ""), "red"));
		} else {
			l.add(Game.txt("Tasks: " + done + "/" + p.tasks.size(), "green"));
		}
		int room = 15 - l.size();
		int shown = 0;
		for (Game.TaskRef t : p.tasks) {
			if (shown >= room) break;
			MapData.TaskDef d = Game.map.tasks.get(t.idx);
			String name = d.name.length() > 18 ? d.name.substring(0, 18) : d.name;
			MapData.Step s = d.steps.get(Math.min(t.step, d.steps.size() - 1));
			String prog = d.steps.size() > 1 && !t.done ? " " + t.step + "/" + d.steps.size() : "";
			String text = (t.done ? "+ " : "- ") + name + prog + " [" + MapData.diffTag(d.diff) + "]";
			if (!t.done && !withMap) text += " " + s.room;
			l.add(Game.txt(text, t.done ? "dark_gray" : MapData.diffColor(d.diff)));
			shown++;
		}
		return l;
	}

	static void refresh(Game.PState p, boolean force) {
		net.minecraft.server.level.ServerPlayer sp = Game.find(p.id);
		String loc = sp != null ? locationOf(sp) : "?";
		int mode = hasSidebar(p) ? p.mini : 1;
		boolean inGame = Game.phase != Game.Phase.LOBBY;
		if (mode == 1 && sp != null) updateLocBar(p, "Location: " + loc);
		else clearLoc(p);
		if (!hasSidebar(p)) return;
		List<String> map = mode == 0 && inGame && Game.map.lobby != null ? mapRows(p, sp) : null;
		List<String> now = lines(p, loc, map);
		String o = obj(p);
		if (p.hudLines == null || p.hudLines.size() != now.size() || force) {
			Game.cmd("scoreboard objectives remove " + o);
			Game.cmd("scoreboard objectives add " + o + " dummy " + Game.txt("AMONG US", "gold"));
			Game.cmd("scoreboard objectives modify " + o + " numberformat blank");
			for (int k = 0; k < now.size(); k++) {
				Game.cmd("scoreboard players set l" + k + " " + o + " " + (now.size() - k));
				Game.cmd("scoreboard players display name l" + k + " " + o + " " + now.get(k));
			}
			Game.cmd("scoreboard objectives setdisplay sidebar.team." + TEAM_COLORS[p.slot] + " " + o);
		} else {
			for (int k = 0; k < now.size(); k++) {
				if (!now.get(k).equals(p.hudLines.get(k))) {
					Game.cmd("scoreboard players display name l" + k + " " + o + " " + now.get(k));
				}
			}
		}
		p.hudLines = now;
	}
}
