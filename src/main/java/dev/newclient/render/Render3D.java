package dev.newclient.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.newclient.util.ColorUtil;
import dev.newclient.util.WorldUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Batched 3D renderer for ESP: boxes, lines, tracers, billboard labels and waypoints.
 * Modules queue shapes from {@code onRender3D}; everything is flushed at the end of the
 * main world pass with see-through (no depth test) render types.
 */
public final class Render3D {
	private Render3D() {}

	private record Quad(float[] v, int color, boolean walls) {}
	private record Line(float x1, float y1, float z1, float x2, float y2, float z2, int color, float width, boolean walls) {}
	private record Label(Vec3 pos, String text, int color, float scale) {}

	private static final List<Quad> QUADS = new ArrayList<>();
	private static final List<Line> LINES = new ArrayList<>();
	private static final List<Label> LABELS = new ArrayList<>();
	private static Vec3 cam = Vec3.ZERO;
	private static float lineScale = 1f;

	/** Called before modules render. */
	public static void begin() {
		Minecraft mc = Minecraft.getInstance();
		cam = mc.gameRenderer.getMainCamera().position();
		lineScale = mc.getWindow().getAppropriateLineWidth() / 2.5f;
		QUADS.clear();
		LINES.clear();
		LABELS.clear();
	}

	/** Draws everything queued this frame. */
	public static void flush(MultiBufferSource.BufferSource buffers) {
		if (QUADS.isEmpty() && LINES.isEmpty() && LABELS.isEmpty()) return;
		PoseStack poseStack = new PoseStack();
		PoseStack.Pose pose = poseStack.last();

		for (int pass = 0; pass < 2; pass++) {
			boolean walls = pass == 1;
			RenderType quadType = walls ? EspRenderTypes.QUADS_SEE_THROUGH : EspRenderTypes.QUADS_DEPTH;
			VertexConsumer vc = null;
			for (Quad q : QUADS) {
				if (q.walls != walls) continue;
				if (vc == null) vc = buffers.getBuffer(quadType);
				float[] v = q.v;
				for (int i = 0; i < 12; i += 3) vc.addVertex(pose, v[i], v[i + 1], v[i + 2]).setColor(q.color);
			}
			if (vc != null) buffers.endBatch(quadType);

			RenderType lineType = walls ? EspRenderTypes.LINES_SEE_THROUGH : EspRenderTypes.LINES_DEPTH;
			vc = null;
			for (Line l : LINES) {
				if (l.walls != walls) continue;
				if (vc == null) vc = buffers.getBuffer(lineType);
				float nx = l.x2 - l.x1, ny = l.y2 - l.y1, nz = l.z2 - l.z1;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-6f) continue;
				nx /= len;
				ny /= len;
				nz /= len;
				float w = l.width * lineScale;
				vc.addVertex(pose, l.x1, l.y1, l.z1).setColor(l.color).setNormal(pose, nx, ny, nz).setLineWidth(w);
				vc.addVertex(pose, l.x2, l.y2, l.z2).setColor(l.color).setNormal(pose, nx, ny, nz).setLineWidth(w);
			}
			if (vc != null) buffers.endBatch(lineType);
		}

