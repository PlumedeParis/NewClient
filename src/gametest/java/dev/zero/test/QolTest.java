package dev.zero.test;

import dev.zero.config.ClientSettings;
import dev.zero.gui.AlertsScreen;
import dev.zero.gui.ClickGuiScreen;
import dev.zero.gui.SetupScreen;
import dev.zero.hud.Notifications;
import dev.zero.module.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.ChunkPos;

/** 2.0: quieter alerts + history, BaseFinder accuracy, setups, GUI. */
public class QolTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-QOL-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			ctx.runOnClient(mc -> { for (var m : ModuleManager.all()) m.setEnabled(false); });

			// ---------------- alerts
			ctx.runOnClient(mc -> { Notifications.clearToasts(); Notifications.clearHistory(); ClientSettings.ALERTS.set(Notifications.Level.IMPORTANT); });
			ctx.runOnClient(mc -> { for (int i = 0; i < 6; i++) Notifications.sign("Grown kelp", "10 60 " + i, Notifications.Type.FOUND, 6000); });
			check(ctx.computeOnClient(mc -> Notifications.visible()) == 1, "Important: 6 small signs = 1 merged pop-up (" + ctx.computeOnClient(mc -> Notifications.visible()) + ")");
			check(ctx.computeOnClient(mc -> Notifications.history().size()) == 6, "all 6 are in the alert history");
			ctx.waitTicks(10);
			ctx.takeScreenshot("alerts_merged");
			ctx.runOnClient(mc -> { Notifications.clearToasts(); ClientSettings.ALERTS.set(Notifications.Level.MINIMAL); });
			ctx.runOnClient(mc -> { for (int i = 0; i < 6; i++) Notifications.sign("Dug tunnel", "20 30 " + i, Notifications.Type.FOUND, 6000); });
			check(ctx.computeOnClient(mc -> Notifications.visible()) == 0, "Minimal: no pop-up for small signs");
			ctx.runOnClient(mc -> Notifications.push("Base found!", "100 64 100", Notifications.Type.FOUND, 7000));
			check(ctx.computeOnClient(mc -> Notifications.visible()) == 1, "Minimal: a base still pops up");
			ctx.runOnClient(mc -> { Notifications.clearToasts(); ClientSettings.ALERTS.set(Notifications.Level.EVERYTHING); });
			ctx.runOnClient(mc -> { for (int i = 0; i < 6; i++) Notifications.sign("Grown geode", "5 -20 " + i, Notifications.Type.FOUND, 6000); });
			check(ctx.computeOnClient(mc -> Notifications.visible()) == 3, "Everything: one each, capped at Max Pop-ups (3)");
			ctx.runOnClient(mc -> { Notifications.clearToasts(); ClientSettings.ALERTS.set(Notifications.Level.IMPORTANT); });
			ctx.runOnClient(mc -> ModuleManager.fullbright.setEnabled(true));
			check(ctx.computeOnClient(mc -> Notifications.visible()) == 0, "no pop-up when toggling a module (off by default)");
			ctx.runOnClient(mc -> ModuleManager.fullbright.setEnabled(false));

			// ---------------- BaseFinder accuracy (row z = chunk 6, away from spawn)
			int z = 100;
			server.runCommand("tp @a 140 -60 " + z);
			ctx.waitTicks(60);
			// A (chunk 4): village: bell here, houses with beds / chests / furnaces 2 chunks away (chunk 6)
			server.runCommand("setblock 72 -60 " + z + " bell");
			for (int i = 0; i < 4; i++) server.runCommand("setblock " + (98 + i * 2) + " -60 " + z + " chest");
			server.runCommand("setblock 98 -60 " + (z + 2) + " furnace");
			server.runCommand("setblock 100 -60 " + (z + 2) + " crafting_table");
			server.runCommand("setblock 102 -60 " + (z + 2) + " red_bed");
			server.runCommand("setblock 104 -60 " + (z + 2) + " white_bed");
			server.runCommand("setblock 106 -60 " + (z + 2) + " composter");
			// B (chunk 8): shipwreck-like: 3 loose chests
			for (int i = 0; i < 3; i++) server.runCommand("setblock " + (130 + i * 3) + " -60 " + (z + 6) + " chest");
			// C (chunk 10): a real base
			for (int i = 0; i < 4; i++) server.runCommand("setblock " + (162 + i) + " -60 " + z + " chest");
			server.runCommand("setblock 162 -60 " + (z + 2) + " ender_chest");
			server.runCommand("setblock 164 -60 " + (z + 2) + " enchanting_table");
			server.runCommand("setblock 166 -60 " + (z + 2) + " crafting_table");
			server.runCommand("setblock 168 -60 " + (z + 2) + " furnace");
			server.runCommand("setblock 170 -60 " + (z + 2) + " anvil");
			server.runCommand("setblock 172 -60 " + (z + 2) + " hopper");
			// D (chunks 12+13): a base cut in two by a chunk border: each half alone is too weak
			server.runCommand("setblock 206 -60 " + z + " ender_chest");
			server.runCommand("setblock 204 -60 " + z + " chest");
			server.runCommand("setblock 202 -60 " + z + " anvil");
			server.runCommand("setblock 200 -60 " + z + " crafting_table");
			server.runCommand("setblock 198 -60 " + z + " purple_shulker_box");
			server.runCommand("setblock 209 -60 " + z + " hopper");
			server.runCommand("setblock 211 -60 " + z + " chest");
			server.runCommand("setblock 213 -60 " + z + " furnace");
			server.runCommand("setblock 215 -60 " + z + " enchanting_table");
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> ModuleManager.baseFinder.setEnabled(true));
			ctx.waitTicks(120);
			int cz = z >> 4;
			check(kind(ctx, 6, cz) == null, "village houses 2 chunks from the bell are ignored: " + kind(ctx, 6, cz));
			check(kind(ctx, 8, cz) == null, "3 loose chests (shipwreck loot) ignored: " + kind(ctx, 8, cz));
			check("BASE".equals(kind(ctx, 10, cz)), "real base flagged: " + kind(ctx, 10, cz));
			check("BASE".equals(kind(ctx, 12, cz)) || "BASE".equals(kind(ctx, 13, cz)), "base split over two chunks still found: " + kind(ctx, 12, cz) + " / " + kind(ctx, 13, cz));
			ctx.runOnClient(mc -> ModuleManager.baseFinder.setEnabled(false));

			// ---------------- setups
			ctx.runOnClient(mc -> SetupScreen.apply(SetupScreen.PRESETS.get(2)));
			check(ctx.computeOnClient(mc -> ModuleManager.seedOreFinder.isEnabled() && ModuleManager.safeMiner.isEnabled() && !ModuleManager.baseFinder.isEnabled()), "Mining setup turns on the mining modules only");
			ctx.runOnClient(mc -> SetupScreen.apply(SetupScreen.PRESETS.get(4)));
			check(ctx.computeOnClient(mc -> ModuleManager.all().stream().noneMatch(m -> m.isEnabled() && m != ModuleManager.crosshair)), "Everything off");

			// ---------------- screens
			ctx.runOnClient(mc -> Notifications.clearToasts());
			ctx.setScreen(ClickGuiScreen::new);
			ctx.waitTicks(30);
			ctx.takeScreenshot("gui_v2");
			ctx.runOnClient(mc -> mc.setScreen(new SetupScreen(mc.screen)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("setup");
			ctx.runOnClient(mc -> mc.setScreen(new AlertsScreen(null)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("alerts_screen");
			ctx.runOnClient(mc -> mc.setScreen(null));
		}
	}

	private static String kind(ClientGameTestContext ctx, int cx, int cz) {
		return ctx.computeOnClient(mc -> ModuleManager.baseFinder.kindAt(new ChunkPos(cx, cz)));
	}
}
