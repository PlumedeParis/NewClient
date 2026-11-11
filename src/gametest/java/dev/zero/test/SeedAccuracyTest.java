package dev.zero.test;

import dev.zero.module.ModuleManager;
import dev.zero.seed.SeedWorld;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Measures SeedOreFinder against the real world generator: a world is created with a known
 * seed, ZERØ predicts ores from the seed + loaded terrain only, and the predictions are
 * compared with the blocks the integrated server actually generated.
 */
public class SeedAccuracyTest implements FabricClientGameTest {
	private static final String SEED = "8675309";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed(SEED)).create()) {
			ctx.runOnClient(mc -> {
				mc.options.renderDistance().set(5);
				mc.options.simulationDistance().set(5);
			});
			sp.getClientWorld().waitForChunksDownload();
			sp.getServer().runCommand("gamemode spectator @a");
			check(ctx, sp, Level.OVERWORLD, Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), "Diamonds", "tp @a 0 40 0");
			mineCheck(ctx, sp);
			// Holograms (look down through the terrain) + predictions beyond the loaded chunks
			sp.getServer().runCommand("tp @a 0 40 0 30 70");
			ctx.waitTicks(40);
			ctx.takeScreenshot("seed_holograms");
			ctx.waitTicks(60);
			int bio = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.biomeMatchPercent());
			System.out.println("[ZERO-SEED-TEST] biome check with the RIGHT seed: " + bio + "%" + (bio >= 90 ? " OK" : " FAIL"));
			ctx.takeScreenshot("seed_hud_right");
			ctx.runOnClient(mc -> {
				ModuleManager.seedOreFinder.setEnabled(false);
				ModuleManager.seedOreFinder.setSeedText("12345");
				ModuleManager.seedOreFinder.setEnabled(true);
			});
			ctx.waitTicks(200);
			int bioWrong = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.biomeMatchPercent());
			int drawnWrong = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.visibleCount());
			System.out.println("[ZERO-SEED-TEST] biome check with a WRONG seed: " + bioWrong + "%" + (bioWrong >= 0 && bioWrong < 40 ? " OK" : " FAIL")
				+ " | holograms still shown: " + drawnWrong + (drawnWrong > 0 ? " OK" : " FAIL"));
			ctx.takeScreenshot("seed_hud_wrong");
			ctx.runOnClient(mc -> {
				ModuleManager.seedOreFinder.setEnabled(false);
				ModuleManager.seedOreFinder.setSeedText(SEED);
				ModuleManager.seedOreFinder.setEnabled(true);
			});
			ctx.waitTicks(100);
			ctx.waitFor(mc -> ModuleManager.seedOreFinder.idle(), 20 * 120);
			int drawnUnloaded = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.drawnInUnloadedChunks());
			int drawnTotal = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.visibleCount());
			System.out.println("[ZERO-SEED-TEST] holograms drawn: " + drawnTotal + ", of which in chunks the game has NOT loaded: " + drawnUnloaded
				+ (drawnUnloaded > 0 ? " OK" : " FAIL"));
			check(ctx, sp, Level.OVERWORLD, Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), "Iron", null);
			check(ctx, sp, Level.OVERWORLD, Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE), "Redstone", null);
			check(ctx, sp, Level.NETHER, Set.of(Blocks.ANCIENT_DEBRIS), "Ancient debris", "execute in minecraft:the_nether run tp @a 0 40 0");
			check(ctx, sp, Level.NETHER, Set.of(Blocks.NETHER_GOLD_ORE), "Nether gold", null);
		}
	}

	/** Per-feature precision on the center chunk's own features. */
	private void perTask(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		var names = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.predictor().world().taskNames());
		ChunkPos center = ctx.computeOnClient(mc -> mc.player.chunkPosition());
		for (int t = 0; t < names.size(); t++) {
			final int ti = t;
			java.util.List<SeedWorld.Prediction> all = new java.util.ArrayList<>();
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++) {
					final int cx = center.x + dx, cz = center.z + dz;
					ctx.runOnClient(mc -> {
						var pr = ModuleManager.seedOreFinder.predictor();
						pr.world().onlyTask = ti;
						pr.forget(ChunkPos.asLong(cx, cz));
						pr.submit(mc.level, cx, cz, Set.of());
					});
					ctx.waitFor(mc -> !ModuleManager.seedOreFinder.predictor().isPending(ChunkPos.asLong(cx, cz)), 2000);
					all.addAll(ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.predictor().results().get(ChunkPos.asLong(cx, cz)).predictions()));
				}
			int[] r = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				int hit = 0;
				for (SeedWorld.Prediction p : all) if (level.getBlockState(p.pos()).getBlock() == p.block()) hit++;
				return new int[] {hit, all.size()};
			});
			System.out.printf("[ZERO-SEED-TEST] task %s: %d/%d correct%n", names.get(t), r[0], r[1]);
		}
		ctx.runOnClient(mc -> ModuleManager.seedOreFinder.predictor().world().onlyTask = -1);
	}

	/** Mining a predicted diamond must remove its box right away. */
	private void mineCheck(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		BlockPos target = ctx.computeOnClient(mc -> {
			for (SeedWorld.Prediction p : ModuleManager.seedOreFinder.snapshot(true))
				if ((p.block() == Blocks.DIAMOND_ORE || p.block() == Blocks.DEEPSLATE_DIAMOND_ORE) && Math.sqrt(p.pos().distToCenterSqr(mc.gameRenderer.getMainCamera().position())) < 85) return p.pos();
			return null;
		});
		if (target == null) {
			System.out.println("[ZERO-SEED-TEST] mine check: no diamond in range");
			return;
		}
		ctx.waitTicks(5);
		int before = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.visibleCount());
		sp.getServer().runCommand("setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " air");
		ctx.waitTicks(3);
		int after = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.visibleCount());
		System.out.println("[ZERO-SEED-TEST] mine check: boxes before=" + before + " after=" + after + (after == before - 1 ? " OK" : " FAIL"));
	}

	private void check(ClientGameTestContext ctx, TestSingleplayerContext sp, ResourceKey<Level> dim, Set<Block> ores, String name, String tp) {
		if (tp != null) {
			sp.getServer().runCommand(tp);
			ctx.waitTicks(300);
			ctx.runOnClient(mc -> {
				var m = ModuleManager.seedOreFinder;
				m.setEnabled(false);
				m.setSeedText(SEED);
				m.setEnabled(true);
			});
			for (int i = 0; i < 60; i++) {
				ctx.waitTicks(40);
				String st = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.status() + " preds=" + ModuleManager.seedOreFinder.snapshot(false).size()
					+ " real=" + ModuleManager.seedOreFinder.snapshot(true).size() + " enabled=" + ModuleManager.seedOreFinder.isEnabled());
				System.out.println("[ZERO-SEED-TEST] status " + st);
				if (ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.idle() && !ModuleManager.seedOreFinder.snapshot(true).isEmpty())) break;
			}
			ctx.waitTicks(200);
			ctx.waitFor(mc -> ModuleManager.seedOreFinder.idle(), 20 * 120);
		}
		ChunkPos center = ctx.computeOnClient(mc -> mc.player.chunkPosition());
		List<SeedWorld.Prediction> preds = ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.snapshot(true));
		// Only judge the inner 3x3 chunks: their whole neighbourhood was loaded and simulated.
		Set<BlockPos> predicted = new HashSet<>();
		for (SeedWorld.Prediction p : preds) {
			if (!ores.contains(p.block())) continue;
			if (Math.abs((p.pos().getX() >> 4) - center.x) <= 1 && Math.abs((p.pos().getZ() >> 4) - center.z) <= 1) predicted.add(p.pos());
		}
		int[] r = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(dim);
			int hit = 0;
			java.util.Map<String, Integer> wrong = new java.util.TreeMap<>();
			for (BlockPos p : predicted) {
				if (ores.contains(level.getBlockState(p).getBlock())) hit++;
				else wrong.merge(level.getBlockState(p).getBlock().toString() + "@" + (p.getY() < 0 ? "neg" : "pos"), 1, Integer::sum);
			}

			int actual = 0;
			java.util.Map<String, Integer> missed = new java.util.TreeMap<>();
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++) {
					LevelChunk c = level.getChunk(center.x + dx, center.z + dz);
					LevelChunkSection[] secs = c.getSections();
					for (int i = 0; i < secs.length; i++)
						for (int y = 0; y < 16; y++)
							for (int z = 0; z < 16; z++)
								for (int x = 0; x < 16; x++)
									if (ores.contains(secs[i].getBlockState(x, y, z).getBlock())) {
										actual++;
										BlockPos ap = new BlockPos(c.getPos().getMinBlockX() + x, c.getSectionYFromSectionIndex(i) * 16 + y, c.getPos().getMinBlockZ() + z);
										if (!predicted.contains(ap)) {
											StringBuilder nb = new StringBuilder();
											for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
												var st = level.getBlockState(ap.relative(d));
												if (!st.isSolidRender()) nb.append(st.getBlock().toString().replace("Block{minecraft:", "").replace("}", "")).append(' ');
											}
											missed.merge("y" + (ap.getY() >> 4 << 4) + " nb[" + nb + "]", 1, Integer::sum);
										}
									}
				}

			return new int[] {hit, actual};
		});
		double precision = predicted.isEmpty() ? 0 : 100.0 * r[0] / predicted.size();
		double recall = r[1] == 0 ? 0 : 100.0 * r[0] / r[1];
		System.out.printf("[ZERO-SEED-TEST] %s: predicted=%d actual=%d correct=%d precision=%.1f%% recall=%.1f%%%n", name, predicted.size(), r[1], r[0], precision, recall);
		ctx.takeScreenshot("seed_" + name.replace(' ', '_').toLowerCase());
	}
}
