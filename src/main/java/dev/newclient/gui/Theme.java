package dev.newclient.gui;

import dev.newclient.config.ClientSettings;
import dev.newclient.util.ColorUtil;

/** Central palette for the ClickGUI. */
public final class Theme {
	private Theme() {}

	public static final int TEXT = 0xFFF2F2F7;
	public static final int SUBTEXT = 0xFF9A9AB0;
	public static final int MUTED = 0xFF5C5C70;
	public static final int ROW_HOVER = 0x18FFFFFF;
	public static final int TRACK = 0xFF2A2A38;
	public static final int FIELD = 0xFF1A1A24;

	public static int panelBg() { return ColorUtil.withAlpha(0x0E0E14, (int) (255 * ClientSettings.GUI_OPACITY.get())); }

	public static int headerBg() { return ColorUtil.withAlpha(0x15151E, Math.min(255, (int) (255 * ClientSettings.GUI_OPACITY.get()) + 15)); }

	public static int settingsBg() { return 0x40000000; }

	public static int accent() { return ClientSettings.accent(); }

	public static int accent2() { return ClientSettings.accent2(); }
}
