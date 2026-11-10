package dev.newclient.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.newclient.mixin.RenderTypeAccessor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** Line and quad render types that ignore the depth buffer (through-wall ESP) or respect it. */
public final class EspRenderTypes {
	private EspRenderTypes() {}

	private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("newclient", "pipeline/" + path); }

	public static final RenderPipeline LINES_SEE_THROUGH_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(id("esp_lines"))
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withDepthWrite(false)
		.build();

	public static final RenderPipeline QUADS_SEE_THROUGH_PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("esp_quads"))
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withDepthWrite(false)
		.withCull(false)
		.build();

	public static final RenderPipeline LINES_DEPTH_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(id("esp_lines_depth"))
		.withDepthWrite(false)
		.build();

	public static final RenderPipeline QUADS_DEPTH_PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("esp_quads_depth"))
		.withDepthWrite(false)
		.withCull(false)
		.build();

	public static final RenderType LINES_SEE_THROUGH = create("newclient_esp_lines", LINES_SEE_THROUGH_PIPELINE);
	public static final RenderType QUADS_SEE_THROUGH = create("newclient_esp_quads", QUADS_SEE_THROUGH_PIPELINE);
	public static final RenderType LINES_DEPTH = create("newclient_esp_lines_depth", LINES_DEPTH_PIPELINE);
	public static final RenderType QUADS_DEPTH = create("newclient_esp_quads_depth", QUADS_DEPTH_PIPELINE);

	private static RenderType create(String name, RenderPipeline pipeline) {
		return RenderTypeAccessor.newclient$create(name, RenderSetup.builder(pipeline).bufferSize(RenderType.BIG_BUFFER_SIZE).createRenderSetup());
	}
}
