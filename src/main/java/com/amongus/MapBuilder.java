package com.amongus;

/**
 * Builds a ready-made Among Us map around the player using plain vanilla commands.
 * Layout: Cafeteria in the middle, 4 corridors, 4 rooms (Medbay N, Engine S, Reactor W, Electrical E).
 */
public final class MapBuilder {
	private MapBuilder() {}

	private static int ox, oy, oz;

	private static void fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		Game.cmd("fill " + (ox + x1) + " " + (oy + y1) + " " + (oz + z1) + " "
			+ (ox + x2) + " " + (oy + y2) + " " + (oz + z2) + " " + block);
	}

	private static void set(int x, int y, int z, String block) {
		Game.cmd("setblock " + (ox + x) + " " + (oy + y) + " " + (oz + z) + " " + block);
	}

	private static MapData.P p(int x, int y, int z) {
		return new MapData.P(ox + x, oy + y, oz + z);
	}

	public static MapData build(int originX, int originY, int originZ) {
		ox = originX;
		oy = originY;
		oz = originZ;

		// clear space (split so each fill stays under the 32768 block limit)
		fill(-36, 0, -36, 36, 3, 36, "minecraft:air");
		fill(-36, 4, -36, 36, 8, 36, "minecraft:air");
		// base floor
		fill(-35, -1, -35, 35, -1, 35, "minecraft:stone_bricks");

		// x1, z1, x2, z2
		int[][] boxes = {
			{-10, -10, 10, 10},   // cafeteria
			{-2, -19, 2, -10},    // north corridor
			{-2, 10, 2, 19},      // south corridor
			{-19, -2, -10, 2},    // west corridor
			{10, -2, 19, 2},      // east corridor
			{-8, -28, 8, -19},    // medbay
			{-8, 19, 8, 28},      // engine
			{-28, -8, -19, 8},    // reactor
			{19, -8, 28, 8}       // electrical
		};

		for (int[] b : boxes) {
			fill(b[0], -1, b[1], b[2], 5, b[3], "minecraft:stone_bricks hollow");
		}
		for (int[] b : boxes) {
			fill(b[0] + 1, -1, b[1] + 1, b[2] - 1, -1, b[3] - 1, "minecraft:polished_andesite");
		}

		// doors between corridors and rooms
		fill(-1, 0, -10, 1, 3, -10, "minecraft:air");
		fill(-1, 0, -19, 1, 3, -19, "minecraft:air");
		fill(-1, 0, 10, 1, 3, 10, "minecraft:air");
		fill(-1, 0, 19, 1, 3, 19, "minecraft:air");
		fill(-10, 0, -1, -10, 3, 1, "minecraft:air");
		fill(-19, 0, -1, -19, 3, 1, "minecraft:air");
		fill(10, 0, -1, 10, 3, 1, "minecraft:air");
		fill(19, 0, -1, 19, 3, 1, "minecraft:air");

		// ceiling lights
		for (int[] b : boxes) {
			for (int x = b[0] + 2; x <= b[2] - 2; x += 4) {
				for (int z = b[1] + 2; z <= b[3] - 2; z += 4) {
					set(x, 5, z, "minecraft:sea_lantern");
				}
			}
		}

		MapData m = new MapData();
		m.lobby = p(0, 0, 4);
		// emergency button: a crimson button on top of a red pedestal in the middle of the Cafeteria
		set(0, 0, 0, "minecraft:red_concrete");
		set(0, 1, 0, "minecraft:crimson_button[face=floor]");
		m.button = p(0, 1, 0);

		// task stations (lecterns)
		Object[][] tasks = {
			{"Cafeteria: Fix Wiring", "click", 8, -8},
			{"Cafeteria: Download Data", "hold", -8, 8},
			{"Medbay: Submit Scan", "hold", 0, -26},
			{"Medbay: Enter ID Code", "code", -6, -21},
			{"Engine: Align Output", "click", 0, 26},
			{"Engine: Fuel Engines", "hold", 6, 21},
			{"Reactor: Start Reactor", "code", -26, 0},
			{"Reactor: Unlock Manifolds", "click", -21, 6},
			{"Electrical: Calibrate Distributor", "click", 26, 0},
			{"Electrical: Divert Power", "code", 21, -6}
		};
		for (Object[] t : tasks) {
			int x = (Integer) t[2];
			int z = (Integer) t[3];
			set(x, 0, z, "minecraft:lectern");
			m.tasks.add(new MapData.TaskDef((String) t[0], (String) t[1], p(x, 0, z)));
		}

		// vents (iron trapdoors on the floor), travel order = list order
		int[][] vents = {{-6, -26}, {8, 8}, {-26, -6}, {-6, 26}, {26, 6}};
		for (int[] v : vents) {
			set(v[0], 0, v[1], "minecraft:iron_trapdoor");
			m.vents.add(p(v[0], 0, v[1]));
		}

		// sabotage fix panels
		set(24, 0, -6, "minecraft:redstone_lamp");
		m.lights = p(24, 0, -6);
		set(-24, 0, 6, "minecraft:crying_obsidian");
		m.reactor = p(-24, 0, 6);

		Game.cmd("difficulty peaceful");
		m.save();
		return m;
	}
}
