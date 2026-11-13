package dev.zero.modules;

import dev.zero.config.FindsStore;
import dev.zero.config.FindsStore.Find;
import dev.zero.config.FindsStore.Kind;
import dev.zero.hud.Notifications;
import dev.zero.module.Category;
import dev.zero.module.HuntTarget;
import dev.zero.module.ScanningModule;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ChatUtil;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2FloatLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.tags.BlockTags;
import java.util.HashSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Flags "suspicious" chunks: signs of hidden player activity that are not visible from the
 * surface - dug tunnels, buried storage, lights and portals underground, rails in the
 * Nether, grown farms, out-of-place blocks - while ignoring natural structures.
 * Optionally overlays chunk generation age (old vs. new chunks) to follow travel trails.
 * <p>
 * 100% passive: analyses chunk data already received.
 */
public class SusChunk extends ScanningModule implements HuntTarget.Provider {
	public enum AgeOverlay { OFF, NEW, OLD, BOTH }

	public enum Mode { BASES, ACTIVITY }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Bases = only chunks with real base evidence (storage, crafting / furnaces, beds, farms...). Activity = also tunnels, torches and portals on their own", Mode.BASES)).onChange(this::rescan);
	private final NumberSetting minScore = add(new NumberSetting("Min Score", "Score needed to highlight a chunk", 20, 5, 150, 1)).onChange(this::rescan);
	private final NumberSetting alertScore = add(new NumberSetting("Alert Score", "Score that triggers an alert", 45, 10, 300, 1));
	private final BoolSetting undergroundOnly = add(new BoolSetting("Underground Only", "Only count things buried under natural ground (hidden bases). Off = also surface builds (can flag villages)", true)).onChange(this::rescan);
	private final BoolSetting storage = add(new BoolSetting("Storage", "Several chests / barrels together, shulkers, ender chests", true)).onChange(this::rescan);
	private final BoolSetting workshop = add(new BoolSetting("Base Blocks", "Crafting tables, furnaces, anvils, enchanting, beds, redstone...", true)).onChange(this::rescan);
	private final BoolSetting farms = add(new BoolSetting("Farms", "Fully grown crops underground (lit farms)", true)).onChange(this::rescan);
	private final BoolSetting tunnels = add(new BoolSetting("Tunnels", "Straight 1x2 dug tunnels underground", true)).onChange(this::rescan);
	private final NumberSetting tunnelLength = add(new NumberSetting("Tunnel Length", "Min straight length to count as dug", 12, 8, 16, 1)).visibleWhen(tunnels::get).onChange(this::rescan);
	private final BoolSetting lights = add(new BoolSetting("Lights", "Torches and lanterns underground", true)).onChange(this::rescan);
	private final BoolSetting portals = add(new BoolSetting("Portals", "Lit nether portals", true)).onChange(this::rescan);
	private final BoolSetting outOfPlace = add(new BoolSetting("Out Of Place", "Rails in the Nether, overworld blocks in the Nether...", true)).onChange(this::rescan);
	private final BoolSetting ignoreHighways = add(new BoolSetting("Ignore Highways", "Ignore chunks on the main axis / diagonal highways", true)).onChange(this::rescan);
	private final NumberSetting highwayWidth = add(new NumberSetting("Highway Width", "Half-width of the ignored highway band", 24, 4, 128, 4, "m")).visibleWhen(ignoreHighways::get).onChange(this::rescan);
	private final EnumSetting<AgeOverlay> ageOverlay = add(new EnumSetting<>("Chunk Age", "Overlay old / new generated chunks (trail following)", AgeOverlay.OFF));
	private final NumberSetting overlayY = add(new NumberSetting("Overlay Height", "Y offset of the age overlay below the camera", 8, -64, 64, 1)).visibleWhen(() -> ageOverlay.get() != AgeOverlay.OFF);
	private final ColorSetting lowColor = add(new ColorSetting("Low Color", "Color of weakly suspicious chunks", 0xFFFFE066, false));
	private final ColorSetting highColor = add(new ColorSetting("High Color", "Color of very suspicious chunks", 0xFFFF3B6B, false));
	private final ColorSetting newChunkColor = add(new ColorSetting("New Chunk Color", "Chunk age overlay: new chunks", 0xFFFF4D6D, false));
	private final ColorSetting oldChunkColor = add(new ColorSetting("Old Chunk Color", "Chunk age overlay: old chunks", 0xFF4ADE80, false));
	private final BoolSetting labels = add(new BoolSetting("Labels", "Show score and reasons", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Toast + chat when a chunk passes the alert score", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Local sound on alerts", true));
	private final BoolSetting save = add(new BoolSetting("Save Finds", "Save alerts to config/zero/finds", true));

