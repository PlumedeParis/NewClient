package dev.zero.test;

import dev.zero.gui.UpdateScreen;
import dev.zero.gui.WhatsNewScreen;
import dev.zero.update.SwapHelper;
import dev.zero.update.Updater;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Updater: versions, changelog, the jar swap helper (real separate process), and a live check against GitHub. */
public class UpdaterTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-UPDATE-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		check(Updater.compare("2.10.0", "2.9.3") == 1 && Updater.compare("2.4.0", "2.4.0") == 0 && Updater.compare("2.3.0", "2.4.0") == -1, "version comparison");
		String notes = Updater.changelog("2.2.0", "2.4.0");
		check(notes.contains("## 2.4.0") && notes.contains("## 2.3.0") && !notes.contains("## 2.2.0"), "changelog from 2.2.0 to 2.4.0 has 2.4.0 + 2.3.0 only");

		// ---- the swap helper, as a real separate Java process
		try {
			Path root = Files.createTempDirectory("zero-update-test");
			Path mods = Files.createDirectories(root.resolve("mods")), upd = Files.createDirectories(root.resolve("update"));
			Path old = mods.resolve("zero-2.3.0.jar"), fresh = upd.resolve("zero-2.4.0.jar"), target = mods.resolve("zero-2.4.0.jar");
			Files.writeString(old, "OLD");
			Files.writeString(fresh, "NEW");
			Files.writeString(upd.resolve("pending.json"), "{}");
			Path relaunched = root.resolve("relaunched.txt");
			Process game = new ProcessBuilder("sleep", "2").start(); // stands in for Minecraft
			String cp = Path.of(SwapHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
			String java = ProcessHandle.current().info().command().orElse("java");
			Process helper = new ProcessBuilder(java, "-cp", cp, SwapHelper.class.getName(), String.valueOf(game.pid()), old.toString(), fresh.toString(),
				target.toString(), upd.resolve("update.log").toString(), "1", root.toString()).start();
			try (var w = new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8)) {
				for (String a : List.of("touch", relaunched.toString())) w.write(Base64.getEncoder().encodeToString(a.getBytes(StandardCharsets.UTF_8)) + "\n");
			}
			Thread.sleep(800);
			check(Files.exists(old) && !Files.exists(target), "nothing is touched while the game is still running");
			helper.waitFor();
			Thread.sleep(500);
			check(!Files.exists(old) && Files.readString(target).equals("NEW"), "after the game closed: old jar removed, new jar installed");
			check(!Files.exists(fresh) && !Files.exists(upd.resolve("pending.json")), "download and pending file cleaned up");
			check(Files.exists(relaunched), "the game is started again (Update now)");
			System.out.println("[ZERO-UPDATE-TEST] log: " + Files.readString(upd.resolve("update.log")).replace("\n", " | "));
		} catch (Exception e) {
			check(false, "swap helper: " + e);
		}

		// ---- screens
		ctx.runOnClient(mc -> mc.setScreen(new WhatsNewScreen(null, "2.2.0", "2.4.0")));
		ctx.waitTicks(15);
		ctx.takeScreenshot("whats_new");
		ctx.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);

		// ---- live: pretend to be 2.3.0 and ask GitHub
		if (System.getenv("ZERO_UPDATE_LIVE") != null) {
			ctx.runOnClient(mc -> { Updater.setCurrentForTest("2.3.0"); Updater.check(); });
			for (int i = 0; i < 200 && ctx.computeOnClient(mc -> Updater.state() == Updater.State.CHECKING); i++) ctx.waitTicks(2);
			var st = ctx.computeOnClient(mc -> Updater.state());
			check(st == Updater.State.AVAILABLE, "live check finds the release on GitHub: " + st + " " + ctx.computeOnClient(mc -> Updater.error()));
			ctx.runOnClient(mc -> mc.setScreen(new UpdateScreen(null)));
			ctx.waitTicks(10);
			ctx.takeScreenshot("update_available");
			ctx.runOnClient(mc -> Updater.download(false));
			for (int i = 0; i < 600 && ctx.computeOnClient(mc -> Updater.state() == Updater.State.DOWNLOADING); i++) ctx.waitTicks(2);
			st = ctx.computeOnClient(mc -> Updater.state());
			check(st == Updater.State.READY, "real download + SHA-256 check: " + st + " " + ctx.computeOnClient(mc -> Updater.error()));
			ctx.waitTicks(5);
			ctx.takeScreenshot("update_ready");
			ctx.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		}
	}
}
