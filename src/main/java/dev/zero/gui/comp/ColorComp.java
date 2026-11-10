package dev.zero.gui.comp;

import dev.zero.config.ConfigManager;
import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import dev.zero.setting.ColorSetting;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Color row with an expandable HSV picker (saturation/value square, hue bar, alpha bar). */
public class ColorComp extends SettingComp<ColorSetting> {
	private static final int PICKER_H = 56;
	private boolean open;
	private final Anim openAnim = new Anim(0, 16);
	private int drag; // 0 none, 1 sv, 2 hue, 3 alpha
	private float hue, sat, val;

	public ColorComp(ColorSetting s) {
		super(s);
		syncFromSetting();
	}

	private void syncFromSetting() {
		float[] hsv = ColorUtil.toHsv(setting.get());
		hue = hsv[0];
		sat = hsv[1];
		val = hsv[2];
	}

	private int extra() { return (int) ((PICKER_H + (setting.allowsAlpha() ? 10 : 0)) * openAnim.getF()); }

	@Override public int height() { return 14 + extra(); }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		openAnim.setTarget(open ? 1 : 0);
		if (drag != 0) update(mx, my);
		if (mx >= x && mx < x + w && my >= y && my < y + 14) Render2D.rect(g, x, y, w, 14, Theme.ROW_HOVER);
		Render2D.text(g, Render2D.trim(setting.getName(), w - 32), x + 6, y + 3, Theme.TEXT, false);
		int c = setting.get();
		// Checkerboard behind the swatch so alpha is visible.
		int sx = x + w - 22, sy = y + 3;
		Render2D.roundedRect(g, sx - 1, sy - 1, 18, 10, 3, 0xFF3A3A48);
		Render2D.roundedRect(g, sx, sy, 16, 8, 2, c);

		int ex = extra();
		if (ex <= 0) return;
		g.enableScissor(x, y + 14, x + w, y + 14 + ex);
		int px = x + 6, py = y + 16, pw = w - 24, ph = PICKER_H - 6;
		// Saturation/value square
		int hueColor = ColorUtil.fromHsv(hue, 1, 1, 255);
		Render2D.gradientH(g, px, py, pw, ph, 0xFFFFFFFF, hueColor);
		Render2D.gradientV(g, px, py, pw, ph, 0x00000000, 0xFF000000);
		int cx = px + (int) (sat * pw), cy = py + (int) ((1 - val) * ph);
		Render2D.circle(g, cx, cy, 3, 0xFFFFFFFF);
		Render2D.circle(g, cx, cy, 2, ColorUtil.withAlpha(c, 255));
		// Hue bar
		int hx = px + pw + 4, hw = 8;
		for (int i = 0; i < 6; i++) {
			int top = ColorUtil.fromHsv(i / 6f, 1, 1, 255), bot = ColorUtil.fromHsv((i + 1) / 6f, 1, 1, 255);
			int y1 = py + ph * i / 6, y2 = py + ph * (i + 1) / 6;
			Render2D.gradientV(g, hx, y1, hw, y2 - y1, top, bot);
		}
		int hy = py + (int) (hue * ph);
		Render2D.rect(g, hx - 1, hy - 1, hw + 2, 2, 0xFFFFFFFF);
		// Alpha bar
		if (setting.allowsAlpha()) {
			int ay = py + ph + 4;
			Render2D.rect(g, px, ay, pw + 12, 5, 0xFF3A3A48);
			Render2D.gradientH(g, px, ay, pw + 12, 5, ColorUtil.withAlpha(c, 0), ColorUtil.withAlpha(c, 255));
			int ax = px + (int) ((pw + 12) * (ColorUtil.a(c) / 255f));
			Render2D.rect(g, ax - 1, ay - 1, 2, 7, 0xFFFFFFFF);
		}
		g.disableScissor();
	}

	private void update(double mx, double my) {
		int px = x + 6, py = y + 16, pw = w - 24, ph = PICKER_H - 6;
		int alpha = ColorUtil.a(setting.get());
		switch (drag) {
			case 1 -> {
				sat = (float) Mth.clamp((mx - px) / pw, 0, 1);
				val = 1 - (float) Mth.clamp((my - py) / ph, 0, 1);
			}
			case 2 -> hue = (float) Mth.clamp((my - py) / ph, 0, 0.999);
			case 3 -> alpha = (int) (Mth.clamp((mx - px) / (pw + 12), 0, 1) * 255);
			default -> {}
		}
		setting.set(ColorUtil.fromHsv(hue, sat, val, setting.allowsAlpha() ? alpha : 255));
		ConfigManager.markDirty();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my)) return false;
		if (my < y + 14) {
			if (button == 1) {
				setting.reset();
				syncFromSetting();
			} else open = !open;
			return true;
		}
		int px = x + 6, py = y + 16, pw = w - 24, ph = PICKER_H - 6;
		if (mx >= px && mx <= px + pw && my >= py && my <= py + ph) drag = 1;
		else if (mx >= px + pw + 3 && mx <= px + pw + 13 && my >= py && my <= py + ph) drag = 2;
		else if (setting.allowsAlpha() && my >= py + ph + 2 && my <= py + ph + 10) drag = 3;
		if (drag != 0) update(mx, my);
		return true;
	}

	@Override
	public void mouseReleased(double mx, double my, int button) { drag = 0; }
}
