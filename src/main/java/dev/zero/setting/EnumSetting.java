package dev.zero.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class EnumSetting<E extends Enum<E>> extends Setting<E> {
	private final E[] values;

	public EnumSetting(String name, String description, E defaultValue) {
		super(name, description, defaultValue);
		this.values = defaultValue.getDeclaringClass().getEnumConstants();
	}

	public E[] getValues() { return values; }

	public void cycle(boolean forward) {
		int i = value.ordinal() + (forward ? 1 : -1);
		set(values[(i + values.length) % values.length]);
	}

	public String display() {
		String n = value.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	@Override public JsonElement toJson() { return new JsonPrimitive(value.name()); }

	@Override public void fromJson(JsonElement element) {
		for (E e : values) if (e.name().equalsIgnoreCase(element.getAsString())) value = e;
	}
}
