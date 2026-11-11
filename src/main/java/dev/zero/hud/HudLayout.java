package dev.zero.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom HUD positions set in the HUD editor. Positions are stored relative to the free
 * space (0 = left/top edge, 1 = right/bottom edge) so they survive window / GUI-scale changes.
 */
public final class HudLayout {
	/** id -> {fx, fy} */
	private static final Map<String, double[]> CUSTOM = new LinkedHashMap<>();
	/** id -> {x, y, w, h} of what was drawn last frame (for the editor). */
	static final Map<String, int[]> LAST = new LinkedHashMap<>();

	private HudLayout() {}

	public static boolean isCustom(String id) { return CUSTOM.containsKey(id); }

	public static double[] get(String id) { return CUSTOM.get(id); }

	public static void set(String id, double fx, double fy) { CUSTOM.put(id, new double[] {clamp(fx), clamp(fy)}); }

	public static void reset(String id) { CUSTOM.remove(id); }

	public static void clear() { CUSTOM.clear(); }

	private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

	/** Converts a stored fraction into a pixel position for an element of the given size. */
	public static int[] place(String id, int w, int h, int sw, int sh) {
		double[] f = CUSTOM.get(id);
		return new int[] {(int) Math.round(f[0] * Math.max(0, sw - w)), (int) Math.round(f[1] * Math.max(0, sh - h))};
	}

	static void record(String id, int x, int y, int w, int h) { LAST.put(id, new int[] {x, y, w, h}); }

	public static JsonObject save() {
		JsonObject o = new JsonObject();
		for (Map.Entry<String, double[]> e : CUSTOM.entrySet()) {
			JsonArray a = new JsonArray();
			a.add(e.getValue()[0]);
			a.add(e.getValue()[1]);
			o.add(e.getKey(), a);
		}
		return o;
	}

	public static void load(JsonObject o) {
		CUSTOM.clear();
		for (String k : o.keySet()) {
			JsonArray a = o.getAsJsonArray(k);
			CUSTOM.put(k, new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()});
		}
	}
}
