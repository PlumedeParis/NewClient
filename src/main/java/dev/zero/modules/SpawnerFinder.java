package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.ChunkFinderModule;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds player-placed mob spawners (DonutSMP sells spawners: a spawner of an unnatural mob,
 * or with no dungeon / mineshaft / fortress / stronghold / bastion around it, was placed by a
 * player) and mob farms (large same-type mob clusters, piles of dropped items).
 */
public class SpawnerFinder extends ChunkFinderModule<SpawnerFinder.Spawners> {
	private final BoolSetting showNatural = add(new BoolSetting("Show Natural", "Also show dungeon / structure spawners", false));
	private final BoolSetting farms = add(new BoolSetting("Mob Farms", "Detect big same-type mob clusters", true));
	private final NumberSetting farmSize = add(new NumberSetting("Farm Size", "Same-type mobs in one chunk to flag a farm", 16, 6, 100, 1)).visibleWhen(farms::get);
	private final NumberSetting itemPile = add(new NumberSetting("Item Pile", "Dropped item entities in one chunk to flag (0 = off)", 40, 0, 300, 5)).visibleWhen(farms::get);
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Tracers to player spawners and farms", true));

	public record Spawner(BlockPos pos, String mob, boolean natural, String reason) {}

	public record Spawners(List<Spawner> list) {}

	private record Farm(String what, int count, Vec3 center) {}

	private static final Set<String> NATURAL_MOBS = Set.of("zombie", "skeleton", "spider", "cave_spider", "blaze", "silverfish", "magma_cube");
	private static final Set<Block> STRUCTURE = Set.of(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBWEB, Blocks.RAIL, Blocks.OAK_FENCE,
		Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.IRON_BARS,
		Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.DARK_OAK_PLANKS);

	private final Long2ObjectOpenHashMap<Farm> farmResults = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet farmAlerts = new LongOpenHashSet();
	private int tick;

	public SpawnerFinder() {
		super("SpawnerFinder", "Player-placed spawners and mob farms", Category.HUNT);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (Spawners s : results.values()) for (Spawner sp : s.list()) if (!sp.natural()) n++;
		n += farmResults.size();
		return n > 0 ? String.valueOf(n) : null;
	}

	@Override
	protected boolean relevant(BlockState o, BlockState n) { return o.is(Blocks.SPAWNER) || n.is(Blocks.SPAWNER); }

	@Override
	protected Spawners analyse(LevelChunk chunk) {
		List<Spawner> list = new ArrayList<>();
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (!(be instanceof SpawnerBlockEntity sp)) continue;
			BlockPos pos = be.getBlockPos();
			String mob = "?";
			try {
				Entity display = sp.getSpawner().getOrCreateDisplayEntity(mc.level, pos);
				if (display != null) mob = EntityType.getKey(display.getType()).getPath();
			} catch (Exception ignored) {
			}
			int structureBlocks = 0;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int dx = -4; dx <= 4; dx++)
				for (int dy = -3; dy <= 3; dy++)
					for (int dz = -4; dz <= 4; dz++)
						if (STRUCTURE.contains(mc.level.getBlockState(m.setWithOffset(pos, dx, dy, dz)).getBlock())) structureBlocks++;
			boolean naturalMob = mob.equals("?") || NATURAL_MOBS.contains(mob);
			boolean natural = naturalMob && structureBlocks >= 6;
			String reason = !naturalMob ? "unnatural mob" : structureBlocks < 6 ? "no structure around" : "structure";
			list.add(new Spawner(pos, mob, natural, reason));
			if (!natural) report(chunk.getPos(), pos, "Player spawner", mob + " spawner (" + reason + ")", 0xFFFF55FF);
		}
		return list.isEmpty() ? null : new Spawners(list);
	}

	@Override
	public void onTick() {
		super.onTick();
		if (!farms.get() || ++tick % 40 != 0) return;
		Map<Long, Map<String, int[]>> perChunk = new HashMap<>();
		Map<Long, double[]> centers = new HashMap<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			String key;
			if (e instanceof ItemEntity) key = "items";
			else if (e instanceof Mob) key = EntityType.getKey(e.getType()).getPath();
			else continue;
			long ck = ChunkPos.asLong(e.blockPosition());
			perChunk.computeIfAbsent(ck, k -> new HashMap<>()).computeIfAbsent(key, k -> new int[1])[0]++;
			double[] c = centers.computeIfAbsent(ck, k -> new double[4]);
			c[0] += e.getX();
			c[1] += e.getY();
			c[2] += e.getZ();
			c[3]++;
		}
		farmResults.clear();
		for (var e : perChunk.entrySet()) {
			String best = null;
			int bestCount = 0;
			for (var t : e.getValue().entrySet()) {
				int need = t.getKey().equals("items") ? itemPile.getInt() : farmSize.getInt();
				if (need <= 0) continue;
				if (t.getValue()[0] >= need && t.getValue()[0] > bestCount) {
					best = t.getKey();
					bestCount = t.getValue()[0];
				}
			}
			if (best == null) continue;
			double[] c = centers.get(e.getKey());
			Vec3 center = new Vec3(c[0] / c[3], c[1] / c[3], c[2] / c[3]);
			farmResults.put((long) e.getKey(), new Farm(best, bestCount, center));
			if (farmAlerts.add(e.getKey())) report(new ChunkPos(e.getKey()), BlockPos.containing(center), "Mob farm", bestCount + " " + best + " in one chunk", 0xFFFFB347);
		}
	}

	@Override
	public void clearAll() {
		super.clearAll();
		farmResults.clear();
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		for (Spawners s : results.values()) {
			for (Spawner sp : s.list()) {
				if (sp.natural() && !showNatural.get()) continue;
				int color = sp.natural() ? 0xFF9CA3AF : 0xFFFF55FF;
				Render3D.box(new AABB(sp.pos()), ColorUtil.withAlpha(color, 50), ColorUtil.withAlpha(color, 240), 2f, true);
				if (!sp.natural() && tracers.get()) Render3D.tracer(Vec3.atCenterOf(sp.pos()), ColorUtil.withAlpha(color, 200), 1.6f);
				Render3D.label(Vec3.atCenterOf(sp.pos()).add(0, 1, 0), (sp.natural() ? "spawner " : "PLAYER SPAWNER ") + sp.mob() + " §7"
					+ WorldUtil.distance(cam.distanceTo(Vec3.atCenterOf(sp.pos()))), color, 1f);
			}
		}
		for (Farm f : farmResults.values()) {
			int color = 0xFFFFB347;
			Render3D.box(new AABB(f.center().subtract(2, 1, 2), f.center().add(2, 2, 2)), ColorUtil.withAlpha(color, 25), ColorUtil.withAlpha(color, 200), 1.5f, true);
			if (tracers.get()) Render3D.tracer(f.center(), ColorUtil.withAlpha(color, 180), 1.4f);
			Render3D.label(f.center().add(0, 2.6, 0), "FARM " + f.count() + " " + f.what() + " §7" + WorldUtil.distance(cam.distanceTo(f.center())), color, 1f);
		}
	}
}
