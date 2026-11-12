package dev.zero.test;

import dev.zero.gui.ClickGuiScreen;
import dev.zero.gui.IntroScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Intro animation: frames along the timeline, then hands over to the ClickGUI. */
public class IntroTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-INTRO-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			ctx.runOnClient(mc -> mc.setScreen(new IntroScreen(new ClickGuiScreen())));
			ctx.waitTicks(3);
			float[] frames = {0.3f, 0.75f, 1.05f, 1.6f, 2.2f, 2.9f, 3.4f, 4.3f, 5.2f, 5.5f, 5.62f, 5.75f};
			for (float f : frames) {
				ctx.runOnClient(mc -> ((IntroScreen) mc.screen).freezeForTest(f));
				ctx.waitTicks(1);
				ctx.takeScreenshot(String.format("intro_%04d", Math.round(f * 1000)));
			}
			ctx.runOnClient(mc -> ((IntroScreen) mc.screen).freezeForTest(-1));
			if (System.getenv("ZERO_INTRO_VIDEO") != null) {
				// every frame of the 6 s at 30 fps in full HD, for the preview video
				ctx.runOnClient(mc -> { mc.getWindow().setWindowed(1920, 1080); org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), 1920, 1080); });
				ctx.waitTicks(20);
				for (int i = 0; i < 180; i++) {
					float f = i / 30f;
					ctx.runOnClient(mc -> ((IntroScreen) mc.screen).freezeForTest(f));
					ctx.waitTicks(1);
					ctx.takeScreenshot(String.format("video_%03d", i));
				}
				ctx.runOnClient(mc -> { ((IntroScreen) mc.screen).seekForTest(5.0f); ((IntroScreen) mc.screen).freezeForTest(-1); });
				ctx.runOnClient(mc -> { mc.getWindow().setWindowed(854, 480); org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), 854, 480); });
				ctx.waitTicks(20);
			}
			ctx.runOnClient(mc -> { if (mc.screen instanceof IntroScreen i) i.seekForTest(5.95f); });
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> mc.screen instanceof ClickGuiScreen), "hands over to the ClickGUI");
			// skip: a click jumps to the reveal
			ctx.runOnClient(mc -> mc.setScreen(new IntroScreen(new ClickGuiScreen())));
			ctx.waitTicks(5);
			ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE);
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> !(mc.screen instanceof IntroScreen intro) || intro.elapsed() >= 5.3f), "any key skips to the reveal");
			ctx.waitTicks(30);
			check(ctx.computeOnClient(mc -> mc.screen instanceof ClickGuiScreen), "GUI open after skip");
			ctx.runOnClient(mc -> mc.setScreen(null));

			// once per join: first GUI open after joining = intro, then the GUI opens normally
			ctx.runOnClient(mc -> dev.zero.gui.IntroScreen.onJoin());
			ctx.runOnClient(mc -> IntroScreen.openGui());
			check(ctx.computeOnClient(mc -> mc.screen instanceof IntroScreen), "first GUI open after joining plays the intro");
			ctx.runOnClient(mc -> mc.setScreen(null));
			ctx.runOnClient(mc -> IntroScreen.openGui());
			check(ctx.computeOnClient(mc -> mc.screen instanceof ClickGuiScreen), "second open: straight to the GUI");
			ctx.runOnClient(mc -> mc.setScreen(null));
		}
		// leave and join another world: the intro is back
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			ctx.runOnClient(mc -> IntroScreen.openGui());
			check(ctx.computeOnClient(mc -> mc.screen instanceof IntroScreen), "rejoining plays the intro again");
			ctx.runOnClient(mc -> mc.setScreen(null));
		}
	}
}
