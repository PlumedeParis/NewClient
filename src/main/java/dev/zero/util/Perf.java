package dev.zero.util;

/** Tiny moving-average timers for ZERØ's own per-frame / per-tick cost. */
public final class Perf {
	private static double renderMs, scanMs, flushMs;
	public static final java.util.Map<String, Double> MODULE_MS = new java.util.concurrent.ConcurrentHashMap<>();

	public static void flush(long ns) { flushMs = flushMs * 0.95 + ns / 1e6 * 0.05; }

	public static double flushMs() { return flushMs; }

	public static void module(String name, long ns) { MODULE_MS.merge(name, ns / 1e6, (a, b) -> a * 0.95 + b * 0.05); }
	private static long maxRenderNs;

	private Perf() {}

	/** Per-module cost per tick / per HUD frame: {average ms, worst ms}. */
	public static final java.util.Map<String, double[]> TICK_MS = new java.util.concurrent.ConcurrentHashMap<>();
	public static final java.util.Map<String, double[]> HUD_MS = new java.util.concurrent.ConcurrentHashMap<>();
	public static final java.util.Map<String, double[]> R3D_MS = new java.util.concurrent.ConcurrentHashMap<>();

	private static void add(java.util.Map<String, double[]> map, String name, long ns) {
		double ms = ns / 1e6;
		double[] v = map.computeIfAbsent(name, k -> new double[3]);
		v[0] += ms;
		v[1] = Math.max(v[1], ms);
		v[2]++;
	}

	public static void tick(String name, long ns) { add(TICK_MS, name, ns); }

	public static void hud(String name, long ns) { add(HUD_MS, name, ns); }

	public static void r3d(String name, long ns) { add(R3D_MS, name, ns); }

	/** "name avg/max" lines, slowest first (in-game performance test). */
	public static String report(java.util.Map<String, double[]> map) {
		java.util.List<java.util.Map.Entry<String, double[]>> l = new java.util.ArrayList<>(map.entrySet());
		l.sort((a, b) -> Double.compare(b.getValue()[0] / b.getValue()[2], a.getValue()[0] / a.getValue()[2]));
		StringBuilder sb = new StringBuilder();
		double total = 0;
		for (var e : l) {
			double avg = e.getValue()[0] / e.getValue()[2];
			total += avg;
			if (avg >= 0.005 || e.getValue()[1] >= 0.5) sb.append(String.format(java.util.Locale.ROOT, "%s %.3f/%.1f  ", e.getKey(), avg, e.getValue()[1]));
		}
		return String.format(java.util.Locale.ROOT, "TOTAL %.3f ms | ", total) + sb;
	}

	public static void render(long ns) {
		renderMs = renderMs * 0.95 + ns / 1e6 * 0.05;
		maxRenderNs = Math.max(maxRenderNs, ns);
	}

	public static void scan(long ns) { scanMs = scanMs * 0.95 + ns / 1e6 * 0.05; }

	public static double renderMs() { return renderMs; }

	public static double scanMs() { return scanMs; }

	public static void reset() {
		renderMs = scanMs = flushMs = 0;
		MODULE_MS.clear();
		TICK_MS.clear();
		HUD_MS.clear();
		R3D_MS.clear();
		maxRenderNs = 0;
	}
}
