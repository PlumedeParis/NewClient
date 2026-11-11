package dev.zero.gui;

import dev.zero.config.ConfigManager;
import dev.zero.hud.HudLayout;
import dev.zero.hud.HudLayoutAccess;
import dev.zero.render.Render2D;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drag ZERØ's HUD panels anywhere. Elements snap to the screen edges, the centre and each
 * other. Right click resets an element to its automatic position.
 */
public class HudEditorScreen extends Screen {
	private static final int SNAP = 6;
	private final Screen parent;
	private String dragging;
	private int offX, offY;
	private final List<int[]> guides = new ArrayList<>(); // {vertical?1:0, position}

	public HudEditorScreen(Screen parent) {
		super(Component.literal("HUD Editor"));
		this.parent = parent;
	}

	private Map<String, int[]> rects() { return HudLayoutAccess.last(); }

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, 0x40000000);
		// centre lines
		g.fill(width / 2, 0, width / 2 + 1, height, 0x20FFFFFF);
		g.fill(0, height / 2, width, height / 2 + 1, 0x20FFFFFF);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		super.render(g, mx, my, pt);
		int accent = Theme.accent();
		for (Map.Entry<String, int[]> e : rects().entrySet()) {
			int[] r = e.getValue();
			boolean hover = Render2D.hovered(mx, my, r[0], r[1], r[2], r[3]);
			boolean active = e.getKey().equals(dragging);
			boolean custom = HudLayout.isCustom(e.getKey());
			int c = active ? accent : hover ? ColorUtil.brighten(accent, 0.3f) : custom ? 0xC0FFFFFF : 0x80FFFFFF;
			g.fill(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1], c);
			g.fill(r[0] - 1, r[1] + r[3], r[0] + r[2] + 1, r[1] + r[3] + 1, c);
			g.fill(r[0] - 1, r[1], r[0], r[1] + r[3], c);
			g.fill(r[0] + r[2], r[1], r[0] + r[2] + 1, r[1] + r[3], c);
			if (hover || active) g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], ColorUtil.withAlpha(accent, 40));
			String label = cap(e.getKey()) + (custom ? "" : " §7(auto)");
			int lw = Render2D.width(label) + 6;
			int ly = r[1] - 11 < 0 ? r[1] + r[3] + 2 : r[1] - 11;
			Render2D.roundedRect(g, r[0], ly, lw, 10, 3, 0xE0101016);
			Render2D.text(g, label, r[0] + 3, ly + 1, c, false);
		}
		for (int[] gl : guides) {
			if (gl[0] == 1) g.fill(gl[1], 0, gl[1] + 1, height, ColorUtil.withAlpha(accent, 200));
			else g.fill(0, gl[1], width, gl[1] + 1, ColorUtil.withAlpha(accent, 200));
		}
		// help bar + buttons
		String tip = "Drag to move  •  Right click: auto position  •  Esc: done";
		int tw = Render2D.width(tip) + 16;
		Render2D.roundedRect(g, width / 2 - tw / 2, height / 2 - 40, tw, 15, 5, 0xD0101016);
		Render2D.text(g, tip, width / 2 - tw / 2 + 8, height / 2 - 36, 0xFFE6E6F0, false);
		button(g, mx, my, resetRect(), "Reset all", 0xFF4A1A28);
		button(g, mx, my, doneRect(), "Done", accent);
	}

	private void button(GuiGraphics g, int mx, int my, int[] r, String text, int color) {
		boolean hover = Render2D.hovered(mx, my, r[0], r[1], r[2], r[3]);
		Render2D.roundedRect(g, r[0], r[1], r[2], r[3], 4, hover ? ColorUtil.brighten(color, 0.2f) : color);
		Render2D.centeredText(g, text, r[0] + r[2] / 2, r[1] + 3, 0xFFFFFFFF, false);
	}

	private int[] resetRect() { return new int[] {width / 2 - 64, height / 2 - 20, 60, 14}; }

	private int[] doneRect() { return new int[] {width / 2 + 4, height / 2 - 20, 60, 14}; }

	private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (Render2D.hovered(mx, my, resetRect()[0], resetRect()[1], resetRect()[2], resetRect()[3])) {
			HudLayout.clear();
			ConfigManager.markDirty();
			Sounds.click();
			return true;
		}
		if (Render2D.hovered(mx, my, doneRect()[0], doneRect()[1], doneRect()[2], doneRect()[3])) {
			onClose();
			return true;
		}
		List<Map.Entry<String, int[]>> list = new ArrayList<>(rects().entrySet());
		for (int i = list.size() - 1; i >= 0; i--) {
			int[] r = list.get(i).getValue();
			if (!Render2D.hovered(mx, my, r[0], r[1], r[2], r[3])) continue;
			String id = list.get(i).getKey();
			if (event.button() == 1) {
				HudLayout.reset(id);
				ConfigManager.markDirty();
				Sounds.click();
			} else {
				dragging = id;
				offX = (int) mx - r[0];
				offY = (int) my - r[1];
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging == null) return super.mouseDragged(event, dx, dy);
		int[] r = rects().get(dragging);
		if (r == null) return true;
		int w = r[2], h = r[3];
		int x = (int) event.x() - offX, y = (int) event.y() - offY;
		guides.clear();
		// Snap to screen edges, centre and the other elements.
		List<Integer> xs = new ArrayList<>(List.of(2, width - 2, width / 2)), ys = new ArrayList<>(List.of(2, height - 2, height / 2));
		for (Map.Entry<String, int[]> e : rects().entrySet()) {
			if (e.getKey().equals(dragging)) continue;
			int[] o = e.getValue();
			xs.add(o[0]);
			xs.add(o[0] + o[2]);
			ys.add(o[1]);
			ys.add(o[1] + o[3] + 4);
			ys.add(o[1] - 4);
		}
		x = snap(x, w, xs, true);
		y = snap(y, h, ys, false);
		x = Math.max(0, Math.min(width - w, x));
		y = Math.max(0, Math.min(height - h, y));
		HudLayout.set(dragging, w >= width ? 0 : x / (double) (width - w), h >= height ? 0 : y / (double) (height - h));
		ConfigManager.markDirty();
		return true;
	}

	/** Snaps either edge (or the centre) of the element to the closest guide line. */
	private int snap(int pos, int size, List<Integer> lines, boolean vertical) {
		int best = pos, bestDist = SNAP + 1, guide = -1;
		for (int line : lines) {
			int[] candidates = {line, line - size, line - size / 2};
			for (int c : candidates) {
				int d = Math.abs(c - pos);
				if (d < bestDist) {
					bestDist = d;
					best = c;
					guide = line;
				}
			}
		}
		if (guide >= 0) guides.add(new int[] {vertical ? 1 : 0, guide});
		return best;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		guides.clear();
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
