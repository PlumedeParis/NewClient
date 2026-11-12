package dev.zero.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import dev.zero.config.ClientSettings;
import dev.zero.module.ModuleManager;
import dev.zero.render.ShapeTextures;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 6-second motion-graphics intro played before the ClickGUI.
 * <p>
 * Every piece of text is rasterised from a vector font (Montserrat, OFL) at the window's
 * real pixel size with anti-aliasing, and drawn 1:1 in physical pixels with linear
 * filtering, so it stays razor sharp at any resolution and GUI scale.
 * Click / any key = skip to the reveal, Esc = straight to the GUI.
 */
public class IntroScreen extends Screen {
	public static final float LENGTH = 6.0f;
	private static boolean playedThisJoin;

	public enum Mode { ONCE_PER_JOIN, EVERY_TIME, OFF }

	/** Joining a world or a server: the next GUI open plays the intro again. */
	public static void onJoin() { playedThisJoin = false; }

	/** Opens the ClickGUI, with the intro first when the setting asks for it. */
	public static void openGui() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		Mode m = ClientSettings.INTRO.get();
		if (m == Mode.OFF || m == Mode.ONCE_PER_JOIN && playedThisJoin) {
			mc.setScreen(new ClickGuiScreen());
			return;
		}
		playedThisJoin = true;
		mc.setScreen(new IntroScreen(new ClickGuiScreen()));
	}

	private final Screen next;
	private long startNanos = -1;
	private float skipTo = 0;

	public IntroScreen(Screen next) {
		super(Component.literal("ZERØ"));
		this.next = next;
	}

	// ================================================================ timing / easing

	private float frozen = -1;

	private float time() {
		if (frozen >= 0) return frozen;
		if (startNanos < 0) return 0;
		return Math.max(skipTo, (System.nanoTime() - startNanos) / 1e9f);
	}

	private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

	private static float seg(float t, float a, float b) { return clamp01((t - a) / (b - a)); }

	private static float outCubic(float x) { return 1 - (float) Math.pow(1 - x, 3); }

	private static float outQuint(float x) { return 1 - (float) Math.pow(1 - x, 5); }

	private static float outExpo(float x) { return x >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * x); }

	private static float inOutCubic(float x) { return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2; }

	private static float inOutExpo(float x) {
		if (x <= 0) return 0;
		if (x >= 1) return 1;
		return x < 0.5f ? (float) Math.pow(2, 20 * x - 10) / 2 : (2 - (float) Math.pow(2, -20 * x + 10)) / 2;
	}

	private static float outBack(float x) {
		float c1 = 1.70158f * 1.15f, c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
	}

	private static int argb(float a, int rgb) { return (Math.round(clamp01(a) * 255) << 24) | (rgb & 0xFFFFFF); }

	private static int mix(int c1, int c2, float t) {
		int r = Math.round(((c1 >> 16) & 255) + (((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * t);
		int g = Math.round(((c1 >> 8) & 255) + (((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * t);
		int b = Math.round((c1 & 255) + ((c2 & 255) - (c1 & 255)) * t);
		return (r << 16) | (g << 8) | b;
	}

	// ================================================================ baked vector text

	private static final class LinearTexture extends DynamicTexture {
		LinearTexture(String name, NativeImage image) {
			super(() -> name, image);
			this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
		}
	}

	/** A text image (and optional soft glow) ready to draw at 1:1 pixel size. */
	private static final class Baked {
		Identifier id, glow;
		int w, h, gw, gh;
		float glowScale = 1, advance;
	}

	private final List<Identifier> owned = new ArrayList<>();
	private int bakeSeq;
	private int bakedForW = -1, bakedForH = -1;
	private Font black, bold, medium;

	private Font load(String file) {
		try (InputStream in = IntroScreen.class.getResourceAsStream("/assets/zero/fonts/" + file)) {
			return Font.createFont(Font.TRUETYPE_FONT, in);
		} catch (Exception e) {
			return new Font(Font.SANS_SERIF, Font.BOLD, 12);
		}
	}

	private Identifier register(String name, NativeImage img) {
		Identifier id = Identifier.fromNamespaceAndPath("zero", "intro/" + (bakeSeq++));
		minecraft.getTextureManager().register(id, new LinearTexture("zero intro " + name, img));
		owned.add(id);
		return id;
	}

	private static NativeImage toNative(BufferedImage img) {
		NativeImage n = new NativeImage(img.getWidth(), img.getHeight(), false);
		for (int y = 0; y < img.getHeight(); y++)
			for (int x = 0; x < img.getWidth(); x++) n.setPixel(x, y, img.getRGB(x, y));
		return n;
	}

	/**
	 * Rasterises text. Box = full ascent/descent so letters of one word share a baseline.
	 * topColor/bottomColor: vertical gradient; leftColor/rightColor != 0: horizontal gradient instead.
	 */
	private Baked bake(String text, Font base, float px, float tracking, int topColor, int bottomColor, boolean horizontal, int glowColor) {
		Font f = base.deriveFont(px);
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		pg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		FontMetrics fm = pg.getFontMetrics(f);
		float adv = 0;
		for (int i = 0; i < text.length(); i++) adv += fm.charWidth(text.charAt(i)) + (i < text.length() - 1 ? tracking * px : 0);
		pg.dispose();
		int pad = Math.round(px * 0.08f) + 2;
		int w = (int) Math.ceil(adv) + pad * 2, h = fm.getAscent() + fm.getDescent() + pad * 2;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setFont(f);
		Color c1 = new Color(topColor, true), c2 = new Color(bottomColor, true);
		g.setPaint(horizontal ? new GradientPaint(pad, 0, c1, w - pad, 0, c2) : new GradientPaint(0, pad + fm.getAscent() * 0.15f, c1, 0, pad + fm.getAscent(), c2));
		float x = pad;
		for (int i = 0; i < text.length(); i++) {
			String ch = String.valueOf(text.charAt(i));
			g.drawString(ch, x, pad + fm.getAscent());
			x += fm.charWidth(text.charAt(i)) + tracking * px;
		}
		g.dispose();
		Baked b = new Baked();
		b.w = w;
		b.h = h;
		b.advance = adv;
		b.id = register(text, toNative(img));
		if (glowColor != 0) {
			// glow: blurred copy at 1/4 resolution (cheap), stretched back with linear filtering
			int ds = 4, gpad = Math.round(px * 0.35f / ds) + 2;
			int gw = w / ds + gpad * 2, gh = h / ds + gpad * 2;
			float[] a = new float[gw * gh];
			for (int y = 0; y < h / ds; y++)
				for (int xx = 0; xx < w / ds; xx++) {
					float sum = 0;
					for (int sy = 0; sy < ds; sy++)
						for (int sx = 0; sx < ds; sx++) sum += (img.getRGB(xx * ds + sx, y * ds + sy) >>> 24) / 255f;
					a[(y + gpad) * gw + xx + gpad] = sum / (ds * ds);
				}
			int r = Math.max(1, Math.round(px * 0.06f / ds));
			for (int pass = 0; pass < 3; pass++) a = boxBlur(a, gw, gh, r);
			NativeImage gimg = new NativeImage(gw, gh, false);
			for (int y = 0; y < gh; y++)
				for (int xx = 0; xx < gw; xx++) gimg.setPixel(xx, y, argb(Math.min(1, a[y * gw + xx] * 1.9f), glowColor));
			b.glow = register(text + " glow", gimg);
			b.gw = gw;
			b.gh = gh;
			b.glowScale = ds;
		}
		return b;
	}

	private static float[] boxBlur(float[] src, int w, int h, int r) {
		float[] tmp = new float[src.length], out = new float[src.length];
		for (int y = 0; y < h; y++) {
			float acc = 0;
			for (int x = -r; x <= r; x++) acc += src[y * w + Math.max(0, Math.min(w - 1, x))];
			for (int x = 0; x < w; x++) {
				tmp[y * w + x] = acc / (2 * r + 1);
				acc += src[y * w + Math.min(w - 1, x + r + 1)] - src[y * w + Math.max(0, x - r)];
			}
		}
		for (int x = 0; x < w; x++) {
			float acc = 0;
			for (int y = -r; y <= r; y++) acc += tmp[Math.max(0, Math.min(h - 1, y)) * w + x];
			for (int y = 0; y < h; y++) {
				out[y * w + x] = acc / (2 * r + 1);
				acc += tmp[Math.min(h - 1, y + r + 1) * w + x] - tmp[Math.max(0, y - r) * w + x];
			}
		}
		return out;
	}

	// ================================================================ assets

	private static final String LOGO = "ZERØ";
	private static final String CLIENT = "CLIENT";
	private static final int WHITE = 0xFFFFFFFF;

	private final Baked[] logo = new Baked[4];
	private final Baked[] client = new Baked[6];
	private Baked devBy, plume, claude, amp, cornerTL, cornerBR, cornerBL, percent;
	private final Baked[] digits = new Baked[10];
	private final List<Baked> statuses = new ArrayList<>();
	private String[] statusText;
	private float S, logoAdvance;
	private final float[] particles = new float[90 * 5];

	private void bakeAll(int W, int H) {
		release();
		bakedForW = W;
		bakedForH = H;
		if (black == null) {
			black = load("Montserrat-Black.ttf");
			bold = load("Montserrat-Bold.ttf");
			medium = load("Montserrat-Medium.ttf");
		}
		int accent = ClientSettings.ACCENT.get(), accent2 = ClientSettings.ACCENT_2.get();
		S = Math.min(H * 0.24f, W * 0.15f);
		int top = 0xFFFFFFFF, bottom = 0xFF000000 | mix(0xFFFFFF, accent, 0.45f);
		logoAdvance = 0;
		for (int i = 0; i < 4; i++) {
			logo[i] = bake(String.valueOf(LOGO.charAt(i)), black, S, 0, top, bottom, false, accent);
			logoAdvance += logo[i].advance + (i < 3 ? S * 0.02f : 0);
		}
		for (int i = 0; i < 6; i++) client[i] = bake(String.valueOf(CLIENT.charAt(i)), medium, S * 0.12f, 0, 0xFFE8E6F5, 0xFFB9B3D9, false, 0);
		devBy = bake("DEVELOPMENT MADE BY", medium, Math.max(11, S * 0.075f), 0.38f, 0xFF9A93B8, 0xFF9A93B8, false, 0);
		plume = bake("PlumeTheCat", bold, S * 0.17f, 0, 0xFF000000 | mix(accent, 0xFFFFFF, 0.15f), 0xFFFF4DD8, true, 0xFFB06BFF);
		claude = bake("ClaudeAI", bold, S * 0.17f, 0, 0xFFFFB38A, 0xFFE8875F, true, 0xFFFF8F5C);
		amp = bake("&", medium, S * 0.12f, 0, 0xFFB9B3D9, 0xFFB9B3D9, false, 0);
		String ver = FabricLoader.getInstance().getModContainer("zero").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
		cornerTL = bake("ZERØ  •  INTRO", medium, Math.max(11, S * 0.065f), 0.28f, 0xFF7C77A0, 0xFF7C77A0, false, 0);
		cornerBR = bake("V" + ver + "  •  FABRIC 1.21.11", medium, Math.max(11, S * 0.065f), 0.28f, 0xFF7C77A0, 0xFF7C77A0, false, 0);
		cornerBL = bake("100% CLIENT-SIDE", medium, Math.max(11, S * 0.065f), 0.28f, 0xFF7C77A0, 0xFF7C77A0, false, 0);
		for (int d = 0; d < 10; d++) digits[d] = bake(String.valueOf(d), bold, Math.max(12, S * 0.072f), 0, 0xFFFFFFFF, 0xFFDAD6F0, false, 0);
		percent = bake("%", bold, Math.max(12, S * 0.072f), 0, 0xFFFFFFFF, 0xFFDAD6F0, false, 0);
		statusText = new String[] {"Initializing core", "Loading " + ModuleManager.all().size() + " modules", "Warming up finders", "Calibrating radar", "Ready"};
		statuses.clear();
		for (String s : statusText) statuses.add(bake(s, medium, Math.max(12, S * 0.072f), 0.04f, 0xFFD9D5EE, 0xFFB9B3D9, false, 0));
		Random r = new Random(0x2E80);
		for (int i = 0; i < particles.length; i += 5) {
			particles[i] = r.nextFloat();          // x
			particles[i + 1] = r.nextFloat();      // y
			particles[i + 2] = 0.02f + r.nextFloat() * 0.07f; // rise speed (screens / s)
			particles[i + 3] = 1.2f + r.nextFloat() * 3.2f;   // size px (scaled by H)
			particles[i + 4] = r.nextFloat() * 6.28f;          // twinkle phase
		}
	}

	private void release() {
		for (Identifier id : owned) minecraft.getTextureManager().release(id);
		owned.clear();
	}

	@Override
	protected void init() {
		next.init(width, height);
	}

	@Override
	public void removed() {
		release();
	}

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public boolean shouldCloseOnEsc() { return false; }

	// ================================================================ input

	private void skip() {
		float t = time();
		if (t < 5.3f) startNanos = System.nanoTime() - (long) (5.3f * 1e9);
		else finish();
	}

	private void finish() {
		minecraft.setScreen(next);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == GLFW.GLFW_KEY_ESCAPE) finish();
		else skip();
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		skip();
		return true;
	}

	// ================================================================ drawing helpers (physical pixels)

	private GuiGraphics g;

	private void rect(float x, float y, float w, float h, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / 64f, h / 64f);
		g.fill(0, 0, 64, 64, color);
		g.pose().popMatrix();
	}

	private void tex(Identifier id, float x, float y, float w, float h, int texW, int texH, int color) {
		if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / texW, h / texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, texW, texH, texW, texH, texW, texH, color);
		g.pose().popMatrix();
	}

	/** Baked text centred at (cx, cy) with scale / rotation (radians). */
	private void text(Baked b, float cx, float cy, float scale, float rot, float alpha, int tint) {
		if (alpha <= 0.003f) return;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		if (rot != 0) g.pose().rotate(rot);
		g.pose().scale(scale, scale);
		g.blit(RenderPipelines.GUI_TEXTURED, b.id, -b.w / 2, -b.h / 2, 0, 0, b.w, b.h, b.w, b.h, b.w, b.h, argb(alpha, tint));
		g.pose().popMatrix();
	}

	private void glow(Baked b, float cx, float cy, float scale, float alpha) {
		if (b.glow == null || alpha <= 0.003f) return;
		float s = scale * b.glowScale;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(s, s);
		g.blit(RenderPipelines.GUI_TEXTURED, b.glow, -b.gw / 2, -b.gh / 2, 0, 0, b.gw, b.gh, b.gw, b.gh, b.gw, b.gh, argb(alpha, 0xFFFFFF));
		g.pose().popMatrix();
	}

	private void softLight(float cx, float cy, float radius, int rgb, float alpha) {
		tex(ShapeTextures.GLOW, cx - radius, cy - radius, radius * 2, radius * 2, ShapeTextures.SIZE, ShapeTextures.SIZE, argb(alpha, rgb));
	}

	private void dot(float cx, float cy, float radius, int rgb, float alpha) {
		tex(ShapeTextures.CIRCLE, cx - radius, cy - radius, radius * 2, radius * 2, ShapeTextures.SIZE, ShapeTextures.SIZE, argb(alpha, rgb));
	}

	private void line(float x1, float y1, float x2, float y2, float thick, int color) {
		float dx = x2 - x1, dy = y2 - y1;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.5f) return;
		g.pose().pushMatrix();
		g.pose().translate(x1, y1);
		g.pose().rotate((float) Math.atan2(dy, dx));
		rect(0, -thick / 2, len, thick, color);
		g.pose().popMatrix();
	}

	/** Soft-edged band (fades on both sides), centred at (cx, cy), rotated. */
	private void band(float cx, float cy, float w, float h, float rot, int rgb, float alpha) {
		if (alpha <= 0.003f) return;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate(rot);
		tex(ShapeTextures.RAMP, -w / 2, -h / 2, w / 2, h, ShapeTextures.SIZE, 1, argb(alpha, rgb));
		tex(ShapeTextures.RAMP_REV, 0, -h / 2, w / 2, h, ShapeTextures.SIZE, 1, argb(alpha, rgb));
		g.pose().popMatrix();
	}

	// ================================================================ the show

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// everything is drawn in render()
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		ShapeTextures.ensure();
		int W = minecraft.getWindow().getWidth(), H = minecraft.getWindow().getHeight();
		if (W != bakedForW || H != bakedForH) bakeAll(W, H);
		if (startNanos < 0) startNanos = System.nanoTime(); // the clock starts on the first drawn frame
		float t = time();
		if (t >= LENGTH) {
			finish();
			return;
		}
		this.g = graphics;
		float gs = (float) minecraft.getWindow().getGuiScale();

		// reveal: the GUI is already there under the shutters
		if (t >= 5.3f) next.render(graphics, mouseX, mouseY, partialTick);

		graphics.pose().pushMatrix();
		graphics.pose().scale(1f / gs, 1f / gs);
		float cx = W / 2f;
		int accent = ClientSettings.ACCENT.get() & 0xFFFFFF;
		float shutter = inOutCubic(seg(t, 5.32f, 5.95f));

		if (t < 5.3f) scene(t, W, H, gs);
		else {
			// the screen splits: each half carries its part of the scene away
			float half = H / 2f, move = shutter * (half + 4);
			for (int side = 0; side < 2; side++) {
				float y0 = side == 0 ? 0 : half + move, y1 = side == 0 ? half - move : H;
				if (y1 - y0 < 1) continue;
				graphics.pose().pushMatrix();
				graphics.pose().identity();
				graphics.enableScissor(0, (int) Math.floor(y0 / gs), (int) Math.ceil(W / gs), (int) Math.ceil(y1 / gs));
				graphics.pose().popMatrix();
				graphics.pose().pushMatrix();
				graphics.pose().translate(0, side == 0 ? -move : move);
				scene(t, W, H, gs);
				graphics.pose().popMatrix();
				graphics.disableScissor();
			}
		}

		// reveal seam: light along the two cut edges
		if (t >= 5.3f) {
			float half = H / 2f, move = shutter * (half + 4);
			float seam = (float) Math.sin(Math.PI * seg(t, 5.3f, 5.8f));
			band(cx, half - move, W * (0.5f + shutter), Math.max(2, H / 300f), 0, 0xFFFFFF, seam);
			band(cx, half + move, W * (0.5f + shutter), Math.max(2, H / 300f), 0, accent, seam * 0.85f);
			softLight(cx, half, H * 0.4f * (1 - shutter * 0.5f), accent, 0.45f * seam);
		}
		graphics.pose().popMatrix();
	}

	/** One full frame of the scene (background + content), in physical pixels. */
	private void scene(float t, int W, int H, float gs) {
		GuiGraphics graphics = g;
		float cx = W / 2f, cy = H / 2f;
		int accent = ClientSettings.ACCENT.get() & 0xFFFFFF, accent2 = ClientSettings.ACCENT_2.get() & 0xFFFFFF;
		final int pink = 0xFF4DD8;
		float fadeIn = outCubic(seg(t, 0, 0.45f));
		float contentOut = 1 - inOutCubic(seg(t, 5.42f, 5.85f));
		float push = 1 + 0.08f * outCubic(seg(t, 5.25f, 5.6f));

		// ---------- background
		int bgTop = 0x07070C, bgMid = 0x0E0B1A;
		rect(0, 0, W, H, argb(fadeIn, bgTop));
		float bgAlpha = fadeIn;
		// drifting coloured lights
		float drift = t * 0.6f;
		softLight(cx - W * 0.28f + (float) Math.sin(drift) * W * 0.04f, cy - H * 0.12f + (float) Math.cos(drift * 0.8f) * H * 0.05f, H * 0.95f, accent, 0.30f * bgAlpha);
		softLight(cx + W * 0.30f + (float) Math.cos(drift * 0.9f) * W * 0.05f, cy + H * 0.05f + (float) Math.sin(drift * 0.7f) * H * 0.06f, H * 0.85f, pink, 0.16f * bgAlpha);
		softLight(cx, cy + H * 0.55f, H * 0.9f, accent2, 0.12f * bgAlpha);
		rect(0, 0, W, H, argb(0.25f * bgAlpha, bgMid));

		// perspective grid floor
		float horizon = H * 0.70f;
		float gridA = 0.22f * bgAlpha * outCubic(seg(t, 0.15f, 1.0f));
		for (int k = 0; k < 16; k++) {
			float z = ((k + t * 0.85f) % 16) / 16f;
			float y = horizon + (H - horizon) * (float) Math.pow(z, 2.3);
			rect(0, y, W, Math.max(1, H / 900f), argb(gridA * z, accent));
		}
		for (int j = -12; j <= 12; j++) {
			float xb = cx + j * W * 0.11f;
			line(cx + j * W * 0.008f, horizon, xb, H, Math.max(1, H / 1000f), argb(gridA * 0.55f, accent));
		}
		band(cx, horizon, W * 1.4f, H / 260f + 1, 0, accent, 0.5f * gridA * 3);

		// rising particles
		for (int i = 0; i < particles.length; i += 5) {
			float px = particles[i] * W;
			float py = ((particles[i + 1] - t * particles[i + 2]) % 1f + 1f) % 1f * H;
			float tw = 0.5f + 0.5f * (float) Math.sin(t * 2.4f + particles[i + 4]);
			float size = particles[i + 3] * H / 1080f;
			dot(px, py, size, i % 3 == 0 ? pink : i % 3 == 1 ? accent2 : 0xFFFFFF, (0.25f + 0.55f * tw) * bgAlpha);
		}

		// opening light streaks
		for (int s = 0; s < 3; s++) {
			float p = seg(t, 0.05f + s * 0.12f, 0.95f + s * 0.12f);
			if (p <= 0 || p >= 1) continue;
			float x = -W * 0.3f + inOutCubic(p) * W * 1.6f;
			band(x, cy, W * (0.07f + 0.04f * s), H * 2.2f, (float) Math.toRadians(-18), s == 0 ? accent : s == 1 ? pink : accent2, 0.42f * (float) Math.sin(p * Math.PI));
		}

		// ---------- content (pushes forward and fades in the outro)
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		graphics.pose().scale(push, push);
		graphics.pose().translate(-cx, -cy);
		float a = contentOut;

		// logo group moves up for the credits
		float lift = inOutCubic(seg(t, 2.45f, 3.25f));
		float logoScale = 1 - 0.2f * lift;
		float logoY = cy - S * 0.05f - lift * S * 0.62f;

		// glow behind the logo
		float glowPulse = outCubic(seg(t, 0.6f, 1.25f)) * (0.55f + 0.12f * (float) Math.sin(t * 2.2f)) + 0.6f * (float) Math.sin(Math.PI * seg(t, 0.9f, 1.6f));
		softLight(cx, logoY, S * 2.2f * logoScale, accent, 0.28f * glowPulse * a);

		// letters
		float x0 = cx - logoAdvance * logoScale / 2;
		float lx = x0;
		for (int i = 0; i < 4; i++) {
			Baked b = logo[i];
			float w = b.advance * logoScale;
			float lcx = lx + w / 2;
			float start = 0.35f + i * 0.1f;
			float p = seg(t, start, start + 0.75f);
			if (p > 0) {
				float e = outBack(p);
				float alpha = outCubic(clamp01(p * 1.8f)) * a;
				float off = (1 - e) * S * 0.95f;
				float rot = (1 - outQuint(p)) * (float) Math.toRadians(i % 2 == 0 ? -14 : 12);
				float sc = logoScale * (1.3f - 0.3f * outQuint(p));
				// motion trail
				for (int k = 3; k >= 1; k--) {
					float pk = clamp01(p - k * 0.045f);
					if (pk <= 0 || p >= 0.75f) continue;
					text(b, lcx, logoY + (1 - outBack(pk)) * S * 0.95f, logoScale * (1.3f - 0.3f * outQuint(pk)), (1 - outQuint(pk)) * (float) Math.toRadians(i % 2 == 0 ? -14 : 12),
						alpha * 0.18f / k, mix(0xFFFFFF, accent, 0.5f));
				}
				glow(b, lcx, logoY + off, sc, (0.5f + 0.5f * glowPulse) * alpha);
				text(b, lcx, logoY + off, sc, rot, alpha, 0xFFFFFF);
				// Ø: light slash as it lands
				if (i == 3) {
					float sp = seg(t, 0.98f, 1.32f);
					if (sp > 0 && sp < 1) {
						float ang = (float) Math.toRadians(-58);
						float len = S * 1.1f * logoScale;
						float ex = (float) Math.cos(ang) * len / 2, ey = (float) Math.sin(ang) * len / 2;
						float k = outExpo(sp);
						line(lcx - ex, logoY - ey, lcx - ex + 2 * ex * k, logoY - ey + 2 * ey * k, S * 0.035f, argb((1 - sp) * a, 0xFFFFFF));
						softLight(lcx - ex + 2 * ex * k, logoY - ey + 2 * ey * k, S * 0.35f, 0xFFFFFF, 0.7f * (1 - sp) * a);
					}
				}
			}
			lx += w + S * 0.02f * logoScale;
		}

		// shine sweeping across the logo (clipped to it)
		float shine = seg(t, 1.3f, 2.0f);
		if (shine > 0 && shine < 1) {
			int sx0 = (int) ((x0 - S * 0.1f) / gs), sy0 = (int) ((logoY - S * 0.6f * logoScale) / gs);
			int sx1 = (int) ((x0 + logoAdvance * logoScale + S * 0.1f) / gs), sy1 = (int) ((logoY + S * 0.55f * logoScale) / gs);
			graphics.pose().pushMatrix();
			graphics.pose().identity();
			graphics.enableScissor(sx0, sy0, sx1, sy1);
			graphics.pose().popMatrix();
			float bx = x0 - S * 0.4f + inOutCubic(shine) * (logoAdvance * logoScale + S * 0.8f);
			band(bx, logoY, S * 0.45f, S * 2.2f, (float) Math.toRadians(20), 0xFFFFFF, 0.38f * (float) Math.sin(shine * Math.PI) * a);
			graphics.disableScissor();
		}

		// underline + spark
		float ul = outExpo(seg(t, 1.35f, 2.1f));
		float ulY = logoY + S * 0.47f * logoScale;
		float ulW = logoAdvance * logoScale * 0.92f * ul;
		if (ul > 0) {
			float th = Math.max(2, S * 0.012f);
			tex(ShapeTextures.RAMP, cx - ulW / 2, ulY - th / 2, ulW / 2, th, ShapeTextures.SIZE, 1, argb(a, accent));
			tex(ShapeTextures.RAMP_REV, cx, ulY - th / 2, ulW / 2, th, ShapeTextures.SIZE, 1, argb(a, accent2));
			float sparkX = cx - ulW / 2 + ulW * ((t * 0.55f) % 1f);
			softLight(sparkX, ulY, S * 0.12f, 0xFFFFFF, 0.8f * a * ul);
			dot(sparkX, ulY, th * 0.9f, 0xFFFFFF, a * ul);
		}

		// C L I E N T with tracking animation
		float cp = outExpo(seg(t, 1.55f, 2.6f));
		if (cp > 0) {
			float tracking = (2.2f - 1.65f * cp) * S * 0.12f;
			float total = 0;
			for (Baked b : client) total += b.advance;
			total = (total + tracking * 5) * logoScale;
			float cxx = cx - total / 2, cyy = ulY + S * 0.2f * logoScale;
			for (int i = 0; i < 6; i++) {
				float w = client[i].advance * logoScale;
				float la = outCubic(seg(t, 1.55f + i * 0.05f, 2.1f + i * 0.05f)) * a;
				text(client[i], cxx + w / 2, cyy, logoScale, 0, la, 0xFFFFFF);
				cxx += w + tracking * logoScale;
			}
		}

		// cinematic corner frames + labels
		float fr = outExpo(seg(t, 0.45f, 1.3f));
		if (fr > 0) {
			float m = Math.min(W, H) * 0.045f, L = Math.min(W, H) * 0.06f * fr, th = Math.max(1.5f, H / 540f);
			int fc = argb(0.55f * a, 0xC9C2FF);
			float off = (1 - fr) * m;
			rect(m - off, m - off, L, th, fc); rect(m - off, m - off, th, L, fc);
			rect(W - m + off - L, m - off, L, th, fc); rect(W - m + off - th, m - off, th, L, fc);
			rect(m - off, H - m + off - th, L, th, fc); rect(m - off, H - m + off - L, th, L, fc);
			rect(W - m + off - L, H - m + off - th, L, th, fc); rect(W - m + off - th, H - m + off - L, th, L, fc);
			float la = outCubic(seg(t, 0.9f, 1.6f)) * a;
			text(cornerTL, m + L * 0.2f + cornerTL.w / 2f + (1 - la) * -40, m + L * 0.55f, 1, 0, la, 0xFFFFFF);
			text(cornerBL, m + L * 0.2f + cornerBL.w / 2f + (1 - la) * -40, H - m - L * 0.55f, 1, 0, la, 0xFFFFFF);
			text(cornerBR, W - m - L * 0.2f - cornerBR.w / 2f + (1 - la) * 40, H - m - L * 0.55f, 1, 0, la, 0xFFFFFF);
		}

		// credits
		float creditsY = cy + S * 0.36f;
		float dp = outCubic(seg(t, 2.65f, 3.2f));
		text(devBy, cx, creditsY - S * 0.16f + (1 - dp) * S * 0.12f, 1, 0, dp * a, 0xFFFFFF);
		float namesY = creditsY + S * 0.08f;
		float gap = S * 0.08f;
		float rowW = plume.advance + gap + amp.advance + gap + claude.advance;
		float px0 = cx - rowW / 2;
		float pp = outExpo(seg(t, 2.85f, 3.65f)), cpp = outExpo(seg(t, 2.95f, 3.75f));
		float pX = px0 + plume.advance / 2 - (1 - pp) * W * 0.32f;
		float cX = px0 + plume.advance + gap + amp.advance + gap + claude.advance / 2 + (1 - cpp) * W * 0.32f;
		glow(plume, pX, namesY, 1, 0.55f * pp * a);
		text(plume, pX, namesY, 1, 0, pp * a, 0xFFFFFF);
		glow(claude, cX, namesY, 1, 0.55f * cpp * a);
		text(claude, cX, namesY, 1, 0, cpp * a, 0xFFFFFF);
		float ap = seg(t, 3.15f, 3.55f);
		text(amp, px0 + plume.advance + gap + amp.advance / 2, namesY, 0.4f + 0.6f * outBack(ap), 0, outCubic(ap) * a, 0xFFFFFF);

		// ---------- loading
		float lp = outCubic(seg(t, 3.45f, 3.95f));
		if (lp > 0) {
			float barW = Math.min(W * 0.36f, S * 2.7f), barH = Math.max(4, S * 0.024f);
			float barX = cx - barW / 2, barY = cy + S * 0.98f + (1 - lp) * S * 0.15f;
			float la = lp * a;
			// progress with "real" pauses, done at 5.15 s
			float raw = seg(t, 3.6f, 5.15f);
			float prog = clamp01(0.55f * outCubic(clamp01(raw / 0.45f)) + 0.25f * inOutCubic(seg(raw, 0.5f, 0.75f)) + 0.2f * outExpo(seg(raw, 0.8f, 1f)));
			if (t > 5.15f) prog = 1;
			// track
			dev.zero.render.Render2D.roundedRect(g, (int) barX, (int) barY, (int) barW, (int) barH, (int) (barH / 2), argb(0.14f * la, 0xFFFFFF));
			// fill (accent -> accent2)
			float fw = barW * prog;
			if (fw > 1) {
				dev.zero.render.Render2D.roundedRect(g, (int) barX, (int) barY, Math.max((int) barH, (int) fw), (int) barH, (int) (barH / 2), argb(la, accent));
				tex(ShapeTextures.RAMP, barX, barY, fw, barH, ShapeTextures.SIZE, 1, argb(la, accent2));
				// shimmer
				float sh = (t * 0.9f) % 1f;
				band(barX + fw * sh, barY + barH / 2, barH * 6, barH, 0, 0xFFFFFF, 0.45f * la);
				// glowing tip
				softLight(barX + fw, barY + barH / 2, barH * 5, accent2, 0.75f * la);
				dot(barX + fw, barY + barH / 2, barH * 0.75f, 0xFFFFFF, la);
			}
			// percent
			int pct = Math.round(prog * 100);
			String ps = String.valueOf(pct);
			float pw = percent.advance;
			for (char ch : ps.toCharArray()) pw += digits[ch - '0'].advance;
			float tx = barX + barW - pw, ty = barY - S * 0.075f;
			for (char ch : ps.toCharArray()) {
				Baked d = digits[ch - '0'];
				text(d, tx + d.advance / 2, ty, 1, 0, la, 0xFFFFFF);
				tx += d.advance;
			}
			text(percent, tx + percent.advance / 2, ty, 1, 0, la * 0.7f, 0xFFFFFF);
			// status (cross-fades) + spinner
			int si = prog >= 1 ? 4 : prog < 0.2f ? 0 : prog < 0.45f ? 1 : prog < 0.72f ? 2 : 3;
			float sxText = barX + S * 0.09f;
			Baked st = statuses.get(si);
			text(st, sxText + st.advance / 2, ty, 1, 0, la, si == 4 ? 0x9CFFC0 : 0xFFFFFF);
			float spR = S * 0.026f, spX = barX + spR, spY = ty;
			if (si < 4) {
				for (int k = 0; k < 12; k++) {
					double ang = t * 7 - k * 0.42;
					float fade = 1 - k / 12f;
					dot(spX + (float) Math.cos(ang) * spR, spY + (float) Math.sin(ang) * spR, Math.max(1.2f, spR * 0.22f) * (0.5f + 0.5f * fade), k == 0 ? 0xFFFFFF : accent2, la * fade);
				}
			} else {
				float ck = outBack(seg(t, 5.15f, 5.35f));
				dot(spX, spY, spR * 0.9f * ck, 0x4ADE80, la);
				softLight(spX, spY, spR * 4, 0x4ADE80, 0.5f * la * ck);
			}
		}
		graphics.pose().popMatrix(); // content

		// scanline
		float scan = (t * 0.22f) % 1f;
		band(cx, scan * H, W, H * 0.004f + 1, 0, accent, 0.10f * bgAlpha);

	}

	/** Freeze the clock at a moment (frame capture for the preview video); -1 = run normally. */
	public void freezeForTest(float seconds) { frozen = seconds; }

	/** Seconds elapsed (in-game test). */
	public float elapsed() { return time(); }

	/** Jump to a moment (in-game test screenshots). */
	public void seekForTest(float seconds) {
		startNanos = System.nanoTime() - (long) (seconds * 1e9);
		skipTo = 0;
	}
}
