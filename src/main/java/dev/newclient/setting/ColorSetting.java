package dev.newclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.newclient.util.ColorUtil;

/** ARGB color. Edited in the GUI through an HSV picker with an alpha bar. */
public class ColorSetting extends Setting<Integer> {
	private final boolean allowAlpha;

	public ColorSetting(String name, String description, int argb, boolean allowAlpha) {
		super(name, description, argb);
		this.allowAlpha = allowAlpha;
	}

	public ColorSetting(String name, String description, int argb) { this(name, description, argb, true); }

	public boolean allowsAlpha() { return allowAlpha; }

	public int withAlpha(int alpha) { return ColorUtil.withAlpha(value, alpha); }

	@Override public JsonElement toJson() { return new JsonPrimitive(String.format("#%08X", value)); }

	@Override public void fromJson(JsonElement element) {
		try {
			value = (int) Long.parseLong(element.getAsString().replace("#", ""), 16);
		} catch (NumberFormatException ignored) {
		}
	}
}
