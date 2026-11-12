package dev.zero.modules;

import dev.zero.hud.Hud;
import dev.zero.hud.Notifications;
import dev.zero.module.Category;
import dev.zero.module.HuntTarget;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render2D;
import dev.zero.render.Render3D;
import dev.zero.scan.ChunkScanner;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.setting.Setting;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DonutSMP hunting loop in one module: turns on the right finders with Donut settings,
 * then judges every area you arrive in (after /rtp, /home, a warp...) once its chunks have
 * loaded and been scanned: "nothing here, rtp again" or "N leads, go check". Remembers the
 * areas you already checked so you don't waste time on them twice.
 *
 * It never types /rtp or anything else for you: you run the commands yourself.
 */
public class DonutHunter extends Module {
	private final BoolSetting useSpawners = add(new BoolSetting("Spawners", "SpawnerESP + SpawnerFinder (Donut bases are full of placed spawners)", true));
	private final BoolSetting useStorage = add(new BoolSetting("Storage", "StorageESP (chests, shulkers, barrels...)", true));
	private final BoolSetting useSus = add(new BoolSetting("SusChunk", "SusChunk in Bases mode: buried storage + base blocks", true));
	private final BoolSetting useGrowth = add(new BoolSetting("Growth", "AmethystFinder + GrowthFinder: areas kept loaded for a long time", true));
	private final BoolSetting usePlayerBlocks = add(new BoolSetting("Player Blocks", "RotatedDeepslate + TunnelFinder: dug tunnels and patched entrances", true));
	private final BoolSetting useRadar = add(new BoolSetting("BaseRadar", "Ranks every lead and puts beams on the best ones", true));
	private final BoolSetting usePlayers = add(new BoolSetting("PlayerDetector", "Know when someone is around", true));
	private final BoolSetting useFullbright = add(new BoolSetting("Fullbright", "See underground", false));
	private final BoolSetting tune = add(new BoolSetting("Donut Settings", "Tune the modules for DonutSMP when turned on (no highway filter, quiet alerts, underground bases)", true));

	public enum Arrival { TELEPORT, DISTANCE }

	private final NumberSetting leadScore = add(new NumberSetting("Lead Score", "Evidence score that counts as a lead worth checking", 40, 15, 120, 5));
	private final NumberSetting newArea = add(new NumberSetting("New Area", "A teleport or moving this far starts a new area", 200, 64, 1000, 16, "m"));
	private final NumberSetting settle = add(new NumberSetting("Settle Time", "Seconds with no new chunks before the area verdict", 4, 1, 20, 1, "s"));
	private final NumberSetting rememberRadius = add(new NumberSetting("Checked Radius", "Warn when you land this close to an area you already checked", 160, 32, 1000, 16, "m"));
	private final BoolSetting beam = add(new BoolSetting("Lead Beam", "Beam on the best lead (when BaseRadar is off)", true));
	private final BoolSetting alerts = add(new BoolSetting("Alerts", "Toast + sound with each area verdict", true));
	private final ColorSetting leadColor = add(new ColorSetting("Lead Color", "Best lead beam", 0xFFFF4DD8, false));
	private final BoolSetting hud = add(new BoolSetting("HUD", "Area verdict and hunt stats", true));

	public DonutHunter() {
		super("DonutHunter", "DonutSMP hunting: the right finders + rtp area verdicts", Category.HUNT);
	}

	// ---------------------------------------------------------------- helpers on/off

	private final Map<Module, Boolean> previous = new IdentityHashMap<>();

