package dev.zero.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A list of free text entries (e.g. friend names). */
public class StringListSetting extends Setting<List<String>> {
	public StringListSetting(String name, String description, List<String> defaults) {
		super(name, description, new ArrayList<>(defaults));
	}

	@Override protected List<String> copyDefault() { return new ArrayList<>(getDefault()); }

	public boolean containsIgnoreCase(String s) {
		for (String v : value) if (v.equalsIgnoreCase(s)) return true;
		return false;
	}

	public void add(String s) {
		s = s.trim();
		if (s.isEmpty() || containsIgnoreCase(s)) return;
		List<String> copy = new ArrayList<>(value);
		copy.add(s);
		set(copy);
	}

	public void remove(String s) {
		List<String> copy = new ArrayList<>(value);
		copy.removeIf(v -> v.toLowerCase(Locale.ROOT).equals(s.toLowerCase(Locale.ROOT)));
		set(copy);
	}

	@Override
	public JsonElement toJson() {
		JsonArray array = new JsonArray();
		value.forEach(array::add);
		return array;
	}

	@Override
	public void fromJson(JsonElement element) {
		if (!element.isJsonArray()) return;
		List<String> list = new ArrayList<>();
		element.getAsJsonArray().forEach(e -> list.add(e.getAsString()));
		value = list;
	}
}
