package dev.zero.gui;

import dev.zero.hud.Notifications;
import dev.zero.render.Render2D;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** History of every alert (also the ones that did not pop up). Click a line to copy it. */
public class AlertsScreen extends Screen {
	private final Screen parent;
	private double scroll;

	public AlertsScreen(Screen parent) {
		super(Component.literal("Alerts"));
		this.parent = parent;
		Notifications.markRead();
	}

	private int[] window() {
		int w = Math.min(width - 20, 420), h = Math.min(height - 30, 300);
		return new int[] {width / 2 - w / 2, height / 2 - h / 2, w, h};
	}

	private int[] clearRect() { int[] w = window(); return new int[] {w[0] + w[2] - 8 - 110, w[1] + 6, 50, 14}; }

	private int[] doneRect() { int[] w = window(); return new int[] {w[0] + w[2] - 8 - 54, w[1] + 6, 54, 14}; }

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xA005050A, 0xC00B0B18);
	}

	private static String ago(long time) {
		long s = (System.currentTimeMillis() - time) / 1000;
		return s < 60 ? s + "s" : s < 3600 ? s / 60 + "m" : s / 3600 + "h";
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		super.render(g, mx, my, partialTick);
		int[] win = window();
		int x = win[0], y = win[1], w = win[2], h = win[3];
		int accent = Theme.accent();
		Render2D.glow(g, x, y, w, h, 8, ColorUtil.withAlpha(accent, 60), 8);
		Render2D.roundedRect(g, x, y, w, h, 8, Theme.panelBg());
		Render2D.gradientH(g, x + 8, y, w - 16, 1, accent, Theme.accent2());
		List<Notifications.Entry> list = Notifications.history();
		Render2D.text(g, "! Alerts  §7" + list.size() + " • click a line to copy it", x + 9, y + 9, Theme.TEXT, false);
		int[] c = clearRect(), d = doneRect();
		Render2D.roundedRect(g, c[0], c[1], c[2], c[3], 4, Render2D.hovered(mx, my, c[0], c[1], c[2], c[3]) ? 0xFFB4234A : 0xFF4A1A28);
		Render2D.centeredText(g, "Clear", c[0] + c[2] / 2, c[1] + 3, 0xFFFFFFFF, false);
		Render2D.roundedRect(g, d[0], d[1], d[2], d[3], 4, accent);
		Render2D.centeredText(g, "Done", d[0] + d[2] / 2, d[1] + 3, 0xFFFFFFFF, false);

		int top = y + 26, bottom = y + h - 6, row = 22;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() * row - (bottom - top))));
		g.enableScissor(x, top, x + w, bottom);
		if (list.isEmpty()) Render2D.centeredText(g, "Nothing yet. Finds, players and leads are listed here.", x + w / 2, top + 30, Theme.SUBTEXT, false);
		for (int i = 0; i < list.size(); i++) {
			Notifications.Entry e = list.get(i);
			int ry = top + i * row - (int) scroll;
			if (ry + row < top || ry > bottom) continue;
			boolean hover = my >= top && my < bottom && Render2D.hovered(mx, my, x + 6, ry, w - 12, row - 2);
			Render2D.roundedRect(g, x + 6, ry, w - 12, row - 2, 4, hover ? 0xFF22222E : Theme.FIELD);
			Render2D.rect(g, x + 6, ry + 4, 2, row - 10, color(e.type()));
			String time = ago(e.time());
			Render2D.text(g, Render2D.trim(e.title(), w - 70), x + 13, ry + 2, Theme.TEXT, false);
			Render2D.text(g, time, x + w - 12 - Render2D.width(time), ry + 2, Theme.MUTED, false);
			Render2D.text(g, Render2D.trim(e.message(), w - 30), x + 13, ry + 11, Theme.SUBTEXT, false);
		}
		g.disableScissor();
	}

	private static int color(Notifications.Type t) {
		return switch (t) {
			case FOUND -> 0xFFA78BFA;
			case ALERT -> 0xFFF472B6;
			case WARNING -> 0xFFFBBF24;
			case ENABLED -> 0xFF4ADE80;
			case DISABLED -> 0xFFF87171;
			default -> 0xFF60A5FA;
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int[] c = clearRect(), d = doneRect(), win = window();
		if (Render2D.hovered(mx, my, d[0], d[1], d[2], d[3])) {
			onClose();
			return true;
		}
		if (Render2D.hovered(mx, my, c[0], c[1], c[2], c[3])) {
			Notifications.clearHistory();
			return true;
		}
		int top = win[1] + 26, row = 22;
		List<Notifications.Entry> list = Notifications.history();
		int i = (int) ((my - top + scroll) / row);
		if (my >= top && i >= 0 && i < list.size() && mx >= win[0] && mx < win[0] + win[2]) {
			Notifications.Entry e = list.get(i);
			minecraft.keyboardHandler.setClipboard(e.title() + " - " + e.message());
			dev.zero.util.Sounds.click();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		scroll -= vertical * 22;
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent instanceof ClickGuiScreen ? new ClickGuiScreen() : parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