	private List<Module> helpers() {
		List<Module> l = new ArrayList<>();
		if (useSpawners.get()) { l.add(ModuleManager.spawnerEsp); l.add(ModuleManager.spawnerFinder); }
		if (useStorage.get()) l.add(ModuleManager.storageEsp);
		if (useSus.get()) l.add(ModuleManager.susChunk);
		if (useGrowth.get()) { l.add(ModuleManager.amethystFinder); l.add(ModuleManager.growthFinder); }
		if (usePlayerBlocks.get()) { l.add(ModuleManager.rotatedDeepslate); l.add(ModuleManager.tunnelFinder); }
		if (useRadar.get()) l.add(ModuleManager.baseRadar);
		if (usePlayers.get()) l.add(ModuleManager.playerDetector);
		if (useFullbright.get()) l.add(ModuleManager.fullbright);
		return l;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void set(Module m, String name, Object value) {
		for (Setting s : m.getSettings()) {
			if (!s.getName().equals(name)) continue;
			if (s instanceof EnumSetting e) {
				for (Object v : e.getValues()) if (((Enum<?>) v).name().equals(value)) e.set((Enum) v);
			} else s.set(value);
		}
	}

	@Override
	protected void onEnable() {
		previous.clear();
		if (tune.get()) {
			// DonutSMP has no 2b2t-style highways, and its bases are buried
			set(ModuleManager.susChunk, "Mode", "BASES");
			set(ModuleManager.susChunk, "Underground Only", true);
			set(ModuleManager.susChunk, "Ignore Highways", false);
			set(ModuleManager.baseRadar, "Quiet Mode", true);
		}
		for (Module m : helpers()) {
			previous.put(m, m.isEnabled());
			if (!m.isEnabled()) m.setEnabledSilently(true);
		}
		area = null;
	}

	@Override
	protected void onDisable() {
		// put the helpers back the way they were
		for (var e : previous.entrySet()) if (!e.getValue()) e.getKey().setEnabledSilently(false);
		previous.clear();
		area = null;
	}

	// ---------------------------------------------------------------- areas

	private static final class Area {
		final BlockPos center;
		final String dim;
		final long start = System.currentTimeMillis();
		int lastLoaded = -1, stableTicks;
		boolean settled, announced;
		int leads;
		Area repeatOf;

		Area(BlockPos center, String dim) { this.center = center; this.dim = dim; }
	}

	private record Lead(BlockPos pos, float score, String reason) {}

	private Area area;
	private final List<Area> checked = new ArrayList<>();
	private List<Lead> leads = List.of();
	private Vec3 lastPos;
	private int areasChecked, emptyAreas, leadAreas;
	private long totalAreaMs;
	private int tick;

	@Override
	public void onWorldLeave() {
		area = null;
		lastPos = null;
	}

	@Override
	public void onTick() {
		tick++;
		Vec3 pos = mc.player.position();
		String dim = WorldUtil.dimensionName(mc.level);
		boolean jumped = lastPos != null && pos.distanceTo(lastPos) > 48; // /rtp, /home, /warp, /tpa...
		lastPos = pos;
		if (area == null || !area.dim.equals(dim) || jumped
			|| Math.sqrt(mc.player.blockPosition().distSqr(area.center)) > newArea.get()) startArea(dim);

		// chunk loading settles when the loaded count stops changing and the scanners are done
		int r = mc.options.getEffectiveRenderDistance();
		int loaded = 0;
		int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				if (mc.level.getChunkSource().hasChunk(pcx + dx, pcz + dz)) loaded++;
		if (loaded != area.lastLoaded || ChunkScanner.pending() > 0) {
			area.lastLoaded = loaded;
			area.stableTicks = 0;
		} else area.stableTicks++;
		if (tick % 10 == 0) leads = collectLeads((r + 1) * 16);
		if (!area.settled && loaded > 0 && area.stableTicks >= settle.getInt() * 20) {
			area.settled = true;
			leads = collectLeads((r + 1) * 16);
			area.leads = leads.size();
			verdict();
		}
		// a strong lead showing up later (you walked closer) still gets reported
		if (area.settled && leads.size() > area.leads) {
			area.leads = leads.size();
			if (alerts.get()) {
				Notifications.push("DonutHunter", "New lead: " + leads.get(0).reason, Notifications.Type.FOUND);
				Sounds.ping();
			}
		}
	}

	private void startArea(String dim) {
		finishArea();
		area = new Area(mc.player.blockPosition(), dim);
		leads = List.of();
		double rr = rememberRadius.get();
		for (Area a : checked)
			if (a.dim.equals(dim) && Math.sqrt(a.center.distSqr(area.center)) < rr) { area.repeatOf = a; break; }
		if (area.repeatOf != null && alerts.get())
			Notifications.push("DonutHunter", "Already checked here (" + (area.repeatOf.leads == 0 ? "empty" : area.repeatOf.leads + " leads") + ")", Notifications.Type.WARNING);
	}

	private void finishArea() {
		if (area == null || !area.settled) return;
		checked.add(area);
		if (checked.size() > 500) checked.remove(0);
		totalAreaMs += System.currentTimeMillis() - area.start;
	}

	private void verdict() {
		areasChecked++;
		if (leads.isEmpty()) emptyAreas++;
		else leadAreas++;
		if (!alerts.get() || area.announced) return;
		area.announced = true;
		if (leads.isEmpty()) {
			Notifications.push("DonutHunter", "Nothing here → /rtp again", Notifications.Type.INFO);
			Sounds.click();
		} else {
			Lead l = leads.get(0);
			Notifications.push("DonutHunter", leads.size() + " lead" + (leads.size() > 1 ? "s" : "") + " • best: " + l.reason, Notifications.Type.FOUND, 6000);
			Sounds.ping();
		}
	}

	/** Evidence from every enabled finder, merged per location (24 m), best first. */
	private List<Lead> collectLeads(int range) {
		List<HuntTarget> all = new ArrayList<>();
		for (Module m : ModuleManager.all())
			if (m != ModuleManager.baseRadar && m.isEnabled() && m instanceof HuntTarget.Provider p) p.collectTargets(all);
		BlockPos me = mc.player.blockPosition();
		List<Lead> merged = new ArrayList<>();
		List<float[]> sums = new ArrayList<>();
		for (HuntTarget t : all) {
			double dx = t.pos().getX() - me.getX(), dz = t.pos().getZ() - me.getZ();
			if (dx * dx + dz * dz > (double) range * range) continue;
			int hit = -1;
			for (int i = 0; i < merged.size(); i++) if (merged.get(i).pos.distSqr(t.pos()) < 24 * 24) { hit = i; break; }
			if (hit < 0) {
				merged.add(new Lead(t.pos(), t.score(), t.reason()));
				sums.add(new float[] {t.score(), t.score()});
			} else {
				float[] s = sums.get(hit);
				s[0] += t.score() * 0.5f; // more evidence at the same place = stronger lead
				if (t.score() > s[1]) {
					s[1] = t.score();
					merged.set(hit, new Lead(t.pos(), s[0], t.reason()));
				} else merged.set(hit, new Lead(merged.get(hit).pos, s[0], merged.get(hit).reason));
			}
		}
		List<Lead> out = new ArrayList<>();
		for (Lead l : merged) if (l.score >= leadScore.get()) out.add(l);
		out.sort((a, b) -> Float.compare(b.score, a.score));
		return out;
	}

	// ---------------------------------------------------------------- render

	@Override
	public void onRender3D(float pt) {
		if (!beam.get() || leads.isEmpty() || ModuleManager.baseRadar.isEnabled()) return;
		Lead l = leads.get(0);
		Render3D.waypoint(Vec3.atCenterOf(l.pos), leadColor.get(), l.reason);
	}

	/** Current verdict line (also used by the in-game test). */
	public String verdictText() {
		if (area == null) return "";
		if (!area.settled) return "§eScanning area… §7" + area.lastLoaded + " chunks";
		if (leads.isEmpty()) return "§7Nothing here → §f/rtp again";
		Lead l = leads.get(0);
		double d = Math.sqrt(l.pos.distToCenterSqr(mc.player.position()));
		return "§a" + leads.size() + " lead" + (leads.size() > 1 ? "s" : "") + " §7• best " + WorldUtil.distance(d) + " "
			+ WorldUtil.direction(mc.player.position(), l.pos.getX() + 0.5, l.pos.getZ() + 0.5);
	}

	public int leadCount() { return leads.size(); }

	public boolean alreadyChecked() { return area != null && area.repeatOf != null; }

	public boolean settled() { return area != null && area.settled; }

	public int areasChecked() { return areasChecked; }

	@Override
	public String getInfo() {
		return area == null ? null : area.settled ? (leads.isEmpty() ? "empty" : leads.size() + "") : "...";
	}

	@Override
	public void onRender2D(GuiGraphics g, float pt) {
		if (!hud.get() || area == null) return;
		List<String> lines = new ArrayList<>();
		lines.add("Donut Hunter");
		lines.add(verdictText());
		if (!leads.isEmpty()) lines.add("§7" + Render2D.trim(leads.get(0).reason, 200));
		if (area.repeatOf != null) lines.add("§e⚠ Already checked here");
		long avg = areasChecked == 0 ? 0 : totalAreaMs / Math.max(1, checked.size()) / 1000;
		lines.add("§8Areas " + areasChecked + " • with leads " + leadAreas + (avg > 0 ? " • ~" + avg + "s/area" : ""));
		int w = 0;
		for (String l : lines) w = Math.max(w, Render2D.width(l));
		w += 14;
		int h = lines.size() * 10 + 6;
		int[] p = Hud.slot("Donut hunter", w, h);
		Render2D.roundedRect(g, p[0], p[1], w, h, 5, 0xC8101016);
		Render2D.rect(g, p[0], p[1] + 4, 2, h - 8, leads.isEmpty() ? 0xFF6B7280 : leadColor.get());
		for (int i = 0; i < lines.size(); i++) Render2D.text(g, lines.get(i), p[0] + 7, p[1] + 4 + i * 10, i == 0 ? 0xFFFFFFFF : 0xFFB4B4C8, false);
	}
}
