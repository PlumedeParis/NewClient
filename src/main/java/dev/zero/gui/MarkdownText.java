package dev.zero.gui;

import dev.zero.render.Render2D;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Draws the small markdown of the changelog: "## version" headings, "- " bullets, **bold**. Wrapped to a width. */
public final class MarkdownText {
	private record Line(String text, int indent, boolean heading) {}

	private final List<Line> lines = new ArrayList<>();
	private int wrappedFor = -1;
	private final String source;

	public MarkdownText(String markdown) { this.source = markdown == null ? "" : markdown; }

	private static String inline(String s) {
		StringBuilder out = new StringBuilder();
		boolean bold = false;
		for (int i = 0; i < s.length(); i++) {
			if (s.startsWith("**", i)) {
				bold = !bold;
				out.append(bold ? "§l" : "§r");
				i++;
			} else out.append(s.charAt(i));
		}
		return out.toString();
	}

	private void wrap(int width) {
		if (width == wrappedFor) return;
		wrappedFor = width;
		lines.clear();
		for (String raw : source.split("\n")) {
			String l = raw.strip();
			if (l.isEmpty()) continue;
			if (l.startsWith("## ")) {
				lines.add(new Line("ZERØ " + l.substring(3).trim(), 0, true));
				continue;
			}
			boolean bullet = l.startsWith("- ") || l.startsWith("* ");
			String text = inline(bullet ? l.substring(2) : l);
			int indent = bullet ? 10 : 0;
			StringBuilder line = new StringBuilder();
			boolean first = true;
			for (String word : text.split(" ")) {
				if (line.length() > 0 && Render2D.width(line + " " + word) > width - indent) {
					lines.add(new Line((first && bullet ? "• " : "") + line, first ? 0 : indent, false));
					first = false;
					line = new StringBuilder();
				}
				if (line.length() > 0) line.append(' ');
				line.append(word);
			}
			if (line.length() > 0) lines.add(new Line((first && bullet ? "• " : "") + line, first ? 0 : indent, false));
		}
	}

	public int height(int width) {
		wrap(width);
		int h = 0;
		for (Line l : lines) h += l.heading ? 16 : 10;
		return h;
	}

	public void draw(GuiGraphics g, int x, int y, int width) {
		wrap(width);
		int cy = y;
		for (Line l : lines) {
			if (l.heading) {
				if (cy > y) cy += 2;
				Render2D.text(g, "§l" + l.text, x, cy, Theme.accent(), false);
				cy += 14;
			} else {
				Render2D.text(g, l.text, x + l.indent + (l.indent == 0 && l.text.startsWith("•") ? 0 : 0), cy, Theme.TEXT, false);
				cy += 10;
			}
		}
	}
}
