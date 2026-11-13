package dev.zero.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A titled group in a settings list: the settings after it (until the next section) fold under it. */
public class SectionSetting extends Setting<Boolean> {
	public SectionSetting(String name, boolean expandedByDefault) {
		super(name, "", expandedByDefault);
	}

	@Override public JsonElement toJson() { return JsonNull.INSTANCE; }
	@Override public void fromJson(JsonElement element) {}
}
