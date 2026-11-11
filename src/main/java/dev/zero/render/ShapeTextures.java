package dev.zero.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Small textures generated once (anti-aliased disc, soft glow, gradient ramps). The HUD and
 * GUI stretch and tint these instead of drawing hundreds of 1-pixel rectangles per frame.
 */
public final class ShapeTextures {
	public static final int SIZE = 64;
	public static final Identifier CIRCLE = Identifier.fromNamespaceAndPath("zero", "shape/circle");
	public static final Identifier GLOW = Identifier.fromNamespaceAndPath("zero", "shape/glow");
	public static final Identifier RAMP = Identifier.fromNamespaceAndPath("zero", "shape/ramp");      // alpha 0 -> 255, left to right
	public static final Identifier RAMP_REV = Identifier.fromNamespaceAndPath("zero", "shape/ramp_rev");

	private static boolean ready;

	private ShapeTextures() {}

	private static final class LinearTexture extends DynamicTexture {
		LinearTexture(String name, NativeImage image) {
			super(() -> name, image);
			this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
		}
	}

	/** Creates the textures on first use (must be on the render thread). */
	public static void ensure() {
		if (ready) return;
		ready = true;
		var tm = Minecraft.getInstance().getTextureManager();

		NativeImage circle = new NativeImage(SIZE, SIZE, false);
		NativeImage glow = new NativeImage(SIZE, SIZE, false);
		double c = SIZE / 2.0;
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				// 4x4 supersampling for a clean anti-aliased edge
				int inside = 0;
				for (int sy = 0; sy < 4; sy++)
					for (int sx = 0; sx < 4; sx++) {
						double dx = x + (sx + 0.5) / 4 - c, dy = y + (sy + 0.5) / 4 - c;
						if (dx * dx + dy * dy <= c * c) inside++;
					}
				circle.setPixel(x, y, (inside * 255 / 16) << 24 | 0xFFFFFF);
				double d = Math.sqrt(Math.pow(x + 0.5 - c, 2) + Math.pow(y + 0.5 - c, 2)) / c; // 0 center .. 1 edge
				double a = d >= 1 ? 0 : Math.pow(1 - d, 2.2);
				glow.setPixel(x, y, (int) Math.round(a * 255) << 24 | 0xFFFFFF);
			}
		tm.register(CIRCLE, new LinearTexture("zero circle", circle));
		tm.register(GLOW, new LinearTexture("zero glow", glow));

		NativeImage ramp = new NativeImage(SIZE, 1, false);
		NativeImage rampRev = new NativeImage(SIZE, 1, false);
		for (int x = 0; x < SIZE; x++) {
			int a = Math.round(x * 255f / (SIZE - 1));
			ramp.setPixel(x, 0, a << 24 | 0xFFFFFF);
			rampRev.setPixel(x, 0, (255 - a) << 24 | 0xFFFFFF);
		}
		tm.register(RAMP, new LinearTexture("zero ramp", ramp));
		tm.register(RAMP_REV, new LinearTexture("zero ramp rev", rampRev));
	}
}
