package dev.newclient.gui;

import dev.newclient.config.FindsStore;
import dev.newclient.config.FindsStore.Find;
import dev.newclient.config.FindsStore.Kind;
import dev.newclient.hud.Notifications;
import dev.newclient.render.Render2D;
import dev.newclient.util.Anim;
import dev.newclient.util.ColorUtil;
import dev.newclient.util.Sounds;
import dev.newclient.util.WorldUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Browser for everything BaseFinder / SusChunk saved on the current server. */
public class FindsScreen extends Screen {
	private enum Tab { ALL, BASES, SUS }

	private final Screen parent;
	private Tab tab = Tab.ALL;
	private final Anim scroll = new Anim(0, 16);
	private double scrollTarget;
	private static final int ROW = 26;

	public FindsScreen(Screen parent) {
		super(Component.literal("Finds"));
		this.parent = parent;
	}

	private List<Find> list() {
		List<Find> out = new ArrayList<>();
		for (Find f : FindsStore.all()) {
			boolean ok = switch (tab) {
				case ALL -> true;
				case BASES -> f.kind == Kind.BASE || f.kind == Kind.STASH || f.kind == Kind.POSSIBLE;
				case SUS -> f.kind == Kind.SUS;
			};
			if (ok) out.add(f);
		}
		Vec3 me = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
		String dim = WorldUtil.dimensionName(minecraft.level);
		out.sort((a, b) -> {
			boolean ad = a.dimension.equals(dim), bd = b.dimension.equals(dim);
			if (ad != bd) return ad ? -1 : 1;
			return Double.compare(me.distanceToSqr(a.x, a.y, a.z), me.distanceToSqr(b.x, b.y, b.z));
		});
		return out;
	}

	private int[] window() {
		int w = Math.min(width - 40, 460), h = height - 50;
		return new int[] {width / 2 - w / 2, 25, w, h};
	}

	private int[] tabRect(int i) {
		int[] win = window();
		return new int[] {win[0] + 8 + i * 58, win[1] + 22, 54, 14};
	}

	private int[] clearRect() {
		int[] win = window();
		return new int[] {win[0] + win[2] - 8 - 120, win[1] + 22, 58, 14};
	}

	private int[] doneRect() {
		int[] win = window();
		return new int[] {win[0] + win[2] - 8 - 58, win[1] + 22, 58, 14};
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xA005050A, 0xC00B0B18);
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
		Render2D.text(g, "★ Saved finds  §7" + WorldUtil.serverId(), x + 9, y + 8, Theme.TEXT, false);

		Tab[] tabs = Tab.values();
		for (int i = 0; i < tabs.length; i++) {
			int[] r = tabRect(i);
			boolean active = tab == tabs[i];
			boolean hover = Render2D.hovered(mx, my, r[0], r[1], r[2], r[3]);
			Render2D.roundedRect(g, r[0], r[1], r[2], r[3], 4, active ? accent : hover ? 0xFF2A2A38 : Theme.FIELD);
			String name = tabs[i].name().charAt(0) + tabs[i].name().substring(1).toLowerCase();
			Render2D.centeredText(g, name, r[0] + r[2] / 2, r[1] + 3, active ? 0xFFFFFFFF : Theme.SUBTEXT, false);
		}
		int[] c = clearRect();
		Render2D.roundedRect(g, c[0], c[1], c[2], c[3], 4, Render2D.hovered(mx, my, c[0], c[1], c[2], c[3]) ? 0xFFB4234A : 0xFF4A1A28);
		Render2D.centeredText(g, "Clear tab", c[0] + c[2] / 2, c[1] + 3, 0xFFFFFFFF, false);
		int[] d = doneRect();
		Render2D.roundedRect(g, d[0], d[1], d[2], d[3], 4, accent);
		Render2D.centeredText(g, "Done", d[0] + d[2] / 2, d[1] + 3, 0xFFFFFFFF, false);

