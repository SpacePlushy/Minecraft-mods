package io.palmisano.rocket.flight;

/** Test-only ground: flies a rocket above a floor at altitude 0, as the entity's collision would. */
final class Pad {
	double y;
	RocketState state;
	int tick;
	int liftoffTick = -1;

	Pad(RocketState state, double y) {
		this.state = state;
		this.y = y;
	}

	Pad fly(RocketPhysics m, RocketInput in, int ticks) {
		for (int i = 0; i < ticks; i++) {
			tick++;
			state = m.step(state, in, y == 0);
			double next = y + state.vy();
			if (next < 0) {
				next = 0;
				state = new RocketState(state.vx(), 0.0, state.vz(), state.yaw(), state.pitchTilt(), state.rollTilt(),
						state.throttle(), state.engine(), state.engineTicks());
			}
			y = next;
			if (liftoffTick < 0 && y > 0.01) {
				liftoffTick = tick;
			}
		}
		return this;
	}
}
