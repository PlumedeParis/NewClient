package dev.zero.gui;

import dev.zero.config.ConfigManager;
import dev.zero.gui.comp.TextField;
import dev.zero.hud.Notifications;
import dev.zero.render.Render2D;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Create, switch, rename and delete config profiles; pick one to auto-load per server. */
public class ProfilesScreen extends Screen {
	private static final int ROW = 20;
	private final Screen parent;
	private final TextField name = new TextField("Profile name...", 24, null, null);
	private double scroll;
	private String confirmDelete;

	public ProfilesScreen(Screen parent) {
		super(Component.literal("Profiles"));
		this.parent = parent;
		TextField.focused = name;
	}

	private int[] win() {
		int w = Math.min(width - 20, 300), h = Math.min(height - 20, 250);
		return new int[] {width / 2 - w / 2, height / 2 - h / 2, w, h};
	}

	private int[] listArea() {
		int[] w = win();
		return new int[] {w[0] + 8, w[1] + 34, w[2] - 16, w[3] - 34 - 70};
	}

	private int[] btn(int i) {
		int[] w = win();
		int bw = (w[2] - 16 - 8) / 3;
		return new int[] {w[0] + 8 + i * (bw + 4), w[1] + w[3] - 48, bw, 14};
	}

	private int[] autoRect() {
		int[] w = win();
		return new int[] {w[0] + 8, w[1] + w[3] - 28, w[2] - 16 - 64, 14};
	}

	private int[] doneRect() {
		int[] w = win();
		return new int[] {w[0] + w[2] - 8 - 60, w[1] + w[3] - 28, 60, 14};
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xA005050A, 0xC00B0B18);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		super.render(g, mx, my, pt);
		int[] w = win();
		int accent = Theme.accent();
		Render2D.glow(g, w[0], w[1], w[2], w[3], 8, ColorUtil.withAlpha(accent, 60), 8);
		Render2D.roundedRect(g, w[0], w[1], w[2], w[3], 8, Theme.panelBg());
		Render2D.gradientH(g, w[0] + 8, w[1], w[2] - 16, 1, accent, Theme.accent2());
		Render2D.text(g, "☰ Profiles", w[0] + 10, w[1] + 9, Theme.TEXT, false);
		String server = minecraft.level != null ? WorldUtil.serverId() : "not in a world";
		Render2D.text(g, Render2D.trim("§7server: " + server, w[2] / 2), w[0] + w[2] - 10 - Math.min(Render2D.width("server: " + server), w[2] / 2), w[1] + 9, Theme.SUBTEXT, false);
		Render2D.text(g, "§8click to load", w[0] + 10, w[1] + 21, Theme.MUTED, false);

		List<String> list = ConfigManager.profiles();
		int[] la = listArea();
		String forServer = minecraft.level != null ? ConfigManager.profileForServer(server) : null;
		double max = Math.max(0, list.size() * ROW - la[3]);
		scroll = Math.max(-max, Math.min(0, scroll));
		g.enableScissor(la[0], la[1], la[0] + la[2], la[1] + la[3]);
		for (int i = 0; i < list.size(); i++) {
			String p = list.get(i);
			int y = la[1] + i * ROW + (int) scroll;
			boolean active = p.equals(ConfigManager.activeProfile());
			boolean hover = Render2D.hovered(mx, my, la[0], y, la[2], ROW - 2) && my >= la[1] && my < la[1] + la[3];
			Render2D.roundedRect(g, la[0], y, la[2], ROW - 2, 5, active ? ColorUtil.withAlpha(accent, 70) : hover ? 0x22FFFFFF : 0x10FFFFFF);
			if (active) Render2D.roundedRect(g, la[0] + 4, y + 4, 3, ROW - 10, 1, accent);
			Render2D.text(g, p, la[0] + 12, y + 5, active ? 0xFFFFFFFF : Theme.TEXT, false);
			int tx = la[0] + 16 + Render2D.width(p);
			if (active) {
				Render2D.text(g, "§7active", tx, y + 5, Theme.SUBTEXT, false);
				tx += Render2D.width("active ") + 4;
			}
			if (p.equals(forServer)) Render2D.text(g, "§a✔ this server", tx, y + 5, 0xFF4ADE80, false);
			if (!active) {
				boolean del = p.equals(confirmDelete);
				int bx = la[0] + la[2] - (del ? 58 : 18);
				boolean hd = Render2D.hovered(mx, my, bx, y + 2, del ? 54 : 14, 14);
				Render2D.roundedRect(g, bx, y + 2, del ? 54 : 14, 14, 4, hd || del ? 0xFFB4234A : Theme.FIELD);
				Render2D.centeredText(g, del ? "Delete?" : "×", bx + (del ? 27 : 7), y + 5, 0xFFFFFFFF, false);
			}
		}
		g.disableScissor();