	private enum Age { UNKNOWN, OLD, NEW }

	private static final class Result {
		float score, base, activity;
		final Object2FloatLinkedOpenHashMap<String> reasons = new Object2FloatLinkedOpenHashMap<>();
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		BlockPos focus;
		boolean alerted;

		void base(String reason, float points, BlockPos at) { add(reason, points, at); base += points; }

		void activity(String reason, float points, BlockPos at) { add(reason, points, at); activity += points; }

		private void add(String reason, float points, BlockPos at) {
			if (points <= 0) return;
			reasons.addTo(reason, points);
			if (at != null) {
				minY = Math.min(minY, at.getY());
				maxY = Math.max(maxY, at.getY());
				if (focus == null) focus = at;
			}
		}
	}

	private static final Set<Block> LIGHTS = Set.of(Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
		Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.JACK_O_LANTERN,
		Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.END_ROD, Blocks.COPPER_TORCH, Blocks.COPPER_WALL_TORCH);
	private static final Set<Block> RAILS = Set.of(Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL);
	private static final Set<Block> OVERWORLD_ONLY = Set.of(Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.OAK_PLANKS,
		Blocks.SPRUCE_PLANKS, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.GLASS, Blocks.SMOOTH_STONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
	private static final Set<Block> NEW_NETHER = Set.of(Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SOUL_SOIL, Blocks.CRIMSON_NYLIUM,
		Blocks.WARPED_NYLIUM, Blocks.NETHER_GOLD_ORE, Blocks.ANCIENT_DEBRIS, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM);
	/** Blocks that only generate inside natural structures: everything around them is ignored (this chunk and its neighbours). */
	private static final Set<Block> STRUCTURE = Set.of(Blocks.BELL, Blocks.END_PORTAL_FRAME, Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK_SHRIEKER,
		Blocks.GILDED_BLACKSTONE, Blocks.NETHER_BRICKS, Blocks.COBWEB, Blocks.VAULT, Blocks.TRIAL_SPAWNER, Blocks.PRISMARINE_BRICKS,
		Blocks.DARK_PRISMARINE, Blocks.CHISELED_SANDSTONE, Blocks.MOSSY_COBBLESTONE, Blocks.CHISELED_STONE_BRICKS, Blocks.PURPUR_BLOCK,
		Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS, Blocks.CHISELED_TUFF,
		Blocks.CHISELED_TUFF_BRICKS, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.CRYING_OBSIDIAN, Blocks.DARK_OAK_FENCE,
		Blocks.TRIPWIRE_HOOK, Blocks.SCULK_CATALYST, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_DEEPSLATE_TILES,
		Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS);
	/** Blocks a player places in a base (never in caves, rarely in structures, and structures are excluded anyway). */
	private static final Set<Block> WORKSHOP = Set.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.ANVIL,
		Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.ENCHANTING_TABLE, Blocks.BREWING_STAND, Blocks.BEACON, Blocks.RESPAWN_ANCHOR,
		Blocks.LODESTONE, Blocks.SMITHING_TABLE, Blocks.GRINDSTONE, Blocks.STONECUTTER, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE,
		Blocks.FLETCHING_TABLE, Blocks.HOPPER, Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.REPEATER, Blocks.COMPARATOR,
		Blocks.REDSTONE_WIRE, Blocks.NOTE_BLOCK, Blocks.JUKEBOX, Blocks.CRAFTER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.DAYLIGHT_DETECTOR,
		Blocks.TARGET, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.COMPOSTER, Blocks.SCAFFOLDING);

	private final Long2ObjectOpenHashMap<Result> results = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<Age> ages = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet structures = new LongOpenHashSet();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private int tickCounter;

	public SusChunk() {
		super("SusChunk", "Flags chunks with hidden player bases", Category.HUNT);
	}

	@Override
	public String getInfo() {
		return results.isEmpty() ? null : String.valueOf(results.size());
	}

