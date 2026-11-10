package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.config.ClientSettings;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Detaches ONLY the render camera from the player.
 * <p>
 * Anti-cheat safety: the player entity is left completely untouched - it stays the camera
 * entity, keeps its vanilla physics and keeps sending the exact same packets as an idle
 * player. Movement keys are zeroed for the player and redirected to the camera, mouse look
 * rotates the camera only, and attack / use / pick-block are blocked while active so no
 * interaction packet can be produced from the camera's point of view.
 */
public class Freecam extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Flight speed (blocks per tick)", 1.0, 0.1, 5.0, 0.05));
	private final NumberSetting boost = add(new NumberSetting("Sprint Boost", "Speed multiplier while sprint is held", 2.5, 1.0, 6.0, 0.1));
	private final BoolSetting smooth = add(new BoolSetting("Smooth", "Cinematic acceleration and glide", true));
	private final BoolSetting showPlayer = add(new BoolSetting("Player Box", "Outline where your real player stands", true));
	private final BoolSetting disableOnDamage = add(new BoolSetting("Disable On Damage", "Snap back if your player takes damage", true));

	private Vec3 pos = Vec3.ZERO, prevPos = Vec3.ZERO, velocity = Vec3.ZERO;
	private float yaw, pitch, prevYaw, prevPitch;
	private float lastHealth;

	public Freecam() {
		super("Freecam", "Move the camera freely while your player stays still", Category.RENDER);
	}

	@Override
	protected void onEnable() {
		if (mc.player == null) {
			setEnabledSilently(false);
			return;
		}
		pos = prevPos = mc.gameRenderer.getMainCamera().position();
		velocity = Vec3.ZERO;
		yaw = prevYaw = mc.player.getYRot();
		pitch = prevPitch = mc.player.getXRot();
		lastHealth = mc.player.getHealth();
	}

	@Override
	public void onWorldLeave() {
		if (isEnabled()) setEnabledSilently(false);
	}

	/** True while freecam controls the camera. */
	public boolean active() {
		return isEnabled() && mc.player != null;
	}

	@Override
	public void onTick() {
		if (mc.player == null) return;
		if (!mc.player.isAlive() || (disableOnDamage.get() && mc.player.getHealth() < lastHealth)) {
			setEnabled(false);
			return;
		}
		lastHealth = mc.player.getHealth();

		prevPos = pos;
		prevYaw = yaw;
		prevPitch = pitch;

		boolean typing = mc.screen != null;
		var o = mc.options;
		double forward = 0, strafe = 0, vertical = 0;
		if (!typing) {
			if (o.keyUp.isDown()) forward++;
			if (o.keyDown.isDown()) forward--;
			if (o.keyLeft.isDown()) strafe++;
			if (o.keyRight.isDown()) strafe--;
			if (o.keyJump.isDown()) vertical++;
			if (o.keyShift.isDown()) vertical--;
		}
		double s = speed.get() * (!typing && o.keySprint.isDown() ? boost.get() : 1);
		double rad = Math.toRadians(yaw);
		double sin = Math.sin(rad), cos = Math.cos(rad);
		// Fly in the direction the camera looks (including pitch) for natural movement.
		double pitchRad = Math.toRadians(pitch);
		double fx = -sin * Math.cos(pitchRad), fy = -Math.sin(pitchRad), fz = cos * Math.cos(pitchRad);
		double rx = cos, rz = sin;
		Vec3 wish = new Vec3(fx * forward + rx * strafe, fy * forward + vertical, fz * forward + rz * strafe);
		if (wish.lengthSqr() > 1) wish = wish.normalize();
		wish = wish.scale(s);
		if (smooth.get()) velocity = velocity.lerp(wish, 0.35);
		else velocity = wish;
		if (velocity.lengthSqr() < 1e-6) velocity = Vec3.ZERO;
		pos = pos.add(velocity);
	}

	/** Called from the Entity#turn mixin instead of rotating the player. */
	public void turn(double dx, double dy) {
		yaw += (float) dx * 0.15f;
		pitch = Mth.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
		// Mouse look should feel instant: move the "previous" values too (like vanilla does).
		prevYaw += (float) dx * 0.15f;
		prevPitch = Mth.clamp(prevPitch + (float) dy * 0.15f, -90f, 90f);
	}

	public Vec3 getPos(float pt) { return prevPos.lerp(pos, pt); }

	public float getYaw(float pt) { return Mth.lerp(pt, prevYaw, yaw); }

	public float getPitch(float pt) { return Mth.lerp(pt, prevPitch, pitch); }

	@Override
	public void onRender3D(float partialTick) {
		if (!showPlayer.get() || mc.player == null) return;
		AABB box = mc.player.getBoundingBox().move(
			Mth.lerp(partialTick, mc.player.xo, mc.player.getX()) - mc.player.getX(),
			Mth.lerp(partialTick, mc.player.yo, mc.player.getY()) - mc.player.getY(),
			Mth.lerp(partialTick, mc.player.zo, mc.player.getZ()) - mc.player.getZ());
		int c = ClientSettings.accent();
		Render3D.box(box, ColorUtil.withAlpha(c, 35), ColorUtil.withAlpha(c, 220), 2f, true);
	}
}
