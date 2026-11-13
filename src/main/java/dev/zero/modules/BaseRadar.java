package dev.zero.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.zero.ZeroClient;
import dev.zero.config.ClientSettings;
import dev.zero.config.ConfigManager;
import dev.zero.hud.Hud;
import dev.zero.hud.Notifications;
import dev.zero.module.Category;
import dev.zero.module.HuntTarget;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render2D;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.KeybindSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * For areas full of bases: merges the evidence of every Hunt module into one target per
 * location, ranks them, shows only the best few as beams, and lets you tick targets off as
 * "checked" (key or automatically when you visit) so you can work through a busy zone.
 */
public class BaseRadar extends Module {
	public enum Sort { SMART, SCORE, DISTANCE }

	private final NumberSetting mergeRadius = add(new NumberSetting("Merge Radius", "Evidence closer than this counts as one base", 24, 8, 96, 4, "m"));
	private final NumberSetting minScore = add(new NumberSetting("Min Score", "Hide weaker targets", 40, 0, 200, 5));
	private final EnumSetting<Sort> sort = add(new EnumSetting<>("Sort", "Smart = evidence weighted by distance", Sort.SMART));
	private final NumberSetting focus = add(new NumberSetting("Focus Targets", "How many top targets get a beam", 3, 1, 10, 1));
	private final BoolSetting others = add(new BoolSetting("Show Others", "Faint markers for the remaining targets", true));
	private final BoolSetting tracer = add(new BoolSetting("Tracer To #1", "Line to the best target", true));
	private final BoolSetting autoCheck = add(new BoolSetting("Auto Check", "Mark a target checked after standing near it for 3 s", true));
	private final NumberSetting checkDistance = add(new NumberSetting("Check Distance", "How close counts as visited", 12, 4, 48, 2, "m")).visibleWhen(autoCheck::get);
	public final KeybindSetting checkKey = add(new KeybindSetting("Check Key", "Mark the nearest target as checked (or un-check it)", GLFW.GLFW_KEY_K));
	private final BoolSetting hideChecked = add(new BoolSetting("Hide Checked", "Hide targets you already checked", true));
	private final BoolSetting quiet = add(new BoolSetting("Quiet Mode", "Silence the Hunt modules' own alerts; only alert strong new targets here", true));
	private final NumberSetting alertScore = add(new NumberSetting("Alert Confidence", "Pop-up when a place reaches this base confidence", 70, 30, 99, 1, "%"));
	private final BoolSetting hud = add(new BoolSetting("HUD", "Ranked list + mini radar", true));
	private final ColorSetting rank1 = add(new ColorSetting("#1 Color", "Best target", 0xFFFF4D6D, false));
	private final ColorSetting rank2 = add(new ColorSetting("#2 Color", "Second target", 0xFFFFB347, false));
	private final ColorSetting rank3 = add(new ColorSetting("#3 Color", "Third target", 0xFFFFE066, false));
	private final ColorSetting rankOther = add(new ColorSetting("Focus Color", "Other focused targets", 0xFF60A5FA, false));
	private final ColorSetting markerColor = add(new ColorSetting("Marker Color", "Faint markers for the other targets", 0xFF94A3B8, false));
	private final BoolSetting clearChecked = add(new BoolSetting("Clear Checked", "Turn on to forget every checked target", false));

	public static final class Target {
		final BlockPos pos;
		float score;
		final Set<String> reasons = new LinkedHashSet<>();
		final List<HuntTarget> evidence = new ArrayList<>();
		dev.zero.module.Confidence.Result conf;
		int color;
		boolean checked;
		double dist;

		Target(HuntTarget t) {
			pos = t.pos();
			score = t.score();
			color = t.color();
			reasons.add(t.reason());
			evidence.add(t);
		}

		String reasonText() { return String.join(", ", conf != null ? conf.reasons() : reasons); }

		public int percent() { return conf == null ? 0 : conf.percent(); }
	}

	private record Checked(String dim, int x, int z) {}

