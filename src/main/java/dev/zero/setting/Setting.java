package dev.zero.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

/** Base class of every configurable value shown in the ClickGUI and saved to the config. */
public abstract class Setting<T> {
	private final String name;
	private final String description;
	protected T value;
	private final T defaultValue;
	private BooleanSupplier visibility = () -> true;
	private Runnable onChange = () -> {};

	protected Setting(String name, String description, T defaultValue) {
		this.name = name;
		this.description = description;
		this.value = defaultValue;
		this.defaultValue = defaultValue;
	}

	public String getName() { return name; }
	public String getDescription() { return description; }
	public T get() { return value; }
	public T getDefault() { return defaultValue; }

	public void set(T value) {
		this.value = value;
		onChange.run();
	}

	public void reset() { set(copyDefault()); }

	protected T copyDefault() { return defaultValue; }

	public boolean isVisible() { return visibility.getAsBoolean(); }

	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier supplier) {
		this.visibility = supplier;
		return (S) this;
	}

	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S onChange(Runnable runnable) {
		this.onChange = runnable;
		return (S) this;
	}

	public abstract JsonElement toJson();

	public abstract void fromJson(JsonElement element);
}