		List<Find> list = list();
		int ly = y + 42, lh = h - 50;
		double max = Math.max(0, list.size() * ROW - lh);
		scrollTarget = Math.max(-max, Math.min(0, scrollTarget));
		int sc = (int) scroll.setTarget(scrollTarget).get();
		if (list.isEmpty()) Render2D.centeredText(g, "Nothing saved yet - enable BaseFinder / SusChunk and explore.", x + w / 2, ly + 20, Theme.MUTED, false);
		Vec3 me = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
		String dim = WorldUtil.dimensionName(minecraft.level);
		g.enableScissor(x, ly, x + w, ly + lh);
		for (int i = 0; i < list.size(); i++) {
			Find f = list.get(i);
			int ry = ly + i * ROW + sc;
			if (ry + ROW < ly || ry > ly + lh) continue;
			boolean hover = Render2D.hovered(mx, my, x + 6, ry, w - 12, ROW - 2) && my >= ly && my < ly + lh;
			Render2D.roundedRect(g, x + 6, ry, w - 12, ROW - 2, 5, hover ? 0x22FFFFFF : 0x10FFFFFF);
			Render2D.roundedRect(g, x + 10, ry + 4, 4, ROW - 10, 2, f.kind.color);
			Render2D.text(g, f.kind.title, x + 19, ry + 3, f.kind.color, false);
			String coords = f.x + " " + f.y + " " + f.z + "  §8" + f.dimension.replace("the_", "");
			Render2D.text(g, coords, x + 19 + Render2D.width(f.kind.title) + 6, ry + 3, Theme.TEXT, false);
			String details = f.details;
			int maxW = w - 120;
			while (Render2D.width(details) > maxW && details.length() > 4) details = details.substring(0, details.length() - 4) + "…";
			Render2D.text(g, details, x + 19, ry + 13, Theme.SUBTEXT, false);
			String right = f.dimension.equals(dim) ? WorldUtil.distance(me.distanceTo(new Vec3(f.x, f.y, f.z))) : "other dim";
			right += "  §8" + ago(f.time);
			Render2D.text(g, right, x + w - 66 - Render2D.width(right), ry + 3, Theme.SUBTEXT, false);
			// Buttons
			int bx = x + w - 58;
			boolean hc = Render2D.hovered(mx, my, bx, ry + 5, 26, 14);
			Render2D.roundedRect(g, bx, ry + 5, 26, 14, 4, hc ? accent : Theme.FIELD);
			Render2D.centeredText(g, "Copy", bx + 13, ry + 8, 0xFFFFFFFF, false);
			boolean hd = Render2D.hovered(mx, my, bx + 30, ry + 5, 16, 14);
			Render2D.roundedRect(g, bx + 30, ry + 5, 16, 14, 4, hd ? 0xFFB4234A : Theme.FIELD);
			Render2D.centeredText(g, "×", bx + 38, ry + 8, 0xFFFFFFFF, false);
		}
		g.disableScissor();
	}

	private static String ago(long time) {
		long s = (System.currentTimeMillis() - time) / 1000;
		if (s < 60) return s + "s ago";
		if (s < 3600) return s / 60 + "m ago";
		if (s < 86400) return s / 3600 + "h ago";
		return s / 86400 + "d ago";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		for (int i = 0; i < Tab.values().length; i++) {
			int[] r = tabRect(i);
			if (Render2D.hovered(mx, my, r[0], r[1], r[2], r[3])) {
				tab = Tab.values()[i];
				scrollTarget = 0;
				Sounds.click();
				return true;
			}
		}
		int[] c = clearRect();
		if (Render2D.hovered(mx, my, c[0], c[1], c[2], c[3])) {
			for (Find f : list()) FindsStore.remove(f);
			Sounds.click();
			return true;
		}
		int[] d = doneRect();
		if (Render2D.hovered(mx, my, d[0], d[1], d[2], d[3])) {
			onClose();
			return true;
		}
		int[] win = window();
		int ly = win[1] + 42, lh = win[3] - 50;
		if (my >= ly && my < ly + lh) {
			List<Find> list = list();
			int i = (int) ((my - ly - scroll.get()) / ROW);
			if (i >= 0 && i < list.size()) {
				Find f = list.get(i);
				int ry = ly + i * ROW + (int) scroll.get();
				int bx = win[0] + win[2] - 58;
				if (Render2D.hovered(mx, my, bx, ry + 5, 26, 14)) {
					minecraft.keyboardHandler.setClipboard(f.x + " " + f.y + " " + f.z);
					Notifications.push("Copied", f.x + " " + f.y + " " + f.z, Notifications.Type.INFO, 1500);
					Sounds.click();
				} else if (Render2D.hovered(mx, my, bx + 30, ry + 5, 16, 14)) {
					FindsStore.remove(f);
					Sounds.click();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		scrollTarget += vertical * ROW;
		return true;
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
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
