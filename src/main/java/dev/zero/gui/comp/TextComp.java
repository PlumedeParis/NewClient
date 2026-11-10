package dev.zero.gui.comp;

import dev.zero.config.ConfigManager;
import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import dev.zero.setting.TextSetting;
import net.minecraft.client.gui.GuiGraphics;

/** Text box row. The value is applied on Enter or when the box loses focus. */
public class TextComp extends SettingComp<TextSetting> {
	private final TextField field;

	public TextComp(TextSetting s) {
		super(s);
		field = new TextField(s.getPlaceholder(), s.getMaxLength(), t -> {
			apply();
			TextField.focused = null;
		}, null);
		field.setText(s.get());
	}

	@Override public int height() { return 30; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		if (!field.isFocused() && !field.getText().equals(setting.get())) {
			if (lastFocused) apply();
			else field.setText(setting.get());
		}
		lastFocused = field.isFocused();
		Render2D.text(g, Render2D.trim(setting.getName(), w - 12), x + 6, y + 3, Theme.TEXT, false);
		field.render(g, x + 6, y + 14, w - 12, 13, Theme.FIELD);
	}

	private boolean lastFocused;

	private void apply() {
		setting.set(field.getText().trim());
		ConfigManager.markDirty();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my)) return false;
		TextField.focused = field;
		if (button == 1) field.setText("");
		return true;
	}

	@Override public boolean keyPressed(int key) { return field.keyPressed(key); }

	@Override public boolean charTyped(char c) { return field.charTyped(c); }

	@Override
	public String tooltip(double mx, double my) {
		String t = super.tooltip(mx, my);
		return t == null ? null : t + "  §8(Enter to apply)";
	}
}
