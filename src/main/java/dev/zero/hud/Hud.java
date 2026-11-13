package dev.zero.hud;

import dev.zero.config.ClientSettings;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render2D;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
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
	public static final String NAME = "ZER\u00D8";
	public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("zero")
		.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.0");

	private static int stackY;
	private static int screenW;

	private Hud() {}

	/** Allocates a slot in the info column (opposite side of the module list). Returns {x, y}. */
	public static int[] slot(String id, int w, int h) {
		int[] pos;
		if (HudLayout.isCustom(id)) {
			pos = HudLayout.place(id, w, h, screenW, screenH);
		} else {
			boolean right = ClientSettings.ARRAY_SIDE.get() == ClientSettings.ArrayListSide.LEFT;
			pos = new int[] {right ? screenW - 6 - w : 6, stackY};
			stackY += h + 5;
		}
		HudLayout.record(id, pos[0], pos[1], w, h);
		return pos;
	}

	private static int screenH;

	/** Where toasts go: {right edge x, base y, 1 = stack upwards / 0 = downwards}. */
	public static int[] notificationAnchor(int sw, int sh) {
		int w = 190, h = 70;
		if (HudLayout.isCustom("notifications")) {
			int[] p = HudLayout.place("notifications", w, h, sw, sh);
			HudLayout.record("notifications", p[0], p[1], w, h);
			boolean up = HudLayout.get("notifications")[1] >= 0.5;
			return new int[] {p[0] + w, up ? p[1] + h : p[1], up ? 1 : 0};
		}
		HudLayout.record("notifications", sw - 8 - w, sh - 26 - h, w, h);
		return new int[] {sw - 8, sh - 26, 1};
	}

	public static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null || dev.zero.ZeroClient.hidden()) return;
		float pt = delta.getGameTimeDeltaPartialTick(false);
		screenW = g.guiWidth();
		screenH = g.guiHeight();
		stackY = 6;
		HudLayout.LAST.clear();

		if (ClientSettings.WATERMARK.get()) watermark(g, mc);
		if (ClientSettings.ARRAY_LIST.get()) arrayList(g);
		if (ClientSettings.INFO_HUD.get()) infoPanel(g, mc);
		if (ClientSettings.PERF_HUD.get()) {
			String perf = String.format(Locale.ROOT, "ZER\u00D8  3D %.1fms  HUD %.1fms  scan %.1fms", dev.zero.util.Perf.renderMs(),
				dev.zero.util.Perf.MODULE_MS.getOrDefault("_hud", 0.0), dev.zero.util.Perf.scanMs());
			int w = Render2D.width(perf) + 10;
			int[] p = slot("perf", w, 13);
			Render2D.roundedRect(g, p[0], p[1], w, 13, 4, 0xC8101016);
			Render2D.text(g, perf, p[0] + 5, p[1] + 3, 0xFFB4B4C8, false);
		}

		for (Module m : ModuleManager.all()) {
			if (m.isEnabled()) {
				try {
					m.onRender2D(g, pt);
				} catch (Exception ignored) {
				}
			}
		}
		LookInfo.render(g);
		Notifications.render(g);
	}

	private static void watermark(GuiGraphics g, Minecraft mc) {
		String fps = mc.getFps() + " fps";
		String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
		if (ClientSettings.STREAMER.get()) server = "hidden";
		String info = " " + VERSION + "  │  " + fps + "  │  " + server;
		int h = 17;
		int w = Render2D.width(NAME) + Render2D.width(info) + 16;
		int[] pos = slot("watermark", w, h);
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
		int sw = g.guiWidth();
		int lineH = 13;
		// Size of the whole list (for custom placement and the HUD editor).
		int boxW = 40, boxH = 0;
		for (Module m : mods) {
			Anim anim = ARRAY_ANIMS.computeIfAbsent(m, k -> new Anim(0, 12));
			anim.setTarget(m.isEnabled() ? 1 : 0);
			if (m.isEnabled()) {
				boxW = Math.max(boxW, width(m) + 10);
				boxH += lineH;
			}
		}
		boxH = Math.max(boxH, lineH);
		boolean right;
		int x0, y;
		if (HudLayout.isCustom("modules")) {
			int[] p = HudLayout.place("modules", boxW, boxH, sw, screenH);
			right = HudLayout.get("modules")[0] >= 0.5;
			x0 = right ? p[0] + boxW : p[0];
			y = p[1];
			HudLayout.record("modules", p[0], p[1], boxW, boxH);
		} else {
			right = ClientSettings.ARRAY_SIDE.get() == ClientSettings.ArrayListSide.RIGHT;
			x0 = right ? sw - 6 : 6;
			y = 6;
			HudLayout.record("modules", right ? sw - 6 - boxW : 6, 6, boxW, boxH);
		}
		int index = 0;
		for (Module m : mods) {
			Anim anim = ARRAY_ANIMS.get(m);
			float a = anim.getF();
			if (a < 0.01f) continue;
			int w = width(m) + 10;
			float ease = (float) Anim.easeOutCubic(a);
			int x = right ? (int) (x0 - w * ease) : (int) (x0 - w + w * ease);
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
		int[] pos = slot("coords", w, h);
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
