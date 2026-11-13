package dev.zero.gui.comp;

import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** A collapsible titled group of components ("Colors", "Alerts", "Performance"...). */
public class SectionComp extends Comp {
	private static final int ROW = 14;
	private final String title;
	private final List<Comp> children;
	private boolean expanded;
	private final Anim anim;

	public SectionComp(String title, List<Comp> children, boolean expanded) {
		this.title = title;
		this.children = children;
		this.expanded = expanded;
		this.anim = new Anim(expanded ? 1 : 0, 14);
	}

	private int childrenHeight() {
		int h = 0;
		for (Comp c : children) if (c.visible()) h += c.height();
		return h;
	}

	@Override
	public boolean visible() {
		for (Comp c : children) if (c.visible()) return true;
		return false;
	}

	@Override
	public int height() { return ROW + (int) (childrenHeight() * anim.getF()); }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		anim.setTarget(expanded ? 1 : 0);
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW;
		Render2D.rect(g, x + 4, y + ROW - 2, w - 8, 1, ColorUtil.withAlpha(Theme.accent(), expanded ? 90 : 35));
		Render2D.text(g, title.toUpperCase(java.util.Locale.ROOT), x + 6, y + 3, hover || expanded ? Theme.TEXT : Theme.SUBTEXT, false);
		Render2D.text(g, expanded ? "–" : "+", x + w - 11, y + 3, Theme.SUBTEXT, false);
		float e = anim.getF();
		if (e > 0.005f) {
			int ch = (int) (childrenHeight() * e);
			g.enableScissor(x, y + ROW, x + w, y + ROW + ch);
			int cy = y + ROW;
			for (Comp c : children) {
				if (!c.visible()) continue;
				c.draw(g, x, cy, w, mx, my);
				cy += c.height();
			}
			g.disableScissor();
		}
	}

	private boolean interactive() { return expanded && anim.getF() > 0.95f; }

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (mx < x || mx >= x + w) return false;
		if (my >= y && my < y + ROW) {
			expanded = !expanded;
			return true;
		}
		if (interactive()) for (Comp c : children) if (c.visible() && c.mouseClicked(mx, my, button)) return true;
		return false;
	}

	@Override
	public void mouseReleased(double mx, double my, int button) {
		for (Comp c : children) c.mouseReleased(mx, my, button);
	}

	@Override
	public boolean keyPressed(int key) {
		for (Comp c : children) if (c.keyPressed(key)) return true;
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		for (Comp c : children) if (c.charTyped(ch)) return true;
		return false;
	}

	@Override
	public String tooltip(double mx, double my) {
		if (mx >= x && mx < x + w && my >= y && my < y + ROW) return title + "  §7click to " + (expanded ? "fold" : "unfold");
		if (interactive()) for (Comp c : children) {
			if (!c.visible()) continue;
			String t = c.tooltip(mx, my);
			if (t != null) return t;
		}
		return null;
	}
}
