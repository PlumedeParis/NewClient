package dev.zero.gui.comp;

import dev.zero.gui.Theme;
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
		if (text.isEmpty() && !isFocused()) Render2D.text(g, placeholder, x + 5, ty, Theme.MUTED, false);
		else Render2D.text(g, shown, x + 5, ty, Theme.TEXT, false);
		if (isFocused() && (System.currentTimeMillis() / 500) % 2 == 0)
			Render2D.rect(g, x + 5 + Render2D.width(shown), ty - 1, 1, 10, Theme.accent());
	}

	public boolean keyPressed(int key) {
		if (!isFocused()) return false;
		switch (key) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (!text.isEmpty()) setText(text.substring(0, text.length() - 1));
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				if (onEnter != null) onEnter.accept(text);
			}
			case GLFW.GLFW_KEY_ESCAPE -> focused = null;
			default -> {
				return key != GLFW.GLFW_KEY_ESCAPE;
			}
		}
		return true;
	}

	public boolean charTyped(char c) {
		if (!isFocused() || c < 32 || text.length() >= maxLength) return false;
		setText(text + c);
		return true;
	}
}
