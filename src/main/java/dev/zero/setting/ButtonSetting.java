package dev.zero.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A clickable row that runs an action (e.g. opens an editor). Has no saved value. */
public class ButtonSetting extends Setting<Runnable> {
	private final String label;

	public ButtonSetting(String name, String description, String label, Runnable action) {
		super(name, description, action);
		this.label = label;
	}

	public String getLabel() { return label; }

	public void press() { value.run(); }

	@Override public JsonElement toJson() { return JsonNull.INSTANCE; }

	@Override public void fromJson(JsonElement element) {}
}
