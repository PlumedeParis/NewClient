package dev.zero.gui.comp;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zero.gui.Theme;
import dev.zero.util.ColorUtil;
import net.minecraft.client.Minecraft;
import dev.zero.render.Render2D;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** Minimal single-line text input with placeholder and blinking caret. */
public class TextField {
	public static TextField focused;

	private String text = "";
	private final String placeholder;
	private final int maxLength;
	private final Consumer<String> onEnter;
	private final Consumer<String> onChange;

	public TextField(String placeholder, int maxLength, Consumer<String> onEnter, Consumer<String> onChange) {
		this.placeholder = placeholder;
		this.maxLength = maxLength;
		this.onEnter = onEnter;
		this.onChange = onChange;
	}

	public String getText() { return text; }

	public void setText(String t) {
		text = t;
		if (onChange != null) onChange.accept(text);
	}

	public boolean isFocused() { return focused == this; }

	public void render(GuiGraphics g, int x, int y, int w, int h, int bg) {
		Render2D.roundedRect(g, x, y, w, h, 3, bg);
		if (isFocused()) Render2D.rect(g, x + 3, y + h - 1, w - 6, 1, Theme.accent());
		int ty = y + (h - 8) / 2;
		String shown = text;
		while (Render2D.width(shown) > w - 10 && !shown.isEmpty()) shown = shown.substring(1);
		if (allSelected && isFocused() && !text.isEmpty()) Render2D.rect(g, x + 4, ty - 1, Render2D.width(shown) + 2, 10, ColorUtil.withAlpha(Theme.accent(), 110));
		if (text.isEmpty() && !isFocused()) Render2D.text(g, placeholder, x + 5, ty, Theme.MUTED, false);
		else Render2D.text(g, shown, x + 5, ty, Theme.TEXT, false);
		if (isFocused() && (System.currentTimeMillis() / 500) % 2 == 0)
			Render2D.rect(g, x + 5 + Render2D.width(shown), ty - 1, 1, 10, Theme.accent());
	}

	public boolean keyPressed(int key) { return keyPressed(key, 0); }

	/** @param modifiers GLFW modifier bits from the key event (Ctrl / Cmd detection) */
	public boolean keyPressed(int key, int modifiers) {
		if (!isFocused()) return false;
		boolean ctrl = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 || ctrlDown();
		if (ctrl) {
			switch (key) {
				case GLFW.GLFW_KEY_V -> {
					insert(Minecraft.getInstance().keyboardHandler.getClipboard());
					return true;
				}
				case GLFW.GLFW_KEY_C -> {
					Minecraft.getInstance().keyboardHandler.setClipboard(text);
					return true;
				}
				case GLFW.GLFW_KEY_X -> {
					Minecraft.getInstance().keyboardHandler.setClipboard(text);
					setText("");
					allSelected = false;
					return true;
				}
				case GLFW.GLFW_KEY_A -> {
					allSelected = !text.isEmpty();
					return true;
				}
				default -> {}
			}
		}
		switch (key) {
			case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
				if (allSelected) setText("");
				else if (ctrl) setText(text.replaceAll("\\S*\\s*$", ""));
				else if (!text.isEmpty() && key == GLFW.GLFW_KEY_BACKSPACE) setText(text.substring(0, text.length() - 1));
				allSelected = false;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				allSelected = false;
				if (onEnter != null) onEnter.accept(text);
			}
			case GLFW.GLFW_KEY_ESCAPE -> {
				allSelected = false;
				focused = null;
			}
			case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER,
				GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> {}
			default -> allSelected = false;
		}
		return true;
	}

	private boolean allSelected;

	public static boolean ctrlDown() {
		var w = Minecraft.getInstance().getWindow();
		return InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_CONTROL)
			|| InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_SUPER) || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_SUPER);
	}

	/** Inserts (pasted) text, dropping line breaks / control characters and respecting the max length. */
	public void insert(String s) {
		if (s == null) return;
		StringBuilder clean = new StringBuilder();
		for (char c : s.toCharArray()) if (c >= 32 && c != 127) clean.append(c);
		String base = allSelected ? "" : text;
		allSelected = false;
		String out = base + clean;
		if (out.length() > maxLength) out = out.substring(0, maxLength);
		setText(out);
	}

	public boolean charTyped(char c) {
		if (!isFocused() || c < 32) return false;
		if (allSelected) {
			allSelected = false;
			setText("");
		}
		if (text.length() >= maxLength) return false;
		setText(text + c);
		return true;
	}
}
