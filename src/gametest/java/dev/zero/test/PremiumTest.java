package dev.zero.test;

import dev.zero.config.ClientSettings;
import dev.zero.gui.ClickGuiScreen;
import dev.zero.gui.Theme;
import dev.zero.hud.Notifications;
import dev.zero.module.Confidence;
import dev.zero.module.HuntTarget;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render2D;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

import java.util.List;

/** 2.2: base confidence, smooth font, themes. */
public class PremiumTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-PREMIUM-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	private static HuntTarget ev(float score, String reason) { return new HuntTarget(BlockPos.ZERO, score, reason, 0xFFFFFFFF); }

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// ---- confidence maths
		int one = Confidence.of(List.of(ev(60, "Player spawner (zombie)"))).percent();
		check(one == 60, "one player spawner = " + one + "%");
		int weak = Confidence.of(List.of(ev(29, "Grown kelp x3"))).percent();
		check(weak < 35 && Confidence.of(List.of(ev(29, "Grown kelp x3"))).label().equals("Weak sign"), "one weak clue stays weak (" + weak + "%)");
		int same = Confidence.of(List.of(ev(29, "Grown kelp x3"), ev(35, "Grown kelp x5"), ev(32, "Grown kelp x4"))).percent();
		check(same == 35, "the same clue three times does not add up (" + same + "%)");
		var strong = Confidence.of(List.of(ev(60, "Player spawner (zombie)"), ev(54, "6 storage blocks"), ev(50, "Sus: 3 kinds of base blocks")));
		check(strong.percent() >= 88 && strong.label().equals("Base"), "spawner + storage + base blocks = " + strong.percent() + "% " + strong.label());
		check(Confidence.of(List.of(ev(90, "a"), ev(90, "b"), ev(90, "c"), ev(90, "d"))).percent() == 99, "never claims 100%");

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			ctx.runOnClient(mc -> { for (var m : ModuleManager.all()) m.setEnabled(false); Notifications.clearToasts(); });
			// hidden base under stone: spawner + storage + workshop
			server.runCommand("tp @a 40 -39 360");
			ctx.waitTicks(40);
			for (int cx = 2; cx <= 3; cx++) server.runCommand("fill " + cx * 16 + " -63 352 " + (cx * 16 + 15) + " -40 367 stone");
			server.runCommand("fill 36 -55 356 44 -53 362 air");
			server.runCommand("setblock 37 -55 357 spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
			for (int i = 0; i < 4; i++) server.runCommand("setblock " + (39 + i) + " -55 357 chest");
			server.runCommand("setblock 43 -55 359 barrel");
			server.runCommand("setblock 39 -55 361 crafting_table");
			server.runCommand("setblock 41 -55 361 furnace");
			server.runCommand("setblock 43 -55 361 anvil");
			server.runCommand("tp @a 10 -39 360 -90 30");
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> {
				ModuleManager.baseRadar.setEnabled(true);
				ModuleManager.storageEsp.setEnabled(true);
				ModuleManager.spawnerFinder.setEnabled(true);
				ModuleManager.susChunk.setEnabled(true);
			});
			ctx.waitTicks(140);
			int top = ctx.computeOnClient(mc -> ModuleManager.baseRadar.topPercent());
			String why = ctx.computeOnClient(mc -> ModuleManager.baseRadar.topWhy());
			check(top >= 80, "hidden base: " + top + "% (" + why + ")");
			ctx.takeScreenshot("confidence");

			// ---- smooth font + themes
			check(ctx.computeOnClient(mc -> Render2D.width("ZERØ client")) > 20, "smooth font measures text");
			ctx.setScreen(ClickGuiScreen::new);
			ctx.waitTicks(30);
			ctx.takeScreenshot("premium_gui");
			for (Theme.Preset p : new Theme.Preset[] {Theme.Preset.NEON, Theme.Preset.EMBER, Theme.Preset.AURORA}) {
				ctx.runOnClient(mc -> ClientSettings.THEME.set(p));
				ctx.waitTicks(4);
				ctx.takeScreenshot("theme_" + p.name().toLowerCase());
			}
			check(ctx.computeOnClient(mc -> ClientSettings.accent()) == Theme.Preset.AURORA.accent, "theme changes the accent color");
			ctx.runOnClient(mc -> { ClientSettings.THEME.set(Theme.Preset.ZERO); ClientSettings.SMOOTH_FONT.set(false); });
			ctx.waitTicks(4);
			ctx.takeScreenshot("pixel_font_gui");
			ctx.runOnClient(mc -> { ClientSettings.SMOOTH_FONT.set(true); mc.setScreen(null); for (var m : ModuleManager.all()) m.setEnabled(false); });
		}
	}
}
