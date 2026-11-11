package dev.zero.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
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

	/** Growable float buffer reused every frame (no per-shape allocation). */
	private static final class Buf {
		float[] v = new float[4096];
		int[] c = new int[1024];
		int n, cn;

		void reset() { n = cn = 0; }

		void add(float a) {
			if (n == v.length) v = java.util.Arrays.copyOf(v, n * 2);
			v[n++] = a;
		}

		void color(int col) {
			if (cn == c.length) c = java.util.Arrays.copyOf(c, cn * 2);
			c[cn++] = col;
		}
	}

	private record Label(float x, float y, float z, String text, int color, float scale, double dist) {}

	// index 0 = depth tested, 1 = through walls
	private static final Buf[] QUADS = {new Buf(), new Buf()};   // 12 floats + 1 color per quad
	private static final Buf[] LINES = {new Buf(), new Buf()};   // 7 floats (xyz xyz width) + 1 color per line
	private static final List<Label> LABELS = new ArrayList<>();
	private static Vec3 cam = Vec3.ZERO;
	private static float lineScale = 1f;
	private static float fx, fy, fz, cosCull, tanHalf;
	private static int maxLabels = 40;
	private static double labelRange = 160;
	private static double lodDistSq = 64 * 64;

	/** Called before modules render. */
	public static void begin() {
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.getMainCamera();
		cam = camera.position();
		Vector3fc f = camera.forwardVector();
		fx = f.x();
		fy = f.y();
		fz = f.z();
		// Cone that contains the whole view frustum (diagonal half-angle + margin).
		double fovY = Math.toRadians(mc.options.fov().get() * 1.15);
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		double half = Math.atan(Math.tan(fovY / 2) * Math.sqrt(1 + aspect * aspect));
		cosCull = (float) Math.cos(Math.min(Math.PI / 2, half + 0.05));
		tanHalf = (float) Math.tan(Math.min(Math.PI / 2 - 0.01, half + 0.05));
		lineScale = mc.getWindow().getAppropriateLineWidth() / 2.5f;
		maxLabels = dev.zero.config.ClientSettings.MAX_LABELS.getInt();
		labelRange = dev.zero.config.ClientSettings.LABEL_RANGE.get();
		double lod = dev.zero.config.ClientSettings.DETAIL_DISTANCE.get();
		lodDistSq = lod >= 512 ? 0 : lod * lod;
		for (int i = 0; i < 2; i++) {
			QUADS[i].reset();
			LINES[i].reset();
		}
		LABELS.clear();
	}

	/**
	 * Whether a sphere (camera-relative center, radius) can be on screen. Cheap cone test that
	 * removes everything behind / beside the camera before any vertex is generated.
	 */
	public static boolean visible(double rx, double ry, double rz, double radius) {
		double d2 = rx * rx + ry * ry + rz * rz;
		if (d2 <= radius * radius) return true;
		double along = rx * fx + ry * fy + rz * fz;
		if (along < -radius) return false;
		double d = Math.sqrt(d2);
		// angle test, widened by the sphere's angular radius
		double perp = Math.sqrt(Math.max(0, d2 - along * along));
		return perp <= Math.max(along, 0) * tanHalf + radius * 1.5;
	}

	/** Draws everything queued this frame. */
	public static void flush(MultiBufferSource.BufferSource buffers) {
		PoseStack poseStack = new PoseStack();
		PoseStack.Pose pose = poseStack.last();
		for (int pass = 0; pass < 2; pass++) {
			Buf q = QUADS[pass];
			if (q.cn > 0) {
				RenderType type = pass == 1 ? EspRenderTypes.QUADS_SEE_THROUGH : EspRenderTypes.QUADS_DEPTH;
				VertexConsumer vc = buffers.getBuffer(type);
				float[] v = q.v;
				for (int i = 0, k = 0; i < q.cn; i++, k += 12) {
					int col = q.c[i];
					vc.addVertex(pose, v[k], v[k + 1], v[k + 2]).setColor(col);
					vc.addVertex(pose, v[k + 3], v[k + 4], v[k + 5]).setColor(col);
					vc.addVertex(pose, v[k + 6], v[k + 7], v[k + 8]).setColor(col);
					vc.addVertex(pose, v[k + 9], v[k + 10], v[k + 11]).setColor(col);
				}
				buffers.endBatch(type);
			}
			Buf l = LINES[pass];
			if (l.cn > 0) {
				RenderType type = pass == 1 ? EspRenderTypes.LINES_SEE_THROUGH : EspRenderTypes.LINES_DEPTH;
				VertexConsumer vc = buffers.getBuffer(type);
				float[] v = l.v;
				for (int i = 0, k = 0; i < l.cn; i++, k += 7) {
					float x1 = v[k], y1 = v[k + 1], z1 = v[k + 2], x2 = v[k + 3], y2 = v[k + 4], z2 = v[k + 5];
					float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len < 1e-6f) continue;
					nx /= len;
					ny /= len;
					nz /= len;
					float w = v[k + 6];
					int col = l.c[i];
					vc.addVertex(pose, x1, y1, z1).setColor(col).setNormal(pose, nx, ny, nz).setLineWidth(w);
					vc.addVertex(pose, x2, y2, z2).setColor(col).setNormal(pose, nx, ny, nz).setLineWidth(w);
				}
				buffers.endBatch(type);
			}
		}

		if (!LABELS.isEmpty()) {
			Minecraft mc = Minecraft.getInstance();
			Font font = mc.font;
			Camera camera = mc.gameRenderer.getMainCamera();
			// Text is the most expensive thing to draw: only the nearest labels, within budget.
			if (LABELS.size() > maxLabels) LABELS.sort((a, b) -> Double.compare(a.dist, b.dist));
			int n = Math.min(LABELS.size(), maxLabels);
			for (int i = 0; i < n; i++) {
				Label lb = LABELS.get(i);
				poseStack.pushPose();
				poseStack.translate(lb.x, lb.y, lb.z);
				poseStack.mulPose(camera.rotation());
				poseStack.scale(lb.scale, -lb.scale, lb.scale);
				Matrix4f m = poseStack.last().pose();
				font.drawInBatch(lb.text, -font.width(lb.text) / 2f, 0, lb.color, false, m, buffers, Font.DisplayMode.SEE_THROUGH, 0x90101016, 0xF000F0);
				poseStack.popPose();
			}
			buffers.endBatch();
		}
	}

	// ---- Shape API --------------------------------------------------------------------------

	public static void box(AABB box, int fill, int line, float lineWidth, boolean throughWalls) {
		float x1 = (float) (box.minX - cam.x), y1 = (float) (box.minY - cam.y), z1 = (float) (box.minZ - cam.z);
		float x2 = (float) (box.maxX - cam.x), y2 = (float) (box.maxY - cam.y), z2 = (float) (box.maxZ - cam.z);
		double hx = (x2 - x1) / 2.0, hy = (y2 - y1) / 2.0, hz = (z2 - z1) / 2.0;
		if (!visible(x1 + hx, y1 + hy, z1 + hz, Math.sqrt(hx * hx + hy * hy + hz * hz))) return;
		int pass = throughWalls ? 1 : 0;
		boolean hasFill = ColorUtil.a(fill) > 0;
		if (hasFill) {
			Buf q = QUADS[pass];
			// Only faces turned towards the camera (camera is at the origin here). A box seen from
			// outside never shows more than 3 faces, so this halves the fill vertices.
			boolean inside = x1 <= 0 && x2 >= 0 && y1 <= 0 && y2 >= 0 && z1 <= 0 && z2 >= 0;
			if (inside || y1 > 0) quad(q, fill, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2); // bottom
			if (inside || y2 < 0) quad(q, fill, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1); // top
			if (inside || z1 > 0) quad(q, fill, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1); // north
			if (inside || z2 < 0) quad(q, fill, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2); // south
			if (inside || x1 > 0) quad(q, fill, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1); // west
			if (inside || x2 < 0) quad(q, fill, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2); // east
		}
		// Level of detail: far, filled boxes skip their 12 outline edges.
		if (hasFill && lodDistSq > 0 && (x1 + hx) * (x1 + hx) + (y1 + hy) * (y1 + hy) + (z1 + hz) * (z1 + hz) > lodDistSq && hx < 4 && hy < 4 && hz < 4) return;
		if (ColorUtil.a(line) > 0 && lineWidth > 0) {
			Buf l = LINES[pass];
			float w = lineWidth * lineScale;
			seg(l, x1, y1, z1, x2, y1, z1, line, w);
			seg(l, x2, y1, z1, x2, y1, z2, line, w);
			seg(l, x2, y1, z2, x1, y1, z2, line, w);
			seg(l, x1, y1, z2, x1, y1, z1, line, w);
			seg(l, x1, y2, z1, x2, y2, z1, line, w);
			seg(l, x2, y2, z1, x2, y2, z2, line, w);
			seg(l, x2, y2, z2, x1, y2, z2, line, w);
			seg(l, x1, y2, z2, x1, y2, z1, line, w);
			seg(l, x1, y1, z1, x1, y2, z1, line, w);
			seg(l, x2, y1, z1, x2, y2, z1, line, w);
			seg(l, x2, y1, z2, x2, y2, z2, line, w);
			seg(l, x1, y1, z2, x1, y2, z2, line, w);
		}
	}

	private static void quad(Buf q, int color, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
		q.add(ax); q.add(ay); q.add(az);
		q.add(bx); q.add(by); q.add(bz);
		q.add(cx); q.add(cy); q.add(cz);
		q.add(dx); q.add(dy); q.add(dz);
		q.color(color);
	}

	private static void seg(Buf l, float x1, float y1, float z1, float x2, float y2, float z2, int color, float width) {
		l.add(x1); l.add(y1); l.add(z1);
		l.add(x2); l.add(y2); l.add(z2);
		l.add(width);
		l.color(color);
	}

	public static void line(Vec3 a, Vec3 b, int color, float width, boolean throughWalls) {
		seg(LINES[throughWalls ? 1 : 0], (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z),
			(float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z), color, width * lineScale);
	}

	/** A line from just in front of the camera to the target, i.e. a tracer. */
	public static void tracer(Vec3 target, int color, float width) {
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vector3fc look = camera.forwardVector();
		Vec3 start = cam.add(look.x() * 0.5, look.y() * 0.5, look.z() * 0.5);
		line(start, target, color, width, true);
	}

	/** Billboard text with a dark backdrop that keeps a near-constant on-screen size. */
	public static void label(Vec3 pos, String text, int color, float sizeMul) { label(pos, text, color, sizeMul, false); }

	/** An important label (waypoints, top targets): ignores the label range and is drawn first. */
	public static void importantLabel(Vec3 pos, String text, int color, float sizeMul) { label(pos, text, color, sizeMul, true); }

	private static void label(Vec3 pos, String text, int color, float sizeMul, boolean important) {
		Minecraft mc = Minecraft.getInstance();
		double rx = pos.x - cam.x, ry = pos.y - cam.y, rz = pos.z - cam.z;
		double rawDist = Math.sqrt(rx * rx + ry * ry + rz * rz);
		if ((!important && rawDist > labelRange) || !visible(rx, ry, rz, 1)) return;
		double dist = Math.max(2, rawDist);
		// World size of one GUI pixel at this distance -> labels look exactly like HUD text.
		double fov = Math.toRadians(mc.options.fov().get());
		double worldPerPixel = dist * 2 * Math.tan(fov / 2) / Math.max(1, mc.getWindow().getHeight());
		float scale = (float) (worldPerPixel * mc.getWindow().getGuiScale() * 0.85 * sizeMul);
		LABELS.add(new Label((float) rx, (float) ry, (float) rz, text, color, scale, important ? -1 : rawDist));
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
			importantLabel(target.add(0, 1.4, 0), label, color, 1.1f);
		} else {
			Vec3 dir = target.subtract(cam).normalize();
			Vec3 p = cam.add(dir.scale(48));
			box(new AABB(p.x - 0.4, p.y - 0.4, p.z - 0.4, p.x + 0.4, p.y + 0.4, p.z + 0.4), ColorUtil.withAlpha(color, 110), ColorUtil.withAlpha(color, 255), 2f, true);
			importantLabel(p.add(0, 1.0, 0), label, color, 1.1f);
		}
	}
}
