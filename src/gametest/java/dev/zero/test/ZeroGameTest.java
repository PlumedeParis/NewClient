package dev.zero.test;

import dev.zero.gui.BlockPickerScreen;
import dev.zero.gui.ClickGuiScreen;
import dev.zero.gui.FindsScreen;
import dev.zero.module.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;

/**
 * In-game smoke test (not part of the shipped mod): creates a world, builds a fake stash,
 * base and tunnel, enables every module and takes screenshots of each feature.
 */
public class ZeroGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("tp @a 302 -30 226 180 30");
			ctx.waitTicks(40);
			sp.getClientWorld().waitForChunksRender();
			// The test world is superflat (ground at y=-61): build a hill to dig into.
			server.runCommand("fill 290 -60 186 315 -42 205 stone");
			// A dug tunnel with torches and a hidden chest (SusChunk)
			server.runCommand("fill 290 -55 195 315 -54 195 air");
			server.runCommand("setblock 294 -55 195 torch");
			server.runCommand("setblock 300 -55 195 torch");
			server.runCommand("setblock 306 -55 195 torch");
			server.runCommand("setblock 310 -55 195 chest");
			// Platform with a stash and base blocks (BaseFinder)
			server.runCommand("fill 290 -41 186 315 -41 205 smooth_stone");
			server.runCommand("fill 298 -40 196 304 -40 197 chest");
			server.runCommand("setblock 306 -40 196 purple_shulker_box");
			server.runCommand("setblock 307 -40 196 shulker_box");
			server.runCommand("setblock 308 -40 196 ender_chest");
			server.runCommand("setblock 297 -40 199 beacon");
			server.runCommand("setblock 305 -40 199 oak_sign");
			server.runCommand("setblock 299 -40 199 enchanting_table");
			// Ores buried in the hill (BlockESP)
			server.runCommand("setblock 293 -50 190 diamond_ore");
			server.runCommand("setblock 294 -50 190 diamond_ore");
			server.runCommand("setblock 312 -52 201 ancient_debris");
			server.runCommand("setblock 296 -56 203 emerald_ore");
			server.runCommand("setblock 305 -58 188 deepslate_diamond_ore");
			// Ores exposed in the tunnel walls (real under anti-xray) - enclosed ones above act like fakes.
			server.runCommand("setblock 298 -53 195 diamond_ore");
			server.runCommand("setblock 304 -56 195 gold_ore");
			server.runCommand("setblock 309 -55 196 iron_ore");
			server.runCommand("setblock 301 -54 194 redstone_ore");
			// --- Hunt module scenarios ---
			// Player-placed (sideways) deepslate patching the tunnel
			server.runCommand("fill 296 -55 194 299 -54 194 deepslate[axis=x]");
			// Dug 1x1 shaft and a staircase inside the hill
			server.runCommand("fill 312 -59 190 312 -45 190 air");
			for (int i = 0; i < 8; i++) server.runCommand("fill " + (292 + i) + " " + (-46 - i) + " 201 " + (292 + i) + " " + (-44 - i) + " 201 air");
			// Fully grown geode: budding amethyst wrapped in clusters
			server.runCommand("fill 330 -60 200 336 -54 206 calcite");
			server.runCommand("fill 331 -59 201 335 -55 205 air");
			server.runCommand("fill 333 -58 203 333 -56 203 budding_amethyst");
			server.runCommand("fill 332 -58 203 332 -56 203 amethyst_cluster[facing=west]");
			server.runCommand("fill 334 -58 203 334 -56 203 amethyst_cluster[facing=east]");
			server.runCommand("fill 333 -58 202 333 -56 202 amethyst_cluster[facing=north]");
			server.runCommand("fill 333 -58 204 333 -56 204 amethyst_cluster[facing=south]");
			server.runCommand("setblock 333 -55 203 small_amethyst_bud[facing=up]");
			// Pond with kelp grown past its natural age
			server.runCommand("fill 330 -60 180 336 -52 186 glass");
			server.runCommand("fill 331 -59 181 335 -52 185 water");
			for (int x = 331; x <= 335; x++) server.runCommand("setblock " + x + " -59 183 kelp[age=25]");
			// Pig spawner (DonutSMP style) and a cow farm
			server.runCommand("setblock 320 -60 214 spawner{SpawnData:{entity:{id:\"minecraft:pig\"}}}");
			server.runCommand("fill 318 -60 216 324 -58 222 oak_fence hollow");
			for (int i = 0; i < 20; i++) server.runCommand("summon cow 321 -60 219 {NoAI:1b}");
			server.runCommand("tp @a 302 -30 226 180 30");
			ctx.runOnClient(mc -> mc.player.getAbilities().flying = true);
			ctx.waitTicks(20);

			ctx.runOnClient(mc -> {
				ModuleManager.blockEsp.setEnabled(true);
				((dev.zero.setting.BoolSetting) ModuleManager.blockEsp.getSettings().stream().filter(x -> x.getName().equals("Labels")).findFirst().orElseThrow()).set(true);
				ModuleManager.baseFinder.setEnabled(true);
				ModuleManager.susChunk.setEnabled(true);
				ModuleManager.playerDetector.setEnabled(true);
				ModuleManager.storageEsp.setEnabled(true);
				ModuleManager.amethystFinder.setEnabled(true);
				ModuleManager.growthFinder.setEnabled(true);
				ModuleManager.rotatedDeepslate.setEnabled(true);
				ModuleManager.spawnerFinder.setEnabled(true);
				ModuleManager.tunnelFinder.setEnabled(true);
			});
			ctx.waitTicks(100);
			ctx.takeScreenshot("01_world_modules");

			ctx.runOnClient(mc -> ModuleManager.freecam.setEnabled(true));
			ctx.getInput().holdKeyFor(o -> o.keyJump, 14);
			ctx.getInput().holdKeyFor(o -> o.keyUp, 8);
			ctx.waitTicks(10);
			ctx.computeOnClient(mc -> {
				if (Math.abs(mc.player.getX() - 302.5) > 0.6 || Math.abs(mc.player.getZ() - 226.5) > 0.6)
					throw new AssertionError("Freecam moved the real player! " + mc.player.position());
				return null;
			});
			ctx.takeScreenshot("02_freecam");
			ctx.runOnClient(mc -> ModuleManager.xray.setEnabled(true));
			ctx.waitTicks(40);
			sp.getClientWorld().waitForChunksRender();
			ctx.takeScreenshot("02b_xray");
			ctx.runOnClient(mc -> ModuleManager.xray.setEnabled(false));
			ctx.waitTicks(20);
			ctx.getInput().holdKeyFor(o -> o.keyJump, 16);
			ctx.waitTicks(10);
			ctx.takeScreenshot("02c_storage");
			// Hunt-only view
			ctx.runOnClient(mc -> {
				ModuleManager.blockEsp.setEnabled(false);
				ModuleManager.storageEsp.setEnabled(false);
				ModuleManager.baseFinder.setEnabled(false);
				ModuleManager.susChunk.setEnabled(false);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("02d_hunt");
			ctx.computeOnClient(mc -> {
				String r = "amethyst=" + ModuleManager.amethystFinder.getInfo() + " kelp=" + ModuleManager.growthFinder.getInfo()
					+ " deepslate=" + ModuleManager.rotatedDeepslate.getInfo() + " spawner=" + ModuleManager.spawnerFinder.getInfo()
					+ " tunnels=" + ModuleManager.tunnelFinder.getInfo();
				System.out.println("[ZERO-HUNT-TEST] " + r);
				return null;
			});
			ctx.runOnClient(mc -> ModuleManager.freecam.setEnabled(false));
			ctx.waitTicks(5);

			ctx.setScreen(ClickGuiScreen::new);
			ctx.waitTicks(20);
			double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
			// Right click the first module (BlockESP) to expand its settings.
			ctx.getInput().setCursorPos((16 + 40) * scale, (42 + 20 + 8) * scale);
			ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			ctx.waitTicks(20);
			ctx.getInput().setCursorPos(400 * scale, 300 * scale);
			ctx.takeScreenshot("03_clickgui");

			ctx.setScreen(() -> new BlockPickerScreen(ModuleManager.blockEsp.blocks, null));
			ctx.waitTicks(10);
			ctx.takeScreenshot("04_block_picker");

			ctx.setScreen(() -> new FindsScreen(null));
			ctx.waitTicks(10);
			ctx.takeScreenshot("05_finds");
			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
		}
	}
}
