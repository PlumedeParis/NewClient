package dev.zero.gui;

import dev.zero.config.ConfigManager;
import dev.zero.module.ModuleManager;
import dev.zero.modules.Crosshair;
import dev.zero.render.Render2D;
import dev.zero.setting.PixelGridSetting;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Pixel editor for the custom crosshair: paint with the left mouse button, erase with the
 * right one, optional mirroring, presets and a live preview on light and dark backgrounds.
 */
public class CrosshairEditorScreen extends Screen {
	private static final int N = PixelGridSetting.SIZE;
	private static final int[] SWATCHES = {0xFFFFFFFF, 0xFF00FF66, 0xFF00E5FF, 0xFFFFE600, 0xFFFF3B3B, 0xFFFF4DD8, 0xFF8B7CFF, 0xFF000000};

	private final PixelGridSetting grid;
	private final Screen parent;
	private final Anim open = new Anim(0, 12);
	private boolean mirrorX = true, mirrorY = true;
	private int painting; // 0 none, 1 paint, 2 erase

	public CrosshairEditorScreen(PixelGridSetting grid, Screen parent) {
		super(Component.literal("Crosshair Editor"));
		this.grid = grid;
		this.parent = parent;
		open.setTarget(1);
	}

	// ---- Layout -------------------------------------------------------------------------------
	private int[] window() {
		int w = Math.min(width - 20, 400), h = Math.min(height - 20, 250);
		return new int[] {width / 2 - w / 2, height / 2 - h / 2, w, h};
	}

	private int cell() {
		int[] win = window();
		return Math.max(6, Math.min(13, (win[3] - 52) / N));
	}

	private int[] gridRect() {
		int[] win = window();
		int c = cell();
		return new int[] {win[0] + 12, win[1] + 30, c * N, c * N};
	}

	private int rightX() { return gridRect()[0] + gridRect()[2] + 14; }

	private int rightW() { int[] win = window(); return win[0] + win[2] - 12 - rightX(); }

	private int[] button(int col, int row) { return button(col, row, 2); }

	private int[] button(int col, int row, int cols) {
		int bw = (rightW() - 4 * (cols - 1)) / cols;
		int by = window()[1] + 30 + 74 + row * 17;
		return new int[] {rightX() + col * (bw + 4), by, bw, 14};
	}

	private static final String[] TOOLS = {"Mirror X", "Mirror Y", "Clear", "Invert"};

