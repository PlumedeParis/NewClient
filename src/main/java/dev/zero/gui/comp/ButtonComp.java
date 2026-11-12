package dev.zero.gui.comp;

import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import dev.zero.setting.ButtonSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import net.minecraft.client.gui.GuiGraphics;

public class ButtonComp extends SettingComp<ButtonSetting> {
	public ButtonComp(ButtonSetting s) { super(s); }

	@Override public int height() { return 18; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		boolean hover = hovered(mx, my);
		Render2D.roundedRect(g, x + 5, y + 2, w - 10, 14, 4, hover ? ColorUtil.withAlpha(Theme.accent(), 150) : Theme.FIELD);
		Render2D.centeredText(g, setting.getLabel(), x + w / 2, y + 5, hover ? 0xFFFFFFFF : Theme.TEXT, false);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my) || button != 0) return false;
		Sounds.click();
		setting.press();
		return true;
	}
}
