package dev.zero.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Mth;

public class NumberSetting extends Setting<Double> {
	private final double min, max, step;
	private final String unit;

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step, String unit) {
		super(name, description, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
		this.unit = unit;
	}

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
		this(name, description, defaultValue, min, max, step, "");
	}

	public double getMin() { return min; }
	public double getMax() { return max; }
	public double getStep() { return step; }
	public int getInt() { return (int) Math.round(value); }
	public float getFloat() { return value.floatValue(); }

	@Override
	public void set(Double v) {
		double snapped = Math.round(v / step) * step;
		super.set(Mth.clamp(snapped, min, max));
	}

	/** Sets from a 0..1 slider fraction. */
	public void setFraction(double f) { set(min + (max - min) * Mth.clamp(f, 0, 1)); }

	public double getFraction() { return (value - min) / (max - min); }

	public String display() {
		boolean integral = step >= 1 && Math.abs(step - Math.rint(step)) < 1e-9;
		String num = integral ? String.valueOf((long) Math.round(value)) : String.format(java.util.Locale.ROOT, step >= 0.1 ? "%.1f" : "%.2f", value);
		return num + unit;
	}

	@Override public JsonElement toJson() { return new JsonPrimitive(value); }

	@Override public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) value = Mth.clamp(element.getAsDouble(), min, max);
	}
}
