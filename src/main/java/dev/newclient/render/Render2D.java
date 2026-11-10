package dev.newclient.render;

import dev.newclient.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Premium-looking 2D primitives built on GuiGraphics: anti-aliased rounded rects, glows, gradients. */
public final class Render2D {
	private Render2D() {}

	public static Font font() { return Minecraft.getInstance().font; }

	public static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0 || ColorUtil.a(color) == 0) return;
		g.fill(x, y, x + w, y + h, color);
	}

	/** Anti-aliased rounded rectangle (scanline corners with fractional edge pixels). */
	public static void roundedRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		roundedRect(g, x, y, w, h, radius, color, true, true);
	}

	public static void roundedRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color, boolean roundTop, boolean roundBottom) {
		if (w <= 0 || h <= 0 || ColorUtil.a(color) == 0) return;
		int r = Math.min(radius, Math.min(w / 2, h / 2));
		if (r <= 0) {
			rect(g, x, y, w, h, color);
			return;
		}
		int top = roundTop ? r : 0, bottom = roundBottom ? r : 0;
		g.fill(x, y + top, x + w, y + h - bottom, color);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			double dx = Math.sqrt(Math.max(0, r * r - dy * dy));
			double inset = r - dx;
			int full = (int) Math.ceil(inset);
			float frac = (float) (full - inset);
			if (roundTop) cornerRow(g, x, y + i, w, full, frac, color);
			if (roundBottom) cornerRow(g, x, y + h - 1 - i, w, full, frac, color);
		}
	}

	private static void cornerRow(GuiGraphics g, int x, int yy, int w, int full, float frac, int color) {
		g.fill(x + full, yy, x + w - full, yy + 1, color);
		if (frac > 0.02f && full > 0) {
			int edge = ColorUtil.fade(color, frac);
			g.fill(x + full - 1, yy, x + full, yy + 1, edge);
			g.fill(x + w - full, yy, x + w - full + 1, yy + 1, edge);
		}
	}

	/** Soft glow / drop shadow around a rounded rect. */
	public static void glow(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int size) {
		int baseAlpha = ColorUtil.a(color);
		for (int i = size; i >= 1; i--) {
			float t = 1f - (float) i / (size + 1);
			int a = Math.round(baseAlpha * t * t / size * 1.6f);
			if (a <= 0) continue;
			roundedRect(g, x - i, y - i, w + i * 2, h + i * 2, radius + i, ColorUtil.withAlpha(color, a));
		}
	}

	/** Rounded rect outline of 1px. */
	public static void roundedOutline(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		// Draw the outline as a ring: outer rect minus inner area by layering edges.
		int r = Math.min(radius, Math.min(w / 2, h / 2));
		g.fill(x + r, y, x + w - r, y + 1, color);
		g.fill(x + r, y + h - 1, x + w - r, y + h, color);
		g.fill(x, y + r, x + 1, y + h - r, color);
		g.fill(x + w - 1, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			int nextInset = i + 1 < r ? (int) Math.round(r - Math.sqrt(Math.max(0, r * r - (r - i - 1.5) * (r - i - 1.5)))) : r;
			int len = Math.max(1, inset - nextInset + 1);
			int lx = x + nextInset, rx = x + w - nextInset - len;
			g.fill(lx, y + i, lx + len, y + i + 1, color);
			g.fill(rx, y + i, rx + len, y + i + 1, color);
			g.fill(lx, y + h - 1 - i, lx + len, y + h - i, color);
			g.fill(rx, y + h - 1 - i, rx + len, y + h - i, color);
		}
	}

	/** Horizontal gradient (GuiGraphics only has vertical gradients). */
	public static void gradientH(GuiGraphics g, int x, int y, int w, int h, int left, int right) {
		if (w <= 0 || h <= 0) return;
		int step = Math.max(1, w / 48);
		for (int i = 0; i < w; i += step) {
			int c = ColorUtil.lerp(left, right, (i + step / 2f) / w);
			g.fill(x + i, y, Math.min(x + w, x + i + step), y + h, c);
		}
	}

	public static void gradientV(GuiGraphics g, int x, int y, int w, int h, int top, int bottom) {
		g.fillGradient(x, y, x + w, y + h, top, bottom);
	}

	public static void text(GuiGraphics g, String s, int x, int y, int color, boolean shadow) {
		g.drawString(font(), s, x, y, color, shadow);
	}

	public static void text(GuiGraphics g, String s, int x, int y, int color) {
		text(g, s, x, y, color, true);
	}

	public static void centeredText(GuiGraphics g, String s, int cx, int y, int color, boolean shadow) {
		text(g, s, cx - font().width(s) / 2, y, color, shadow);
	}

	/** Each character tinted along an animated accent gradient. */
	public static void gradientText(GuiGraphics g, String s, int x, int y, java.util.function.IntUnaryOperator colorAtIndex, boolean shadow) {
		int cx = x;
		for (int i = 0; i < s.length(); i++) {
			String ch = String.valueOf(s.charAt(i));
			g.drawString(font(), ch, cx, y, colorAtIndex.applyAsInt(i), shadow);
			cx += font().width(ch);
		}
	}

	public static int width(String s) { return font().width(s); }

	/** Cuts a string with an ellipsis so it fits in {@code max} pixels. */
	public static String trim(String s, int max) {
		if (width(s) <= max) return s;
		String e = "\u2026";
		while (!s.isEmpty() && width(s + e) > max) s = s.substring(0, s.length() - 1);
		return s + e;
	}

	/** Scaled text, e.g. big titles. */
	public static void scaledText(GuiGraphics g, String s, float x, float y, float scale, int color, boolean shadow) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.drawString(font(), s, 0, 0, color, shadow);
		g.pose().popMatrix();
	}

	/** Filled circle (for radar dots / toggle knobs). */
	public static void circle(GuiGraphics g, int cx, int cy, int radius, int color) {
		roundedRect(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	public static boolean hovered(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
