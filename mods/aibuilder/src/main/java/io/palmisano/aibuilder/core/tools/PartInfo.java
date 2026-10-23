package io.palmisano.aibuilder.core.tools;

import java.util.List;

import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.frame.Pos;

/** A moving part as the tools know it: its displays (local block positions) and its motion (null until animated). */
public record PartInfo(List<Pos> blocks, MovingPart motion) {
	public PartInfo {
		blocks = List.copyOf(blocks);
	}
}
