package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.setting.NumberSetting;

/**
 * See in the dark: every light level is drawn as bright as night vision makes it.
 * Only changes how your own screen is lit (the lightmap); nothing is sent to the server.
 */
public class Fullbright extends Module {
	private final NumberSetting strength = add(new NumberSetting("Strength", "1 = everything fully lit, lower = keep some shading", 1.0, 0.1, 1.0, 0.05));

	public Fullbright() {
		super("Fullbright", "See in the dark (local lighting only)", Category.RENDER);
	}

	/** Night-vision factor to force, 0 when off. */
	public static float factor() {
		Fullbright f = ModuleManager.fullbright;
		return f != null && f.isEnabled() ? f.strength.getFloat() : 0f;
	}
}
