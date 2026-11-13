package dev.zero.test;

import dev.zero.module.ModuleManager;
import dev.zero.modules.XRay;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/** X-Ray + ESP with Sodium installed (skipped when Sodium is not in the mods folder). */
public class SodiumTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-SODIUM-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
		System.out.println("[ZERO-SODIUM-TEST] Sodium installed: " + sodium);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			ctx.runOnClient(mc -> { for (var m : ModuleManager.all()) m.setEnabled(false); });
			// a stone hill with ores inside: one next to an air pocket (exposed), one fully enclosed, plus a chest
			server.runCommand("fill 4 -60 -6 14 -54 6 stone");
			server.runCommand("setblock 8 -58 0 diamond_ore");
			server.runCommand("setblock 9 -58 0 air");
			server.runCommand("setblock 11 -57 3 gold_ore");
			server.runCommand("setblock 6 -57 -3 chest");
			server.runCommand("tp @a -2 -60 0 -90 5");
			ctx.waitTicks(40);
			ctx.takeScreenshot(sodium ? "sodium_before" : "vanilla_before");
			long before = XRay.sodiumHooks();
			ctx.runOnClient(mc -> {
				ModuleManager.xray.setEnabled(true);
				ModuleManager.storageEsp.setEnabled(true);
			});
			ctx.waitTicks(60);
			sp.getClientWorld().waitForChunksRender();
			ctx.takeScreenshot(sodium ? "sodium_xray" : "vanilla_xray");
			long hooks = XRay.sodiumHooks() - before;
			if (sodium) check(hooks > 1000, "X-Ray hooks run inside Sodium's mesher (" + hooks + " blocks judged)");
			else check(hooks == 0, "no Sodium: vanilla hooks only");
			ctx.runOnClient(mc -> {
				ModuleManager.xray.setEnabled(false);
				ModuleManager.storageEsp.setEnabled(false);
			});
			ctx.waitTicks(40);
			sp.getClientWorld().waitForChunksRender();
			ctx.takeScreenshot(sodium ? "sodium_after" : "vanilla_after");
		}
	}
}
