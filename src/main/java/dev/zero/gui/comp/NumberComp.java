package dev.zero.gui.comp;

import dev.zero.config.ConfigManager;
import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import dev.zero.setting.NumberSetting;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;

public class NumberComp extends SettingComp<NumberSetting> {
	private boolean dragging;
	private final Anim fill;

	public NumberComp(NumberSetting s) {
		super(s);
		fill = new Anim(s.getFraction(), 18);
	}

	@Override public int height() { return 22; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		if (dragging) update(mx);
		boolean hover = hovered(mx, my);
		if (hover || dragging) Render2D.rect(g, x, y, w, height(), Theme.ROW_HOVER);
		String v = setting.display();
		Render2D.text(g, Render2D.trim(setting.getName(), w - Render2D.width(v) - 16), x + 6, y + 3, Theme.TEXT, false);
		Render2D.text(g, v, x + w - 6 - Render2D.width(v), y + 3, Theme.accent(), false);
		int tx = x + 6, tw = w - 12, ty = y + 15;
		Render2D.roundedRect(g, tx, ty, tw, 3, 1, Theme.TRACK);
		float f = fill.setTarget(setting.getFraction()).getF();
		int fw = Math.max(2, (int) (tw * f));
		Render2D.gradientH(g, tx, ty, fw, 3, Theme.accent(), Theme.accent2());
		int kx = tx + fw;
		Render2D.circle(g, kx, ty + 1, dragging || hover ? 3 : 2, 0xFFFFFFFF);
		if (dragging) Render2D.glow(g, kx - 3, ty - 2, 6, 6, 3, ColorUtil.withAlpha(Theme.accent(), 120), 3);
	}

	private void update(double mx) {
		setting.setFraction((mx - (x + 6)) / (double) (w - 12));
		ConfigManager.markDirty();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my)) return false;
		if (button == 0) {
			dragging = true;
			update(mx);
		} else if (button == 1) {
			setting.reset();
			ConfigManager.markDirty();
		}
		return true;
	}

	@Override
	public void mouseReleased(double mx, double my, int button) { dragging = false; }

	@Override
	public boolean keyPressed(int key) {
		return false;
	}

	@Override
	public String tooltip(double mx, double my) {
		String t = super.tooltip(mx, my);
		return t == null ? null : t + "  §8(right click: reset)";
	}
}
