package dev.zero.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A free text value (e.g. a world seed), edited with a text box in the GUI. */
public class TextSetting extends Setting<String> {
	private final String placeholder;
	private final int maxLength;

	public TextSetting(String name, String description, String defaultValue, String placeholder, int maxLength) {
		super(name, description, defaultValue);
		this.placeholder = placeholder;
		this.maxLength = maxLength;
	}

	public String getPlaceholder() { return placeholder; }

	public int getMaxLength() { return maxLength; }

	@Override public JsonElement toJson() { return new JsonPrimitive(value); }

	@Override public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) value = element.getAsString();
	}
}
