package io.palmisano.aibuilder.world;

/** Work spread over server ticks, run by {@link JobScheduler}. */
public interface Tickable {
	/** Does one tick's share of the work; true when there's nothing left to do. */
	boolean tick();

	/** {@link #tick()} threw; the scheduler drops this tickable after telling it why. */
	default void failed(RuntimeException error) {
	}
}