	private final List<Target> targets = new ArrayList<>();
	private final Set<Checked> checked = new HashSet<>();
	private final Set<Long> alerted = new HashSet<>();
	private String loadedServer;
	private int tick, nearTicks;
	private Target nearTarget;
	private boolean keyHeld;

	public BaseRadar() {
		super("BaseRadar", "Ranks all base evidence for busy zones; check targets off one by one", Category.HUNT);
	}

	/** Read by the Hunt modules to stay silent in dense areas. */
	public static boolean quietMode() {
		return ModuleManager.baseRadar != null && ModuleManager.baseRadar.isEnabled() && ModuleManager.baseRadar.quiet.get();
	}

	@Override
	public String getInfo() {
		int open = 0;
		for (Target t : targets) if (!t.checked) open++;
		return targets.isEmpty() ? null : open + "/" + targets.size();
	}

	// ---- Checked list persistence (per server) ------------------------------------------------
	private Path file() { return ConfigManager.dir("checked").resolve(WorldUtil.serverId() + ".json"); }

	private void ensureLoaded() {
		String server = WorldUtil.serverId();
		if (server.equals(loadedServer)) return;
		loadedServer = server;
		checked.clear();
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonArray a = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
			for (var e : a) {
				var o = e.getAsJsonObject();
				checked.add(new Checked(o.get("dim").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt()));
			}
		} catch (Exception e) {
			ZeroClient.LOGGER.warn("Could not read checked targets", e);
		}
	}

	private void saveChecked() {
		JsonArray a = new JsonArray();
		for (Checked c : checked) {
			var o = new com.google.gson.JsonObject();
			o.addProperty("dim", c.dim);
			o.addProperty("x", c.x);
			o.addProperty("z", c.z);
			a.add(o);
		}
		try {
			Files.writeString(file(), ConfigManager.GSON.toJson(a), StandardCharsets.UTF_8);
		} catch (Exception e) {
			ZeroClient.LOGGER.warn("Could not save checked targets", e);
		}
	}

	private boolean isChecked(String dim, BlockPos p) {
		double r = mergeRadius.get();
		for (Checked c : checked) {
			if (!c.dim.equals(dim)) continue;
			double dx = c.x - p.getX(), dz = c.z - p.getZ();
			if (dx * dx + dz * dz <= r * r) return true;
		}
		return false;
	}

	private void toggleChecked(Target t) {
		String dim = WorldUtil.dimensionName(mc.level);
		if (isChecked(dim, t.pos)) {
			double r = mergeRadius.get();
			checked.removeIf(c -> c.dim.equals(dim) && Math.pow(c.x - t.pos.getX(), 2) + Math.pow(c.z - t.pos.getZ(), 2) <= r * r);
			Notifications.push("Target re-opened", t.reasonText(), Notifications.Type.INFO, 2500);
		} else {
			checked.add(new Checked(dim, t.pos.getX(), t.pos.getZ()));
			Notifications.push("Target checked ✔", t.reasonText(), Notifications.Type.ENABLED, 2500);
		}
		Sounds.click();
		saveChecked();
		rebuild();
	}

	// ---- Logic --------------------------------------------------------------------------------
	@Override
	public void onTick() {
		ensureLoaded();
		if (clearChecked.get()) {
			clearChecked.set(false);
			checked.clear();
			saveChecked();
			Notifications.push("BaseRadar", "All targets un-checked", Notifications.Type.INFO, 2500);
		}
		if (++tick % 10 == 0) rebuild();

		// Check key (edge-triggered, only in game)
		boolean down = checkKey.isBound() && mc.screen == null && InputConstants.isKeyDown(mc.getWindow(), checkKey.get());
		if (down && !keyHeld) {
			Target nearest = null;
			for (Target t : targets) if (nearest == null || t.dist < nearest.dist) nearest = t;
			if (nearest != null) toggleChecked(nearest);
		}
		keyHeld = down;

		// Auto check when visited
		if (autoCheck.get() && mc.player != null) {
			Target close = null;
			for (Target t : targets) {
				if (t.checked) continue;
				double dx = t.pos.getX() + 0.5 - mc.player.getX(), dz = t.pos.getZ() + 0.5 - mc.player.getZ();
				if (Math.sqrt(dx * dx + dz * dz) <= checkDistance.get()) {
					close = t;
					break;
				}
			}
			if (close != null && nearTarget != null && close.pos.equals(nearTarget.pos)) nearTicks++;
			else nearTicks = 0;
			nearTarget = close;
			if (close != null && nearTicks >= 60) {
				nearTicks = 0;
				toggleChecked(close);
			}
		}
	}

	private void rebuild() {
		if (mc.level == null || mc.player == null) return;
		List<HuntTarget> raw = new ArrayList<>();
		for (Module m : ModuleManager.all())
			if (m != this && m.isEnabled() && m instanceof HuntTarget.Provider p) p.collectTargets(raw);
		raw.sort((a, b) -> Float.compare(b.score(), a.score()));
		double r2 = mergeRadius.get() * mergeRadius.get();
		List<Target> merged = new ArrayList<>();
		for (HuntTarget h : raw) {
			Target into = null;
			for (Target t : merged) {
				double dx = t.pos.getX() - h.pos().getX(), dz = t.pos.getZ() - h.pos().getZ();
				if (dx * dx + dz * dz <= r2) {
					into = t;
					break;
				}
			}
			if (into == null) merged.add(new Target(h));
			else if (into.reasons.add(h.reason())) {
				into.score += h.score() * 0.5f; // independent evidence stacks
				into.evidence.add(h);
			}
		}
		String dim = WorldUtil.dimensionName(mc.level);
		Vec3 me = mc.player.position();
		targets.clear();
		for (Target t : merged) {
			if (t.score < minScore.get()) continue;
			t.conf = dev.zero.module.Confidence.of(t.evidence);
			t.checked = isChecked(dim, t.pos);
			t.dist = Math.sqrt(Math.pow(t.pos.getX() + 0.5 - me.x, 2) + Math.pow(t.pos.getZ() + 0.5 - me.z, 2));
			targets.add(t);
			long key = BlockPos.asLong(t.pos.getX() >> 4, 0, t.pos.getZ() >> 4);
			if (!t.checked && t.percent() >= alertScore.get() && alerted.add(key)) {
				Notifications.push(t.conf.label() + " " + t.percent() + "%", WorldUtil.distance(t.dist) + " " + WorldUtil.direction(me, t.pos.getX(), t.pos.getZ())
					+ "  •  " + t.reasonText(), Notifications.Type.FOUND, 6000);
				Sounds.ping();
			}
		}
		targets.sort(switch (sort.get()) {
			case SCORE -> (a, b) -> Integer.compare(b.percent(), a.percent());
			case DISTANCE -> (a, b) -> Double.compare(a.dist, b.dist);
			case SMART -> (a, b) -> Double.compare(b.percent() / (1 + b.dist / 150), a.percent() / (1 + a.dist / 150));
		});
	}

	private List<Target> visible() {
		List<Target> out = new ArrayList<>();
		for (Target t : targets) if (!t.checked || !hideChecked.get()) out.add(t);
		return out;
	}

	private int rankColor(int rank) {
		return switch (rank) {
			case 0 -> rank1.get();
			case 1 -> rank2.get();
			case 2 -> rank3.get();
			default -> rankOther.get();
		};
	}

	// ---- Rendering ----------------------------------------------------------------------------
	@Override
	public void onRender3D(float pt) {
		List<Target> list = visible();
		Vec3 cam = WorldUtil.cameraPos();
		int n = focus.getInt();
		for (int i = 0; i < list.size(); i++) {
			Target t = list.get(i);
			Vec3 c = Vec3.atCenterOf(t.pos);
			if (i < n && !t.checked) {
				int color = rankColor(i);
				double bottom = mc.level.getMinY(), top = mc.level.getMaxY();
				Render3D.box(new AABB(c.x - 0.4, bottom, c.z - 0.4, c.x + 0.4, top, c.z + 0.4), ColorUtil.withAlpha(color, 50), ColorUtil.withAlpha(color, 170), 1.5f, true);
				AABB tb = new AABB(t.pos).inflate(0.3);
				Render3D.box(tb, ColorUtil.withAlpha(color, 70), ColorUtil.withAlpha(color, 255), 2f, true);
				if (Render3D.look(tb)) Render3D.lookText(t.conf.label() + " " + t.percent() + "% \u00B7 " + t.reasonText(), color);
				String why = t.reasonText();
				if (why.length() > 42) why = why.substring(0, 40) + "…";
				Render3D.importantLabel(c.add(0, 1.6, 0), "#" + (i + 1) + " " + t.percent() + "% " + t.conf.label().toUpperCase(java.util.Locale.ROOT) + " §7" + why + " • " + WorldUtil.distance(cam.distanceTo(c)), color, 1f);
				if (i == 0 && tracer.get()) Render3D.tracer(c, ColorUtil.withAlpha(color, 220), 2f);
			} else if (others.get()) {
				int color = t.checked ? 0xFF6B7280 : markerColor.get();
				Render3D.box(new AABB(t.pos).inflate(0.2), ColorUtil.withAlpha(color, 25), ColorUtil.withAlpha(color, 120), 1f, true);
				if (cam.distanceTo(c) < 64) Render3D.label(c.add(0, 1.2, 0), (t.checked ? "✔ " : "#" + (i + 1) + " ") + t.percent() + "%", color, 0.8f);
			}
		}
	}

	@Override
	public void onRender2D(GuiGraphics g, float pt) {
		if (!hud.get() || mc.player == null) return;
		List<Target> list = visible();
		int open = 0;
		for (Target t : targets) if (!t.checked) open++;
		int rows = Math.max(0, Math.min(5, list.size() - 1));
		int w = 200, rowH = 11, radar = 64;
		int card = list.isEmpty() ? 14 : 50;
		int h = 18 + card + rows * rowH + (rows > 0 ? 3 : 0) + radar + 8;
		int[] pos = Hud.slot("Base Radar", w, h);
		int x = pos[0], y = pos[1];
		int accent = ClientSettings.accent();
		Render2D.glow(g, x, y, w, h, 5, ColorUtil.withAlpha(0xFFFF4D6D, 50), 4);
		Render2D.roundedRect(g, x, y, w, h, 5, 0xE0101016);
		Render2D.gradientH(g, x + 5, y, w - 10, 1, 0xFFFF4D6D, accent);
		Render2D.text(g, "Base Radar", x + 6, y + 5, 0xFFFFFFFF, false);
		String stat = open + " open \u2022 " + (targets.size() - open) + " checked";
		Render2D.text(g, stat, x + w - 6 - Render2D.width(stat), y + 5, 0xFF9A9AB0, false);
		Vec3 me = mc.player.position();
		if (list.isEmpty()) Render2D.text(g, "\u00A78No targets yet \u2014 turn on Hunt / Signs modules", x + 6, y + 18, 0xFF9A9AB0, false);
		else {
			// the best target: confidence ring + why
			Target t = list.get(0);
			int cy0 = y + 17;
			int cc = t.checked ? 0xFF6B7280 : t.conf.color();
			Render2D.roundedRect(g, x + 4, cy0, w - 8, card - 3, 5, 0x66000000);
			Render2D.ring(g, x + 26, cy0 + 23, 17, 3.2f, t.percent() / 100f, cc, 0xFF23232E);
			String pct = t.percent() + "%";
			Render2D.text(g, "\u00A7l" + pct, x + 26 - Render2D.width("\u00A7l" + pct) / 2, cy0 + 19, 0xFFFFFFFF, false);
			String d = WorldUtil.distance(t.dist) + " " + WorldUtil.direction(me, t.pos.getX(), t.pos.getZ());
			Render2D.text(g, "\u00A7l" + (t.checked ? "Checked" : t.conf.label()), x + 50, cy0 + 4, cc, false);
			Render2D.text(g, d, x + w - 9 - Render2D.width(d), cy0 + 4, 0xFFE6E6F0, false);
			List<String> why = t.conf.reasons();
			for (int i = 0; i < Math.min(3, why.size()); i++) {
				String line = i == 2 && why.size() > 3 ? "+" + (why.size() - 2) + " more clues" : why.get(i);
				Render2D.text(g, "\u00A7a\u2714 \u00A7r" + Render2D.trim(line, w - 72), x + 50, cy0 + 15 + i * 10, 0xFFB4B4C8, false);
			}
		}
		for (int i = 0; i < rows; i++) {
			Target t = list.get(i + 1);
			int ry = y + 17 + card + i * rowH;
			int color = t.checked ? 0xFF6B7280 : t.conf.color();
			Render2D.text(g, (t.checked ? "\u2714" : "#" + (i + 2)), x + 6, ry, i + 1 < focus.getInt() && !t.checked ? rankColor(i + 1) : 0xFF9A9AB0, false);
			Render2D.text(g, t.percent() + "%", x + 24, ry, color, false);
			String d = WorldUtil.distance(t.dist) + " " + WorldUtil.direction(me, t.pos.getX(), t.pos.getZ());
			int dw = Render2D.width(d);
			Render2D.text(g, Render2D.trim(t.reasonText(), w - 62 - dw), x + 52, ry, 0xFFB4B4C8, false);
			Render2D.text(g, d, x + w - 6 - dw, ry, 0xFF9A9AB0, false);
		}
		// Mini radar (forward = up)
		int cx = x + w / 2, cy = y + h - radar / 2 - 5, rad = radar / 2 - 1;
		Render2D.circle(g, cx, cy, rad, 0xFF0B0B12);
		Render2D.circle(g, cx, cy, rad / 2, 0xFF14141E);
		Render2D.rect(g, cx - rad, cy, rad * 2, 1, 0x30FFFFFF);
		Render2D.rect(g, cx, cy - rad, 1, rad * 2, 0x30FFFFFF);
		double range = 256;
		double yaw = Math.toRadians(mc.player.getYRot());
		for (int i = list.size() - 1; i >= 0; i--) {
			Target t = list.get(i);
			double dx = t.pos.getX() + 0.5 - me.x, dz = t.pos.getZ() + 0.5 - me.z;
			// rotate so the player's facing is up
			double rx = dx * Math.cos(yaw) + dz * Math.sin(yaw);
			double rz = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
			double dist = Math.sqrt(rx * rx + rz * rz);
			double scale = Math.min(1, dist / range) * rad / Math.max(dist, 1e-3);
			int px = (int) (cx - rx * scale), py = (int) (cy - rz * scale);
			int color = t.checked ? 0xFF6B7280 : i < focus.getInt() ? rankColor(i) : markerColor.get();
			Render2D.circle(g, px, py, i < focus.getInt() && !t.checked ? 2 : 1, color);
		}
		Render2D.circle(g, cx, cy, 2, 0xFFFFFFFF);
		Render2D.text(g, "256m", cx + rad - 18, cy + rad - 8, 0xFF5C5C70, false);
		String hint = "[" + checkKey.display() + "] check";
		Render2D.text(g, hint, x + 6, y + h - 13, 0xFF5C5C70, false);
	}

	/** Confidence of the best open target, 0 when there is none (in-game test). */
	public int topPercent() {
		List<Target> l = visible();
		return l.isEmpty() ? 0 : l.get(0).percent();
	}

	public String topWhy() {
		List<Target> l = visible();
		return l.isEmpty() ? "" : l.get(0).conf.label() + ": " + l.get(0).reasonText();
	}

	public String describe() { return String.format(Locale.ROOT, "%d targets", targets.size()); }

	static double clamp(double v) { return Mth.clamp(v, 0, 1); }
}
