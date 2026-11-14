package dev.zero.test;

import dev.zero.gui.UpdateScreen;
import dev.zero.gui.WhatsNewScreen;
import dev.zero.update.Updater;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Update notice: versions, changelog, the live check against GitHub, "Don't show again" for one version only. */
public class UpdaterTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-UPDATE-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		check(Updater.compare("2.10.0", "2.9.3") == 1 && Updater.compare("2.4.0", "2.4.0") == 0 && Updater.compare("2.3.0", "2.4.0") == -1, "version comparison");
		String notes = Updater.changelog("2.2.0", "2.4.1");
		check(notes.contains("## 2.4.1") && notes.contains("## 2.3.0") && !notes.contains("## 2.2.0"), "changelog from 2.2.0 to 2.4.1 has the newer sections only");
		check(Updater.PAGE.equals("https://github.com/PlumedeParis/NewClient/releases/latest"), "the link opened by Go see is fixed, not taken from downloaded data");

		ctx.runOnClient(mc -> mc.setScreen(new WhatsNewScreen(null, "2.2.0", "2.4.1")));
		ctx.waitTicks(15);
		ctx.takeScreenshot("whats_new");
		ctx.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);

		if (System.getenv("ZERO_UPDATE_LIVE") != null) {
			ctx.runOnClient(mc -> { Updater.setCurrentForTest("2.0.0"); Updater.check(); });
			for (int i = 0; i < 200 && ctx.computeOnClient(mc -> Updater.state() == Updater.State.CHECKING); i++) ctx.waitTicks(2);
			var st = ctx.computeOnClient(mc -> Updater.state());
			check(st == Updater.State.AVAILABLE, "live check finds the release on GitHub: " + st + " " + ctx.computeOnClient(mc -> Updater.error()));
			ctx.runOnClient(mc -> mc.setScreen(new UpdateScreen(null)));
			ctx.waitTicks(10);
			ctx.takeScreenshot("update_available");
			ctx.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			// Don't show again hides this version only
			String v = ctx.computeOnClient(mc -> Updater.info().version());
			ctx.runOnClient(mc -> Updater.hide(v));
			ctx.runOnClient(mc -> mc.setScreen(null));
			ctx.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		}
	}
}
