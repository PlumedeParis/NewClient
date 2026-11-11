package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.HuntTarget;
import dev.zero.module.ChunkFinderModule;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Highlights player-dug passages underground: 1x1 vertical shafts (digging straight down),
 * 1-wide 2-high tunnels with walls, floor and ceiling, and 1-wide staircases going down.
 * Natural caves are irregular, so long perfectly straight 1-wide passages are man-made.
 */
public class TunnelFinder extends ChunkFinderModule<TunnelFinder.Passages> implements HuntTarget.Provider {
	private final BoolSetting shafts = add(new BoolSetting("Shafts", "1x1 vertical holes dug straight down", true)).onChange(this::rescan);
	private final NumberSetting shaftLength = add(new NumberSetting("Shaft Length", "Min depth of a shaft", 8, 4, 64, 1)).onChange(this::rescan);
	private final BoolSetting tunnels = add(new BoolSetting("Tunnels", "1-wide, 2-high straight tunnels", true)).onChange(this::rescan);
	private final NumberSetting tunnelLength = add(new NumberSetting("Tunnel Length", "Min length of a tunnel", 8, 4, 16, 1)).onChange(this::rescan);
	private final BoolSetting stairs = add(new BoolSetting("Staircases", "1-wide staircases going down", true)).onChange(this::rescan);
	private final NumberSetting stairLength = add(new NumberSetting("Stair Length", "Min steps of a staircase", 6, 4, 16, 1)).onChange(this::rescan);
	private final NumberSetting alertSegments = add(new NumberSetting("Alert Segments", "Passages in a chunk to send an alert", 2, 1, 20, 1));

	public enum Kind { SHAFT, TUNNEL, STAIRS }

	public record Segment(Kind kind, AABB box) {}

	public record Passages(List<Segment> segments) {}

	public TunnelFinder() {
		super("TunnelFinder", "Player-dug shafts, 1x2 tunnels and staircases", Category.HUNT);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (Passages p : results.values()) n += p.segments().size();
		return n > 0 ? String.valueOf(n) : null;
	}

	@Override
	protected boolean relevant(BlockState o, BlockState n) { return o.isAir() != n.isAir(); }

	// 0 open, 1 solid, 2 fluid / other
	private static byte cell(BlockState s) {
		if (!s.getFluidState().isEmpty()) return 2;
		if (s.isAir() || !s.blocksMotion()) return 0;
		return 1;
	}

