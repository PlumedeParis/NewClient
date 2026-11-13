package dev.zero.gui;

import dev.zero.gui.comp.Comp;
import dev.zero.render.Render2D;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** A draggable, collapsible column of components. */
public class Panel {
	public static final int WIDTH = 124, HEADER = 20;

	final String id;
	final String title;
	final String icon;
	final int color;
	final List<Comp> comps;
	int x, y;
	boolean collapsed;
	private boolean dragging;
	private int dragOffX, dragOffY;
	private final Anim collapseAnim;

	Panel(String id, String title, String icon, int color, List<Comp> comps, int x, int y) {
		this.id = id;
		this.title = title;
		this.icon = icon;
		this.color = color;
		this.comps = comps;
		this.x = x;
		this.y = y;
		this.collapseAnim = new Anim(1, 14);
	}

	private int contentHeight() {
		int h = 0;
		for (Comp c : comps) if (c.visible()) h += c.height();
		return h;
	}

	int fullHeight() { return HEADER + (collapsed ? 0 : contentHeight() + 4); }

	boolean hasVisible() {
		for (Comp c : comps) if (c.visible()) return true;
		return false;
	}

	void render(GuiGraphics g, int mx, int my, int scroll, float appear) {
		if (dragging) {
			x = mx - dragOffX;
			y = my - dragOffY - scroll;
		}
		collapseAnim.setTarget(collapsed ? 0 : 1);
		int py = y + scroll;
		int ch = (int) (contentHeight() * collapseAnim.getF());
		int total = HEADER + ch + (ch > 0 ? 4 : 0);

		Render2D.glow(g, x, py, WIDTH, total, 7, ColorUtil.withAlpha(0x000000, 150), 8);
		Render2D.glow(g, x, py, WIDTH, total, 7, ColorUtil.withAlpha(color, (int) (40 * appear)), 4);
		Render2D.roundedRect(g, x, py, WIDTH, total, 7, Theme.panelBg());
		Render2D.roundedRect(g, x, py, WIDTH, HEADER, 7, Theme.headerBg(), true, ch <= 0);
		Render2D.gradientH(g, x + 7, py + HEADER - 1, WIDTH - 14, 1, color, ColorUtil.withAlpha(color, 0));
		Render2D.roundedRect(g, x + 6, py + 4, 12, 12, 4, ColorUtil.withAlpha(color, 55));
		Render2D.centeredText(g, icon, x + 12, py + 6, color, false);
		Render2D.text(g, title, x + 23, py + 6, Theme.TEXT, false);
		String arrow = collapsed ? "+" : "–";
		Render2D.text(g, arrow, x + WIDTH - 12, py + 6, Theme.SUBTEXT, false);

		if (ch > 0) {
			g.enableScissor(x, py + HEADER, x + WIDTH, py + HEADER + ch + 2);
			int cy = py + HEADER + 2;
			for (Comp c : comps) {
				if (!c.visible()) continue;
				c.draw(g, x, cy, WIDTH, mx, my);
				cy += c.height();
			}
			g.disableScissor();
		}
	}

	boolean headerHovered(double mx, double my, int scroll) {
		return mx >= x && mx < x + WIDTH && my >= y + scroll && my < y + scroll + HEADER;
	}

	boolean contentHovered(double mx, double my, int scroll) {
		int ch = (int) (contentHeight() * collapseAnim.getF());
		return mx >= x && mx < x + WIDTH && my >= y + scroll + HEADER && my < y + scroll + HEADER + ch + 2;
	}

	boolean mouseClicked(double mx, double my, int button, int scroll) {
		if (headerHovered(mx, my, scroll)) {
			if (button == 0) {
				dragging = true;
				dragOffX = (int) mx - x;
				dragOffY = (int) my - (y + scroll);
			} else if (button == 1) collapsed = !collapsed;
			return true;
		}
		if (!collapsed && contentHovered(mx, my, scroll)) {
			for (Comp c : comps) if (c.visible() && c.mouseClicked(mx, my, button)) return true;
			return true;
		}
		return false;
	}

	void mouseReleased(double mx, double my, int button) {
		dragging = false;
		for (Comp c : comps) c.mouseReleased(mx, my, button);
	}

	boolean isDragging() { return dragging; }

	String tooltip(double mx, double my, int scroll) {
		if (collapsed || !contentHovered(mx, my, scroll)) return null;
		for (Comp c : comps) {
			if (!c.visible()) continue;
			String t = c.tooltip(mx, my);
			if (t != null) return t;
		}
		return null;
	}
}
