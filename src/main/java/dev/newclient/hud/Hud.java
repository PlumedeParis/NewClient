package dev.newclient.hud;

import dev.newclient.config.ClientSettings;
import dev.newclient.module.Module;
import dev.newclient.module.ModuleManager;
import dev.newclient.render.Render2D;
import dev.newclient.util.Anim;
import dev.newclient.util.ColorUtil;
import dev.newclient.util.WorldUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The in-game overlay: watermark, animated module list, info panel, module HUDs and toasts. */
public final class Hud {
	private static final Map<Module, Anim> ARRAY_ANIMS = new HashMap<>();
	public static final String NAME = "NewClient";
	public static final String VERSION = "1.0";

	private static int stackY;
	private static int screenW;

	private Hud() {}

	/** Allocates a slot in the info column (opposite side of the module list). Returns {x, y}. */
	public static int[] slot(int w, int h) {
		boolean right = ClientSettings.ARRAY_SIDE.get() == ClientSettings.ArrayListSide.LEFT;
		int x = right ? screenW - 6 - w : 6;
		int y = stackY;
		stackY += h + 5;
		return new int[] {x, y};
	}

	public static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null) return;
		float pt = delta.getGameTimeDeltaPartialTick(false);
		screenW = g.guiWidth();
		stackY = 6;

		if (ClientSettings.WATERMARK.get()) watermark(g, mc);
		if (ClientSettings.ARRAY_LIST.get()) arrayList(g);
		if (ClientSettings.INFO_HUD.get()) infoPanel(g, mc);

		for (Module m : ModuleManager.all()) {
			if (m.isEnabled()) {
				try {
					m.onRender2D(g, pt);
				} catch (Exception ignored) {
				}
			}
		}
		Notifications.render(g);
	}

	private static void watermark(GuiGraphics g, Minecraft mc) {
		String fps = mc.getFps() + " fps";
		String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
		if (ClientSettings.STREAMER.get()) server = "hidden";
		String info = " " + VERSION + "  │  " + fps + "  │  " + server;
		int h = 17;
		int w = Render2D.width(NAME) + Render2D.width(info) + 16;
		int[] pos = slot(w, h);
		int x = pos[0], y = pos[1];
		Render2D.glow(g, x, y, w, h, 5, ColorUtil.withAlpha(ClientSettings.accent(), 70), 6);
		Render2D.roundedRect(g, x, y, w, h, 5, 0xE6101016);
		Render2D.gradientH(g, x + 5, y, w - 10, 1, ClientSettings.accent(), ClientSettings.accent2());
		Render2D.gradientText(g, NAME, x + 8, y + 5, i -> ClientSettings.accentAt(i / 9f), true);
		Render2D.text(g, info, x + 8 + Render2D.width(NAME), y + 5, 0xFFB4B4C8, true);
	}

	private static void arrayList(GuiGraphics g) {
		List<Module> mods = new ArrayList<>(ModuleManager.all());
		mods.sort((a, b) -> Integer.compare(width(b), width(a)));
		boolean right = ClientSettings.ARRAY_SIDE.get() == ClientSettings.ArrayListSide.RIGHT;
		int sw = g.guiWidth();
		int y = 6;
		int lineH = 13;
		int index = 0;
		for (Module m : mods) {
			Anim anim = ARRAY_ANIMS.computeIfAbsent(m, k -> new Anim(0, 12));
			anim.setTarget(m.isEnabled() ? 1 : 0);
			float a = anim.getF();
			if (a < 0.01f) continue;
			int w = width(m) + 10;
			float ease = (float) Anim.easeOutCubic(a);
			int x = right ? (int) (sw - 6 - w * ease) : (int) (6 - w + w * ease);
			int color = ClientSettings.accentAt(index * 0.08f);
			int bg = ColorUtil.fade(0xD0101016, a);
			Render2D.rect(g, x, y, w, (int) (lineH * a) + 1, bg);
			if (right) Render2D.rect(g, x + w - 2, y, 2, (int) (lineH * a) + 1, ColorUtil.fade(color, a));
			else Render2D.rect(g, x, y, 2, (int) (lineH * a) + 1, ColorUtil.fade(color, a));
			Render2D.text(g, m.getName(), x + 5, y + 3, ColorUtil.fade(color, a), true);
			String info = m.getInfo();
			if (info != null) Render2D.text(g, info, x + 5 + Render2D.width(m.getName() + " "), y + 3, ColorUtil.fade(0xFF9A9AB0, a), true);
			y += (int) (lineH * a);
			index++;
		}
	}

	private static int width(Module m) {
		String info = m.getInfo();
		return Render2D.width(m.getName()) + (info != null ? Render2D.width(" " + info) : 0);
	}

	private static void infoPanel(GuiGraphics g, Minecraft mc) {
		double x = mc.player.getX(), py = mc.player.getY(), z = mc.player.getZ();
		boolean nether = mc.level != null && WorldUtil.dimensionName(mc.level).equals("the_nether");
		List<String> lines = new ArrayList<>();
		if (ClientSettings.STREAMER.get()) lines.add("XYZ  hidden");
		else {
			lines.add(String.format(Locale.ROOT, "XYZ  %.0f  %.0f  %.0f", x, py, z));
			lines.add(nether ? String.format(Locale.ROOT, "OW  %.0f  %.0f", x * 8, z * 8) : String.format(Locale.ROOT, "Nether  %.0f  %.0f", x / 8, z / 8));
		}
		Direction dir = mc.player.getDirection();
		String axis = switch (dir) {
			case NORTH -> "-Z";
			case SOUTH -> "+Z";
			case EAST -> "+X";
			case WEST -> "-X";
			default -> "";
		};
		lines.add("Facing  " + cap(dir.getName()) + " (" + axis + ")");
		int w = 0;
		for (String l : lines) w = Math.max(w, Render2D.width(l));
		w += 14;
		int h = lines.size() * 10 + 6;
		int[] pos = slot(w, h);
		Render2D.roundedRect(g, pos[0], pos[1], w, h, 5, 0xC8101016);
		Render2D.rect(g, pos[0], pos[1] + 4, 2, h - 8, ClientSettings.accent());
		int accent = ClientSettings.accent();
		for (int i = 0; i < lines.size(); i++) drawInfoLine(g, pos[0] + 7, pos[1] + 4 + i * 10, lines.get(i), accent);
	}

	private static void drawInfoLine(GuiGraphics g, int x, int y, String s, int accent) {
		int split = s.indexOf("  ");
		if (split < 0) {
			Render2D.text(g, s, x, y, 0xFFE0E0EA);
			return;
		}
		String label = s.substring(0, split);
		Render2D.text(g, label, x, y, accent, false);
		Render2D.text(g, s.substring(split), x + Render2D.width(label), y, 0xFFE0E0EA, false);
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
