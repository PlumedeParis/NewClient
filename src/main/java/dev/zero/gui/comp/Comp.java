package dev.zero.gui.comp;

import net.minecraft.client.gui.GuiGraphics;

/** A GUI element laid out vertically. Bounds are refreshed every frame by render(). */
public abstract class Comp {
	protected int x, y, w;

	public abstract int height();

	public boolean visible() { return true; }

	/** Renders at the given position and remembers the bounds for input handling. */
	public final void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
		this.x = x;
		this.y = y;
		this.w = w;
		render(g, mx, my);
	}

	protected abstract void render(GuiGraphics g, int mx, int my);

	public boolean mouseClicked(double mx, double my, int button) { return false; }

	public void mouseReleased(double mx, double my, int button) {}

	public void mouseDragged(double mx, double my) {}

	public boolean keyPressed(int key) { return false; }

	public boolean charTyped(char c) { return false; }

	/** Description shown in the bottom tooltip bar while hovered. */
	public String tooltip(double mx, double my) { return null; }

	protected boolean hovered(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + height();
	}
}
