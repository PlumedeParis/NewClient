package dev.newclient.gui.comp;

import dev.newclient.config.ConfigManager;
import dev.newclient.gui.Theme;
import dev.newclient.render.Render2D;
import dev.newclient.setting.BoolSetting;
import dev.newclient.util.Sounds;
import net.minecraft.client.gui.GuiGraphics;

public class BoolComp extends SettingComp<BoolSetting> {
	private final Switch sw;

	public BoolComp(BoolSetting s) {
		super(s);
		sw = new Switch(s.get());
	}

	@Override public int height() { return 14; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		if (hovered(mx, my)) Render2D.rect(g, x, y, w, height(), Theme.ROW_HOVER);
		Render2D.text(g, Render2D.trim(setting.getName(), w - 32), x + 6, y + 3, setting.get() ? Theme.TEXT : Theme.SUBTEXT, false);
		sw.render(g, x + w - 22, y + 3, setting.get());
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my) || button != 0) return false;
		setting.toggle();
		Sounds.click();
		ConfigManager.markDirty();
		return true;
	}
}
