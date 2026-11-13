package dev.zero.modules;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zero.hud.Hud;
import dev.zero.hud.Notifications;
import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render2D;
import dev.zero.render.Render3D;
import dev.zero.seed.SeedWorld;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.KeybindSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.BlockColors;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Draws a mining route to the ores you choose, shaped to look like ordinary mining:
 * straight 1x2 branch tunnels on a regular grid, ores reached from the side of a tunnel
 * (never by digging straight at them), staying on one level, using caves, and avoiding
 * lava, water and falling gravel. Also watches your own mining pace and ore ratio.
 *
 * It only DRAWS the route. It never mines, moves, rotates or sends anything: you dig it
 * yourself, at your own pace.
 */
public class SafeMiner extends Module {
	public enum Source { BOTH, SEED, VISIBLE }

	public enum Pattern { BRANCH, CAVES, SHORTEST }

	private final EnumSetting<Source> source = add(new EnumSetting<>("Targets", "Seed = SeedOreFinder predictions (needs the seed), Visible = real exposed ores you can see", Source.BOTH));
	private final BoolSetting diamonds = add(new BoolSetting("Diamonds", "Path to diamond ore", true));
	private final BoolSetting debris = add(new BoolSetting("Ancient Debris", "Path to ancient debris", true));
	private final BoolSetting emeralds = add(new BoolSetting("Emeralds", "Path to emerald ore", false));
	private final BoolSetting gold = add(new BoolSetting("Gold", "Path to gold ore", false));
	private final BoolSetting iron = add(new BoolSetting("Iron", "Path to iron ore", false));
	private final BoolSetting redstone = add(new BoolSetting("Redstone", "Path to redstone ore", false));
	private final BoolSetting lapis = add(new BoolSetting("Lapis", "Path to lapis ore", false));
	private final BoolSetting copper = add(new BoolSetting("Copper", "Path to copper ore", false));
	private final BoolSetting coal = add(new BoolSetting("Coal", "Path to coal ore", false));
	private final BoolSetting quartz = add(new BoolSetting("Quartz", "Path to nether quartz", false));
	private final EnumSetting<SeedOreFinder.OreType> oreType = add(new EnumSetting<>("Ore Type", "Normal ores, deepslate ores, or both", SeedOreFinder.OreType.BOTH));

	private final EnumSetting<Pattern> pattern = add(new EnumSetting<>("Pattern", "Branch = straight strip-mine tunnels on a grid (most natural). Caves = walk through caves as much as possible. Shortest = least digging (looks the most like X-ray)", Pattern.BRANCH));
	private final NumberSetting spacing = add(new NumberSetting("Branch Spacing", "Distance between branch tunnels, like a real strip mine", 4, 3, 8, 1, "m")).visibleWhen(() -> pattern.get() == Pattern.BRANCH);
	private final BoolSetting sideApproach = add(new BoolSetting("Side Approach", "Reach ores from the side of a tunnel, never by digging straight at them", true));
	private final BoolSetting stayLevel = add(new BoolSetting("Stay On Level", "Avoid going up and down (strip miners stay on one Y level)", true));
	private final NumberSetting maxDistance = add(new NumberSetting("Max Distance", "Only route to ores this close (must be in loaded chunks)", 40, 12, 96, 4, "m"));
	private final BoolSetting avoidLava = add(new BoolSetting("Avoid Lava", "Never open a block next to lava", true));
	private final BoolSetting avoidWater = add(new BoolSetting("Avoid Water", "Avoid opening blocks next to water", true));
	private final BoolSetting avoidGravel = add(new BoolSetting("Avoid Gravel", "Avoid mining under gravel / sand", true));
	private final NumberSetting ahead = add(new NumberSetting("Show Ahead", "How many blocks to mine are highlighted ahead of you", 12, 3, 40, 1));
	private final KeybindSetting replanKey = add(new KeybindSetting("Next Ore Key", "Skip the current ore and route to another one", GLFW.GLFW_KEY_UNKNOWN));

