package dev.zero.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the evidence found at one place into a single "how likely is this a base" percentage.
 * <p>
 * Each kind of evidence (storage, a player spawner, a grown geode...) is worth a probability
 * from its own strength. Different kinds are independent clues, so they combine as
 * 1 - (1-p1)(1-p2)...: two medium clues beat one, and no amount of the same weak clue
 * reaches certainty. Several finds of the same kind only count once (the strongest).
 */
public final class Confidence {
	private Confidence() {}

	public record Result(int percent, String label, List<String> reasons) {
		/** Red (weak) to green (near certain). */
		public int color() {
			if (percent >= 85) return 0xFF4ADE80;
			if (percent >= 60) return 0xFFB8E04A;
			if (percent >= 35) return 0xFFFFC857;
			return 0xFFFF7A6B;
		}
	}

	/** "6 storage blocks" and "12 storage blocks" are the same kind of clue. */
	static String kind(String reason) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < reason.length(); i++) {
			char c = reason.charAt(i);
			if (c == '(' || c == ':' && sb.length() > 4) break;
			if (Character.isLetter(c) || c == ' ') sb.append(Character.toLowerCase(c));
		}
		String k = sb.toString().trim();
		if (k.startsWith("x ")) k = k.substring(2);
		return k.isEmpty() ? reason : k;
	}

	public static Result of(List<HuntTarget> evidence) {
		Map<String, HuntTarget> best = new LinkedHashMap<>();
		for (HuntTarget h : evidence) best.merge(kind(h.reason()), h, (a, b) -> b.score() > a.score() ? b : a);
		List<HuntTarget> kinds = new ArrayList<>(best.values());
		kinds.sort((a, b) -> Float.compare(b.score(), a.score()));
		double miss = 1;
		List<String> reasons = new ArrayList<>();
		for (HuntTarget h : kinds) {
			double p = Math.max(0.05, Math.min(0.92, h.score() / 100.0));
			miss *= 1 - p;
			reasons.add(h.reason());
		}
		int percent = (int) Math.round(Math.min(0.99, 1 - miss) * 100);
		String label = percent >= 85 ? "Base" : percent >= 60 ? "Likely base" : percent >= 35 ? "Possible base" : "Weak sign";
		return new Result(percent, label, reasons);
	}
}
