package dev.zero.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.KeybindSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.setting.Setting;
import dev.zero.util.ColorUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Global client options (theme + HUD). These are NOT modules; they live in the GUI's "Client" panel. */
public final class ClientSettings {
	public enum ArrayListSide { RIGHT, LEFT }

	public static final List<Setting<?>> SETTINGS = new ArrayList<>();

	public static final KeybindSetting GUI_KEY = add(new KeybindSetting("GUI Key", "Opens the ClickGUI", GLFW.GLFW_KEY_RIGHT_SHIFT));
	public static final ColorSetting ACCENT = add(new ColorSetting("Accent", "Main theme color", 0xFF8B7CFF, false));
	public static final ColorSetting ACCENT_2 = add(new ColorSetting("Accent 2", "Second gradient color", 0xFF4FD1C5, false));
	public static final BoolSetting CHROMA = add(new BoolSetting("Chroma", "Animated rainbow accent", false));
	public static final NumberSetting GUI_OPACITY = add(new NumberSetting("Panel Opacity", "ClickGUI background opacity", 0.86, 0.4, 1.0, 0.01));
	public static final BoolSetting BLUR = add(new BoolSetting("Blur", "Blur the world behind the ClickGUI", true));
	public static final BoolSetting WATERMARK = add(new BoolSetting("Watermark", "Show the client watermark", true));
	public static final BoolSetting ARRAY_LIST = add(new BoolSetting("Module List", "Show enabled modules", true));
	public static final EnumSetting<ArrayListSide> ARRAY_SIDE = add(new EnumSetting<>("List Side", "Side of the module list", ArrayListSide.RIGHT));
	public static final BoolSetting INFO_HUD = add(new BoolSetting("Info HUD", "Coords, nether coords, facing and FPS", true));
	public static final BoolSetting NOTIFICATIONS = add(new BoolSetting("Notifications", "Animated toasts", true));
	public static final BoolSetting TOGGLE_TOASTS = add(new BoolSetting("Toggle Toasts", "Toast when a module is toggled", true));
	public static final NumberSetting MAX_LABELS = add(new NumberSetting("Max Labels", "Most 3D text labels drawn per frame (nearest first). Lower = more FPS", 40, 5, 200, 5));
	public static final NumberSetting LABEL_RANGE = add(new NumberSetting("Label Range", "Labels farther than this are not drawn", 160, 32, 512, 16, "m"));
	public static final NumberSetting DETAIL_DISTANCE = add(new NumberSetting("Detail Distance", "Beyond this, filled ESP boxes skip their outlines (512 = always outline)", 64, 16, 512, 16, "m"));
	public static final BoolSetting PERF_HUD = add(new BoolSetting("Perf HUD", "Show how many ms per frame ZERØ itself uses", false));
	public static final NumberSetting SCAN_BUDGET = add(new NumberSetting("Scan Budget", "Max ms per tick spent analysing chunks (auto-lowered when FPS drops)", 3, 0.5, 8, 0.5, "ms"));
	public static final dev.zero.setting.ButtonSetting BLOCK_COLORS = add(new dev.zero.setting.ButtonSetting("Block Colors", "Choose the color of each ore / block", "\u25C8 Block Colors \u203A", () -> net.minecraft.client.Minecraft.getInstance().setScreen(new dev.zero.gui.BlockColorScreen(net.minecraft.client.Minecraft.getInstance().screen))));
	public static final BoolSetting LOOK_INFO = add(new BoolSetting("Look Info", "Label next to the crosshair naming the highlighted thing you aim at", true));
	public static final BoolSetting STREAMER = add(new BoolSetting("Hide Coords", "Hide coordinates on the HUD (streamer mode)", false));

	private ClientSettings() {}

	private static <S extends Setting<?>> S add(S s) {
		SETTINGS.add(s);
		return s;
	}

	public static int accent() {
		return CHROMA.get() ? ColorUtil.chroma(0, 1f, 0.55f, 1f) : ACCENT.get();
	}

	public static int accent2() {
		return CHROMA.get() ? ColorUtil.chroma(1500, 1f, 0.55f, 1f) : ACCENT_2.get();
	}

	/** Gradient accent at position t (0..1) for gradient texts / bars, slowly animated. */
	public static int accentAt(float t) {
		double wave = (Math.sin((System.currentTimeMillis() / 1200.0) + t * Math.PI * 2) + 1) / 2;
		return ColorUtil.lerp(accent(), accent2(), (float) wave);
	}

	public static JsonObject toJson() {
		JsonObject o = new JsonObject();
		for (Setting<?> s : SETTINGS) o.add(s.getName(), s.toJson());
		return o;
	}

	public static void fromJson(JsonObject o) {
		for (Setting<?> s : SETTINGS) {
			JsonElement e = o.get(s.getName());
			if (e != null) {
				try {
					s.fromJson(e);
				} catch (Exception ignored) {
				}
			}
		}
	}
}
