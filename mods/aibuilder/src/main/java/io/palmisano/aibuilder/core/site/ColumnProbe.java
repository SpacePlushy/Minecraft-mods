package io.palmisano.aibuilder.core.site;

/** One column of the world, block by block. */
@FunctionalInterface
public interface ColumnProbe {
	Cell at(int y);
}
