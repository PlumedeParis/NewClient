package dev.zero.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/** A square grid of on/off pixels (the custom crosshair). Saved as rows of '#' and '.'. */
public class PixelGridSetting extends Setting<boolean[]> {
	public static final int SIZE = 15;

	public PixelGridSetting(String name, String description, boolean[] defaults) {
		super(name, description, defaults.clone());
	}

	@Override protected boolean[] copyDefault() { return getDefault().clone(); }

	public boolean get(int x, int y) { return value[y * SIZE + x]; }

	public void setPixel(int x, int y, boolean on) {
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
		value[y * SIZE + x] = on;
	}

	public boolean isEmpty() {
		for (boolean b : value) if (b) return false;
		return true;
	}

	public static boolean[] fromRows(String... rows) {
		boolean[] g = new boolean[SIZE * SIZE];
		for (int y = 0; y < Math.min(SIZE, rows.length); y++)
			for (int x = 0; x < Math.min(SIZE, rows[y].length()); x++) g[y * SIZE + x] = rows[y].charAt(x) == '#';
		return g;
	}

	@Override
	public JsonElement toJson() {
		JsonArray a = new JsonArray();
		for (int y = 0; y < SIZE; y++) {
			StringBuilder sb = new StringBuilder();
			for (int x = 0; x < SIZE; x++) sb.append(get(x, y) ? '#' : '.');
			a.add(sb.toString());
		}
		return a;
	}

	@Override
	public void fromJson(JsonElement e) {
		if (!e.isJsonArray()) return;
		String[] rows = new String[e.getAsJsonArray().size()];
		for (int i = 0; i < rows.length; i++) rows[i] = e.getAsJsonArray().get(i).getAsString();
		value = fromRows(rows);
	}
}
