package dev.newclient.gui.comp;

import dev.newclient.gui.Theme;
import dev.newclient.render.Render2D;
import dev.newclient.util.Anim;
import dev.newclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;

/** Animated pill toggle switch. */
public final class Switch {
	private final Anim anim;

	public Switch(boolean initial) {
		anim = new Anim(initial ? 1 : 0, 16);
	}

	public void render(GuiGraphics g, int x, int y, boolean on) {
		anim.setTarget(on ? 1 : 0);
		float t = anim.getF();
		int w = 16, h = 8;
		int track = ColorUtil.lerp(Theme.TRACK, Theme.accent(), t);
		Render2D.roundedRect(g, x, y, w, h, 4, track);
		int knobX = (int) (x + 1 + (w - h) * t);
		Render2D.roundedRect(g, knobX, y + 1, h - 2, h - 2, 3, ColorUtil.lerp(0xFFB8B8C8, 0xFFFFFFFF, t));
	}
}
