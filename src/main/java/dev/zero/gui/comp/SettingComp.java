package dev.zero.gui.comp;

import dev.zero.setting.BlockListSetting;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.KeybindSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.setting.Setting;
import dev.zero.setting.StringListSetting;

public abstract class SettingComp<S extends Setting<?>> extends Comp {
	protected final S setting;

	protected SettingComp(S setting) {
		this.setting = setting;
	}

	@Override
	public boolean visible() { return setting.isVisible(); }

	@Override
	public String tooltip(double mx, double my) {
		return hovered(mx, my) ? setting.getName() + "  §7" + setting.getDescription() : null;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Comp of(Setting<?> s) {
		if (s instanceof BoolSetting b) return new BoolComp(b);
		if (s instanceof NumberSetting n) return new NumberComp(n);
		if (s instanceof EnumSetting e) return new EnumComp(e);
		if (s instanceof ColorSetting c) return new ColorComp(c);
		if (s instanceof BlockListSetting bl) return new BlockListComp(bl);
		if (s instanceof StringListSetting sl) return new StringListComp(sl);
		if (s instanceof KeybindSetting k) return new KeybindComp(k);
		throw new IllegalArgumentException("No component for " + s.getClass());
	}
}
