package dev.zero.hud;

import dev.zero.config.ClientSettings;
import dev.zero.render.Render2D;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;

/** "ZERØ client, made by PlumeTheCat" in the top-right corner of the title screen. */
public final class TitleBranding {
	private static final String NAME = "ZERØ";
	private static final String REST = " client, made by PlumeTheCat";

	private TitleBranding() {}

	public static void render(GuiGraphics g, int screenWidth) {
		int w = Render2D.width(NAME) + Render2D.width(REST) + 12;
		int x = screenWidth - w - 4, y = 4, h = 15;
		Render2D.glow(g, x, y, w, h, 5, ColorUtil.withAlpha(ClientSettings.accent(), 60), 5);
		Render2D.roundedRect(g, x, y, w, h, 5, 0xB0101016);
		Render2D.gradientH(g, x + 5, y, w - 10, 1, ClientSettings.accent(), ClientSettings.accent2());
		Render2D.gradientText(g, NAME, x + 6, y + 4, i -> ClientSettings.accentAt(i / 9f), true);
		Render2D.text(g, REST, x + 6 + Render2D.width(NAME), y + 4, 0xFFE6E6F0, true);
	}
}