		// name field + buttons
		int[] b0 = btn(0);
		name.render(g, w[0] + 8, b0[1] - 18, w[2] - 16, 14, Theme.FIELD);
		String[] labels = {"+ New", "Duplicate", "Rename"};
		for (int i = 0; i < 3; i++) {
			int[] r = btn(i);
			boolean hover = Render2D.hovered(mx, my, r[0], r[1], r[2], r[3]);
			Render2D.roundedRect(g, r[0], r[1], r[2], r[3], 4, hover ? 0xFF2A2A38 : Theme.FIELD);
			Render2D.centeredText(g, labels[i], r[0] + r[2] / 2, r[1] + 3, Theme.TEXT, false);
		}
		int[] ar = autoRect();
		boolean on = forServer != null && forServer.equals(ConfigManager.activeProfile());
		boolean ha = Render2D.hovered(mx, my, ar[0], ar[1], ar[2], ar[3]);
		Render2D.roundedRect(g, ar[0], ar[1], ar[2], ar[3], 4, on ? ColorUtil.withAlpha(0xFF4ADE80, 120) : ha ? 0xFF2A2A38 : Theme.FIELD);
		Render2D.centeredText(g, minecraft.level == null ? "§8Join a server to auto-load" : (on ? "✔ Auto-load on this server" : "Auto-load on this server"), ar[0] + ar[2] / 2, ar[1] + 3, Theme.TEXT, false);
		int[] d = doneRect();
		Render2D.roundedRect(g, d[0], d[1], d[2], d[3], 4, accent);
		Render2D.centeredText(g, "Done", d[0] + d[2] / 2, d[1] + 3, 0xFFFFFFFF, false);
	}

	private void toast(String title, String msg) {
		Notifications.push(title, msg, Notifications.Type.INFO, 2500);
		Sounds.click();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int[] la = listArea();
		List<String> list = ConfigManager.profiles();
		if (mx >= la[0] && mx < la[0] + la[2] && my >= la[1] && my < la[1] + la[3]) {
			int i = (int) ((my - la[1] - scroll) / ROW);
			if (i >= 0 && i < list.size()) {
				String p = list.get(i);
				int y = la[1] + i * ROW + (int) scroll;
				boolean active = p.equals(ConfigManager.activeProfile());
				boolean del = p.equals(confirmDelete);
				int bx = la[0] + la[2] - (del ? 58 : 18);
				if (!active && Render2D.hovered(mx, my, bx, y + 2, del ? 54 : 14, 14)) {
					if (del) {
						ConfigManager.delete(p);
						confirmDelete = null;
						toast("Profile deleted", p);
					} else confirmDelete = p;
					return true;
				}
				confirmDelete = null;
				if (!active) {
					ConfigManager.switchTo(p);
					toast("Profile loaded", p);
				}
			}
			return true;
		}
		confirmDelete = null;
		int[] b0 = btn(0);
		int[] w = win();
		if (Render2D.hovered(mx, my, w[0] + 8, b0[1] - 18, w[2] - 16, 14)) {
			TextField.focused = name;
			return true;
		}
		for (int i = 0; i < 3; i++) {
			int[] r = btn(i);
			if (!Render2D.hovered(mx, my, r[0], r[1], r[2], r[3])) continue;
			String n = ConfigManager.cleanName(name.getText());
			if (n == null) {
				toast("Type a name first", "Letters, numbers, spaces, - _ .");
				TextField.focused = name;
				return true;
			}
			if (ConfigManager.exists(n)) {
				toast("Name already used", n);
				return true;
			}
			switch (i) {
				case 0 -> {
					ConfigManager.createBlank(n);
					toast("New profile", n + " (default settings)");
				}
				case 1 -> {
					ConfigManager.duplicate(n);
					toast("Profile duplicated", n);
				}
				default -> {
					ConfigManager.rename(n);
					toast("Profile renamed", n);
				}
			}
			name.setText("");
			return true;
		}
		int[] ar = autoRect();
		if (minecraft.level != null && Render2D.hovered(mx, my, ar[0], ar[1], ar[2], ar[3])) {
			String server = WorldUtil.serverId();
			String current = ConfigManager.profileForServer(server);
			boolean on = current != null && current.equals(ConfigManager.activeProfile());
			ConfigManager.setServerProfile(server, on ? null : ConfigManager.activeProfile());
			toast(on ? "Auto-load off" : "Auto-load on", server);
			return true;
		}
		int[] d = doneRect();
		if (Render2D.hovered(mx, my, d[0], d[1], d[2], d[3])) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scroll += v * ROW;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (name.keyPressed(event.key(), event.modifiers())) return true;
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		String s = event.codepointAsString();
		TextField.focused = name;
		return !s.isEmpty() && name.charTyped(s.charAt(0));
	}

	@Override
	public void onClose() {
		TextField.focused = null;
		minecraft.setScreen(parent instanceof ClickGuiScreen ? new ClickGuiScreen() : parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
