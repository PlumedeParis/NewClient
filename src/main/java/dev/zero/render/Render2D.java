package dev.zero.render;

import dev.zero.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

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
		if (r <= 1) {
			rect(g, x, y, w, h, color);
			return;
		}
		ShapeTextures.ensure();
		int half = ShapeTextures.SIZE / 2, tex = ShapeTextures.SIZE;
		int top = roundTop ? r : 0, bottom = roundBottom ? r : 0;
		// body
		g.fill(x, y + top, x + w, y + h - bottom, color);
		if (roundTop) {
			g.fill(x + r, y, x + w - r, y + r, color);
			g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.CIRCLE, x, y, 0, 0, r, r, half, half, tex, tex, color);
			g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.CIRCLE, x + w - r, y, half, 0, r, r, half, half, tex, tex, color);
		}
		if (roundBottom) {
			g.fill(x + r, y + h - r, x + w - r, y + h, color);
			g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.CIRCLE, x, y + h - r, 0, half, r, r, half, half, tex, tex, color);
			g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.CIRCLE, x + w - r, y + h - r, half, half, r, r, half, half, tex, tex, color);
		}
	}

	/** Soft glow / drop shadow around a rounded rect: a 9-slice of a pre-blurred texture. */
	public static void glow(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int size) {
		if (ColorUtil.a(color) == 0 || size <= 0) return;
		ShapeTextures.ensure();
		int half = ShapeTextures.SIZE / 2, tex = ShapeTextures.SIZE;
		int c = Math.min(size + radius, Math.min(w / 2 + size, h / 2 + size)); // corner size
		int x0 = x - size, y0 = y - size, x1 = x + w + size, y1 = y + h + size;
		int ew = x1 - x0 - 2 * c, eh = y1 - y0 - 2 * c;
		var P = RenderPipelines.GUI_TEXTURED;
		var T = ShapeTextures.GLOW;
		g.blit(P, T, x0, y0, 0, 0, c, c, half, half, tex, tex, color);
		g.blit(P, T, x1 - c, y0, half, 0, c, c, half, half, tex, tex, color);
		g.blit(P, T, x0, y1 - c, 0, half, c, c, half, half, tex, tex, color);
		g.blit(P, T, x1 - c, y1 - c, half, half, c, c, half, half, tex, tex, color);
		if (ew > 0) {
			g.blit(P, T, x0 + c, y0, half - 0.5f, 0, ew, c, 1, half, tex, tex, color);
			g.blit(P, T, x0 + c, y1 - c, half - 0.5f, half, ew, c, 1, half, tex, tex, color);
		}
		if (eh > 0) {
			g.blit(P, T, x0, y0 + c, 0, half - 0.5f, c, eh, half, 1, tex, tex, color);
			g.blit(P, T, x1 - c, y0 + c, half, half - 0.5f, c, eh, half, 1, tex, tex, color);
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

	/** Horizontal gradient (GuiGraphics only has vertical gradients): a tinted alpha ramp texture. */
	public static void gradientH(GuiGraphics g, int x, int y, int w, int h, int left, int right) {
		if (w <= 0 || h <= 0) return;
		ShapeTextures.ensure();
		int tex = ShapeTextures.SIZE;
		int al = ColorUtil.a(left), ar = ColorUtil.a(right);
		if (al == ar) {
			// exact lerp: left underneath, right faded in on top
			g.fill(x, y, x + w, y + h, left);
			g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.RAMP, x, y, 0, 0, w, h, tex, 1, tex, 1, right);
		} else if (ar == 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.RAMP_REV, x, y, 0, 0, w, h, tex, 1, tex, 1, left);
		} else if (al == 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.RAMP, x, y, 0, 0, w, h, tex, 1, tex, 1, right);
		} else {
			int step = Math.max(1, w / 16);
			for (int i = 0; i < w; i += step) g.fill(x + i, y, Math.min(x + w, x + i + step), y + h, ColorUtil.lerp(left, right, (i + step / 2f) / w));
		}
	}

	public static void gradientV(GuiGraphics g, int x, int y, int w, int h, int top, int bottom) {
		g.fillGradient(x, y, x + w, y + h, top, bottom);
	}

	public static void text(GuiGraphics g, String s, int x, int y, int color, boolean shadow) {
		if (SmoothFont.active()) {
			SmoothFont.draw(g, s, x, y, color, shadow);
			return;
		}
		if (s.indexOf('\u00D8') < 0) {
			g.drawString(font(), s, x, y, color, shadow);
			return;
		}
		// Minecraft's font draws \u00D8 almost like a zero: draw "O" with a real diagonal slash.
		int cx = x;
		int start = 0;
		for (int i = 0; i <= s.length(); i++) {
			if (i == s.length() || s.charAt(i) == '\u00D8') {
				String part = s.substring(start, i);
				if (!part.isEmpty()) {
					g.drawString(font(), part, cx, y, color, shadow);
					cx += font().width(part);
				}
				if (i < s.length()) {
					slashedO(g, cx, y, color, shadow);
					cx += font().width("O");
				}
				start = i + 1;
			}
		}
	}

	/** Draws an "O" crossed by a diagonal slash (the \u00D8 of ZER\u00D8). */
	public static void slashedO(GuiGraphics g, int x, int y, int color, boolean shadow) {
		if (shadow) {
			int sc = ColorUtil.argb(ColorUtil.a(color), ColorUtil.r(color) / 4, ColorUtil.g(color) / 4, ColorUtil.b(color) / 4);
			g.drawString(font(), "O", x + 1, y + 1, sc, false);
			slash(g, x + 1, y + 1, sc);
		}
		g.drawString(font(), "O", x, y, color, false);
		slash(g, x, y, color);
	}

	private static void slash(GuiGraphics g, int x, int y, int color) {
		// from just below the bottom-left to just above the top-right of the 5x7 glyph
		for (int r = 0; r <= 8; r++) {
			int px = x + Math.round(r * 4 / 8f), py = y + 7 - r;
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	public static void text(GuiGraphics g, String s, int x, int y, int color) {
		text(g, s, x, y, color, true);
	}

	public static void centeredText(GuiGraphics g, String s, int cx, int y, int color, boolean shadow) {
		text(g, s, cx - width(s) / 2, y, color, shadow);
	}

	/** Each character tinted along an animated accent gradient. */
	public static void gradientText(GuiGraphics g, String s, int x, int y, java.util.function.IntUnaryOperator colorAtIndex, boolean shadow) {
		if (SmoothFont.active()) {
			int px = x;
			for (int i = 0; i < s.length(); i++) {
				String ch = String.valueOf(s.charAt(i));
				SmoothFont.draw(g, ch, px, y, colorAtIndex.applyAsInt(i), shadow);
				px += SmoothFont.width(ch);
			}
			return;
		}
		int cx = x;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\u00D8') {
				slashedO(g, cx, y, colorAtIndex.applyAsInt(i), shadow);
				cx += font().width("O");
				continue;
			}
			String ch = String.valueOf(c);
			g.drawString(font(), ch, cx, y, colorAtIndex.applyAsInt(i), shadow);
			cx += font().width(ch);
		}
	}

	public static int width(String s) { return SmoothFont.active() ? SmoothFont.width(s) : font().width(s.replace('\u00D8', 'O')); }

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
		if (SmoothFont.active()) SmoothFont.draw(g, s, 0, 0, color, shadow);
		else g.drawString(font(), s, 0, 0, color, shadow);
		g.pose().popMatrix();
	}

	/** Filled circle (for radar dots / toggle knobs). */
	public static void circle(GuiGraphics g, int cx, int cy, int radius, int color) {
		if (radius <= 0) return;
		ShapeTextures.ensure();
		int tex = ShapeTextures.SIZE;
		g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.CIRCLE, cx - radius, cy - radius, 0, 0, radius * 2, radius * 2, tex, tex, tex, tex, color);
	}

	/** Smooth progress ring (0..1), starting at the top, drawn in real pixels so it stays round and sharp. */
	public static void ring(GuiGraphics g, int cx, int cy, float radius, float thickness, float progress, int color, int track) {
		ShapeTextures.ensure();
		float gs = (float) Minecraft.getInstance().getWindow().getGuiScale();
		int tex = ShapeTextures.SIZE;
		int size = Math.max(2, Math.round(thickness * gs));
		int steps = Math.max(28, (int) (radius * gs * 0.9f));
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(1f / gs, 1f / gs);
		for (int pass = 0; pass < 2; pass++) {
			int n = pass == 0 ? steps : Math.round(steps * Math.max(0, Math.min(1, progress)));
			int c = pass == 0 ? track : color;
			for (int i = 0; i <= n; i++) {
				double a = -Math.PI / 2 + 2 * Math.PI * i / steps;
				int px = (int) Math.round(Math.cos(a) * radius * gs) - size / 2, py = (int) Math.round(Math.sin(a) * radius * gs) - size / 2;
				g.blit(RenderPipelines.GUI_TEXTURED, ShapeTextures.CIRCLE, px, py, 0, 0, size, size, tex, tex, tex, tex, c);
			}
		}
		g.pose().popMatrix();
	}

	public static boolean hovered(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
