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

	private final NumberSetting minScore = add(new NumberSetting("Min Score", "Score needed to highlight a chunk", 8, 2, 100, 1)).onChange(this::rescan);
	private final NumberSetting alertScore = add(new NumberSetting("Alert Score", "Score that triggers an alert", 30, 5, 200, 1));
	private final BoolSetting tunnels = add(new BoolSetting("Tunnels", "Straight dug corridors with flat floor and ceiling", true)).onChange(this::rescan);
	private final NumberSetting tunnelLength = add(new NumberSetting("Tunnel Length", "Min straight length to count as dug", 13, 8, 16, 1)).visibleWhen(tunnels::get).onChange(this::rescan);
	private final BoolSetting storage = add(new BoolSetting("Buried Storage", "Chests / shulkers hidden underground", true)).onChange(this::rescan);
	private final BoolSetting lights = add(new BoolSetting("Lights", "Torches and lanterns underground", true)).onChange(this::rescan);
	private final BoolSetting portals = add(new BoolSetting("Portals", "Lit nether portals", true)).onChange(this::rescan);
	private final BoolSetting farms = add(new BoolSetting("Farms", "Fully grown crops and nether wart", true)).onChange(this::rescan);
	private final BoolSetting outOfPlace = add(new BoolSetting("Out Of Place", "Rails in the Nether, overworld blocks in the Nether...", true)).onChange(this::rescan);
	private final BoolSetting ignoreHighways = add(new BoolSetting("Ignore Highways", "Ignore chunks on the main axis / diagonal highways", true)).onChange(this::rescan);
	private final NumberSetting highwayWidth = add(new NumberSetting("Highway Width", "Half-width of the ignored highway band", 24, 4, 128, 4, "m")).visibleWhen(ignoreHighways::get).onChange(this::rescan);
	private final EnumSetting<AgeOverlay> ageOverlay = add(new EnumSetting<>("Chunk Age", "Overlay old / new generated chunks (trail following)", AgeOverlay.OFF));
	private final NumberSetting overlayY = add(new NumberSetting("Overlay Height", "Y offset of the age overlay below the camera", 8, -64, 64, 1)).visibleWhen(() -> ageOverlay.get() != AgeOverlay.OFF);
	private final BoolSetting labels = add(new BoolSetting("Labels", "Show score and reasons", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Toast + chat when a chunk passes the alert score", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Local sound on alerts", true));
	private final BoolSetting save = add(new BoolSetting("Save Finds", "Save alerts to config/zero/finds", true));

	private enum Age { UNKNOWN, OLD, NEW }

	private static final class Result {
		float score;
		final Object2FloatLinkedOpenHashMap<String> reasons = new Object2FloatLinkedOpenHashMap<>();
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		BlockPos focus;
		boolean alerted;

		void add(String reason, float points, int y) {
			if (points <= 0) return;
			score += points;
			reasons.addTo(reason, points);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
		}
	}

	private static final Set<Block> LIGHTS = Set.of(Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
		Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.JACK_O_LANTERN,
		Blocks.SEA_LANTERN, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.END_ROD, Blocks.COPPER_TORCH, Blocks.COPPER_WALL_TORCH);
	private static final Set<Block> RAILS = Set.of(Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL);
	private static final Set<Block> OVERWORLD_ONLY = Set.of(Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.OAK_PLANKS,
		Blocks.SPRUCE_PLANKS, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.GLASS, Blocks.SMOOTH_STONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
	private static final Set<Block> NEW_NETHER = Set.of(Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SOUL_SOIL, Blocks.CRIMSON_NYLIUM,
		Blocks.WARPED_NYLIUM, Blocks.NETHER_GOLD_ORE, Blocks.ANCIENT_DEBRIS, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM);
	private static final Set<Block> STRUCTURE = Set.of(Blocks.BELL, Blocks.END_PORTAL_FRAME, Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK_SHRIEKER,
		Blocks.GILDED_BLACKSTONE, Blocks.NETHER_BRICKS, Blocks.COBWEB, Blocks.VAULT, Blocks.TRIAL_SPAWNER, Blocks.PRISMARINE_BRICKS,
		Blocks.DARK_PRISMARINE, Blocks.CHISELED_SANDSTONE, Blocks.MOSSY_COBBLESTONE, Blocks.SPAWNER, Blocks.CHISELED_STONE_BRICKS, Blocks.PURPUR_BLOCK);

	private final Long2ObjectOpenHashMap<Result> results = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<Age> ages = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private int tickCounter;

	public SusChunk() {
		super("SusChunk", "Flags chunks with hidden player activity", Category.HUNT);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (Result r : results.values()) if (r.score >= minScore.get()) n++;
		return n > 0 ? String.valueOf(n) : null;
	}

	private boolean onHighway(ChunkPos pos) {
		if (!ignoreHighways.get()) return false;
		int w = highwayWidth.getInt() + 8;
		int x = Math.abs(pos.getMiddleBlockX()), z = Math.abs(pos.getMiddleBlockZ());
		return x < w || z < w || Math.abs(x - z) < w;
	}

	@Override
	public void scanChunk(LevelChunk chunk) {
		long key = chunk.getPos().toLong();
		String dim = WorldUtil.dimensionName(mc.level);
		boolean nether = dim.equals("the_nether"), overworld = dim.equals("overworld");

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY();
		int height = sections.length * 16;
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();

		// 0 = open (air / no collision), 1 = solid, 2 = fluid
		byte[] cells = tunnels.get() ? new byte[height * 256] : null;
		Reference2IntOpenHashMap<Block> counts = new Reference2IntOpenHashMap<>();
		Result r = new Result();
		List<BlockPos> lightPositions = new ArrayList<>();
		int grownCrops = 0, cropY = 0;
		boolean deepslateNearZero = false, copper = false, newNether = false;

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			int sy = chunk.getSectionYFromSectionIndex(i) << 4;
			if (s == null || s.hasOnlyAir()) continue; // cells default to 0 (open)
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
						if (STRUCTURE.contains(b)) counts.addTo(b, 1);
						if (LIGHTS.contains(b)) {
							counts.addTo(Blocks.TORCH, 1);
							if (lightPositions.size() < 64) lightPositions.add(new BlockPos(bx + x, wy, bz + z));
						} else if (RAILS.contains(b)) counts.addTo(Blocks.RAIL, 1);
						else if (b == Blocks.NETHER_PORTAL) counts.addTo(b, 1);
						else if (b == Blocks.OAK_FENCE) counts.addTo(b, 1);
						else if (b instanceof CropBlock crop && crop.isMaxAge(st)) {
							grownCrops++;
							cropY = wy;
						} else if (b instanceof NetherWartBlock && st.getValue(NetherWartBlock.AGE) == 3 && overworld) {
							grownCrops++;
							cropY = wy;
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

		if (onHighway(chunk.getPos())) {
			results.remove(key);
			return;
		}

		boolean village = counts.containsKey(Blocks.BELL);
		boolean stronghold = counts.containsKey(Blocks.END_PORTAL_FRAME) || counts.containsKey(Blocks.CHISELED_STONE_BRICKS);
		boolean ancient = counts.containsKey(Blocks.REINFORCED_DEEPSLATE) || counts.containsKey(Blocks.SCULK_SHRIEKER);
		boolean mineshaft = counts.containsKey(Blocks.COBWEB) || (counts.containsKey(Blocks.RAIL) && counts.containsKey(Blocks.OAK_FENCE) && overworld);
		boolean fortress = counts.containsKey(Blocks.NETHER_BRICKS);
		boolean bastion = counts.containsKey(Blocks.GILDED_BLACKSTONE);
		boolean trial = counts.containsKey(Blocks.VAULT) || counts.containsKey(Blocks.TRIAL_SPAWNER);
		boolean monument = counts.containsKey(Blocks.PRISMARINE_BRICKS) || counts.containsKey(Blocks.DARK_PRISMARINE);
		boolean temple = counts.containsKey(Blocks.CHISELED_SANDSTONE) || counts.containsKey(Blocks.MOSSY_COBBLESTONE) || counts.containsKey(Blocks.SPAWNER);
		boolean endCity = counts.containsKey(Blocks.PURPUR_BLOCK);
		boolean structure = village || stronghold || ancient || mineshaft || fortress || bastion || trial || monument || temple || endCity;

		// ---- Tunnels ----
		if (cells != null && !(ancient || mineshaft || fortress || trial || monument || stronghold)) {
			int rows = countTunnelRows(cells, height, nether, minY, bx, bz, r);
			if (rows > 0) r.add("Tunnel", Math.min(rows, 6) * 8f, r.minY);
		}

		// ---- Lights underground ----
		if (lights.get() && !(village || stronghold || ancient || trial || endCity || bastion)) {
			int under = 0;
			for (BlockPos p : lightPositions) {
				int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, p.getX() & 15, p.getZ() & 15);
				if (nether || p.getY() < surface - 6) {
					under++;
					if (r.focus == null) r.focus = p;
					r.minY = Math.min(r.minY, p.getY());
					r.maxY = Math.max(r.maxY, p.getY());
				}
			}
			if (under > 0) r.add("Lights", Math.min(under, 10) * 2f, r.minY);
		}

		// ---- Portals ----
		if (portals.get() && counts.containsKey(Blocks.NETHER_PORTAL)) {
			BlockPos any = firstOf(chunk, Blocks.NETHER_PORTAL);
			r.add("Portal", 8, any != null ? any.getY() : 64);
			if (r.focus == null) r.focus = any;
		}

		// ---- Rails / out of place blocks ----
		if (outOfPlace.get()) {
			if (counts.containsKey(Blocks.RAIL) && !mineshaft && !overworld) r.add("Rails", Math.min(counts.getInt(Blocks.RAIL), 12), 64);
			if (nether && counts.containsKey(Blocks.COBBLESTONE) && !fortress && !bastion)
				r.add("Overworld blocks", Math.min(counts.getInt(Blocks.COBBLESTONE), 40) * 0.25f, 64);
		}

		// ---- Farms ----
		if (farms.get() && !village && grownCrops > 0) r.add("Grown crops", Math.min(grownCrops, 30) * 0.4f, cropY);

		// ---- Buried storage ----
		if (storage.get() && !(bastion || fortress || ancient || trial || temple || endCity || mineshaft || stronghold)) {
			int buried = 0;
			for (BlockEntity be : chunk.getBlockEntities().values()) {
				if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity || be instanceof EnderChestBlockEntity)) continue;
				if (village && !(be instanceof ShulkerBoxBlockEntity || be instanceof EnderChestBlockEntity)) continue;
				BlockPos p = be.getBlockPos();
				int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, p.getX() & 15, p.getZ() & 15);
				boolean hidden = nether || p.getY() < surface - 5 || be instanceof ShulkerBoxBlockEntity;
				if (!hidden) continue;
				buried++;
				r.focus = p;
				r.minY = Math.min(r.minY, p.getY());
				r.maxY = Math.max(r.maxY, p.getY());
			}
			if (buried > 0) r.add("Buried storage", Math.min(buried, 10) * 5f, r.minY);
		}

		if (structure && r.score < minScore.get() * 2) r.score *= 0.75f; // be conservative near structures

		if (r.score < minScore.get()) {
			results.remove(key);
			return;
		}
		if (r.focus == null) r.focus = new BlockPos(chunk.getPos().getMiddleBlockX(), r.minY == Integer.MAX_VALUE ? 64 : r.minY, chunk.getPos().getMiddleBlockZ());
		Result old = results.get(key);
		if (old != null) r.alerted = old.alerted;
		results.put(key, r);
		maybeAlert(chunk.getPos(), r);
	}

	/** Counts rows (along X and Z) containing a straight 2-3 high corridor with flat floor and ceiling. */
	private int countTunnelRows(byte[] cells, int height, boolean nether, int minY, int bx, int bz, Result r) {
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
							&& (cells[((ly + 2) << 8) | col] == 1 || (cells[((ly + 2) << 8) | col] == 0 && cells[((ly + 3) << 8) | col] == 1));
						run = ok ? run + 1 : 0;
						best = Math.max(best, run);
					}
					if (best >= need) {
						rows++;
						int wy = ly + minY;
						r.minY = Math.min(r.minY, wy);
						r.maxY = Math.max(r.maxY, wy + 2);
						if (r.focus == null) r.focus = new BlockPos(bx + (axis == 0 ? 8 : a), wy, bz + (axis == 0 ? a : 8));
						a++; // a 2-3 wide tunnel should not count 3 times
					}
				}
			}
		}
		return rows;
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
			Notifications.push("Sus chunk " + Math.round(r.score) + "pts", WorldUtil.fmt(f) + "  •  " + reasons, Notifications.Type.ALERT, 6000);
			ChatUtil.info(Component.literal("Sus chunk ").withStyle(s -> s.withColor(Kind.SUS.color & 0xFFFFFF).withBold(true))
				.append(Component.literal("at " + WorldUtil.fmt(f) + " ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("(" + reasons + ")").withStyle(ChatFormatting.GRAY)));
		}
		if (sound.get()) Sounds.alert();
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
		dirty.add(chunk.getPos().toLong());
	}

	@Override
	public void onTick() {
		if (++tickCounter % 40 == 0 && !dirty.isEmpty()) {
			for (long key : dirty.toLongArray()) {
				LevelChunk c = mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
				if (c != null) scanChunk(c);
			}
			dirty.clear();
		}
	}

	@Override
	public void onChunkUnload(ChunkPos pos) {
		results.remove(pos.toLong());
		ages.remove(pos.toLong());
	}

	@Override
	public void clearAll() {
		results.clear();
		ages.clear();
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
			int color = ColorUtil.lerp(0xFFFFE066, 0xFFFF3B6B, heat);
			int y1 = r.minY == Integer.MAX_VALUE ? (int) cam.y - 2 : r.minY - 1;
			int y2 = r.maxY == Integer.MIN_VALUE ? y1 + 4 : r.maxY + 2;
			AABB box = new AABB(cp.getMinBlockX(), y1, cp.getMinBlockZ(), cp.getMaxBlockX() + 1, y2, cp.getMaxBlockZ() + 1);
			Render3D.box(box, ColorUtil.withAlpha(color, (int) (10 + 22 * heat)), ColorUtil.withAlpha(color, (int) (110 + 100 * heat)), 1.4f, true);
			if (labels.get()) {
				Vec3 lp = new Vec3(cp.getMiddleBlockX() + 0.5, y2 + 0.5, cp.getMiddleBlockZ() + 0.5);
				if (lp.distanceTo(cam) < 160)
					Render3D.label(lp, "SUS " + Math.round(r.score) + " §7" + reasons(r), color, 1f);
			}
		}

		AgeOverlay mode = ageOverlay.get();
		if (mode != AgeOverlay.OFF) {
			double y = Math.floor(cam.y) - overlayY.get();
			for (var e : ages.long2ObjectEntrySet()) {
				Age age = e.getValue();
				boolean show = age == Age.NEW ? (mode == AgeOverlay.NEW || mode == AgeOverlay.BOTH) : age == Age.OLD && (mode == AgeOverlay.OLD || mode == AgeOverlay.BOTH);
				if (!show) continue;
				int color = age == Age.NEW ? 0xFFFF4D6D : 0xFF4ADE80;
				ChunkPos cp = new ChunkPos(e.getLongKey());
				Render3D.chunkSquare(cp.x, cp.z, y, ColorUtil.withAlpha(color, 45), ColorUtil.withAlpha(color, 110), 1f);
			}
		}
	}

	public String ageAt(ChunkPos pos) {
		Age a = ages.get(pos.toLong());
		return a == null ? "?" : a.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public void collectTargets(List<HuntTarget> out) {
		for (Result r : results.values())
			if (r.focus != null) out.add(new HuntTarget(r.focus, Math.min(70, r.score), "Sus: " + reasons(r), 0xFFFF7AF5));
	}

}