	private boolean onHighway(ChunkPos pos) {
		if (!ignoreHighways.get()) return false;
		int w = highwayWidth.getInt() + 8;
		int x = Math.abs(pos.getMiddleBlockX()), z = Math.abs(pos.getMiddleBlockZ());
		return x < w || z < w || Math.abs(x - z) < w;
	}

	/** Blocks the analysis reads: anything else changing cannot change a chunk's verdict. */
	private static boolean interesting(BlockState st) {
		if (st.isAir()) return false;
		Block b = st.getBlock();
		return st.hasBlockEntity() || WORKSHOP.contains(b) || LIGHTS.contains(b) || STRUCTURE.contains(b) || RAILS.contains(b) || st.is(BlockTags.BEDS)
			|| b == Blocks.NETHER_PORTAL || b instanceof CropBlock || b instanceof NetherWartBlock;
	}

	private boolean nearStructure(ChunkPos p) {
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				if (structures.contains(ChunkPos.asLong(p.x + dx, p.z + dz))) return true;
		return false;
	}

	/** Natural terrain: what "buried" is measured against (houses, trees and water don't count). */
	private static boolean terrain(BlockState st) {
		return st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.TERRACOTTA)
			|| st.is(Blocks.GRAVEL) || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE) || st.is(Blocks.CLAY) || st.is(Blocks.SNOW_BLOCK)
			|| st.is(BlockTags.BASE_STONE_NETHER) || st.is(BlockTags.NYLIUM) || st.is(Blocks.SOUL_SOIL) || st.is(Blocks.SOUL_SAND)
			|| st.is(BlockTags.COAL_ORES) || st.is(BlockTags.IRON_ORES) || st.is(BlockTags.COPPER_ORES) || st.is(Blocks.CALCITE)
			|| st.is(Blocks.PACKED_ICE) || st.is(Blocks.MUD) || st.is(Blocks.END_STONE);
	}

	@Override
	public void scanChunk(LevelChunk chunk) {
		ChunkPos cp = chunk.getPos();
		long key = cp.toLong();
		String dim = WorldUtil.dimensionName(mc.level);
		boolean nether = dim.equals("the_nether"), overworld = dim.equals("overworld"), end = dim.equals("the_end");

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY();
		int height = sections.length * 16;
		int bx = cp.getMinBlockX(), bz = cp.getMinBlockZ();

		// 0 = open (air / no collision), 1 = solid, 2 = fluid
		// the tunnel pass needs every block; it only counts in Activity mode, so Bases mode skips it
		byte[] cells = tunnels.get() && mode.get() == Mode.ACTIVITY ? new byte[height * 256] : null;
		// natural ground height per column, worked out only for the columns that need it
		int[] ground = new int[256];
		java.util.Arrays.fill(ground, Integer.MAX_VALUE);
		final boolean netherF = nether;
		java.util.function.Predicate<BlockState> wanted = st -> interesting(st) || netherF && OVERWORLD_ONLY.contains(st.getBlock());
		Reference2IntOpenHashMap<Block> counts = new Reference2IntOpenHashMap<>();
		List<BlockPos> lightPositions = new ArrayList<>();
		List<BlockPos> workshopPositions = new ArrayList<>();
		List<BlockPos> crops = new ArrayList<>();
		boolean deepslateNearZero = false, copper = false, newNether = false, structureHere = false;

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			int sy = chunk.getSectionYFromSectionIndex(i) << 4;
			if (s == null || s.hasOnlyAir()) continue; // cells default to 0 (open)
			// chunk age from the palettes alone (no need to read every block)
			if (overworld && sy == 0 && s.maybeHas(st -> st.is(Blocks.DEEPSLATE) || st.is(Blocks.TUFF))) deepslateNearZero = true;
			if (overworld && sy >= 0 && !copper && s.maybeHas(st -> st.is(Blocks.COPPER_ORE))) copper = true;
			if (nether && !newNether && s.maybeHas(st -> NEW_NETHER.contains(st.getBlock()))) newNether = true;
			// nothing the analysis reads in this 16x16x16 section: skip all 4096 blocks
			if (cells == null && !s.maybeHas(wanted)) continue;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						BlockState st = s.getBlockState(x, y, z);
						int wy = sy + y;
						if (cells != null) {
							byte c;
							if (!st.getFluidState().isEmpty()) c = 2;
							else if (st.isAir() || !st.blocksMotion()) c = 0;
							else c = 1;
							cells[((wy - minY) << 8) | (z << 4) | x] = c;
						}
						if (st.isAir()) continue;
						Block b = st.getBlock();
						if (STRUCTURE.contains(b)) {
							structureHere = true;
							counts.addTo(b, 1);
						}
						if (LIGHTS.contains(b)) {
							boolean natural = nether && (b == Blocks.GLOWSTONE || b == Blocks.SHROOMLIGHT) || end && b == Blocks.END_ROD;
							if (!natural && lightPositions.size() < 64) lightPositions.add(new BlockPos(bx + x, wy, bz + z));
						} else if (RAILS.contains(b)) counts.addTo(Blocks.RAIL, 1);
						else if (b == Blocks.NETHER_PORTAL) counts.addTo(b, 1);
						else if (b == Blocks.OAK_FENCE) counts.addTo(b, 1);
						else if (WORKSHOP.contains(b) || st.is(BlockTags.BEDS)) {
							if (workshopPositions.size() < 256) workshopPositions.add(new BlockPos(bx + x, wy, bz + z));
							counts.addTo(st.is(BlockTags.BEDS) ? Blocks.RED_BED : b, 0);
						} else if (b instanceof CropBlock crop && crop.isMaxAge(st) || b instanceof NetherWartBlock && st.getValue(NetherWartBlock.AGE) == 3 && overworld) {
							if (crops.size() < 128) crops.add(new BlockPos(bx + x, wy, bz + z));
						}
						if (nether) {
							if (OVERWORLD_ONLY.contains(b)) counts.addTo(Blocks.COBBLESTONE, 1);
							if (NEW_NETHER.contains(b)) newNether = true;
						} else if (overworld) {
							if (wy >= 0 && wy < 8 && (b == Blocks.DEEPSLATE || b == Blocks.TUFF)) deepslateNearZero = true;
							if (wy >= 0 && (b == Blocks.COPPER_ORE)) copper = true;
						}
					}
		}

		// ---- Chunk age (generation version) ----
		Age age = Age.UNKNOWN;
		if (overworld) age = deepslateNearZero || copper ? Age.NEW : (chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) > 0 ? Age.OLD : Age.UNKNOWN);
		else if (nether) age = newNether ? Age.NEW : Age.OLD;
		ages.put(key, age);

		// a structure here also clears the neighbours (structures cross chunk borders)
		boolean wasStructure = structures.contains(key);
		if (structureHere) structures.add(key); else structures.remove(key);
		if (structureHere != wasStructure)
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) dev.zero.scan.ChunkScanner.markDirty(this, ChunkPos.asLong(cp.x + dx, cp.z + dz));

		if (onHighway(cp)) {
			results.remove(key);
			return;
		}
		boolean structure = nearStructure(cp);
		boolean mineshaft = counts.containsKey(Blocks.COBWEB) || (counts.containsKey(Blocks.RAIL) && counts.containsKey(Blocks.OAK_FENCE) && overworld);
		boolean under = undergroundOnly.get();
		Result r = new Result();

		// ---- Storage ----
		if (storage.get()) {
			int containers = 0, shulkers = 0, enders = 0;
			BlockPos at = null;
			for (BlockEntity be : chunk.getBlockEntities().values()) {
				BlockPos p = be.getBlockPos();
				if (be instanceof ShulkerBoxBlockEntity) { shulkers++; at = p; continue; } // never natural
				if (be instanceof EnderChestBlockEntity && !end) { enders++; at = p; continue; }
				if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue;
				if (structure || under && !buried(chunk, p, ground, nether, end)) continue;
				containers++;
				if (at == null) at = p;
			}
			if (shulkers > 0) r.base(shulkers + " shulker" + (shulkers > 1 ? "s" : ""), Math.min(shulkers, 4) * 25f, at);
			if (enders > 0) r.base("Ender chest", Math.min(enders, 3) * 15f, at);
			// a single chest is natural all the time (dungeons, shipwrecks, buried treasure, ruined portals...)
			if (containers >= 2) r.base(containers + " storage", Math.min(containers, 16) * 5f, at);
			else if (containers == 1) r.activity("1 chest", 3, at);
		}

		// ---- Base blocks (crafting, furnaces, beds, redstone...) ----
		if (workshop.get() && !structure) {
			Set<Block> kinds = new HashSet<>();
			int n = 0;
			BlockPos at = null;
			for (BlockPos p : workshopPositions) {
				if (under && !buried(chunk, p, ground, nether, end)) continue;
				BlockState st = chunk.getBlockState(p);
				kinds.add(st.is(BlockTags.BEDS) ? Blocks.RED_BED : st.getBlock());
				n++;
				if (at == null) at = p;
			}
			if (n > 0) r.base(kinds.size() > 1 ? kinds.size() + " kinds of base blocks" : kinds.iterator().next().getName().getString(), Math.min(kinds.size(), 6) * 6f + Math.min(n, 20) * 0.5f, at);
		}

		// ---- Farms (grown crops; underground = lit farm) ----
		if (farms.get() && !structure && !crops.isEmpty()) {
			int buriedCrops = 0;
			for (BlockPos p : crops) if (buried(chunk, p, ground, nether, end)) buriedCrops++;
			if (buriedCrops > 0) r.base("Underground farm", Math.min(buriedCrops, 25) * 0.8f, crops.get(0));
			else if (!under) r.activity("Grown crops", Math.min(crops.size(), 30) * 0.3f, crops.get(0));
		}

		// ---- Tunnels ----
		if (cells != null && !structure && !mineshaft) {
			if (!nether) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) groundAt(chunk, ground, lx, lz);
			int rows = countTunnelRows(cells, height, nether, minY, bx, bz, ground, r);
			if (rows > 0) r.activity("Dug tunnel", Math.min(rows, 6) * 5f, null);
		}

		// ---- Lights underground ----
		if (lights.get() && !structure && !mineshaft) {
			int n = 0;
			BlockPos at = null;
			for (BlockPos p : lightPositions) if (buried(chunk, p, ground, nether, end)) { n++; if (at == null) at = p; }
			if (n > 0) r.activity(n + " lights", Math.min(n, 10) * 1.5f, at);
		}

		// ---- Portals ----
		if (portals.get() && counts.containsKey(Blocks.NETHER_PORTAL)) r.activity("Lit portal", 10, firstOf(chunk, Blocks.NETHER_PORTAL));

		// ---- Rails / out of place blocks ----
		if (outOfPlace.get() && nether && !structure) {
			if (counts.containsKey(Blocks.RAIL)) r.activity("Rails", Math.min(counts.getInt(Blocks.RAIL), 12), null);
			if (counts.containsKey(Blocks.COBBLESTONE)) r.activity("Overworld blocks", Math.min(counts.getInt(Blocks.COBBLESTONE), 40) * 0.25f, null);
		}

		// Bases mode: activity alone (tunnels, torches...) is everywhere on old servers; only real base evidence flags a chunk.
		if (mode.get() == Mode.BASES) r.score = r.base >= 12 ? r.base + r.activity * 0.5f : 0;
		else r.score = r.base + r.activity;

		if (r.score < minScore.get()) {
			results.remove(key);
			return;
		}
		if (r.focus == null) r.focus = new BlockPos(cp.getMiddleBlockX(), r.minY == Integer.MAX_VALUE ? 64 : r.minY, cp.getMiddleBlockZ());
		Result old = results.get(key);
		if (old != null) r.alerted = old.alerted;
		results.put(key, r);
		maybeAlert(cp, r);
	}

	/** Under at least 4 blocks of natural ground (the whole Nether below the roof counts). */
	private static boolean buried(LevelChunk chunk, BlockPos p, int[] ground, boolean nether, boolean end) {
		if (nether) return p.getY() < 123;
		if (end) return false;
		int g = groundAt(chunk, ground, p.getX() & 15, p.getZ() & 15);
		return g != Integer.MIN_VALUE && p.getY() < g - 3;
	}

	/** Highest natural-terrain block of a column (cached per scan; Integer.MIN_VALUE = none). */
	private static int groundAt(LevelChunk chunk, int[] ground, int lx, int lz) {
		int i = (lz << 4) | lx;
		if (ground[i] != Integer.MAX_VALUE) return ground[i];
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int bx = chunk.getPos().getMinBlockX() + lx, bz = chunk.getPos().getMinBlockZ() + lz;
		for (int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz); y >= chunk.getMinY(); y--) {
			if (terrain(chunk.getBlockState(m.set(bx, y, bz)))) return ground[i] = y;
		}
		return ground[i] = Integer.MIN_VALUE;
	}

	/** Rows (along X and Z) containing a straight 1-wide, 2-high corridor with walls, floor and ceiling, under natural ground. */
	private int countTunnelRows(byte[] cells, int height, boolean nether, int minY, int bx, int bz, int[] ground, Result r) {
		int need = tunnelLength.getInt();
		int rows = 0;
		int maxLocalY = height - 4;
		if (nether) maxLocalY = Math.min(maxLocalY, 122 - minY); // nether roof travel is not a tunnel
		for (int ly = 1; ly < maxLocalY; ly++) {
			for (int axis = 0; axis < 2; axis++) {
				for (int a = 0; a < 16; a++) {
					int run = 0, best = 0;
					for (int b = 0; b < 16; b++) {
						int x = axis == 0 ? b : a, z = axis == 0 ? a : b;
						int col = (z << 4) | x;
						boolean ok = cells[(ly << 8) | col] == 0 && cells[((ly + 1) << 8) | col] == 0 && cells[((ly - 1) << 8) | col] == 1
							&& cells[((ly + 2) << 8) | col] == 1
							&& wall(cells, ly, axis == 0 ? x : x - 1, axis == 0 ? z - 1 : z) && wall(cells, ly, axis == 0 ? x : x + 1, axis == 0 ? z + 1 : z)
							&& (nether || ground[col] != Integer.MIN_VALUE && ly + minY < ground[col] - 3);
						run = ok ? run + 1 : 0;
						best = Math.max(best, run);
					}
					if (best >= need) {
						rows++;
						int wy = ly + minY;
						r.minY = Math.min(r.minY, wy);
						r.maxY = Math.max(r.maxY, wy + 2);
						if (r.focus == null) r.focus = new BlockPos(bx + (axis == 0 ? 8 : a), wy, bz + (axis == 0 ? a : 8));
					}
				}
			}
		}
		return rows;
	}

	/** Solid at feet and head height (outside the chunk counts as a wall). */
	private static boolean wall(byte[] cells, int ly, int x, int z) {
		if (x < 0 || x > 15 || z < 0 || z > 15) return true;
		int col = (z << 4) | x;
		return cells[(ly << 8) | col] == 1 && cells[((ly + 1) << 8) | col] == 1;
	}

	private BlockPos firstOf(LevelChunk chunk, Block block) {
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s == null || s.hasOnlyAir() || !s.getStates().maybeHas(st -> st.is(block))) continue;
			int sy = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++)
						if (s.getBlockState(x, y, z).is(block))
							return new BlockPos(chunk.getPos().getMinBlockX() + x, sy + y, chunk.getPos().getMinBlockZ() + z);
		}
		return null;
	}

	private void maybeAlert(ChunkPos pos, Result r) {
		if (r.alerted || r.score < alertScore.get()) return;
		r.alerted = true;
		String dim = WorldUtil.dimensionName(mc.level);
		BlockPos f = r.focus;
		if (FindsStore.near(dim, f.getX(), f.getZ(), 48, Kind.SUS) != null) return;
		String reasons = reasons(r);
		if (save.get()) FindsStore.add(new Find(Kind.SUS, f.getX(), f.getY(), f.getZ(), dim, reasons, System.currentTimeMillis()), true);
		if (BaseRadar.quietMode()) return;
		if (notify.get()) {
			if (r.base >= 12) Notifications.push("Hidden base? " + Math.round(r.score) + " pts", WorldUtil.fmt(f) + "  •  " + reasons, Notifications.Type.ALERT, 6000);
			else Notifications.sign("Activity " + Math.round(r.score) + " pts", WorldUtil.fmt(f) + "  •  " + reasons, Notifications.Type.ALERT, 6000);
			ChatUtil.alert(Component.literal("Sus chunk ").withStyle(s -> s.withColor(Kind.SUS.color & 0xFFFFFF).withBold(true))
				.append(Component.literal("at " + WorldUtil.fmt(f) + " ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("(" + reasons + ")").withStyle(ChatFormatting.GRAY)));
		}
		if (sound.get() && (r.base >= 12 || Notifications.signSound())) Sounds.alert();
	}

	private static String reasons(Result r) {
		List<Map.Entry<String, Float>> list = new ArrayList<>(r.reasons.entrySet());
		list.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(3, list.size()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(list.get(i).getKey());
		}
		return sb.toString();
	}

	@Override
	public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState) {
		// only changes that can matter: base blocks, containers, lights, crops... (not grass, water, leaves)
		if (interesting(oldState) || interesting(newState) || mode.get() == Mode.ACTIVITY && tunnels.get() && oldState.blocksMotion() != newState.blocksMotion())
			dev.zero.scan.ChunkScanner.markDirty(this, chunk.getPos().toLong());
	}

	@Override
	public void onTick() {
	}

	@Override
	public void onChunkUnload(ChunkPos pos) {
		results.remove(pos.toLong());
		ages.remove(pos.toLong());
		structures.remove(pos.toLong());
	}

	@Override
	public void clearAll() {
		results.clear();
		ages.clear();
		structures.clear();
		dirty.clear();
	}

	@Override
	public void onRender3D(float partialTick) {
		Vec3 cam = WorldUtil.cameraPos();
		float alert = alertScore.getFloat();
		for (var e : results.long2ObjectEntrySet()) {
			Result r = e.getValue();
			ChunkPos cp = new ChunkPos(e.getLongKey());
			float heat = Math.min(1f, r.score / alert);
			int color = ColorUtil.lerp(lowColor.get(), highColor.get(), heat);
			int y1 = r.minY == Integer.MAX_VALUE ? (int) cam.y - 2 : r.minY - 1;
			int y2 = r.maxY == Integer.MIN_VALUE ? y1 + 4 : r.maxY + 2;
			AABB box = new AABB(cp.getMinBlockX(), y1, cp.getMinBlockZ(), cp.getMaxBlockX() + 1, y2, cp.getMaxBlockZ() + 1);
			Render3D.box(box, ColorUtil.withAlpha(color, (int) (10 + 22 * heat)), ColorUtil.withAlpha(color, (int) (110 + 100 * heat)), 1.4f, true);
			if (labels.get()) {
				Vec3 lp = new Vec3(cp.getMiddleBlockX() + 0.5, y2 + 0.5, cp.getMiddleBlockZ() + 0.5);
				if (lp.distanceTo(cam) < 160)
					Render3D.label(lp, (mode.get() == Mode.BASES ? "BASE? " : "SUS ") + Math.round(r.score) + " §7" + reasons(r), color, 1f);
			}
		}

		AgeOverlay mode = ageOverlay.get();
		if (mode != AgeOverlay.OFF) {
			double y = Math.floor(cam.y) - overlayY.get();
			for (var e : ages.long2ObjectEntrySet()) {
				Age age = e.getValue();
				boolean show = age == Age.NEW ? (mode == AgeOverlay.NEW || mode == AgeOverlay.BOTH) : age == Age.OLD && (mode == AgeOverlay.OLD || mode == AgeOverlay.BOTH);
				if (!show) continue;
				int color = age == Age.NEW ? newChunkColor.get() : oldChunkColor.get();
				ChunkPos cp = new ChunkPos(e.getLongKey());
				Render3D.chunkSquare(cp.x, cp.z, y, ColorUtil.withAlpha(color, 45), ColorUtil.withAlpha(color, 110), 1f);
			}
		}
	}

	/** Score of a highlighted chunk, or -1 (in-game test). */
	public float scoreAt(ChunkPos pos) {
		Result r = results.get(pos.toLong());
		return r == null ? -1 : r.score;
	}

	public String reasonsAt(ChunkPos pos) {
		Result r = results.get(pos.toLong());
		return r == null ? "" : reasons(r);
	}

	public void setModeForTest(boolean bases) { mode.set(bases ? Mode.BASES : Mode.ACTIVITY); }

	public String ageAt(ChunkPos pos) {
		Age a = ages.get(pos.toLong());
		return a == null ? "?" : a.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public void collectTargets(List<HuntTarget> out) {
		for (Result r : results.values())
			if (r.focus != null) out.add(new HuntTarget(r.focus, Math.min(70, r.score), "Buried: " + reasons(r), 0xFFFF7AF5));
	}

}
