package dev.zero.gui.comp;

import dev.zero.config.ConfigManager;
import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import dev.zero.setting.StringListSetting;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Editable list of names (e.g. friends): click × to remove, type + Enter to add. */
public class StringListComp extends SettingComp<StringListSetting> {
	private TextField field;

	public StringListComp(StringListSetting s) {
		super(s);
		field = new TextField("+ Add name...", 16, text -> {
			if (!text.isBlank()) {
				setting.add(text);
				ConfigManager.markDirty();
			}
			field.setText("");
		}, null);
	}

	@Override public int height() { return 14 + setting.get().size() * 11 + 16; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		Render2D.text(g, setting.getName(), x + 6, y + 3, Theme.TEXT, false);
		String count = String.valueOf(setting.get().size());
		Render2D.text(g, count, x + w - 6 - Render2D.width(count), y + 3, Theme.SUBTEXT, false);
		List<String> list = setting.get();
		int ry = y + 14;
		for (String s : list) {
			boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + 11;
			if (hover) Render2D.rect(g, x, ry, w, 11, Theme.ROW_HOVER);
			Render2D.text(g, s, x + 10, ry + 2, Theme.SUBTEXT, false);
			Render2D.text(g, "×", x + w - 12, ry + 1, hover ? 0xFFFF6B81 : Theme.MUTED, false);
			ry += 11;
		}
		field.render(g, x + 6, ry + 1, w - 12, 13, Theme.FIELD);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my)) return false;
		int ry = y + 14;
		for (String s : List.copyOf(setting.get())) {
			if (my >= ry && my < ry + 11) {
				if (mx >= x + w - 16) {
					setting.remove(s);
					ConfigManager.markDirty();
				}
				return true;
			}
			ry += 11;
		}
		if (my >= ry) TextField.focused = field;
		return true;
	}

	@Override public boolean keyPressed(int key) { return field.keyPressed(key); }

	@Override public boolean charTyped(char c) { return field.charTyped(c); }
}
