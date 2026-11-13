package dev.zero.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import dev.zero.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Smooth vector text for the whole client (GUI, HUD, pop-ups): Montserrat glyphs rasterised
 * with anti-aliasing at the exact pixel size of the current GUI scale and drawn 1:1 in
 * physical pixels, so text is crisp at any resolution instead of Minecraft's pixel font.
 * Characters the font lacks fall back to a system font, then to Minecraft's own font.
 */
public final class SmoothFont {
	private SmoothFont() {}

	/** Text size in GUI pixels (Minecraft's font is 8 high; this matches its visual weight). */
	private static final float SIZE = 8.6f;
	private static final String EXTRA = "•–—…×→←↑↓✔✖★☰▣✦⚠◈›‹↺▦⬢◉⌖⚙·✓✕▸▾∞≈±☆●○→⟳";
	private static final int[] MC_COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private static final class Atlas {
		Identifier id;
		int w, h, ascent, pad;
		final int[][] glyph = new int[0x2C00][]; // {u, v, w, h} per char, null = not in this atlas
		final float[] advance = new float[0x2C00];
	}

	private static Atlas regular, bold;
	private static int builtScale = -1;
	private static Font fRegular, fBold, fallback;
	private static boolean broken;
	private static int seq;

	private static Font load(String file) throws Exception {
		try (InputStream in = SmoothFont.class.getResourceAsStream("/assets/zero/fonts/" + file)) {
			return Font.createFont(Font.TRUETYPE_FONT, in);
		}
	}

	public static boolean active() {
		return ClientSettings.SMOOTH_FONT.get() && !broken;
	}

	private static int scale() {
		return Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
	}

	private static void ensure() {
		int s = scale();
		if (s == builtScale && regular != null) return;
		try {
			if (fRegular == null) {
				fRegular = load("Montserrat-Medium.ttf");
				fBold = load("Montserrat-Bold.ttf");
				fallback = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
			}
			var tm = Minecraft.getInstance().getTextureManager();
			if (regular != null) tm.release(regular.id);
			if (bold != null) tm.release(bold.id);
			regular = build(fRegular, s);
			bold = build(fBold, s);
			builtScale = s;
		} catch (Throwable t) {
			broken = true; // keep the game usable: Minecraft's font takes over
			dev.zero.ZeroClient.LOGGER.warn("Smooth font unavailable, using Minecraft's font", t);
		}
	}

