package dev.zero.hud;

import dev.zero.config.ClientSettings;
import dev.zero.render.Render2D;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Small label next to the crosshair naming the highlighted thing you are aiming at. */
public final class LookInfo {
	private static volatile String text;
	private static volatile int color;
	private static volatile double distance;

	private LookInfo() {}

	public static void set(String t, int c, double d) {
		text = t;
		color = c;
		distance = d;
	}

	public static String current() { return text; }

	public static void render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		String t = text;
		if (t == null || !ClientSettings.LOOK_INFO.get() || mc.screen != null) return;
		String dist = WorldUtil.distance(distance);
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		int w = Render2D.width(t) + Render2D.width("  " + dist) + 16, h = 14;
		int x = cx + 10, y = cy + 6;
		if (x + w > g.guiWidth() - 2) x = cx - 10 - w;
		int c = ColorUtil.withAlpha(color, 255);
		Render2D.roundedRect(g, x, y, w, h, 4, 0xD8101016);
		Render2D.roundedRect(g, x + 4, y + 4, 6, 6, 3, c);
		Render2D.text(g, t, x + 13, y + 3, 0xFFF2F2F7, false);
		Render2D.text(g, "  " + dist, x + 13 + Render2D.width(t), y + 3, 0xFF9A9AB0, false);
	}
}
