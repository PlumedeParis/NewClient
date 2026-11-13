package dev.zero.gui.comp;

import dev.zero.gui.Theme;
import dev.zero.render.Render2D;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** A short wrapped text (what a module does), shown at the top of its settings. */
public class DescComp extends Comp {
	private final String text;
	private List<String> lines = List.of();
	private int wrappedFor = -1;

	public DescComp(String text) { this.text = text; }

	private void wrap(int width) {
		if (width == wrappedFor) return;
		wrappedFor = width;
		lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && Render2D.width(line + " " + word) > width) {
				lines.add(line.toString());
				line = new StringBuilder();
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) lines.add(line.toString());
	}

	@Override
	public int height() { return Math.max(1, lines.size()) * 9 + 5; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		wrap(w - 12);
		for (int i = 0; i < lines.size(); i++) Render2D.text(g, lines.get(i), x + 6, y + 3 + i * 9, Theme.SUBTEXT, false);
	}
}
