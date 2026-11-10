package dev.newclient.util;

/** Frame-rate independent exponential smoothing value for UI animations. */
public class Anim {
	private double value;
	private double target;
	private final double speed;
	private long last = System.nanoTime();

	public Anim(double initial, double speed) {
		this.value = initial;
		this.target = initial;
		this.speed = speed;
	}

	public Anim setTarget(double target) {
		this.target = target;
		return this;
	}

	public void snap(double v) {
		value = target = v;
	}

	public double get() {
		long now = System.nanoTime();
		double dt = Math.min((now - last) / 1e9, 0.1);
		last = now;
		value += (target - value) * (1 - Math.exp(-speed * dt));
		if (Math.abs(target - value) < 1e-4) value = target;
		return value;
	}

	public float getF() { return (float) get(); }

	public double getTarget() { return target; }

	public static double easeOutBack(double t) {
		double c1 = 1.70158, c3 = c1 + 1;
		return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
	}

	public static double easeOutCubic(double t) { return 1 - Math.pow(1 - t, 3); }
}
