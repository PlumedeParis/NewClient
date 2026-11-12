package dev.zero.modules;

import dev.zero.config.ClientSettings;
import dev.zero.hud.Hud;
import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.render.Render2D;
import dev.zero.render.Render3D;
import dev.zero.seed.OrePredictor;
import dev.zero.seed.SeedWorld;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.setting.TextSetting;
import dev.zero.util.BlockColors;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Predicts ore positions from the world seed by running Minecraft 1.21.11's own ore
 * generation locally against a private copy of the terrain you have loaded.
 * 100% client-side: the seed is typed in by you, nothing is asked from the server.
 * <p>
 * Works only where the server uses vanilla ore generation with that seed (no randomized
 * "feature seeds") and on chunks generated in 1.18+. The live seed check tells you.
 */
public class SeedOreFinder extends Module {
	private final TextSetting seed = add(new TextSetting("Seed", "World seed (number or text). Must be the server's real seed", "", "Type the world seed...", 40));
	public enum OreType { BOTH, NORMAL_ONLY, DEEPSLATE_ONLY }

	public enum Style { HOLOGRAM, BOX, BOTH }

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Hologram = see-through copy of the ore block where it should be", Style.HOLOGRAM));
	private final NumberSetting holoOpacity = add(new NumberSetting("Hologram Opacity", "How solid the ore holograms look", 0.65, 0.2, 1.0, 0.05)).visibleWhen(() -> style.get() != Style.BOX);

	private final java.util.Map<Block, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites = new java.util.IdentityHashMap<>();

	/** The ore's real texture, used for the hologram. */
	private net.minecraft.client.renderer.texture.TextureAtlasSprite sprite(Block b) {
		return sprites.computeIfAbsent(b, k -> mc.getBlockRenderer().getBlockModel(k.defaultBlockState()).particleIcon());
	}

