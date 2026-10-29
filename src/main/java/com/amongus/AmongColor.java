package com.amongus;

import java.util.Arrays;
import java.util.List;

public final class AmongColor {
	public static final String[] NAMES = {
		"red", "blue", "green", "pink", "orange", "yellow",
		"black", "white", "purple", "brown", "cyan", "lime",
		"maroon", "rose", "banana", "gray", "tan", "coral", "olive", "teal"
	};
	public static final int[] RGB = {
		0xC51111, 0x132ED1, 0x117F2D, 0xED54BA, 0xEF7D0E, 0xF5F557,
		0x3F474E, 0xD6E0F0, 0x6B2FBB, 0x71491E, 0x38FEDC, 0x50EF39,
		0x5F1D2D, 0xECC0D3, 0xFFFEBE, 0x708496, 0x928776, 0xEC7578, 0x5B6E2A, 0x1E8C8C
	};

	private AmongColor() {}

	public static List<String> names() {
		return Arrays.asList(NAMES);
	}

	public static int indexOf(String name) {
		for (int i = 0; i < NAMES.length; i++) {
			if (NAMES[i].equalsIgnoreCase(name)) return i;
		}
		return -1;
	}

	public static String hex(int i) {
		return String.format("#%06X", RGB[i]);
	}

	public static String label(int i) {
		String n = NAMES[i];
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}
}
