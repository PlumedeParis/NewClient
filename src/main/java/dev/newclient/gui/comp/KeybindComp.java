package dev.newclient.gui.comp;

import dev.newclient.config.ConfigManager;
import dev.newclient.gui.Theme;
import dev.newclient.render.Render2D;
import dev.newclient.setting.KeybindSetting;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

public class KeybindComp extends SettingComp<KeybindSetting> {
	public static KeybindComp listening;

	public KeybindComp(KeybindSetting s) { super(s); }

	@Override public int height() { return 14; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		if (hovered(mx, my)) Render2D.rect(g, x, y, w, height(), Theme.ROW_HOVER);
		boolean l = listening == this;
		String v = l ? "..." : setting.display();
		int vw = Render2D.width(v);
		Render2D.text(g, Render2D.trim(setting.getName(), w - vw - 22), x + 6, y + 3, Theme.TEXT, false);
		int px = x + w - 10 - vw;
		Render2D.roundedRect(g, px - 3, y + 1, vw + 7, 12, 3, l ? Theme.accent() : Theme.FIELD);
		Render2D.text(g, v, px + 1, y + 3, l ? 0xFFFFFFFF : Theme.SUBTEXT, false);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my)) return false;
		if (button == 1) {
			setting.set(GLFW.GLFW_KEY_UNKNOWN);
			listening = null;
			ConfigManager.markDirty();
		} else listening = listening == this ? null : this;
		return true;
	}

	@Override
	public boolean keyPressed(int key) {
		if (listening != this) return false;
		if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) setting.set(GLFW.GLFW_KEY_UNKNOWN);
		else setting.set(key);
		listening = null;
		ConfigManager.markDirty();
		return true;
	}

	@Override
	public String tooltip(double mx, double my) {
		String t = super.tooltip(mx, my);
		return t == null ? null : t + "  §8(click, then press a key; right click: unbind)";
	}
}
