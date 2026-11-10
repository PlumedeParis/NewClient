package dev.newclient.util;

import net.minecraft.util.Mth;

/** ARGB helpers. Every color in the client is a packed 0xAARRGGBB int. */
public final class ColorUtil {
	private ColorUtil() {}

	public static int a(int c) { return c >>> 24; }
	public static int r(int c) { return c >> 16 & 0xFF; }
	public static int g(int c) { return c >> 8 & 0xFF; }
	public static int b(int c) { return c & 0xFF; }

	public static int argb(int a, int r, int g, int b) {
		return (Mth.clamp(a, 0, 255) << 24) | (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
	}

	public static int withAlpha(int c, int alpha) { return (Mth.clamp(alpha, 0, 255) << 24) | (c & 0xFFFFFF); }

	/** Multiplies the existing alpha by {@code f} (0..1). */
	public static int fade(int c, float f) { return withAlpha(c, Math.round(a(c) * Mth.clamp(f, 0f, 1f))); }

	public static int lerp(int c1, int c2, float t) {
		t = Mth.clamp(t, 0f, 1f);
		return argb(
			Math.round(a(c1) + (a(c2) - a(c1)) * t),
			Math.round(r(c1) + (r(c2) - r(c1)) * t),
			Math.round(g(c1) + (g(c2) - g(c1)) * t),
			Math.round(b(c1) + (b(c2) - b(c1)) * t));
	}

	public static int brighten(int c, float f) {
		return argb(a(c), Math.round(r(c) + (255 - r(c)) * f), Math.round(g(c) + (255 - g(c)) * f), Math.round(b(c) + (255 - b(c)) * f));
	}

	public static int darken(int c, float f) {
		return argb(a(c), Math.round(r(c) * (1 - f)), Math.round(g(c) * (1 - f)), Math.round(b(c) * (1 - f)));
	}

	/** @return {hue, saturation, value} each 0..1 */
	public static float[] toHsv(int c) {
		float r = r(c) / 255f, g = g(c) / 255f, b = b(c) / 255f;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		float d = max - min, h = 0;
		if (d != 0) {
			if (max == r) h = ((g - b) / d) % 6;
			else if (max == g) h = (b - r) / d + 2;
			else h = (r - g) / d + 4;
			h /= 6;
			if (h < 0) h += 1;
		}
		return new float[] {h, max == 0 ? 0 : d / max, max};
	}

	public static int fromHsv(float h, float s, float v, int alpha) {
		return withAlpha(Mth.hsvToRgb(((h % 1) + 1) % 1, Mth.clamp(s, 0, 1), Mth.clamp(v, 0, 1)), alpha);
	}

	/** Smooth hue-shifting color, used for "chroma" accents. */
	public static int chroma(long offsetMs, float speed, float sat, float val) {
		float hue = (System.currentTimeMillis() + offsetMs) % (long) (10000 / speed) / (10000f / speed);
		return fromHsv(hue, sat, val, 255);
	}
}
