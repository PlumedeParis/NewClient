package dev.zero.test;

import dev.zero.module.ModuleManager;
import dev.zero.util.Perf;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Cost of every module in a real (generated) world with everything turned on. */
public class PerfTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("ZERO_PERF") == null) return; // measurement run: ZERO_PERF=1 ./gradlew runClientGameTest
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode spectator @a");
			ctx.runOnClient(mc -> {
				for (var m : ModuleManager.all()) m.setEnabled(false);
				String seed = String.valueOf(mc.getSingleplayerServer().overworld().getSeed());
				ModuleManager.seedOreFinder.setSeedText(seed);
			});
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> {
				for (var m : ModuleManager.all()) if (m != ModuleManager.xray && m != ModuleManager.freecam && m != ModuleManager.donutHunter) m.setEnabledSilently(true);
				Perf.reset();
			});
			// phase 1: arriving somewhere new (chunks loading + scanning)
			server.runCommand("tp @a ~600 30 ~600");
			ctx.waitTicks(200);
			print(ctx, "LOADING");
			// phase 2: standing still underground, everything scanned
			ctx.runOnClient(mc -> Perf.reset());
			ctx.waitTicks(200);
			print(ctx, "IDLE");
			// phase 3: moving (new chunks all the time)
			ctx.runOnClient(mc -> Perf.reset());
			for (int i = 0; i < 20; i++) {
				server.runCommand("tp @a ~24 ~ ~");
				ctx.waitTicks(10);
			}
			print(ctx, "MOVING");
			ctx.runOnClient(mc -> { for (var m : ModuleManager.all()) m.setEnabledSilently(false); });
		}
	}

	private static void print(ClientGameTestContext ctx, String phase) {
		ctx.runOnClient(mc -> {
			System.out.println("[ZERO-PERF2] " + phase + " fps=" + mc.getFps());
			System.out.println("[ZERO-PERF2] " + phase + " TICK  " + Perf.report(Perf.TICK_MS));
			System.out.println("[ZERO-PERF2] " + phase + " 3D    " + Perf.report(Perf.R3D_MS));
			System.out.println("[ZERO-PERF2] " + phase + " HUD   " + Perf.report(Perf.HUD_MS));
		});
	}
}
