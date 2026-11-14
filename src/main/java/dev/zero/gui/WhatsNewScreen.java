package dev.zero.gui;

import dev.zero.render.Render2D;
import dev.zero.update.Updater;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Shown once after ZERØ was updated: everything new since the version you had. */
public class WhatsNewScreen extends Screen {
	private final Screen parent;
	private final String from, to;
	private final MarkdownText notes;
	private final long opened = System.currentTimeMillis();
	private double scroll;

	public WhatsNewScreen(Screen parent, String from, String to) {
		super(Component.literal("What's new"));
		this.parent = parent;
		this.from = from;
		this.to = to;
		String text = Updater.changelog(from, to);
		this.notes = new MarkdownText(text.isEmpty() ? "## " + to + "\n- Improvements and fixes" : text);
	}

	private int[] window() {
		int w = Math.min(width - 20, 380), h = Math.min(height - 20, 280);
		return new int[] {width / 2 - w / 2, height / 2 - h / 2, w, h};
	}

	private int[] playButton() {
		int[] win = window();
		return new int[] {win[0] + win[2] / 2 - 60, win[1] + win[3] - 30, 120, 20};
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xC005050A, 0xE00B0B18);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		super.render(g, mx, my, partialTick);
		float t = Math.min(1, (System.currentTimeMillis() - opened) / 350f);
		float e = 1 - (float) Math.pow(1 - t, 3);
		int[] win = window();
		int x = win[0], y = win[1] + (int) ((1 - e) * 16), w = win[2], h = win[3];
		int accent = Theme.accent();
		Render2D.glow(g, x, y, w, h, 9, ColorUtil.withAlpha(accent, (int) (90 * e)), 10);
		Render2D.roundedRect(g, x, y, w, h, 9, Theme.panelBg());
		Render2D.gradientH(g, x + 9, y, w - 18, 1, accent, Theme.accent2());
		Render2D.roundedRect(g, x + 10, y + 10, 30, 13, 4, accent);
		Render2D.text(g, "§lNEW", x + 13, y + 13, 0xFFFFFFFF, false);
		Render2D.text(g, "§lWhat's new in ZERØ " + to, x + 46, y + 13, Theme.TEXT, false);
		Render2D.text(g, "Updated from " + from + " ✔", x + 46, y + 25, 0xFF4ADE80, false);
		int top = y + 42, bottom = y + h - 40;
		int ch = notes.height(w - 28);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, ch - (bottom - top))));
		Render2D.roundedRect(g, x + 8, top - 4, w - 16, bottom - top + 8, 6, 0x50000000);
		g.enableScissor(x + 8, top, x + w - 8, bottom);
		notes.draw(g, x + 14, top - (int) scroll, w - 28);
		g.disableScissor();
		int[] b = playButton();
		b[1] = y + h - 30;
		boolean hover = Render2D.hovered(mx, my, b[0], b[1], b[2], b[3]);
		Render2D.glow(g, b[0], b[1], b[2], b[3], 6, ColorUtil.withAlpha(accent, hover ? 120 : 60), 5);
		Render2D.roundedRect(g, b[0], b[1], b[2], b[3], 6, hover ? ColorUtil.brighten(accent, 0.15f) : accent);
		Render2D.centeredText(g, "§lPlay!", b[0] + b[2] / 2, b[1] + 6, 0xFFFFFFFF, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int[] b = playButton();
		if (Render2D.hovered(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
			dev.zero.util.Sounds.click();
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		scroll -= vertical * 20;
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
