package dev.zero.gui;

import dev.zero.config.ClientSettings;
import dev.zero.util.ColorUtil;

/** Central palette for the ClickGUI. */
public final class Theme {
	private Theme() {}

	/** Color themes: two accent colors and the tint of panels. Custom takes the accents from the settings. */
	public enum Preset {
		ZERO(0xFF8B7CFF, 0xFF4FD1C5, 0x0E0E14, 0x15151E),
		MIDNIGHT(0xFF5B8CFF, 0xFF9B7CFF, 0x0A0D18, 0x111627),
		NEON(0xFFFF4DD8, 0xFF3DE8FF, 0x100A16, 0x1A1024),
		EMBER(0xFFFF7A3D, 0xFFFF3B6B, 0x140C0A, 0x201310),
		AURORA(0xFF3DDC97, 0xFF4FB8FF, 0x08120F, 0x0F1D19),
		GOLD(0xFFFFC857, 0xFFFF8A3D, 0x12100A, 0x1D1910),
		MONO(0xFFE8E8F0, 0xFF8E8EA0, 0x0C0C0E, 0x161619),
		CUSTOM(0xFF8B7CFF, 0xFF4FD1C5, 0x0E0E14, 0x15151E);

		public final int accent, accent2, panel, header;

		Preset(int accent, int accent2, int panel, int header) {
			this.accent = accent;
			this.accent2 = accent2;
			this.panel = panel;
			this.header = header;
		}
	}

	public static final int TEXT = 0xFFF2F2F7;
	public static final int SUBTEXT = 0xFF9A9AB0;
	public static final int MUTED = 0xFF5C5C70;
	public static final int ROW_HOVER = 0x18FFFFFF;
	public static final int TRACK = 0xFF2A2A38;
	public static final int FIELD = 0xFF1A1A24;

	public static int panelBg() { return ColorUtil.withAlpha(ClientSettings.THEME.get().panel, (int) (255 * ClientSettings.GUI_OPACITY.get())); }

	public static int headerBg() { return ColorUtil.withAlpha(ClientSettings.THEME.get().header, Math.min(255, (int) (255 * ClientSettings.GUI_OPACITY.get()) + 15)); }

	public static int settingsBg() { return 0x40000000; }

	public static int accent() { return ClientSettings.accent(); }

	public static int accent2() { return ClientSettings.accent2(); }
}