	private static Atlas build(Font base, int guiScale) {
		float px = SIZE * guiScale;
		Font font = base.deriveFont(px), fb = fallback.deriveFont(px * 0.95f);
		StringBuilder chars = new StringBuilder();
		for (char c = 32; c < 127; c++) chars.append(c);
		for (char c = 160; c < 256; c++) chars.append(c);
		chars.append(EXTRA);
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		pg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		FontMetrics fm = pg.getFontMetrics(font), fmb = pg.getFontMetrics(fb);
		Atlas a = new Atlas();
		a.pad = 1;
		a.ascent = Math.max(fm.getAscent(), fmb.getAscent());
		int cellH = a.ascent + Math.max(fm.getDescent(), fmb.getDescent()) + a.pad * 2;
		a.w = 1024;
		// first pass: layout
		int x = 0, y = 0;
		int[][] cells = new int[chars.length()][];
		for (int i = 0; i < chars.length(); i++) {
			char c = chars.charAt(i);
			boolean own = font.canDisplay(c);
			if (!own && !fb.canDisplay(c)) continue; // Minecraft's font draws it
			float adv = (own ? fm : fmb).charWidth(c);
			int cw = (int) Math.ceil(adv) + a.pad * 2 + 1;
			if (x + cw > a.w) {
				x = 0;
				y += cellH;
			}
			cells[i] = new int[] {x, y, cw, cellH, own ? 1 : 0};
			a.advance[c] = adv;
			x += cw;
		}
		a.h = y + cellH;
		pg.dispose();
		BufferedImage img = new BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setColor(Color.WHITE);
		for (int i = 0; i < chars.length(); i++) {
			int[] c = cells[i];
			if (c == null) continue;
			g.setFont(c[4] == 1 ? font : fb);
			g.drawString(String.valueOf(chars.charAt(i)), c[0] + a.pad, c[1] + a.pad + a.ascent);
			a.glyph[chars.charAt(i)] = new int[] {c[0], c[1], c[2], c[3]};
		}
		g.dispose();
		NativeImage n = new NativeImage(a.w, a.h, false);
		for (int py = 0; py < a.h; py++)
			for (int px2 = 0; px2 < a.w; px2++) n.setPixel(px2, py, (img.getRGB(px2, py) & 0xFF000000) | 0xFFFFFF);
		a.id = Identifier.fromNamespaceAndPath("zero", "font/" + (seq++));
		DynamicTexture tex = new DynamicTexture(() -> "zero font", n) {
			{ this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false); }
		};
		Minecraft.getInstance().getTextureManager().register(a.id, tex);
		return a;
	}

	private static boolean has(Atlas a, char c) { return c < a.glyph.length && a.glyph[c] != null; }

	/** Width in GUI pixels (formatting codes are not counted). */
	public static int width(String s) {
		ensure();
		if (broken) return Minecraft.getInstance().font.width(s);
		float w = 0;
		boolean b = false;
		int gs = builtScale;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				char code = Character.toLowerCase(s.charAt(++i));
				if (code == 'l') b = true;
				else if (code == 'r' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) b = false;
				continue;
			}
			Atlas a = b ? bold : regular;
			if (has(a, c)) w += a.advance[c];
			else w += Minecraft.getInstance().font.width(String.valueOf(c)) * gs;
		}
		return (int) Math.ceil(w / gs);
	}

	/** Draws text at GUI coordinates; understands Minecraft's color codes and bold. */
	public static void draw(GuiGraphics g, String s, float x, float y, int color, boolean shadow) {
		ensure();
		if (broken) {
			g.drawString(Minecraft.getInstance().font, s, (int) x, (int) y, color, shadow);
			return;
		}
		if ((color >>> 24) == 0) return;
		int gs = builtScale;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(1f / gs, 1f / gs);
		if (shadow) {
			int off = Math.max(1, gs / 2);
			g.pose().pushMatrix();
			g.pose().translate(off, off);
			run(g, s, ((color >>> 24) * 2 / 3) << 24 | ((color >> 2) & 0x3F3F3F), true, gs);
			g.pose().popMatrix();
		}
		run(g, s, color, false, gs);
		g.pose().popMatrix();
	}

	private static void run(GuiGraphics g, String s, int base, boolean isShadow, int gs) {
		int alpha = base & 0xFF000000;
		int color = base;
		boolean b = false;
		float pen = 0;
		// baseline where Minecraft's font has it: 7 GUI pixels under the text's y
		int top = Math.round(7.25f * gs) - regular.ascent - regular.pad;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				char code = Character.toLowerCase(s.charAt(++i));
				int idx = code >= '0' && code <= '9' ? code - '0' : code >= 'a' && code <= 'f' ? code - 'a' + 10 : -1;
				if (idx >= 0) {
					int rgb = MC_COLORS[idx];
					color = alpha | (isShadow ? (rgb >> 2) & 0x3F3F3F : rgb);
					b = false;
				} else if (code == 'l') b = true;
				else if (code == 'r') {
					color = base;
					b = false;
				}
				continue;
			}
			Atlas a = b ? bold : regular;
			if (has(a, c)) {
				int[] q = a.glyph[c];
				if (c != ' ') g.blit(RenderPipelines.GUI_TEXTURED, a.id, Math.round(pen) - a.pad, top, q[0], q[1], q[2], q[3], a.w, a.h, color);
				pen += a.advance[c];
			} else {
				// not in the vector fonts: Minecraft's own glyph, at its normal size
				var font = Minecraft.getInstance().font;
				String ch = String.valueOf(c);
				g.pose().pushMatrix();
				g.pose().translate(Math.round(pen), 0);
				g.pose().scale(gs, gs);
				g.drawString(font, ch, 0, 0, color, false);
				g.pose().popMatrix();
				pen += font.width(ch) * gs;
			}
		}
	}
}
