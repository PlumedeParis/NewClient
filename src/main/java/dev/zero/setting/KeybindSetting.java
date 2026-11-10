package dev.zero.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.lwjgl.glfw.GLFW;

/** A GLFW key code; {@link GLFW#GLFW_KEY_UNKNOWN} means unbound. */
public class KeybindSetting extends Setting<Integer> {
	public KeybindSetting(String name, String description, int key) {
		super(name, description, key);
	}

	public boolean isBound() { return value != GLFW.GLFW_KEY_UNKNOWN; }

	public String display() { return keyName(value); }

	public static String keyName(int key) {
		if (key == GLFW.GLFW_KEY_UNKNOWN) return "None";
		return switch (key) {
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
			case GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift";
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl";
			case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
			case GLFW.GLFW_KEY_RIGHT_ALT -> "RAlt";
			case GLFW.GLFW_KEY_LEFT_ALT -> "LAlt";
			case GLFW.GLFW_KEY_INSERT -> "Insert";
			case GLFW.GLFW_KEY_DELETE -> "Delete";
			case GLFW.GLFW_KEY_HOME -> "Home";
			case GLFW.GLFW_KEY_END -> "End";
			case GLFW.GLFW_KEY_PAGE_UP -> "PgUp";
			case GLFW.GLFW_KEY_PAGE_DOWN -> "PgDn";
			case GLFW.GLFW_KEY_TAB -> "Tab";
			case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps";
			case GLFW.GLFW_KEY_SPACE -> "Space";
			case GLFW.GLFW_KEY_UP -> "Up";
			case GLFW.GLFW_KEY_DOWN -> "Down";
			case GLFW.GLFW_KEY_LEFT -> "Left";
			case GLFW.GLFW_KEY_RIGHT -> "Right";
			default -> {
				if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) yield "F" + (key - GLFW.GLFW_KEY_F1 + 1);
				if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) yield "Num" + (key - GLFW.GLFW_KEY_KP_0);
				String n = GLFW.glfwGetKeyName(key, 0);
				yield n == null ? "Key" + key : n.toUpperCase(java.util.Locale.ROOT);
			}
		};
	}

	@Override public JsonElement toJson() { return new JsonPrimitive(value); }

	@Override public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) value = element.getAsInt();
	}
}
