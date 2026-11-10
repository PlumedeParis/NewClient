package dev.zero.gui.comp;

import dev.zero.config.ConfigManager;
import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import dev.zero.setting.EnumSetting;
import dev.zero.util.Sounds;
import net.minecraft.client.gui.GuiGraphics;

public class EnumComp extends SettingComp<EnumSetting<?>> {
	public EnumComp(EnumSetting<?> s) { super(s); }

	@Override public int height() { return 14; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		if (hovered(mx, my)) Render2D.rect(g, x, y, w, height(), Theme.ROW_HOVER);
		String v = setting.display();
		int vw = Render2D.width(v);
		Render2D.text(g, Render2D.trim(setting.getName(), w - vw - 22), x + 6, y + 3, Theme.TEXT, false);
		int px = x + w - 6 - vw - 4;
		Render2D.roundedRect(g, px - 3, y + 1, vw + 10, 12, 3, Theme.FIELD);
		Render2D.text(g, v, px + 2, y + 3, Theme.accent(), false);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my) || button > 1) return false;
		setting.cycle(button == 0);
		Sounds.click();
		ConfigManager.markDirty();
		return true;
	}

	@Override
	public String tooltip(double mx, double my) {
		String t = super.tooltip(mx, my);
		return t == null ? null : t + "  §8(left/right click to cycle)";
	}
}