	private final EnumSetting<OreType> oreType = add(new EnumSetting<>("Ore Type", "Normal ores (stone), deepslate ores, or both. Nether ores are not affected", OreType.BOTH));
	private final BoolSetting diamonds = add(new BoolSetting("Diamonds", "Diamond ore", true));
	private final BoolSetting debris = add(new BoolSetting("Ancient Debris", "Netherite (Nether)", true));
	private final BoolSetting emeralds = add(new BoolSetting("Emeralds", "Emerald ore (mountains)", true));
	private final BoolSetting gold = add(new BoolSetting("Gold", "Gold ore and Nether gold", false));
	private final BoolSetting iron = add(new BoolSetting("Iron", "Iron ore", false));
	private final BoolSetting redstone = add(new BoolSetting("Redstone", "Redstone ore", false));
	private final BoolSetting lapis = add(new BoolSetting("Lapis", "Lapis ore", false));
	private final BoolSetting copper = add(new BoolSetting("Copper", "Copper ore", false));
	private final BoolSetting coal = add(new BoolSetting("Coal", "Coal ore", false));
	private final BoolSetting quartz = add(new BoolSetting("Quartz", "Nether quartz ore", false));
	private final NumberSetting radius = add(new NumberSetting("Radius", "Prediction radius in chunks (does not need loaded chunks)", 6, 1, 16, 1, " ch"));
	private final BoolSetting unloaded = add(new BoolSetting("Unloaded Chunks", "Also predict ores in chunks you haven't loaded / can't see", true));
	private final dev.zero.setting.ButtonSetting colors = add(new dev.zero.setting.ButtonSetting("Block Colors", "Choose the color of each ore / block", "\u25C8 Block Colors \u203A", () -> net.minecraft.client.Minecraft.getInstance().setScreen(new dev.zero.gui.BlockColorScreen(net.minecraft.client.Minecraft.getInstance().screen))));
	private final BoolSetting dimUnmatched = add(new BoolSetting("Dim Unmatched", "Draw predictions that don't match what you see dimmer (off = all look the same)", false));
	private final BoolSetting hideMined = add(new BoolSetting("Hide Mined", "Hide predictions that are visibly mined out", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Tracers to the nearest predicted ores", false));
	private final BoolSetting hud = add(new BoolSetting("HUD", "Show prediction status and seed match", true));

	private final OrePredictor predictor = new OrePredictor();
	private final Map<BlockPos, Integer> verified = new HashMap<>(); // 1 confirmed, 2 mismatch, 3 mined
	private int confirmed, mismatched, tick;
	private String lastDim = "";

	public SeedOreFinder() {
		super("SeedOreFinder", "Predicts ores from the world seed (client-side world generation)", Category.WORLD);
	}

	@Override
	public String getInfo() {
		if (!predictor.ready()) return null;
		int total = confirmed + mismatched;
		return total >= 5 ? Math.round(100f * confirmed / total) + "%" : String.valueOf(count);
	}

	private int count;

	public Set<Block> wantedBlocks() { return wanted(); }

	private Set<Block> lastPredict;

	private Set<Block> predictSet() {
		Set<Block> s = wanted();
		if (dev.zero.module.ModuleManager.safeMiner != null) s.addAll(dev.zero.module.ModuleManager.safeMiner.seedRequests());
		return s;
	}

	private Set<Block> wanted() {
		Set<Block> s = new HashSet<>();
		if (diamonds.get()) s.addAll(List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE));
		if (debris.get()) s.add(Blocks.ANCIENT_DEBRIS);
		if (emeralds.get()) s.addAll(List.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE));
		if (gold.get()) s.addAll(List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE));
		if (iron.get()) s.addAll(List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
		if (redstone.get()) s.addAll(List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE));
		if (lapis.get()) s.addAll(List.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE));
		if (copper.get()) s.addAll(List.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
		if (coal.get()) s.addAll(List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
		if (quartz.get()) s.add(Blocks.NETHER_QUARTZ_ORE);
		if (oreType.get() != OreType.BOTH) {
			boolean wantDeep = oreType.get() == OreType.DEEPSLATE_ONLY;
			s.removeIf(b -> {
				String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
				if (id.startsWith("nether_") || b == Blocks.ANCIENT_DEBRIS) return false;
				return id.startsWith("deepslate_") != wantDeep;
			});
		}
		return s;
	}

	public static Long parseSeed(String text) {
		text = text.trim();
		if (text.isEmpty()) return null;
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			return (long) text.hashCode(); // same rule as the vanilla "create world" screen
		}
	}

	@Override
	protected void onDisable() {
		sortedCache = null;
		biomeMatch = biomeTotal = 0;
		biomeChecked.clear();
		predictor.unconfigure();
		verified.clear();
		confirmed = mismatched = 0;
	}

	@Override
	public void onWorldLeave() {
		biomeMatch = biomeTotal = 0;
		biomeChecked.clear();
		predictor.unconfigure();
		verified.clear();
		confirmed = mismatched = 0;
	}

	@Override
	public void onTick() {
		Long s = parseSeed(seed.get());
		String dim = WorldUtil.dimensionName(mc.level);
		if (s == null || dim.equals("the_end")) {
			predictor.unconfigure();
			return;
		}
		if (!dim.equals(lastDim)) {
			lastDim = dim;
			verified.clear();
			confirmed = mismatched = 0;
			biomeMatch = biomeTotal = 0;
			biomeChecked.clear();
		}
		predictor.configure(s, dim.equals("the_nether"), mc.level.getMinY(), mc.level.getHeight());
		if (!predictor.ready()) return;
		// ores to predict = yours + the ones SafeMiner routes to; recompute if that set changes
		Set<Block> predict = predictSet();
		if (!predict.equals(lastPredict)) {
			if (lastPredict != null && !predict.containsAll(lastPredict) || lastPredict != null && !lastPredict.containsAll(predict)) predictor.reset();
			lastPredict = predict;
		}
		tick++;
		if (tick % 5 == 0) refreshCache();

		ChunkPos center = mc.player.chunkPosition();
		int r = radius.getInt();
		if (predictor.pendingCount() < 6) {
			int submitted = 0;
			// Nearest chunks first.
			for (int ring = 0; ring <= r && submitted < 3; ring++) {
				for (int dx = -ring; dx <= ring && submitted < 3; dx++) {
					for (int dz = -ring; dz <= ring && submitted < 3; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
						int cx = center.x + dx, cz = center.z + dz;
						long key = ChunkPos.asLong(cx, cz);
						if (predictor.isPending(key)) continue;
						boolean loaded = mc.level.getChunkSource().hasChunk(cx, cz);
						OrePredictor.Result res = predictor.results().get(key);
						if (res != null && (res.realTerrain() || !loaded)) continue;
						if (!loaded && !unloaded.get()) continue;
						predictor.submit(mc.level, cx, cz, predict);
						submitted++;
					}
				}
			}
		}
		if (tick % 40 == 0) {
			// Forget far chunks.
			predictor.results().keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - center.x), Math.abs(ChunkPos.getZ(k) - center.z)) > r + 3);
			verify();
		}
	}

	private int biomeMatch, biomeTotal;
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet biomeChecked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	/**
	 * Seed check that anti-xray cannot affect: compares the biomes the seed generates with the
	 * real biomes the server sent for each loaded chunk.
	 */
	private void verifyBiomes() {
		SeedWorld w = predictor.world();
		if (w == null) return;
		ChunkPos center = mc.player.chunkPosition();
		int r = Math.min(8, mc.options.getEffectiveRenderDistance());
		for (int cx = center.x - r; cx <= center.x + r; cx++)
			for (int cz = center.z - r; cz <= center.z + r; cz++) {
				long key = ChunkPos.asLong(cx, cz);
				if (biomeChecked.contains(key) || !mc.level.getChunkSource().hasChunk(cx, cz)) continue;
				biomeChecked.add(key);
				int qx0 = cx << 2, qz0 = cz << 2;
				// a few cells per chunk, at surface height
				int qy = Math.max(mc.level.getMinY(), Math.min(mc.level.getMaxY() - 1, mc.level.getSeaLevel())) >> 2;
				for (int i = 0; i < 4; i++) {
					int qx = qx0 + (i & 1) * 2 + 1, qz = qz0 + (i >> 1) * 2 + 1;
					String real = mc.level.getNoiseBiome(qx, qy, qz).unwrapKey().map(k -> k.identifier().toString()).orElse("");
					String predicted = w.biomeIdAtQuart(qx, qy, qz);
					biomeTotal++;
					if (real.equals(predicted)) biomeMatch++;
				}
			}
		if (biomeChecked.size() > 100_000) biomeChecked.clear();
	}

	/** Compares predictions with what the client can actually see (exposed blocks only). */
	private void verify() {
		verifyBiomes();
		for (OrePredictor.Result res : predictor.results().values()) {
			if (!res.realTerrain()) continue;
			for (SeedWorld.Prediction p : res.predictions()) {
				Integer prev = verified.get(p.pos());
				if ((prev != null && prev != 1) || !mc.level.isLoaded(p.pos())) continue;
				BlockState actual = mc.level.getBlockState(p.pos());
				if (actual.is(Blocks.VOID_AIR)) continue; // not really loaded: nothing to judge
				if (actual.isAir() || !actual.getFluidState().isEmpty()) {
					verified.put(p.pos(), 3);
				} else if (prev == null && XRay.isExposed(p.pos(), mc.level)) {
					boolean ok = actual.getBlock() == p.block();
					verified.put(p.pos(), ok ? 1 : 2);
					if (ok) confirmed++;
					else mismatched++;
				}
			}
		}
		if (verified.size() > 200_000) verified.clear();
	}

	private record Visible(SeedWorld.Prediction p, double dist, boolean real) {}

	private List<Visible> sortedCache;

	/** Rebuilt a few times per second instead of every frame. */
	private void refreshCache() {
		Set<Block> wanted = wanted();
		Vec3 cam = WorldUtil.cameraPos();
		double max = (radius.get() + 1) * 16;
		List<Visible> list = new ArrayList<>();
		for (OrePredictor.Result res : predictor.results().values()) {
			for (SeedWorld.Prediction p : res.predictions()) {
				if (!wanted.contains(p.block())) continue;
				Integer v = verified.get(p.pos());
				if (v != null && v == 3 && hideMined.get()) continue; // you can see it was mined
				double d = Math.sqrt(p.pos().distToCenterSqr(cam));
				if (d > max) continue;
				list.add(new Visible(p, d, res.realTerrain()));
			}
		}
		list.sort((a, b) -> Double.compare(a.dist, b.dist));
		if (list.size() > 3000) list = new ArrayList<>(list.subList(0, 3000));
		sortedCache = list;
	}

	@Override
	public void onRender3D(float pt) {
		if (!predictor.ready()) return;
		if (sortedCache == null) refreshCache();
		List<Visible> list = new ArrayList<>(Math.min(sortedCache.size(), 2500));
		boolean hide = hideMined.get();
		for (Visible v : sortedCache) {
			if (list.size() >= 2500) break;
			if (hide && mc.level.isLoaded(v.p.pos())) {
				// Mined just now? Hide it instantly - but only where the chunk is really loaded:
				// unloaded / unseen positions read as air and must keep showing.
				BlockState now = mc.level.getBlockState(v.p.pos());
				if (!now.is(Blocks.VOID_AIR) && (now.isAir() || !now.getFluidState().isEmpty())) {
					verified.put(v.p.pos(), 3);
					continue;
				}
			}
			list.add(v);
		}
		count = list.size();
		int n = list.size();
		int unloadedDrawn = 0;
		for (Visible v : list) if (!mc.level.isLoaded(v.p.pos())) unloadedDrawn++;
		drawnUnloaded = unloadedDrawn;
		// gentle "hologram" shimmer
		float pulse = 0.85f + 0.15f * (float) Math.sin(System.currentTimeMillis() / 400.0);
		for (int i = 0; i < n; i++) {
			Visible v = list.get(i);
			int c = BlockColors.get(v.p.block());
			boolean confirmedHere = Integer.valueOf(1).equals(verified.get(v.p.pos()));
			// Estimated (unloaded terrain) predictions are drawn the same way, only a little dimmer.
			boolean notHere = Integer.valueOf(2).equals(verified.get(v.p.pos()));
			float strength = notHere && dimUnmatched.get() ? 0.45f : v.real || confirmedHere || notHere ? 1f : 0.85f;
			AABB ob = new AABB(v.p.pos()).deflate(0.06);
			if (style.get() != Style.BOX) {
				int a = (int) (255 * holoOpacity.get() * strength * pulse);
				// your Block Colors choice tints the hologram; otherwise the real texture is shown as-is
				int tint = BlockColors.isCustom(v.p.block()) ? ColorUtil.brighten(BlockColors.get(v.p.block()), 0.35f) & 0xFFFFFF : 0xFFFFFF;
				Render3D.hologram(new AABB(v.p.pos()).deflate(0.02), sprite(v.p.block()), (a << 24) | tint);
				Render3D.box(ob, 0, ColorUtil.withAlpha(c, (int) (200 * strength)), 1.2f, true);
			}
			if (style.get() != Style.HOLOGRAM) {
				int fillA = (int) (55 * strength), lineA = (int) (230 * strength);
				if (confirmedHere) fillA = 90;
				Render3D.box(ob, ColorUtil.withAlpha(c, fillA), ColorUtil.withAlpha(c, lineA), 1.8f, true);
			}
			if (Render3D.look(ob))
				Render3D.lookText(v.p.block().getName().getString() + (confirmedHere ? " \u00B7 confirmed" : notHere ? " \u00B7 predicted, not found here"
					: v.real ? " \u00B7 predicted" : " \u00B7 predicted (unloaded chunk)"), c);
		}
		if (tracers.get()) for (int i = 0; i < Math.min(n, 10); i++) Render3D.tracer(Vec3.atCenterOf(list.get(i).p.pos()), ColorUtil.withAlpha(BlockColors.get(list.get(i).p.block()), 200), 1.5f);
	}

	@Override
	public void onRender2D(GuiGraphics g, float pt) {
		if (!hud.get()) return;
		String line1, line2, line3 = null;
		if (parseSeed(seed.get()) == null) {
			line1 = "Seed Ores: no seed";
			line2 = "Open the GUI and type the seed";
		} else if (!predictor.ready()) {
			line1 = "Seed Ores";
			line2 = predictor.status();
		} else {
			int total = confirmed + mismatched;
			line1 = "Seed Ores  \u00A77" + count + " predicted" + (predictor.pendingCount() > 0 ? " \u00A78(working)" : "");
			int bp = biomeTotal == 0 ? 0 : Math.round(100f * biomeMatch / biomeTotal);
			int op = total == 0 ? 0 : Math.round(100f * confirmed / total);
			String bc = bp >= 75 ? "\u00A7a" : bp >= 40 ? "\u00A7e" : "\u00A7c";
			String oc = op >= 75 ? "\u00A7a" : op >= 30 ? "\u00A7e" : "\u00A7c";
			line2 = "\u00A77Biomes " + (biomeTotal < 8 ? "\u00A78..." : bc + bp + "%") + "\u00A77  \u2022  Ores "
				+ (total < 5 ? "\u00A78explore caves" : oc + op + "%\u00A77 (" + confirmed + "/" + total + ")");
			if (biomeTotal >= 8) {
				if (bp >= 75) {
					if (total >= 20 && op < 25) line3 = "\u00A7eSeed is right, but this server scrambles ore placement";
					else if (total < 5) line3 = "\u00A7aSeed is right \u2714";
					else line3 = "\u00A7aSeed works \u2714";
				} else if (bp < 40) line3 = "\u00A7cWrong seed for this world (or very old chunks)";
				else line3 = "\u00A7eMixed: old and new chunks around you";
			}
		}
		int w = Math.max(Render2D.width(line1), Math.max(Render2D.width(line2), line3 == null ? 0 : Render2D.width(line3))) + 14;
		int h = line3 == null ? 26 : 36;
		int[] pos = Hud.slot("Seed ores", w, h);
		Render2D.roundedRect(g, pos[0], pos[1], w, h, 5, 0xC8101016);
		Render2D.rect(g, pos[0], pos[1] + 4, 2, h - 8, BlockColors.get(Blocks.DIAMOND_ORE));
		Render2D.text(g, line1, pos[0] + 7, pos[1] + 4, 0xFFFFFFFF, false);
		Render2D.text(g, line2, pos[0] + 7, pos[1] + 14, 0xFFB4B4C8, false);
		if (line3 != null) Render2D.text(g, line3, pos[0] + 7, pos[1] + 24, 0xFFB4B4C8, false);
	}

	/** Biome agreement in percent (in-game test). */
	public int biomeMatchPercent() { return biomeTotal == 0 ? -1 : Math.round(100f * biomeMatch / biomeTotal); }

	/** Snapshot of current predictions (used by the in-game accuracy test). */
	public List<SeedWorld.Prediction> snapshot(boolean realTerrainOnly) {
		List<SeedWorld.Prediction> out = new ArrayList<>();
		for (OrePredictor.Result r : predictor.results().values()) if (r.realTerrain() || !realTerrainOnly) out.addAll(r.predictions());
		return out;
	}

	public OrePredictor predictor() { return predictor; }

	private int drawnUnloaded;

	/** Predictions drawn last frame in chunks the game has not loaded (in-game test). */
	public int drawnInUnloadedChunks() { return drawnUnloaded; }

	/** Boxes drawn last frame (used by the in-game test). */
	public int visibleCount() { return count; }

	public boolean idle() { return predictor.ready() && predictor.pendingCount() == 0; }

	public void setSeedText(String text) { seed.set(text); }

	public String status() { return predictor.status() + String.format(Locale.ROOT, " %d/%d", confirmed, confirmed + mismatched); }
}
