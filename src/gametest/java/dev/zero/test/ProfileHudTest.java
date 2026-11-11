package dev.zero.test;

import dev.zero.config.ConfigManager;
import dev.zero.gui.HudEditorScreen;
import dev.zero.gui.ProfilesScreen;
import dev.zero.hud.HudLayout;
import dev.zero.hud.HudLayoutAccess;
import dev.zero.module.ModuleManager;
import dev.zero.util.WorldUtil;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Title-screen branding, config profiles and the HUD editor. */
public class ProfileHudTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-PROFILE-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.waitTicks(20);
		ctx.takeScreenshot("title_screen");
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			ctx.waitTicks(20);
			// --- Profiles ---
			ctx.runOnClient(mc -> {
				for (var m : ModuleManager.all()) m.setEnabled(false);
				ModuleManager.blockEsp.setEnabled(true);
				ModuleManager.crosshair.setEnabled(true);
				ConfigManager.save();
			});
			String start = ctx.computeOnClient(mc -> ConfigManager.activeProfile());
			ctx.runOnClient(mc -> {
				ConfigManager.duplicate("Hunting");
				ModuleManager.blockEsp.setEnabled(false);
				ModuleManager.baseRadar.setEnabled(true);
			});
			check(ctx.computeOnClient(mc -> ConfigManager.activeProfile()).equals("Hunting"), "duplicate switches to the new profile");
			ctx.runOnClient(mc -> ConfigManager.switchTo(start));
			check(ctx.computeOnClient(mc -> ModuleManager.blockEsp.isEnabled() && !ModuleManager.baseRadar.isEnabled()), "switching back restores " + start + " (BlockESP on, BaseRadar off)");
			ctx.runOnClient(mc -> ConfigManager.switchTo("Hunting"));
			check(ctx.computeOnClient(mc -> !ModuleManager.blockEsp.isEnabled() && ModuleManager.baseRadar.isEnabled() && ModuleManager.crosshair.isEnabled()), "Hunting keeps its own modules (BlockESP off, BaseRadar + Crosshair on)");
			ctx.runOnClient(mc -> ConfigManager.createBlank("Fresh"));
			check(ctx.computeOnClient(mc -> ModuleManager.all().stream().noneMatch(m -> m.isEnabled())), "a new blank profile starts with every module off");
			ctx.runOnClient(mc -> ConfigManager.rename("Clean"));
			check(ctx.computeOnClient(mc -> ConfigManager.profiles().contains("Clean") && !ConfigManager.profiles().contains("Fresh")), "rename");
			ctx.runOnClient(mc -> {
				ConfigManager.switchTo("Hunting");
				ConfigManager.delete("Clean");
			});
			check(ctx.computeOnClient(mc -> !ConfigManager.profiles().contains("Clean")), "delete");
			ctx.runOnClient(mc -> ConfigManager.setServerProfile(WorldUtil.serverId(), "Hunting"));
			check(ctx.computeOnClient(mc -> "Hunting".equals(ConfigManager.profileForServer(WorldUtil.serverId()))), "auto-load profile remembered for this server");
			ctx.setScreen(() -> new ProfilesScreen(null));
			ctx.waitTicks(10);
			ctx.takeScreenshot("profiles_screen");
			ctx.setScreen(() -> null);

			// --- HUD editor ---
			ctx.runOnClient(mc -> ModuleManager.seedOreFinder.setEnabled(true));
			ctx.waitTicks(5);
			ctx.setScreen(() -> new HudEditorScreen(null));
			ctx.waitTicks(10);
			ctx.takeScreenshot("hud_editor");
			ctx.runOnClient(mc -> HudLayout.set("coords", 1, 1));
			ctx.waitTicks(3);
			int[] coords = ctx.computeOnClient(mc -> HudLayoutAccess.last().get("coords"));
			int[] screen = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
			check(coords != null && coords[0] + coords[2] >= screen[0] - 1 && coords[1] + coords[3] >= screen[1] - 1, "coords panel moved to the bottom-right corner");
			ctx.takeScreenshot("hud_editor_moved");
			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
			ctx.takeScreenshot("hud_custom_layout");
			ctx.runOnClient(mc -> {
				HudLayout.clear();
				// leave no state behind for other tests
				ConfigManager.setServerProfile(WorldUtil.serverId(), null);
				ConfigManager.switchTo(start);
				ConfigManager.delete("Hunting");
			});
		}
	}
}