	// ---- Rendering ----------------------------------------------------------------------------
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xB005050A, 0xD00B0B18);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		super.render(g, mx, my, pt);
		Crosshair ch = ModuleManager.crosshair;
		int color = ch.color.get(), outlineColor = ch.outlineColor.get();
		boolean outline = ch.outline.get();
		int[] win = window();
		int accent = Theme.accent();
		float o = (float) Anim.easeOutCubic(open.get());
		g.pose().pushMatrix();
		g.pose().translate(0, (1 - o) * 12);

		Render2D.glow(g, win[0], win[1], win[2], win[3], 8, ColorUtil.withAlpha(accent, 60), 8);
		Render2D.roundedRect(g, win[0], win[1], win[2], win[3], 8, Theme.panelBg());
		Render2D.gradientH(g, win[0] + 8, win[1], win[2] - 16, 1, accent, Theme.accent2());
		Render2D.text(g, "✚ Crosshair Editor", win[0] + 10, win[1] + 9, Theme.TEXT, false);
		Render2D.text(g, "§7left: paint  right: erase", win[0] + win[2] - 10 - Render2D.width("left: paint  right: erase"), win[1] + 9, Theme.SUBTEXT, false);

		// Grid
		int[] gr = gridRect();
		int c = cell();
		Render2D.roundedRect(g, gr[0] - 3, gr[1] - 3, gr[2] + 6, gr[3] + 6, 4, 0xFF15151E);
		int hx = (int) Math.floor((mx - gr[0]) / (double) c), hy = (int) Math.floor((my - gr[1]) / (double) c);
		boolean hoverGrid = hx >= 0 && hy >= 0 && hx < N && hy < N;
		for (int y = 0; y < N; y++)
			for (int x = 0; x < N; x++) {
				int px = gr[0] + x * c, py = gr[1] + y * c;
				boolean on = grid.get(x, y);
				boolean center = x == N / 2 && y == N / 2;
				int bg = ((x + y) & 1) == 0 ? 0xFF22222E : 0xFF1C1C26;
				if (center) bg = 0xFF2E2E40;
				g.fill(px, py, px + c, py + c, bg);
				if (on) g.fill(px + 1, py + 1, px + c - 1, py + c - 1, ColorUtil.withAlpha(color, 255));
				if (hoverGrid && mirrored(hx, hy, x, y)) Render2D.rect(g, px, py, c, c, on ? 0x40000000 : ColorUtil.withAlpha(accent, 90));
			}
		// center guides
		Render2D.rect(g, gr[0] + (N / 2) * c + c / 2, gr[1] - 3, 1, 2, accent);
		Render2D.rect(g, gr[0] - 3, gr[1] + (N / 2) * c + c / 2, 2, 1, accent);

		// Preview
		int rx = rightX(), rw = rightW(), py = gr[1];
		Render2D.text(g, "Preview", rx, py - 10 + 1, Theme.SUBTEXT, false);
		int half = (rw - 4) / 2;
		Render2D.gradientV(g, rx, py, half, 64, 0xFF79A6FF, 0xFFBFD8FF);
		Render2D.gradientV(g, rx + half + 4, py, half, 64, 0xFF3A3A3A, 0xFF1E1E1E);
		float s = ch.scale.getFloat();
		Crosshair.draw(g, grid, rx + half / 2f, py + 32, s, color, outline, outlineColor);
		Crosshair.draw(g, grid, rx + half + 4 + half / 2f, py + 32, s, color, outline, outlineColor);

		// Tools
		for (int i = 0; i < TOOLS.length; i++) {
			int[] b = button(i % 2, i / 2);
			boolean active = (i == 0 && mirrorX) || (i == 1 && mirrorY);
			boolean hov = Render2D.hovered(mx, my, b[0], b[1], b[2], b[3]);
			Render2D.roundedRect(g, b[0], b[1], b[2], b[3], 4, active ? ColorUtil.withAlpha(accent, 150) : hov ? 0xFF2A2A38 : Theme.FIELD);
			Render2D.centeredText(g, TOOLS[i], b[0] + b[2] / 2, b[1] + 3, active ? 0xFFFFFFFF : Theme.SUBTEXT, false);
		}
		// Presets
		int presetRow0 = 2;
		Render2D.text(g, "Presets", rx, button(0, presetRow0)[1] - 1, Theme.SUBTEXT, false);
		for (int i = 0; i < Crosshair.PRESETS.length; i++) {
			int[] b = button(i % 3, presetRow0 + 1 + i / 3, 3);
			b[1] -= 6;
			boolean hov = Render2D.hovered(mx, my, b[0], b[1], b[2], b[3]);
			Render2D.roundedRect(g, b[0], b[1], b[2], b[3], 4, hov ? 0xFF2A2A38 : Theme.FIELD);
			Render2D.centeredText(g, Crosshair.PRESETS[i][0], b[0] + b[2] / 2, b[1] + 3, Theme.TEXT, false);
		}
		// Colour swatches + done
		int sy = win[1] + win[3] - 22;
		for (int i = 0; i < SWATCHES.length; i++) {
			int sx = gr[0] + i * 16;
			Render2D.roundedRect(g, sx, sy, 13, 13, 3, 0xFF3A3A48);
			Render2D.roundedRect(g, sx + 1, sy + 1, 11, 11, 3, SWATCHES[i]);
			if ((color & 0xFFFFFF) == (SWATCHES[i] & 0xFFFFFF)) Render2D.rect(g, sx + 2, sy + 14, 9, 1, accent);
		}
		int[] done = doneRect();
		boolean hd = Render2D.hovered(mx, my, done[0], done[1], done[2], done[3]);
		Render2D.roundedRect(g, done[0], done[1], done[2], done[3], 4, hd ? ColorUtil.brighten(accent, 0.15f) : accent);
		Render2D.centeredText(g, "Done", done[0] + done[2] / 2, done[1] + 3, 0xFFFFFFFF, false);
		g.pose().popMatrix();
	}

	private int[] doneRect() {
		int[] win = window();
		return new int[] {win[0] + win[2] - 12 - 60, win[1] + win[3] - 22, 60, 14};
	}

	/** Whether cell (x, y) is (hx, hy) or one of its mirror images. */
	private boolean mirrored(int hx, int hy, int x, int y) {
		int mx2 = N - 1 - hx, my2 = N - 1 - hy;
		if (x == hx && y == hy) return true;
		if (mirrorX && x == mx2 && y == hy) return true;
		if (mirrorY && x == hx && y == my2) return true;
		return mirrorX && mirrorY && x == mx2 && y == my2;
	}

	private void paint(double mx, double my) {
		int[] gr = gridRect();
		int c = cell();
		int x = (int) Math.floor((mx - gr[0]) / c), y = (int) Math.floor((my - gr[1]) / c);
		if (x < 0 || y < 0 || x >= N || y >= N) return;
		boolean on = painting == 1;
		grid.setPixel(x, y, on);
		if (mirrorX) grid.setPixel(N - 1 - x, y, on);
		if (mirrorY) grid.setPixel(x, N - 1 - y, on);
		if (mirrorX && mirrorY) grid.setPixel(N - 1 - x, N - 1 - y, on);
		ConfigManager.markDirty();
	}

	// ---- Input --------------------------------------------------------------------------------
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int[] gr = gridRect();
		if (Render2D.hovered(mx, my, gr[0], gr[1], gr[2], gr[3])) {
			int c = cell();
			int x = (int) ((mx - gr[0]) / c), y = (int) ((my - gr[1]) / c);
			painting = event.button() == 1 ? 2 : grid.get(x, y) ? 2 : 1;
			paint(mx, my);
			return true;
		}
		for (int i = 0; i < TOOLS.length; i++) {
			int[] b = button(i % 2, i / 2);
			if (Render2D.hovered(mx, my, b[0], b[1], b[2], b[3])) {
				switch (i) {
					case 0 -> mirrorX = !mirrorX;
					case 1 -> mirrorY = !mirrorY;
					case 2 -> {
						for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) grid.setPixel(x, y, false);
					}
					case 3 -> {
						for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) grid.setPixel(x, y, !grid.get(x, y));
					}
					default -> {}
				}
				Sounds.click();
				ConfigManager.markDirty();
				return true;
			}
		}
		for (int i = 0; i < Crosshair.PRESETS.length; i++) {
			int[] b = button(i % 3, 3 + i / 3, 3);
			b[1] -= 6;
			if (Render2D.hovered(mx, my, b[0], b[1], b[2], b[3])) {
				boolean[] p = Crosshair.preset(i);
				for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) grid.setPixel(x, y, p[y * N + x]);
				Sounds.click();
				ConfigManager.markDirty();
				return true;
			}
		}
		int sy = window()[1] + window()[3] - 22;
		for (int i = 0; i < SWATCHES.length; i++) {
			if (Render2D.hovered(mx, my, gr[0] + i * 16, sy, 13, 13)) {
				int alpha = ColorUtil.a(ModuleManager.crosshair.color.get());
				ModuleManager.crosshair.color.set(ColorUtil.withAlpha(SWATCHES[i], Math.max(alpha, 60)));
				Sounds.click();
				ConfigManager.markDirty();
				return true;
			}
		}
		int[] done = doneRect();
		if (Render2D.hovered(mx, my, done[0], done[1], done[2], done[3])) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (painting != 0) {
			paint(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		painting = 0;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		ConfigManager.save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
