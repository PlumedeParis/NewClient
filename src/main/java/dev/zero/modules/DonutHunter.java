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

	public enum Mode { UNDERGROUND, RTP }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Underground = you mine around: live list of every lead + where to explore. RTP = verdict for each area you teleport to", Mode.UNDERGROUND));

	private final NumberSetting leadScore = add(new NumberSetting("Lead Score", "Evidence score that counts as a lead worth checking", 40, 15, 120, 5));
	private final NumberSetting visitRadius = add(new NumberSetting("Visit Radius", "Being this close to a lead for 3 s marks it checked", 10, 4, 32, 1, "m")).visibleWhen(() -> mode.get() == Mode.UNDERGROUND);
	private final NumberSetting shown = add(new NumberSetting("Leads Shown", "Leads listed on the HUD", 3, 1, 8, 1)).visibleWhen(() -> mode.get() == Mode.UNDERGROUND);
	private final BoolSetting exploreHint = add(new BoolSetting("Explore Hint", "Points to the direction with the most ground you haven't scanned yet", true)).visibleWhen(() -> mode.get() == Mode.UNDERGROUND);
	private final NumberSetting newArea = add(new NumberSetting("New Area", "A teleport or moving this far starts a new area", 200, 64, 1000, 16, "m")).visibleWhen(() -> mode.get() == Mode.RTP);
	private final NumberSetting settle = add(new NumberSetting("Settle Time", "Seconds with no new chunks before the area verdict", 4, 1, 20, 1, "s")).visibleWhen(() -> mode.get() == Mode.RTP);
	private final NumberSetting rememberRadius = add(new NumberSetting("Checked Radius", "Warn when you land this close to an area you already checked", 160, 32, 1000, 16, "m")).visibleWhen(() -> mode.get() == Mode.RTP);
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

	private record Lead(BlockPos pos, float score, String reason, int conf) {}

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
		if (mode.get() == Mode.UNDERGROUND) {
			undergroundTick();
			return;
		}
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
		List<List<HuntTarget>> groups = new ArrayList<>();
		for (HuntTarget t : all) {
			double dx = t.pos().getX() - me.getX(), dz = t.pos().getZ() - me.getZ();
			if (dx * dx + dz * dz > (double) range * range) continue;
			List<HuntTarget> hit = null;
			for (List<HuntTarget> grp : groups) if (grp.get(0).pos().distSqr(t.pos()) < 24 * 24) { hit = grp; break; }
			if (hit == null) {
				hit = new ArrayList<>();
				groups.add(hit);
			}
			hit.add(t);
		}
		List<Lead> out = new ArrayList<>();
		for (List<HuntTarget> grp : groups) {
			// strongest clue first: it gives the lead its place and name; the others raise the score
			grp.sort((a, b) -> Float.compare(b.score(), a.score()));
			float score = grp.get(0).score();
			for (int i = 1; i < grp.size(); i++) score += grp.get(i).score() * 0.5f;
			if (score < leadScore.get()) continue;
			var conf = dev.zero.module.Confidence.of(grp);
			String reason = conf.reasons().size() > 1 ? conf.reasons().get(0) + " +" + (conf.reasons().size() - 1) : conf.reasons().get(0);
			out.add(new Lead(grp.get(0).pos(), score, reason, conf.percent()));
		}
		out.sort((a, b) -> Float.compare(b.score, a.score));
		return out;
	}

	// ---------------------------------------------------------------- underground mode

	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet explored = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet announced = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet visited = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap nearTicks = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	private String exploredDim = "";
	private int exploreDir = -1;
	private int foundTotal;

	/** Leads are grouped per 16 m cell, so the same base is announced once. */
	private static long cell(BlockPos p) { return BlockPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4); }

	private void undergroundTick() {
		String dim = WorldUtil.dimensionName(mc.level);
		if (!dim.equals(exploredDim)) {
			exploredDim = dim;
			explored.clear();
			announced.clear();
			visited.clear();
		}
		int r = mc.options.getEffectiveRenderDistance();
		int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
		if (tick % 20 == 0)
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++)
					if (mc.level.getChunkSource().hasChunk(pcx + dx, pcz + dz)) explored.add(net.minecraft.world.level.ChunkPos.asLong(pcx + dx, pcz + dz));
		if (tick % 10 != 0) return;
		List<Lead> all = collectLeads(Integer.MAX_VALUE / 4);
		List<Lead> open = new ArrayList<>();
		BlockPos me = mc.player.blockPosition();
		double vr = visitRadius.get();
		for (Lead l : all) {
			long c = cell(l.pos);
			if (visited.contains(c)) continue;
			// standing near a lead for 3 s = you checked it
			if (Math.sqrt(l.pos.distSqr(me)) <= vr) {
				int t = nearTicks.addTo(c, 10) + 10;
				if (t >= 60) {
					visited.add(c);
					Notifications.minor("DonutHunter", "Lead checked \u2714", Notifications.Type.INFO, 2500);
					continue;
				}
			}
			open.add(l);
			if (announced.add(c)) {
				foundTotal++;
				if (alerts.get()) {
					Notifications.push("DonutHunter", "New lead " + l.conf + "%: " + l.reason + " \u2022 " + where(l.pos), Notifications.Type.FOUND, 6000);
					Sounds.ping();
				}
			}
		}
		// closest first, but a much stronger lead a bit further away wins
		open.sort((a, b) -> Double.compare(rank(a, me), rank(b, me)));
		leads = open;
		if (tick % 40 == 0) exploreDir = exploreDirection(pcx, pcz, r);
	}

	private static double rank(Lead l, BlockPos me) {
		return Math.sqrt(l.pos.distSqr(me)) / Math.max(1f, l.score / 40f);
	}

	private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

	/** The direction (of 8) with the most chunks you haven't loaded yet, preferring the way you're facing. */
	private int exploreDirection(int pcx, int pcz, int r) {
		double best = -1;
		int bestDir = -1;
		float yaw = mc.player.getYRot();
		for (int d = 0; d < 8; d++) {
			double ang = Math.toRadians(d * 45);
			double fx = Math.sin(ang), fz = -Math.cos(ang);
			int fresh = 0, total = 0;
			for (int dist = r + 1; dist <= r + 10; dist++)
				for (int side = -3; side <= 3; side++) {
					int cx = pcx + (int) Math.round(fx * dist - fz * side), cz = pcz + (int) Math.round(fz * dist + fx * side);
					total++;
					if (!explored.contains(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) fresh++;
				}
			double facing = Math.cos(Math.toRadians(d * 45) - Math.toRadians(yaw + 180));
			double score = fresh / (double) total + 0.05 * facing;
			if (score > best) { best = score; bestDir = d; }
		}
		return bestDir;
	}

	private String where(BlockPos p) {
		Vec3 me = mc.player.position();
		double d = Math.sqrt(p.distToCenterSqr(me));
		int dy = p.getY() - mc.player.blockPosition().getY();
		String v = Math.abs(dy) < 3 ? "" : dy > 0 ? " \u2191" + dy : " \u2193" + (-dy);
		return WorldUtil.distance(d) + " " + WorldUtil.direction(me, p.getX() + 0.5, p.getZ() + 0.5) + v;
	}

	public int exploredChunks() { return explored.size(); }

	public String exploreHintText() { return exploreDir < 0 ? "" : DIRS[exploreDir]; }

	public void markVisitedForTest(BlockPos p) { visited.add(cell(p)); }

	// ---------------------------------------------------------------- render

	@Override
	public void onRender3D(float pt) {
		if (mode.get() == Mode.UNDERGROUND && beam.get()) {
			for (int i = 0; i < Math.min(leads.size(), shown.getInt()); i++) {
				Lead l = leads.get(i);
				Render3D.waypoint(Vec3.atCenterOf(l.pos), i == 0 ? leadColor.get() : dev.zero.util.ColorUtil.withAlpha(leadColor.get(), 150), "#" + (i + 1) + " " + l.reason);
			}
			return;
		}
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
		if (mode.get() == Mode.UNDERGROUND) return leads.isEmpty() ? null : String.valueOf(leads.size());
		return area == null ? null : area.settled ? (leads.isEmpty() ? "empty" : leads.size() + "") : "...";
	}

	@Override
	public void onRender2D(GuiGraphics g, float pt) {
		if (!hud.get()) return;
		List<String> lines = new ArrayList<>();
		if (mode.get() == Mode.UNDERGROUND) {
			lines.add("Donut Hunter \u00A77\u2022 underground");
			if (leads.isEmpty()) lines.add("\u00A77No leads in range \u2014 keep mining");
			for (int i = 0; i < Math.min(leads.size(), shown.getInt()); i++) {
				Lead l = leads.get(i);
				lines.add((i == 0 ? "\u00A7a#1 " : "\u00A7f#" + (i + 1) + " ") + (l.conf >= 85 ? "\u00A7a" : l.conf >= 60 ? "\u00A7e" : "\u00A76") + l.conf + "% \u00A7f" + Render2D.trim(l.reason, 150) + " \u00A77" + where(l.pos));
			}
			if (leads.size() > shown.getInt()) lines.add("\u00A78+" + (leads.size() - shown.getInt()) + " more");
			if (exploreHint.get() && exploreDir >= 0) lines.add("\u00A77Explore \u00A7b" + DIRS[exploreDir] + "\u00A77 (new ground)");
			lines.add("\u00A78Scanned " + explored.size() + " chunks \u2022 " + foundTotal + " leads found \u2022 " + visited.size() + " checked");
			drawPanel(g, lines, leads.isEmpty() ? 0xFF6B7280 : leadColor.get());
			return;
		}
		if (area == null) return;
		lines.add("Donut Hunter");
		lines.add(verdictText());
		if (!leads.isEmpty()) lines.add("§7" + Render2D.trim(leads.get(0).reason, 200));
		if (area.repeatOf != null) lines.add("§e⚠ Already checked here");
		long avg = areasChecked == 0 ? 0 : totalAreaMs / Math.max(1, checked.size()) / 1000;
		lines.add("§8Areas " + areasChecked + " • with leads " + leadAreas + (avg > 0 ? " • ~" + avg + "s/area" : ""));
		drawPanel(g, lines, leads.isEmpty() ? 0xFF6B7280 : leadColor.get());
	}

	private void drawPanel(GuiGraphics g, List<String> lines, int bar) {
		int w = 0;
		for (String l : lines) w = Math.max(w, Render2D.width(l));
		w += 14;
		int h = lines.size() * 10 + 6;
		int[] p = Hud.slot("Donut hunter", w, h);
		Render2D.roundedRect(g, p[0], p[1], w, h, 5, 0xC8101016);
		Render2D.rect(g, p[0], p[1] + 4, 2, h - 8, bar);
		for (int i = 0; i < lines.size(); i++) Render2D.text(g, lines.get(i), p[0] + 7, p[1] + 4 + i * 10, i == 0 ? 0xFFFFFFFF : 0xFFB4B4C8, false);
	}

	public void setModeForTest(boolean underground) { mode.set(underground ? Mode.UNDERGROUND : Mode.RTP); }
}
