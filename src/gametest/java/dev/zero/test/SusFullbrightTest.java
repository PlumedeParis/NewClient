package dev.zero.test;

import dev.zero.module.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.ChunkPos;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** SusChunk only flags real hidden bases; Fullbright lights a dark cave. */
public class SusFullbrightTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-SUS-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			server.runCommand("time set midnight");
			server.runCommand("gamerule doDaylightCycle false");
			ctx.runOnClient(mc -> { for (var m : ModuleManager.all()) m.setEnabled(false); });
			// solid ground (stone up to y=-40) for chunks 0..7 of row z=2
			for (int cx = 0; cx <= 7; cx++) server.runCommand("fill " + cx * 16 + " -63 32 " + (cx * 16 + 15) + " -40 47 stone");
			// A: hidden base
			server.runCommand("fill 2 -55 36 8 -53 40 air");
			server.runCommand("setblock 3 -55 37 chest");
			server.runCommand("setblock 3 -55 39 chest");
			server.runCommand("setblock 5 -55 37 crafting_table");
			server.runCommand("setblock 6 -55 37 furnace");
			server.runCommand("setblock 7 -55 37 anvil");
			// B: one buried chest
			server.runCommand("fill 36 -55 38 38 -54 40 air");
			server.runCommand("setblock 37 -55 39 chest");
			// D: dug 1x2 tunnel with torches
			server.runCommand("fill 64 -55 40 79 -54 40 air");
			server.runCommand("setblock 66 -55 40 torch");
			server.runCommand("setblock 72 -55 40 torch");
			// C: dungeon (structure)
			server.runCommand("fill 115 -56 36 121 -52 42 mossy_cobblestone");
			server.runCommand("fill 116 -55 37 120 -53 41 air");
			server.runCommand("setblock 118 -55 39 spawner");
			server.runCommand("setblock 116 -55 37 chest");
			server.runCommand("setblock 120 -55 41 chest");
			// E: surface "village": chests + crafting + bell on the grass, leaves overhead
			server.runCommand("setblock 165 -60 38 chest");
			server.runCommand("setblock 167 -60 38 chest");
			server.runCommand("setblock 169 -60 38 crafting_table");
			server.runCommand("setblock 171 -60 38 bell");
			server.runCommand("fill 160 -58 32 175 -58 47 oak_leaves[persistent=true]");
			server.runCommand("tp @a 60 -39 40");
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> {
				// the test area sits next to the 0,0 axis: turn off the highway filter for it
				for (var st : ModuleManager.susChunk.getSettings()) if (st.getName().equals("Ignore Highways")) ((dev.zero.setting.BoolSetting) st).set(false);
				ModuleManager.susChunk.setEnabled(true);
				ModuleManager.susChunk.setModeForTest(true);
			});
			ctx.waitTicks(100);
			ctx.takeScreenshot("sus_base");
			check(score(ctx, 0) >= 20, "hidden base flagged: " + score(ctx, 0) + " (" + reasons(ctx, 0) + ")");
			check(score(ctx, 2) < 0, "single buried chest ignored: " + score(ctx, 2));
			check(score(ctx, 4) < 0, "tunnel + torches alone not a base: " + score(ctx, 4));
			check(score(ctx, 7) < 0, "dungeon ignored: " + score(ctx, 7));
			check(score(ctx, 10) < 0, "surface village-like chests ignored: " + score(ctx, 10));
			int flagged = ctx.computeOnClient(mc -> {
				int n = 0;
				for (int x = -6; x <= 14; x++) for (int z = -6; z <= 8; z++) if (ModuleManager.susChunk.scoreAt(new ChunkPos(x, z)) >= 0) n++;
				return n;
			});
			check(flagged == 1, "only 1 chunk flagged in the whole area (" + flagged + ")");
			ctx.runOnClient(mc -> {
				ModuleManager.susChunk.setModeForTest(false);
				for (var st : ModuleManager.susChunk.getSettings()) if (st.getName().equals("Min Score")) ((dev.zero.setting.NumberSetting) st).set(8.0);
			});
			ctx.waitTicks(40);
			check(score(ctx, 4) > 0, "Activity mode shows the tunnel: " + score(ctx, 4) + " (" + reasons(ctx, 4) + ")");
			ctx.runOnClient(mc -> {
				ModuleManager.susChunk.setModeForTest(true);
				for (var st : ModuleManager.susChunk.getSettings()) {
					if (st.getName().equals("Min Score")) ((dev.zero.setting.NumberSetting) st).set(20.0);
					if (st.getName().equals("Ignore Highways")) ((dev.zero.setting.BoolSetting) st).set(true);
				}
			});

			// Fullbright: dark cave at night
			ctx.runOnClient(mc -> ModuleManager.susChunk.setEnabled(false));
			server.runCommand("fill 1 -55 32 14 -50 34 air");
			server.runCommand("tp @a 4 -55 33 -90 10");
			ctx.waitTicks(40);
			double dark = brightness(ctx.takeScreenshot("fullbright_off"));
			ctx.runOnClient(mc -> ModuleManager.fullbright.setEnabled(true));
			ctx.waitTicks(10);
			double lit = brightness(ctx.takeScreenshot("fullbright_on"));
			check(lit > dark * 2, String.format("Fullbright brightens the cave (%.0f -> %.0f)", dark, lit));
			ctx.runOnClient(mc -> ModuleManager.fullbright.setEnabled(false));
		}
	}

	private static float score(ClientGameTestContext ctx, int cx) {
		return ctx.computeOnClient(mc -> ModuleManager.susChunk.scoreAt(new ChunkPos(cx, 2)));
	}

	private static String reasons(ClientGameTestContext ctx, int cx) {
		return ctx.computeOnClient(mc -> ModuleManager.susChunk.reasonsAt(new ChunkPos(cx, 2)));
	}

	/** Mean brightness of the middle of the screen (HUD excluded). */
	private static double brightness(Path png) {
		try {
			BufferedImage img = ImageIO.read(png.toFile());
			long sum = 0, n = 0;
			for (int y = img.getHeight() / 4; y < img.getHeight() * 3 / 4; y += 2)
				for (int x = img.getWidth() / 4; x < img.getWidth() * 3 / 4; x += 2) {
					int c = img.getRGB(x, y);
					sum += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
					n += 3;
				}
			return sum / (double) n;
		} catch (Exception e) {
			return -1;
		}
	}
}
