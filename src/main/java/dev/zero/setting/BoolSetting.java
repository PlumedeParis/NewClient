package dev.zero.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {
	public BoolSetting(String name, String description, boolean defaultValue) {
		super(name, description, defaultValue);
	}

	public void toggle() { set(!value); }

	@Override public JsonElement toJson() { return new JsonPrimitive(value); }

	@Override public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) value = element.getAsBoolean();
	}
}
