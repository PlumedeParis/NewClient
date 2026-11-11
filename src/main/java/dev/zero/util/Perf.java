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
		maxRenderNs = 0;
	}
}