	@Override
	protected Passages analyse(LevelChunk chunk) {
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		int height = sections.length * 16;
		// 18x18 grid: the chunk plus a 1-block border read from neighbouring chunks.
		byte[] g = new byte[height * 18 * 18];
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		boolean anyAir = false;
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			int sy = i * 16;
			if (s.hasOnlyAir()) {
				anyAir = true;
				continue; // stays 0 = open
			}
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) g[idx(x + 1, sy + y, z + 1)] = cell(s.getBlockState(x, y, z));
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int ly = 0; ly < height; ly++) {
			for (int a = -1; a <= 16; a++) {
				g[idx(0, ly, a + 1)] = cell(mc.level.getBlockState(m.set(bx - 1, ly + minY, bz + a)));
				g[idx(17, ly, a + 1)] = cell(mc.level.getBlockState(m.set(bx + 16, ly + minY, bz + a)));
				g[idx(a + 1, ly, 0)] = cell(mc.level.getBlockState(m.set(bx + a, ly + minY, bz - 1)));
				g[idx(a + 1, ly, 17)] = cell(mc.level.getBlockState(m.set(bx + a, ly + minY, bz + 16)));
			}
		}
		int[] surface = new int[256];
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++) surface[x + z * 16] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - minY - 3;

		List<Segment> segs = new ArrayList<>();
		if (shafts.get()) findShafts(g, height, surface, bx, bz, minY, segs);
		if (tunnels.get()) findTunnels(g, height, surface, bx, bz, minY, segs);
		if (stairs.get()) findStairs(g, height, surface, bx, bz, minY, segs);
		if (segs.isEmpty()) return null;
		if (segs.size() >= alertSegments.getInt()) {
			AABB b = segs.get(0).box();
			report(chunk.getPos(), BlockPos.containing(b.getCenter()), "Dug passages", segs.size() + " (" + summary(segs) + ")", 0xFFFFD166);
		}
		return new Passages(segs);
	}

	private static String summary(List<Segment> segs) {
		int s = 0, t = 0, st = 0;
		for (Segment g : segs) switch (g.kind()) {
			case SHAFT -> s++;
			case TUNNEL -> t++;
			case STAIRS -> st++;
		}
		return s + " shafts, " + t + " tunnels, " + st + " stairs";
	}

	private static int idx(int x, int y, int z) { return (y * 18 + z) * 18 + x; }

	private static byte at(byte[] g, int height, int x, int y, int z) {
		if (y < 0 || y >= height) return 1;
		return g[idx(x, y, z)];
	}

	private void findShafts(byte[] g, int height, int[] surface, int bx, int bz, int minY, List<Segment> out) {
		int need = shaftLength.getInt();
		for (int z = 1; z <= 16; z++)
			for (int x = 1; x <= 16; x++) {
				int top = Math.min(surface[(x - 1) + (z - 1) * 16], height - 1);
				int run = 0;
				for (int y = top; y >= 0; y--) {
					boolean ok = at(g, height, x, y, z) == 0
						&& at(g, height, x - 1, y, z) == 1 && at(g, height, x + 1, y, z) == 1 && at(g, height, x, y, z - 1) == 1 && at(g, height, x, y, z + 1) == 1;
					if (ok) run++;
					if (!ok || y == 0) {
						if (run >= need) {
							int y1 = ok ? y : y + 1;
							out.add(new Segment(Kind.SHAFT, new AABB(bx + x - 1, y1 + minY, bz + z - 1, bx + x, y1 + run + minY, bz + z)));
						}
						run = 0;
					}
				}
			}
	}

	private void findTunnels(byte[] g, int height, int[] surface, int bx, int bz, int minY, List<Segment> out) {
		int need = tunnelLength.getInt();
		for (int axis = 0; axis < 2; axis++) {
			for (int a = 1; a <= 16; a++) {
				for (int y = 1; y < height - 2; y++) {
					int run = 0, start = 0;
					for (int b = 1; b <= 17; b++) {
						boolean ok = false;
						if (b <= 16) {
							int x = axis == 0 ? b : a, z = axis == 0 ? a : b;
							int sx = axis == 0 ? 0 : 1, sz = axis == 0 ? 1 : 0; // side direction
							ok = y < surface[(x - 1) + (z - 1) * 16]
								&& at(g, height, x, y, z) == 0 && at(g, height, x, y + 1, z) == 0
								&& at(g, height, x, y - 1, z) == 1 && at(g, height, x, y + 2, z) == 1
								&& at(g, height, x - sx, y, z - sz) == 1 && at(g, height, x + sx, y, z + sz) == 1
								&& at(g, height, x - sx, y + 1, z - sz) == 1 && at(g, height, x + sx, y + 1, z + sz) == 1;
						}
						if (ok) {
							if (run == 0) start = b;
							run++;
						} else {
							if (run >= need) {
								AABB box = axis == 0
									? new AABB(bx + start - 1, y + minY, bz + a - 1, bx + start - 1 + run, y + 2 + minY, bz + a)
									: new AABB(bx + a - 1, y + minY, bz + start - 1, bx + a, y + 2 + minY, bz + start - 1 + run);
								out.add(new Segment(Kind.TUNNEL, box));
							}
							run = 0;
						}
					}
				}
			}
		}
	}

	private void findStairs(byte[] g, int height, int[] surface, int bx, int bz, int minY, List<Segment> out) {
		int need = stairLength.getInt();
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : dirs) {
			for (int z = 1; z <= 16; z++)
				for (int x = 1; x <= 16; x++)
					for (int y = height - 4; y > need; y--) {
						// only start at the top step of a staircase
						if (!step(g, height, x, y, z, surface, d) || step(g, height, x - d[0], y + 1, z - d[1], surface, d)) continue;
						int n = 1;
						while (n < 32) {
							int nx = x + d[0] * n, nz = z + d[1] * n;
							if (nx < 1 || nx > 16 || nz < 1 || nz > 16) break;
							if (!step(g, height, nx, y - n, nz, surface, d)) break;
							n++;
						}
						if (n >= need) {
							int x2 = x + d[0] * (n - 1), z2 = z + d[1] * (n - 1);
							out.add(new Segment(Kind.STAIRS, new AABB(bx + Math.min(x, x2) - 1, y - n + 1 + minY, bz + Math.min(z, z2) - 1,
								bx + Math.max(x, x2), y + 3 + minY, bz + Math.max(z, z2))));
						}
					}
		}
	}

	/** A step: solid floor, 3 open blocks above it, solid walls on both sides. */
	private static boolean step(byte[] g, int height, int x, int y, int z, int[] surface, int[] d) {
		if (x < 1 || x > 16 || z < 1 || z > 16 || y < 1 || y >= height - 3) return false;
		if (y >= surface[(x - 1) + (z - 1) * 16]) return false;
		int sx = d[1] != 0 ? 1 : 0, sz = d[0] != 0 ? 1 : 0; // perpendicular to the stair direction
		return at(g, height, x, y - 1, z) == 1 && at(g, height, x, y, z) == 0 && at(g, height, x, y + 1, z) == 0 && at(g, height, x, y + 2, z) == 0
			&& at(g, height, x - sx, y, z - sz) == 1 && at(g, height, x + sx, y, z + sz) == 1
			&& at(g, height, x - sx, y + 1, z - sz) == 1 && at(g, height, x + sx, y + 1, z + sz) == 1;
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		for (Passages p : results.values()) {
			boolean labeled = false;
			for (Segment s : p.segments()) {
				int color = switch (s.kind()) {
					case SHAFT -> 0xFFFF8A4C;
					case TUNNEL -> 0xFFFFD166;
					case STAIRS -> 0xFF4FD1C5;
				};
				Render3D.box(s.box(), ColorUtil.withAlpha(color, 40), ColorUtil.withAlpha(color, 220), 1.6f, true);
				if (!labeled) {
					labeled = true;
					Render3D.label(new Vec3(s.box().getCenter().x, s.box().maxY + 0.6, s.box().getCenter().z),
						s.kind().name() + (p.segments().size() > 1 ? " +" + (p.segments().size() - 1) : "") + " §7" + WorldUtil.distance(cam.distanceTo(s.box().getCenter())), color, 1f);
				}
			}
		}
	}

	@Override
	public void collectTargets(List<HuntTarget> out) {
		for (Passages p : results.values()) {
			if (p.segments().isEmpty()) continue;
			out.add(new HuntTarget(BlockPos.containing(p.segments().get(0).box().getCenter()), Math.min(55, 15 + p.segments().size() * 12),
				"Dug passages (" + summary(p.segments()) + ")", 0xFFFFD166));
		}
	}

}
