package io.palmisano.aibuilder.core.anim;

/** A rigid motion, {@code w → r·w + t}. */
public record Rigid(Quat r, Vec3 t) {
	public static final Rigid IDENTITY = new Rigid(Quat.IDENTITY, Vec3.ZERO);

	public Vec3 apply(Vec3 w) {
		return r.rotate(w).plus(t);
	}

	/** {@code outer} after this one: outer ∘ this. */
	public Rigid then(Rigid outer) {
		return new Rigid(outer.r.times(r), outer.r.rotate(t).plus(outer.t));
	}
}