		if (!LABELS.isEmpty()) {
			Minecraft mc = Minecraft.getInstance();
			Font font = mc.font;
			Camera camera = mc.gameRenderer.getMainCamera();
			for (Label l : LABELS) {
				poseStack.pushPose();
				poseStack.translate((float) (l.pos.x - cam.x), (float) (l.pos.y - cam.y), (float) (l.pos.z - cam.z));
				poseStack.mulPose(camera.rotation());
				float s = l.scale;
				poseStack.scale(s, -s, s);
				Matrix4f m = poseStack.last().pose();
				float x = -font.width(l.text) / 2f;
				font.drawInBatch(l.text, x, 0, l.color, false, m, buffers, Font.DisplayMode.SEE_THROUGH, 0x90101016, 0xF000F0);
				poseStack.popPose();
			}
			buffers.endBatch();
		}
		QUADS.clear();
		LINES.clear();
		LABELS.clear();
	}

	// ---- Shape API --------------------------------------------------------------------------

	public static void box(AABB box, int fill, int line, float lineWidth, boolean throughWalls) {
		float x1 = (float) (box.minX - cam.x), y1 = (float) (box.minY - cam.y), z1 = (float) (box.minZ - cam.z);
		float x2 = (float) (box.maxX - cam.x), y2 = (float) (box.maxY - cam.y), z2 = (float) (box.maxZ - cam.z);
		if (ColorUtil.a(fill) > 0) {
			quad(fill, throughWalls, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2); // bottom
			quad(fill, throughWalls, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1); // top
			quad(fill, throughWalls, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1); // north
			quad(fill, throughWalls, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2); // south
			quad(fill, throughWalls, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1); // west
			quad(fill, throughWalls, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2); // east
		}
		if (ColorUtil.a(line) > 0 && lineWidth > 0) {
			rawLine(x1, y1, z1, x2, y1, z1, line, lineWidth, throughWalls);
			rawLine(x2, y1, z1, x2, y1, z2, line, lineWidth, throughWalls);
			rawLine(x2, y1, z2, x1, y1, z2, line, lineWidth, throughWalls);
			rawLine(x1, y1, z2, x1, y1, z1, line, lineWidth, throughWalls);
			rawLine(x1, y2, z1, x2, y2, z1, line, lineWidth, throughWalls);
			rawLine(x2, y2, z1, x2, y2, z2, line, lineWidth, throughWalls);
			rawLine(x2, y2, z2, x1, y2, z2, line, lineWidth, throughWalls);
			rawLine(x1, y2, z2, x1, y2, z1, line, lineWidth, throughWalls);
			rawLine(x1, y1, z1, x1, y2, z1, line, lineWidth, throughWalls);
			rawLine(x2, y1, z1, x2, y2, z1, line, lineWidth, throughWalls);
			rawLine(x2, y1, z2, x2, y2, z2, line, lineWidth, throughWalls);
			rawLine(x1, y1, z2, x1, y2, z2, line, lineWidth, throughWalls);
		}
	}

	private static void quad(int color, boolean walls, float... v) {
		QUADS.add(new Quad(v, color, walls));
	}

	private static void rawLine(float x1, float y1, float z1, float x2, float y2, float z2, int color, float width, boolean walls) {
		LINES.add(new Line(x1, y1, z1, x2, y2, z2, color, width, walls));
	}

	public static void line(Vec3 a, Vec3 b, int color, float width, boolean throughWalls) {
		rawLine((float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z), (float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z), color, width, throughWalls);
	}

	/** A line from just in front of the camera to the target, i.e. a tracer. */
	public static void tracer(Vec3 target, int color, float width) {
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vector3fc look = camera.forwardVector();
		Vec3 start = cam.add(look.x() * 0.5, look.y() * 0.5, look.z() * 0.5);
		line(start, target, color, width, true);
	}

	/** Billboard text with a dark backdrop that keeps a near-constant on-screen size. */
	public static void label(Vec3 pos, String text, int color, float sizeMul) {
		Minecraft mc = Minecraft.getInstance();
		double dist = Math.max(2, cam.distanceTo(pos));
		// World size of one GUI pixel at this distance -> labels look exactly like HUD text.
		double fov = Math.toRadians(mc.options.fov().get());
		double worldPerPixel = dist * 2 * Math.tan(fov / 2) / Math.max(1, mc.getWindow().getHeight());
		float scale = (float) (worldPerPixel * mc.getWindow().getGuiScale() * 0.85 * sizeMul);
		LABELS.add(new Label(pos, text, color, scale));
	}

	/** Flat square on a horizontal plane, used for chunk overlays. */
	public static void chunkSquare(int chunkX, int chunkZ, double y, int fill, int line, float width) {
		double x1 = chunkX << 4, z1 = chunkZ << 4;
		box(new AABB(x1 + 0.05, y, z1 + 0.05, x1 + 15.95, y + 0.02, z1 + 15.95), fill, line, width, false);
	}

	/**
	 * A waypoint: a light beam when it is inside the render distance, otherwise a marker
	 * projected at a fixed distance in its direction so it stays visible from far away.
	 */
	public static void waypoint(Vec3 target, int color, String text) {
		Minecraft mc = Minecraft.getInstance();
		double dist = Math.sqrt(Math.pow(target.x - cam.x, 2) + Math.pow(target.z - cam.z, 2));
		double renderDist = mc.options.getEffectiveRenderDistance() * 16 - 8;
		String label = text + " §7" + WorldUtil.distance(cam.distanceTo(target));
		if (dist < renderDist) {
			double bottom = mc.level != null ? mc.level.getMinY() : -64;
			double top = mc.level != null ? mc.level.getMaxY() : 320;
			box(new AABB(target.x - 0.35, bottom, target.z - 0.35, target.x + 0.35, top, target.z + 0.35), ColorUtil.withAlpha(color, 45), ColorUtil.withAlpha(color, 150), 1.5f, true);
			box(new AABB(target.x - 0.5, target.y - 0.5, target.z - 0.5, target.x + 0.5, target.y + 0.5, target.z + 0.5),
				ColorUtil.withAlpha(color, 90), ColorUtil.withAlpha(color, 255), 2f, true);
			label(target.add(0, 1.4, 0), label, color, 1.1f);
		} else {
			Vec3 dir = target.subtract(cam).normalize();
			Vec3 p = cam.add(dir.scale(48));
			box(new AABB(p.x - 0.4, p.y - 0.4, p.z - 0.4, p.x + 0.4, p.y + 0.4, p.z + 0.4), ColorUtil.withAlpha(color, 110), ColorUtil.withAlpha(color, 255), 2f, true);
			label(p.add(0, 1.0, 0), label, color, 1.1f);
		}
	}
}
