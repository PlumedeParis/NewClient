package dev.zero.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.zero.hud.Notifications;
import dev.zero.setting.KeybindSetting;
import dev.zero.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A toggleable feature. Every module in this client is purely client-side: it only
 * reads data the server already sent and draws on the local screen.
 */
public abstract class Module {
	protected static final Minecraft mc = Minecraft.getInstance();

	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean enabled;

	public final KeybindSetting keybind;

	protected Module(String name, String description, Category category) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.keybind = new KeybindSetting("Keybind", "Key that toggles this module", GLFW.GLFW_KEY_UNKNOWN);
	}

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public String getName() { return name; }
	public String getDescription() { return description; }
	public Category getCategory() { return category; }
	public List<Setting<?>> getSettings() { return settings; }
	public boolean isEnabled() { return enabled; }

	/** Optional short info shown next to the module name in the HUD array list. */
	public String getInfo() { return null; }

	public void toggle() { setEnabled(!enabled); }

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		this.enabled = enabled;
		if (enabled) onEnable();
		else onDisable();
		dev.zero.config.ConfigManager.markDirty();
		Notifications.push(name, enabled ? "Enabled" : "Disabled", enabled ? Notifications.Type.ENABLED : Notifications.Type.DISABLED);
	}

	/** Used by the config loader: changes state without notifications. */
	public void setEnabledSilently(boolean enabled) {
		if (this.enabled == enabled) return;
		this.enabled = enabled;
		if (enabled) onEnable();
		else onDisable();
	}

	protected void onEnable() {}
	protected void onDisable() {}

	/** Called every client tick while enabled and in a world. */
	public void onTick() {}

	/** Called at the end of the main world pass while enabled; queue shapes with {@link dev.zero.render.Render3D}. */
	public void onRender3D(float partialTick) {}

	/** Called while the HUD renders. */
	public void onRender2D(GuiGraphics graphics, float partialTick) {}

	/** Called when the player leaves a world/server. */
	public void onWorldLeave() {}

	public JsonObject toJson() {
		JsonObject obj = new JsonObject();
		obj.addProperty("enabled", enabled);
		obj.add("keybind", keybind.toJson());
		JsonObject s = new JsonObject();
		for (Setting<?> setting : settings) s.add(setting.getName(), setting.toJson());
		obj.add("settings", s);
		return obj;
	}

	public void fromJson(JsonObject obj) {
		if (obj.has("keybind")) keybind.fromJson(obj.get("keybind"));
		if (obj.has("settings")) {
			JsonObject s = obj.getAsJsonObject("settings");
			for (Setting<?> setting : settings) {
				JsonElement e = s.get(setting.getName());
				if (e != null) {
					try {
						setting.fromJson(e);
					} catch (Exception ignored) {
					}
				}
			}
		}
		if (obj.has("enabled")) setEnabledSilently(obj.get("enabled").getAsBoolean());
	}
}
