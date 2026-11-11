package dev.zero.test;

import dev.zero.hud.LookInfo;
import dev.zero.module.ModuleManager;
import dev.zero.setting.EnumSetting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.block.Blocks;

/** Crosshair "look info" label through walls + SeedOreFinder ore type filter. */
public class LookInfoTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-LOOK-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			// A stone wall between the player and the targets (everything is seen through it).
			server.runCommand("fill -3 -60 -3 3 -55 -3 stone");
			server.runCommand("tp @a 0.5 -60 0.5 180 0");
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> {
				for (var m : ModuleManager.all()) m.setEnabled(false);
				ModuleManager.storageEsp.setEnabled(true);
				ModuleManager.blockEsp.setEnabled(true);
				ModuleManager.spawnerEsp.setEnabled(true);
			});
			String[][] cases = {
				{"chest", "Chest"},
				{"diamond_ore", "Diamond Ore"},
				{"deepslate_diamond_ore", "Deepslate Diamond Ore"},
				{"hopper", "Hopper"},
				{"spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}", "Zombie Spawner"},
			};
			for (String[] c : cases) {
				server.runCommand("setblock 0 -59 -7 " + c[0]);
				ctx.waitTicks(30);
				String seen = ctx.computeOnClient(mc -> LookInfo.current());
				check(seen != null && seen.startsWith(c[1]), "aiming through a wall at " + c[1] + " shows: " + seen);
				if (c[1].equals("Deepslate Diamond Ore")) ctx.takeScreenshot("look_info");
				server.runCommand("setblock 0 -59 -7 air");
			}
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> LookInfo.current()) == null, "nothing highlighted -> no label");

			// SeedOreFinder ore type
			ctx.runOnClient(mc -> setOreType("DEEPSLATE_ONLY"));
			check(ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.wantedBlocks().contains(Blocks.DEEPSLATE_DIAMOND_ORE)
				&& !ModuleManager.seedOreFinder.wantedBlocks().contains(Blocks.DIAMOND_ORE)), "Ore Type = Deepslate only");
			ctx.runOnClient(mc -> setOreType("NORMAL_ONLY"));
			check(ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.wantedBlocks().contains(Blocks.DIAMOND_ORE)
				&& !ModuleManager.seedOreFinder.wantedBlocks().contains(Blocks.DEEPSLATE_DIAMOND_ORE)
				&& ModuleManager.seedOreFinder.wantedBlocks().contains(Blocks.ANCIENT_DEBRIS)), "Ore Type = Normal only (debris unaffected)");
			ctx.runOnClient(mc -> setOreType("BOTH"));
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setOreType(String value) {
		for (var s : ModuleManager.seedOreFinder.getSettings()) {
			if (s.getName().equals("Ore Type")) {
				EnumSetting e = (EnumSetting) s;
				for (Object v : e.getValues()) if (((Enum<?>) v).name().equals(value)) e.set((Enum) v);
			}
		}
	}
}
