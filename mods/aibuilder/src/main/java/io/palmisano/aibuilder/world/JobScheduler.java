package io.palmisano.aibuilder.world;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.palmisano.aibuilder.AiBuilderMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Ticks every running job at the end of each server tick until it says it's done. */
public final class JobScheduler {
	private static final List<Tickable> RUNNING = new CopyOnWriteArrayList<>();

	private JobScheduler() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> tickAll());
		// A job works on one server's level: never carry it into the next world.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> RUNNING.clear());
	}

	/** Server thread. */
	public static void add(Tickable tickable) {
		RUNNING.add(tickable);
	}

	private static void tickAll() {
		for (Tickable tickable : RUNNING) {
			boolean done;
			try {
				done = tickable.tick();
			} catch (RuntimeException e) {
				AiBuilderMod.LOG.error("A build job failed", e);
				try {
					tickable.failed(e);
				} catch (RuntimeException again) {
					AiBuilderMod.LOG.error("A build job failed while stopping", again);
				}
				done = true;
			}
			if (done) {
				RUNNING.remove(tickable);
			}
		}
	}
}
