package io.palmisano.aibuilder.core.place;

import java.util.Comparator;
import java.util.List;

/** Supports before what rests on them: by placement pass, then bottom-up, otherwise in Claude's order. */
public final class PlacementOrder {
	private PlacementOrder() {
	}

	public static List<Placement> sort(List<Placement> batch) {
		return batch.stream()
				.sorted(Comparator.comparing((Placement p) -> p.block().info().pass()).thenComparingInt(p -> p.pos().y()))
				.toList();
	}
}
