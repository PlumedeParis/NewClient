package dev.zero.test;

import dev.zero.module.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** DonutHunter: helpers on/off, area verdicts after teleports, already-checked warning. */
public class DonutHunterTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-DONUT-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			ctx.runOnClient(mc -> { for (var m : ModuleManager.all()) m.setEnabled(false); });
			// a hidden base 2 chunks away: spawner, storage, workshop, buried under stone
			for (int cx = 2; cx <= 3; cx++) server.runCommand("fill " + cx * 16 + " -63 32 " + (cx * 16 + 15) + " -40 47 stone");
			server.runCommand("fill 36 -55 36 44 -53 42 air");
			server.runCommand("setblock 37 -55 37 spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
			server.runCommand("setblock 39 -55 37 chest");
			server.runCommand("setblock 41 -55 37 chest");
			server.runCommand("setblock 43 -55 37 barrel");
			server.runCommand("setblock 39 -55 41 crafting_table");
			server.runCommand("setblock 41 -55 41 furnace");
			server.runCommand("tp @a 40 -39 40");
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> {
				for (var st : ModuleManager.donutHunter.getSettings()) if (st.getName().equals("Settle Time")) ((dev.zero.setting.NumberSetting) st).set(2.0);
				ModuleManager.donutHunter.setEnabled(true);
			});
			check(ctx.computeOnClient(mc -> ModuleManager.spawnerFinder.isEnabled() && ModuleManager.storageEsp.isEnabled() && ModuleManager.susChunk.isEnabled() && ModuleManager.baseRadar.isEnabled()), "turns on the finders");
			waitSettled(ctx);
			String v = ctx.computeOnClient(mc -> ModuleManager.donutHunter.verdictText());
			check(ctx.computeOnClient(mc -> ModuleManager.donutHunter.leadCount()) >= 1, "base area -> lead: " + v);
			ctx.takeScreenshot("donut_hunter");

			// "/rtp" far away into empty land
			server.runCommand("tp @a 3000 -60 3000");
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> !ModuleManager.donutHunter.settled()), "teleport starts a new area");
			waitSettled(ctx);
			v = ctx.computeOnClient(mc -> ModuleManager.donutHunter.verdictText());
			check(ctx.computeOnClient(mc -> ModuleManager.donutHunter.leadCount()) == 0 && v.contains("Nothing here"), "empty area -> " + v);
			check(ctx.computeOnClient(mc -> ModuleManager.donutHunter.areasChecked()) == 2, "2 areas checked");

			// back to the first place -> already checked
			server.runCommand("tp @a 3010 -60 3000");
			ctx.waitTicks(5);
			server.runCommand("tp @a 40 -39 40");
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> ModuleManager.donutHunter.alreadyChecked()), "warns: already checked here");

			ctx.runOnClient(mc -> ModuleManager.donutHunter.setEnabled(false));
			check(ctx.computeOnClient(mc -> !ModuleManager.spawnerFinder.isEnabled() && !ModuleManager.storageEsp.isEnabled() && !ModuleManager.baseRadar.isEnabled()), "turning it off restores the other modules");
		}
	}

	private static void waitSettled(ClientGameTestContext ctx) {
		for (int i = 0; i < 300; i++) {
			if (ctx.computeOnClient(mc -> ModuleManager.donutHunter.settled())) return;
			ctx.waitTicks(2);
		}
	}
}
