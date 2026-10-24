package com.amongus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The Among Us map: where everything is. Saved to config/amongus-map.json. */
public final class MapData {
	public static final class P {
		public int x;
		public int y;
		public int z;

		public P() {}

		public P(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		public boolean is(BlockPos b) {
			return b.getX() == x && b.getY() == y && b.getZ() == z;
		}

		public double cx() {
			return x + 0.5;
		}

		public double cz() {
			return z + 0.5;
		}
	}

	public static final class TaskDef {
		public String name;
		public String type; // click, hold, code
		public P pos;

		public TaskDef() {}

		public TaskDef(String name, String type, P pos) {
			this.name = name;
			this.type = type;
			this.pos = pos;
		}
	}

	public P lobby;
	public P button;
	public P lights;
	public P reactor;
	public List<P> vents = new ArrayList<>();
	public List<TaskDef> tasks = new ArrayList<>();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean ready() {
		return lobby != null && button != null && !tasks.isEmpty();
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("amongus-map.json");
	}

	public static MapData load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				MapData m = GSON.fromJson(Files.readString(p), MapData.class);
				if (m != null) {
					if (m.vents == null) m.vents = new ArrayList<>();
					if (m.tasks == null) m.tasks = new ArrayList<>();
					return m;
				}
			}
		} catch (Exception e) {
			AmongUsMod.LOGGER.warn("Could not read amongus-map.json", e);
		}
		return new MapData();
	}

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException e) {
			AmongUsMod.LOGGER.warn("Could not save amongus-map.json", e);
		}
	}
}
