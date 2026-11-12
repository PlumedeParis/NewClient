package dev.zero.test;

import dev.zero.gui.BlockColorScreen;
import dev.zero.module.ModuleManager;
import dev.zero.setting.ColorSetting;
import dev.zero.util.BlockColors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.block.Blocks;

/** Custom block colors (coal -> orange) and per-module color settings; no ore labels in SeedOreFinder. */
public class ColorTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-COLOR-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			int defCoal = ctx.computeOnClient(mc -> BlockColors.get(Blocks.COAL_ORE));
			ctx.runOnClient(mc -> mc.setScreen(new BlockColorScreen(null)));
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> ((BlockColorScreen) mc.screen).testSetColor(Blocks.COAL_ORE, 0xFF8A00));
			ctx.waitTicks(5);
			ctx.takeScreenshot("block_colors");
			check(ctx.computeOnClient(mc -> BlockColors.get(Blocks.COAL_ORE)) == 0xFFFF8A00, "coal ore is orange (was " + Integer.toHexString(defCoal) + ")");
			check(ctx.computeOnClient(mc -> BlockColors.get(Blocks.DEEPSLATE_COAL_ORE)) == 0xFFFF8A00, "deepslate coal follows (link)");
			check(ctx.computeOnClient(mc -> BlockColors.get(Blocks.DIAMOND_ORE)) != 0xFFFF8A00, "other ores unchanged");
			check(ctx.computeOnClient(mc -> BlockColors.save().has("minecraft:coal_ore")), "saved in config");
			ctx.runOnClient(mc -> mc.setScreen(null));

			check(ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.getSettings().stream().noneMatch(s -> s.getName().equals("Labels"))), "SeedOreFinder has no ore labels setting");
			String[] mods = {"AmethystFinder", "GrowthFinder", "RotatedDeepslate", "SpawnerFinder", "TunnelFinder", "SusChunk", "BaseRadar", "StorageESP", "SpawnerESP", "PlayerDetector", "Freecam", "BaseFinder"};
			for (String name : mods) {
				long n = ctx.computeOnClient(mc -> ModuleManager.all().stream().filter(m -> m.getName().equals(name)).findFirst()
					.map(m -> m.getSettings().stream().filter(s -> s instanceof ColorSetting).count()).orElse(-1L));
				check(n > 0, name + " has " + n + " color setting(s)");
			}
			ctx.runOnClient(mc -> { BlockColors.resetCustom(Blocks.COAL_ORE); BlockColors.resetCustom(Blocks.DEEPSLATE_COAL_ORE); });
			check(ctx.computeOnClient(mc -> BlockColors.get(Blocks.COAL_ORE)) == defCoal, "reset restores default");
		}
	}
}
