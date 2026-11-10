package dev.zero.gui.comp;

import dev.zero.gui.CrosshairEditorScreen;
import dev.zero.gui.Theme;
import dev.zero.module.ModuleManager;
import dev.zero.modules.Crosshair;
import dev.zero.render.Render2D;
import dev.zero.setting.PixelGridSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Row with a live preview of the crosshair design; click to open the editor. */
public class PixelGridComp extends SettingComp<PixelGridSetting> {
	public PixelGridComp(PixelGridSetting s) { super(s); }

	@Override public int height() { return 26; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		if (hovered(mx, my)) Render2D.rect(g, x, y, w, height(), Theme.ROW_HOVER);
		Render2D.text(g, setting.getName(), x + 6, y + 4, Theme.TEXT, false);
		Render2D.text(g, "Edit ›", x + 6, y + 14, Theme.accent(), false);
		int bx = x + w - 26;
		Render2D.roundedRect(g, bx, y + 2, 22, 22, 4, 0xFF2A3A55);
		Crosshair c = ModuleManager.crosshair;
		Crosshair.draw(g, setting, bx + 11, y + 13, 1, c != null ? c.color.get() : -1, c == null || c.outline.get(), c != null ? c.outlineColor.get() : 0xC0000000);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my)) return false;
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new CrosshairEditorScreen(setting, mc.screen));
		return true;
	}
}