	private final BoolSetting paceMonitor = add(new BoolSetting("Pace Monitor", "Warn when you mine too fast or too many ores compared to stone", true));
	private final NumberSetting maxPace = add(new NumberSetting("Max Blocks/min", "Warn above this mining speed", 60, 10, 240, 5)).visibleWhen(paceMonitor::get);
	private final NumberSetting maxOre = add(new NumberSetting("Max Ore %", "Warn when this share of your recent mined blocks are ores", 8, 1, 40, 1, "%")).visibleWhen(paceMonitor::get);

	private final ColorSetting pathColor = add(new ColorSetting("Path Color", "Route line", 0xFF4FD1C5, false));
	private final ColorSetting mineColor = add(new ColorSetting("Mine Color", "Blocks to mine next", 0xFFFFD166, false));
	private final ColorSetting targetColor = add(new ColorSetting("Target Color", "Ore the route leads to", 0xFFFF4DD8, false));
	private final BoolSetting hud = add(new BoolSetting("HUD", "Directions, path stats and mining pace", true));

	public SafeMiner() {
		super("SafeMiner", "Draws a natural-looking mining route to chosen ores (you mine it yourself)", Category.WORLD);
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			if (isEnabled()) onMined(state);
		});
	}

	// ---------------------------------------------------------------- targets

	private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};
	private static final String[] DIR_NAMES = {"North", "East", "South", "West"};
	private static final int NONE = 4;

	public Set<Block> wanted() {
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
		if (oreType.get() != SeedOreFinder.OreType.BOTH) {
			boolean wantDeep = oreType.get() == SeedOreFinder.OreType.DEEPSLATE_ONLY;
			s.removeIf(b -> {
				String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
				if (id.startsWith("nether_") || b == Blocks.ANCIENT_DEBRIS) return false;
				return id.startsWith("deepslate_") != wantDeep;
			});
		}
		return s;
	}

	/** Ores SeedOreFinder should also predict while SafeMiner wants them. */
	public Set<Block> seedRequests() {
		return isEnabled() && source.get() != Source.VISIBLE ? wanted() : Set.of();
	}

	private record Target(BlockPos pos, Block block, boolean predicted) {}

	private final List<Target> visible = new ArrayList<>();
	private final LongOpenHashSet skipped = new LongOpenHashSet();
	private List<Target> testTargets;

	/** Real ores touching air/water/lava in loaded chunks (anti-xray sends those truthfully). */
	private void scanVisible(Set<Block> want) {
		visible.clear();
		if (want.isEmpty()) return;
		BlockPos me = mc.player.blockPosition();
		int r = maxDistance.getInt();
		int minSy = SectionPos.blockToSectionCoord(Math.max(mc.level.getMinY(), me.getY() - r));
		int maxSy = SectionPos.blockToSectionCoord(Math.min(mc.level.getMaxY() - 1, me.getY() + r));
		int cr = (r >> 4) + 1;
		ChunkPos c = mc.player.chunkPosition();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int cx = c.x - cr; cx <= c.x + cr; cx++)
			for (int cz = c.z - cr; cz <= c.z + cr; cz++) {
				LevelChunk ch = mc.level.getChunkSource().getChunk(cx, cz, false);
				if (ch == null) continue;
				for (int sy = minSy; sy <= maxSy; sy++) {
					int idx = mc.level.getSectionIndexFromSectionY(sy);
					if (idx < 0 || idx >= ch.getSections().length) continue;
					LevelChunkSection sec = ch.getSections()[idx];
					if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> want.contains(st.getBlock()))) continue;
					for (int i = 0; i < 4096; i++) {
						int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
						BlockState st = sec.getBlockState(x, y, z);
						if (!want.contains(st.getBlock())) continue;
						m.set((cx << 4) + x, (sy << 4) + y, (cz << 4) + z);
						if (m.distSqr(me) > (double) r * r) continue;
						if (XRay.isExposed(m, mc.level)) visible.add(new Target(m.immutable(), st.getBlock(), false));
					}
				}
			}
	}

	private List<Target> targets() {
		Set<Block> want = wanted();
		List<Target> out = new ArrayList<>();
		if (testTargets != null) out.addAll(testTargets);
		else {
		LongOpenHashSet seen = new LongOpenHashSet();
		BlockPos me = mc.player.blockPosition();
		double r2 = (double) maxDistance.getInt() * maxDistance.getInt();
		if (source.get() != Source.SEED) for (Target t : visible) if (seen.add(t.pos.asLong())) out.add(t);
		if (source.get() != Source.VISIBLE && ModuleManager.seedOreFinder.isEnabled()) {
			for (SeedWorld.Prediction p : ModuleManager.seedOreFinder.snapshot(false)) {
				if (!want.contains(p.block()) || p.pos().distSqr(me) > r2 || !seen.add(p.pos().asLong())) continue;
				out.add(new Target(p.pos(), p.block(), true));
			}
		}
		}
		out.removeIf(t -> {
			if (skipped.contains(t.pos.asLong()) || !mc.level.isLoaded(t.pos)) return true;
			BlockState now = mc.level.getBlockState(t.pos);
			return !now.is(Blocks.VOID_AIR) && (now.isAir() || !now.getFluidState().isEmpty()); // already mined
		});
		return out;
	}

	// ---------------------------------------------------------------- route

	/** One step of the route: where your feet go, which way you move, and what to dig for it. */
	public record Step(BlockPos feet, int dir, int dy, List<BlockPos> mine) {}

	private List<Step> route = List.of();
	private Target goal;
	private int progress, offPath, sinceSearch, mineTotal;
	private Search search;
	private String status = "Looking for ores...";
	private boolean keyHeld;

	public List<Step> route() { return route; }
	public BlockPos goalPos() { return goal == null ? null : goal.pos; }
	public boolean searching() { return search != null; }
	public String status() { return status; }

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	public void onWorldLeave() {
		reset();
		skipped.clear();
	}

	private void reset() {
		route = List.of();
		goal = null;
		search = null;
		progress = offPath = 0;
		sinceSearch = 1000;
		status = "Looking for ores...";
	}

	public void replan() {
		route = List.of();
		goal = null;
		search = null;
		sinceSearch = 1000;
	}

	public void setTestTargets(List<BlockPos> ores) {
		if (ores == null) { testTargets = null; return; }
		testTargets = new ArrayList<>();
		for (BlockPos p : ores) testTargets.add(new Target(p, mc.level.getBlockState(p).getBlock(), false));
		skipped.clear();
		replan();
	}

	private int tick;

	@Override
	public void onTick() {
		tick++;
		sinceSearch++;
		boolean down = replanKey.isBound() && mc.screen == null && InputConstants.isKeyDown(mc.getWindow(), replanKey.get());
		if (down && !keyHeld && goal != null) {
			skipped.add(goal.pos.asLong());
			replan();
			Notifications.push("SafeMiner", "Skipped, routing to the next ore", Notifications.Type.INFO);
		}
		keyHeld = down;
		if (testTargets == null && source.get() != Source.SEED && (tick % 100 == 1 || visible.isEmpty() && tick % 40 == 1)) scanVisible(wanted());

		if (search != null) {
			Search.Result r = search.run(700_000L); // at most 0.7 ms of path search per tick
			if (r == Search.Result.RUNNING) return;
			if (r == Search.Result.FOUND) {
				route = search.path();
				goal = search.reached();
				progress = 0;
				offPath = 0;
				mineTotal = 0;
				for (Step s : route) mineTotal += s.mine.size();
				status = null;
			} else {
				route = List.of();
				goal = null;
				status = search.targets == 0 ? noTargetsText() : "No safe route to the " + search.targets + " ore(s) in range";
				sinceSearch = -160; // nothing reachable right now: try again in 10 s, not at once
			}
			search = null;
			return;
		}

		if (route.isEmpty()) {
			if (sinceSearch >= 40) startSearch();
			return;
		}

		// target mined (or gone)? -> next one
		BlockState gs = mc.level.getBlockState(goal.pos);
		if (!gs.is(Blocks.VOID_AIR) && (gs.isAir() || !gs.getFluidState().isEmpty() || gs.getBlock() != goal.block && !goal.predicted)) {
			skipped.add(goal.pos.asLong());
			Sounds.ping();
			replan();
			return;
		}

		// follow progress
		BlockPos feet = mc.player.blockPosition();
		int best = -1;
		double bestD = Double.MAX_VALUE;
		for (int i = Math.max(0, progress - 3); i < Math.min(route.size(), progress + 12); i++) {
			double d = route.get(i).feet.distSqr(feet);
			if (d < bestD) { bestD = d; best = i; }
		}
		if (best >= 0 && bestD <= 2.5) {
			progress = Math.max(progress, best);
			offPath = 0;
		} else if (++offPath > 60) {
			replan(); // you left the route: recompute from where you are
			return;
		}
		// something dangerous appeared ahead (lava flowed in...) or the route got stale
		if (tick % 20 == 0) {
			for (int i = progress; i < Math.min(route.size(), progress + 6); i++)
				for (BlockPos p : route.get(i).mine)
					if (!mc.level.getBlockState(p).getFluidState().isEmpty() && mc.level.getBlockState(p).getFluidState().is(Fluids.LAVA)) { replan(); return; }
		}
	}

	private String noTargetsText() {
		if (wanted().isEmpty()) return "No ores selected";
		if (source.get() == Source.SEED && !ModuleManager.seedOreFinder.isEnabled()) return "Turn on SeedOreFinder (with the seed)";
		return "No selected ores within " + maxDistance.getInt() + "m";
	}

	private void startSearch() {
		sinceSearch = 0;
		List<Target> ts = targets();
		BlockPos start = mc.player.blockPosition();
		ts.sort((a, b) -> Double.compare(a.pos.distSqr(start), b.pos.distSqr(start)));
		if (ts.size() > 24) ts = new ArrayList<>(ts.subList(0, 24));
		search = new Search(start, ts);
		status = ts.isEmpty() ? noTargetsText() : "Planning route...";
		if (ts.isEmpty()) search = null;
	}

	// ---------------------------------------------------------------- A*

	private static final byte UNKNOWN = 0, OPEN = 1, SOLID = 2, WATER = 3, LAVA = 4, BLOCKED = 5;

	private final class Search {
		enum Result { RUNNING, FOUND, FAILED }

		final BlockPos start;
		final int targets;
		final List<Target> list;
		final Long2IntOpenHashMap goalDirs = new Long2IntOpenHashMap(); // cell -> allowed arrival dirs mask (bit 4 = any)
		final Long2ObjectOpenHashMap<Target> goalOre = new Long2ObjectOpenHashMap<>();
		final Long2ByteOpenHashMap cells = new Long2ByteOpenHashMap();
		final Long2ObjectOpenHashMap<Node> best = new Long2ObjectOpenHashMap<>();
		final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		final Pattern pat = pattern.get();
		final int space = spacing.getInt();
		final boolean side = sideApproach.get(), level = stayLevel.get(), noLava = avoidLava.get(), noWater = avoidWater.get(), noGravel = avoidGravel.get();
		final int limit;
		int expanded;
		Node found;

		final class Node {
			final int x, y, z, dir, dy;
			final double g, f;
			final Node parent;
			final List<BlockPos> mine;

			Node(int x, int y, int z, int dir, int dy, double g, double h, Node parent, List<BlockPos> mine) {
				this.x = x; this.y = y; this.z = z; this.dir = dir; this.dy = dy; this.g = g; this.f = g + h * 1.25; this.parent = parent; this.mine = mine;
			}
		}

		Search(BlockPos start, List<Target> ts) {
			this.start = start;
			this.targets = ts.size();
			this.list = ts;
			this.limit = 50_000;
			for (Target t : ts) {
				for (int d = 0; d < 4; d++) {
					// standing next to the ore (ore at feet or head height), arriving in any direction except straight at it
					int mask = side ? (0xF & ~(1 << d)) : 0xF;
					for (int hy = 0; hy <= 1; hy++) {
						long cell = BlockPos.asLong(t.pos.getX() - DX[d], t.pos.getY() - hy, t.pos.getZ() - DZ[d]);
						goalDirs.put(cell, goalDirs.get(cell) | mask);
						goalOre.putIfAbsent(cell, t);
					}
				}
				long under = BlockPos.asLong(t.pos.getX(), t.pos.getY() - 2, t.pos.getZ()); // ore just above your head
				goalDirs.put(under, goalDirs.get(under) | 0x1F);
				goalOre.putIfAbsent(under, t);
			}
			Node n = new Node(start.getX(), start.getY(), start.getZ(), NONE, 0, 0, h(start.getX(), start.getY(), start.getZ()), null, List.of());
			open.add(n);
		}

		double h(int x, int y, int z) {
			double m = Double.MAX_VALUE;
			for (Target t : list) m = Math.min(m, Math.abs(t.pos.getX() - x) + Math.abs(t.pos.getY() - y) + Math.abs(t.pos.getZ() - z) - 1);
			return Math.max(0, m);
		}

		long key(int x, int y, int z, int dir) {
			return ((long) (x - start.getX() + 1024) << 34) | ((long) (z - start.getZ() + 1024) << 23) | ((long) (y + 2048) << 3) | dir;
		}

		byte cell(int x, int y, int z) {
			long k = BlockPos.asLong(x, y, z);
			byte c = cells.getOrDefault(k, UNKNOWN);
			if (c != UNKNOWN) return c;
			BlockPos p = new BlockPos(x, y, z);
			if (!mc.level.isLoaded(p) || y < mc.level.getMinY() || y >= mc.level.getMaxY()) c = BLOCKED;
			else {
				BlockState s = mc.level.getBlockState(p);
				if (s.is(Blocks.VOID_AIR)) c = BLOCKED;
				else if (s.getFluidState().is(Fluids.LAVA) || s.getFluidState().is(Fluids.FLOWING_LAVA)) c = LAVA;
				else if (!s.getFluidState().isEmpty()) c = WATER;
				else if (s.getCollisionShape(mc.level, p).isEmpty()) c = s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.POWDER_SNOW) ? BLOCKED : OPEN;
				else if (s.getDestroySpeed(mc.level, p) < 0) c = BLOCKED;
				else c = SOLID;
			}
			cells.put(k, c);
			return c;
		}

		boolean falling(int x, int y, int z) {
			BlockPos p = new BlockPos(x, y, z);
			return mc.level.isLoaded(p) && mc.level.getBlockState(p).getBlock() instanceof FallingBlock;
		}

		/** Cost of making (x,y,z) a free cell the player walks through; NaN = not allowed. */
		double open(int x, int y, int z, boolean head, List<BlockPos> mine) {
			byte c = cell(x, y, z);
			if (c == BLOCKED || c == LAVA) return Double.NaN;
			double cost = 0;
			if (c == WATER) {
				if (noWater) return Double.NaN;
				cost += 4;
			}
			boolean dig = c == SOLID;
			if (dig) {
				mine.add(new BlockPos(x, y, z));
				cost += pat == Pattern.CAVES ? 5 : 2;
			}
			// hazards around the opened cell
			for (int d = 0; d < 6; d++) {
				int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0), nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
				byte n = cell(nx, ny, nz);
				if (n == LAVA) {
					if (dig && noLava) return Double.NaN;
					cost += dig ? 30 : 6;
				} else if (n == WATER && dig) {
					if (noWater) return Double.NaN;
					cost += 6;
				}
			}
			if (dig && head && falling(x, y + 1, z)) {
				if (noGravel) return Double.NaN;
				cost += 12;
			}
			return cost;
		}

		boolean floor(int x, int y, int z) {
			byte c = cell(x, y, z);
			return c == SOLID;
		}

		Result run(long budgetNanos) {
			long t0 = System.nanoTime();
			for (int i = 0; ; i++) {
				if ((i & 31) == 31 && System.nanoTime() - t0 > budgetNanos) break;
				Node n = open.poll();
				if (n == null) return Result.FAILED;
				if (++expanded > limit) return Result.FAILED;
				long cellKey = BlockPos.asLong(n.x, n.y, n.z);
				int allowed = goalDirs.getOrDefault(cellKey, 0);
				if (allowed != 0 && (n.dir == NONE || (allowed & 0x10) != 0 || (allowed & (1 << n.dir)) != 0)) {
					found = n;
					return Result.FOUND;
				}
				for (int d = 0; d < 4; d++) {
					if (n.dir != NONE && d == ((n.dir + 2) & 3)) continue; // no U-turns
					for (int dy = -1; dy <= 1; dy++) expand(n, d, dy);
				}
			}
			return Result.RUNNING;
		}

		void expand(Node n, int d, int dy) {
			int nx = n.x + DX[d], nz = n.z + DZ[d];
			int ny = n.y + dy;
			if (Math.abs(nx - start.getX()) > 1000 || Math.abs(nz - start.getZ()) > 1000) return;
			List<BlockPos> mine = new ArrayList<>(3);
			double cost = 1;
			double c;
			if (dy == 1) {
				// step up: room above your head first, then the next cell one higher
				c = open(n.x, n.y + 2, n.z, true, mine); if (Double.isNaN(c)) return; cost += c;
				if (!floor(nx, n.y, nz)) return;
			} else if (dy == -1) {
				// step down: clear the cell in front at your height, then one lower
				c = open(nx, n.y + 1, nz, true, mine); if (Double.isNaN(c)) return; cost += c;
				if (!floor(nx, ny - 1, nz)) return;
			} else if (!floor(nx, ny - 1, nz)) return;
			c = open(nx, ny, nz, false, mine); if (Double.isNaN(c)) return; cost += c;
			c = open(nx, ny + 1, nz, true, mine); if (Double.isNaN(c)) return; cost += c;
			if (dy != 0) cost += level ? 4 : 1;
			if (n.dir != NONE && d != n.dir) cost += switch (pat) { case BRANCH -> 3; case CAVES -> 0.5; case SHORTEST -> 0; };
			if (pat == Pattern.BRANCH && !mine.isEmpty()) {
				// strip-mine grid: tunnels along Z sit on x lines, tunnels along X sit on z lines
				boolean onGrid = (d == 0 || d == 2) ? Math.floorMod(nx - start.getX(), space) == 0 : Math.floorMod(nz - start.getZ(), space) == 0;
				if (!onGrid) cost += 3 * mine.size();
			}
			double g = n.g + cost;
			long k = key(nx, ny, nz, d);
			Node prev = best.get(k);
			if (prev != null && prev.g <= g) return;
			Node m = new Node(nx, ny, nz, d, dy, g, h(nx, ny, nz), n, mine);
			best.put(k, m);
			open.add(m);
		}

		List<Step> path() {
			ArrayDeque<Step> out = new ArrayDeque<>();
			for (Node n = found; n != null && n.parent != null; n = n.parent) out.addFirst(new Step(new BlockPos(n.x, n.y, n.z), n.dir, n.dy, n.mine));
			return new ArrayList<>(out);
		}

		Target reached() { return goalOre.get(BlockPos.asLong(found.x, found.y, found.z)); }
	}

	// ---------------------------------------------------------------- pace monitor

	private final ArrayDeque<long[]> mined = new ArrayDeque<>(); // {time, isOre}
	private long lastWarn;

	private static boolean isOre(Block b) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
		return id.endsWith("_ore") || b == Blocks.ANCIENT_DEBRIS;
	}

	private void onMined(BlockState state) {
		mined.addLast(new long[] {System.currentTimeMillis(), isOre(state.getBlock()) ? 1 : 0});
		while (mined.size() > 400) mined.pollFirst();
		if (!paceMonitor.get()) return;
		String warn = paceWarning();
		long now = System.currentTimeMillis();
		if (warn != null && now - lastWarn > 20_000) {
			lastWarn = now;
			Notifications.push("SafeMiner", warn, Notifications.Type.WARNING);
			Sounds.alert();
		}
	}

	/** Blocks you mined during the last minute. */
	public int pace() {
		long now = System.currentTimeMillis();
		int n = 0;
		for (long[] m : mined) if (now - m[0] <= 60_000) n++;
		return n;
	}

	/** Share of ores among your last mined blocks (up to 200), in percent; -1 if too few. */
	public int orePercent() {
		int total = 0, ores = 0;
		var it = mined.descendingIterator();
		while (it.hasNext() && total < 200) {
			long[] m = it.next();
			total++;
			ores += (int) m[1];
		}
		return total < 20 ? -1 : Math.round(100f * ores / total);
	}

	private String paceWarning() {
		int p = pace(), o = orePercent();
		if (p > maxPace.getInt()) return "Slow down: " + p + " blocks/min";
		if (o > maxOre.getInt()) return "Mine more stone: " + o + "% of your blocks are ores";
		return null;
	}

	// ---------------------------------------------------------------- render

	@Override
	public void onRender3D(float pt) {
		if (route.isEmpty() || goal == null) return;
		int pc = pathColor.get(), mc_ = mineColor.get(), tc = targetColor.get();
		// route line along the tunnel floor
		Vec3 prev = mc.player.getPosition(pt).add(0, 0.1, 0);
		for (int i = progress; i < route.size(); i++) {
			Vec3 p = Vec3.atBottomCenterOf(route.get(i).feet).add(0, 0.1, 0);
			Render3D.line(prev, p, ColorUtil.withAlpha(pc, 230), 2.5f, true);
			prev = p;
		}
		// next blocks to dig
		int shown = 0, max = ahead.getInt();
		boolean first = true;
		for (int i = progress; i < route.size() && shown < max; i++) {
			for (BlockPos b : route.get(i).mine) {
				if (mc.level.getBlockState(b).isAir()) continue; // already dug
				AABB box = new AABB(b).deflate(0.03);
				float fade = 1f - shown / (float) (max + 2);
				if (first) {
					Render3D.box(box, ColorUtil.withAlpha(mc_, 90), ColorUtil.withAlpha(mc_, 255), 2.4f, true);
					if (Render3D.look(box)) Render3D.lookText("SafeMiner · mine this next", mc_);
					first = false;
				} else Render3D.box(box, ColorUtil.withAlpha(mc_, (int) (35 * fade)), ColorUtil.withAlpha(mc_, (int) (200 * fade)), 1.4f, true);
				if (++shown >= max) break;
			}
		}
		float pulse = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 250.0);
		AABB ore = new AABB(goal.pos).deflate(0.01);
		Render3D.box(ore, ColorUtil.withAlpha(tc, (int) (80 * pulse)), ColorUtil.withAlpha(tc, 255), 2.2f, true);
		Render3D.importantLabel(Vec3.atCenterOf(goal.pos).add(0, 0.9, 0), goal.block.getName().getString() + " §7" + remainingMine() + " to mine", tc, 1f);
		if (Render3D.look(ore)) Render3D.lookText(goal.block.getName().getString() + " · SafeMiner target", tc);
	}

	private int remainingCache, remainingTick = -1;

	/** Blocks still to dig on the route; counted once per tick, not on every frame. */
	private int remainingMine() {
		if (remainingTick == tick) return remainingCache;
		remainingTick = tick;
		return remainingCache = countRemaining();
	}

	private int countRemaining() {
		int n = 0;
		for (int i = progress; i < route.size(); i++) for (BlockPos b : route.get(i).mine) if (!mc.level.getBlockState(b).isAir()) n++;
		return n;
	}

	/** Plain-language next instruction, e.g. "Dig 5 North (ahead)". */
	public String instruction() {
		if (route.isEmpty()) return status == null ? "" : status;
		if (progress >= route.size() - 1 && route.get(route.size() - 1).feet.distSqr(mc.player.blockPosition()) <= 1) return "Ore is next to you ✔ mine it";
		int i = Math.min(progress + (route.get(progress).feet.equals(mc.player.blockPosition()) ? 1 : 0), route.size() - 1);
		Step s = route.get(i);
		int count = 0;
		boolean dig = false;
		for (int j = i; j < route.size(); j++) {
			Step t = route.get(j);
			if (t.dir != s.dir || t.dy != s.dy) break;
			count++;
			if (!t.mine.isEmpty()) dig = true;
		}
		String verb = s.dy > 0 ? "Step up" : s.dy < 0 ? "Step down" : dig ? "Dig" : "Walk";
		String rel = relative(s.dir);
		String txt = verb + " " + count + " " + DIR_NAMES[s.dir] + " §7(" + rel + ")";
		int next = i + count;
		if (next < route.size()) txt += "§7, then " + relativeTo(s.dir, route.get(next).dir);
		return txt;
	}

	private String relative(int dir) {
		int facing = switch (mc.player.getDirection()) { case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
		return switch ((dir - facing + 4) & 3) { case 0 -> "ahead"; case 1 -> "right"; case 2 -> "behind"; default -> "left"; };
	}

	private static String relativeTo(int from, int to) {
		return switch ((to - from + 4) & 3) { case 0 -> "up/down"; case 1 -> "right"; case 2 -> "back"; default -> "left"; };
	}

	@Override
	public String getInfo() {
		return goal == null ? null : remainingMine() + "";
	}

	@Override
	public void onRender2D(GuiGraphics g, float pt) {
		if (!hud.get()) return;
		List<String> lines = new ArrayList<>();
		lines.add(goal == null ? "Safe Miner" : "Safe Miner  §7→ " + goal.block.getName().getString() + " §8" + WorldUtil.distance(Math.sqrt(goal.pos.distToCenterSqr(mc.player.position()))));
		lines.add("§f" + instruction());
		if (!route.isEmpty()) {
			int rem = remainingMine();
			lines.add("§7Route " + (route.size() - progress) + " steps • " + rem + " blocks to dig" + (pattern.get() == Pattern.SHORTEST ? " §e(shortest: looks like X-ray)" : ""));
		}
		if (paceMonitor.get()) {
			int p = pace(), o = orePercent();
			String pc = p > maxPace.getInt() ? "§c" : p > maxPace.getInt() * 0.75 ? "§e" : "§a";
			String oc = o < 0 ? "§8" : o > maxOre.getInt() ? "§c" : o > maxOre.getInt() * 0.75 ? "§e" : "§a";
			lines.add("§7Pace " + pc + p + "/min§7 • Ores " + oc + (o < 0 ? "-" : o + "%") + (paceWarning() != null ? "  §c⚠ " + paceWarning() : ""));
		}
		int w = 0;
		for (String l : lines) w = Math.max(w, Render2D.width(l));
		w += 14;
		int h = lines.size() * 10 + 6;
		int[] pos = Hud.slot("Safe miner", w, h);
		Render2D.roundedRect(g, pos[0], pos[1], w, h, 5, 0xC8101016);
		Render2D.rect(g, pos[0], pos[1] + 4, 2, h - 8, pathColor.get());
		for (int i = 0; i < lines.size(); i++) Render2D.text(g, lines.get(i), pos[0] + 7, pos[1] + 4 + i * 10, i == 0 ? 0xFFFFFFFF : 0xFFB4B4C8, false);
	}

	/** Used by the in-game test: unmined blocks of the route. */
	public int remaining() { return remainingMine(); }

	public int progressIndex() { return progress; }
}
