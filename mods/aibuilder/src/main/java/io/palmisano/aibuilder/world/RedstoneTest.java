package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.tools.RedstoneSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * test_redstone (spec v2 §5.2): a short experiment on the build's machine, in the world, over ticks. It refuses while
 * anything breaks the machine rules (spec v2 §5.5), a step that would flip or press what isn't the build's, and a press
 * of a button beside something that reacts to power and isn't the build's. Before it starts, before each flip or
 * press, and before the levers go back, a player where a piston of the build would push holds it back, at most
 * {@link Tunables#MACHINE_PLAYER_WAIT_TICKS}, the action bar saying where; still there, the test ends where it is (M8
 * review). It gives the build's redstone its shape and starts what isn't started yet (the power-up pass, once a
 * component); runs the steps, reading what's asked; then puts each lever it flipped back as it was and lets the
 * machine settle. Ended early (the way stayed blocked, the build was cancelled, or its answer took too long), it puts
 * its levers back where it safely can and leaves the rest to the build's end. Server thread.
 */
final class RedstoneTest implements Tickable {
	/** What the test came to: its report, or why it was refused. */
	record Outcome(boolean refused, String text) {
	}

	private enum Stage { CHECK, PLAYERS, SHAPE, START, STEPS, SETTLE }

	private final ServerLevel level;
	private final LocalFrame frame;
	private final UndoJournal<BlockState, CompoundTag> journal;
	private final PlacementJob job;
	private final List<RedstoneSteps.Step> steps;
	private final Rotation back;
	private final CompletableFuture<Outcome> result = new CompletableFuture<>();
	private final StringBuilder report = new StringBuilder();
	/** Each lever the test flips, as it was before it was first flipped. */
	private final Map<BlockPos, Boolean> leversBefore = new LinkedHashMap<>();
	private Stage stage = Stage.CHECK;
	private List<BlockPos> pistons = List.of();
	/** While a player stands where a piston would push: the game time the test gives up (0: nobody in the way). */
	private long blockedUntil;
	private volatile boolean stopRequested;
	private ShapePass shapePass;
	private PowerUp powerUp;
	private int next;
	private long waitUntil;
	private long began;
	private long settleUntil;

	RedstoneTest(ServerLevel level, LocalFrame frame, UndoJournal<BlockState, CompoundTag> journal, PlacementJob job, List<RedstoneSteps.Step> steps) {
		this.level = level;
		this.frame = frame;
		this.journal = journal;
		this.job = job;
		this.steps = steps;
		this.back = McWorldReader.inverse(StateFactory.rotation(frame.facing()));
	}

	CompletableFuture<Outcome> result() {
		return result;
	}

	/** Ends the test at its next tick (no one is waiting for its answer any more). Any thread. */
	void stop() {
		stopRequested = true;
	}

	@Override
	public boolean tick() {
		if (result.isDone()) {
			return true;
		}
		if (stopRequested || job.cancelled()) {
			end("The build was stopped, so the test was too.");
			return true;
		}
		long deadline = System.nanoTime() + Tunables.TICK_BUDGET_NANOS;
		switch (stage) {
			case CHECK -> check();
			case PLAYERS -> players();
			case SHAPE -> {
				if (shapePass.run(deadline)) {
					powerUp = job.newPass();
					stage = Stage.START;
				}
			}
			case START -> start(deadline);
			case STEPS -> steps();
			case SETTLE -> {
				if (level.getGameTime() >= settleUntil) {
					result.complete(new Outcome(false, report.toString().strip()));
				}
			}
		}
		return result.isDone();
	}

	@Override
	public void failed(RuntimeException error) {
		job.testWaiting(null);
		result.completeExceptionally(error);
	}

	/** Nothing may break the machine rules; every step's lever or button must be the build's, and no button may power what isn't. */
	private void check() {
		List<MachineRules.Breaker> breakers = job.machines().breakers();
		if (!breakers.isEmpty()) {
			List<String> each = breakers.stream().limit(5).map(b -> local(b.pos()) + " " + b.detail()).toList();
			refuse("test_redstone can't run while the build's redstone could move or power something that isn't the build's: "
					+ String.join("; ", each) + (breakers.size() > 5 ? "; and " + (breakers.size() - 5) + " more" : "")
					+ ". Change it first (check_build lists it all).");
			return;
		}
		for (RedstoneSteps.Step step : steps) {
			if (step instanceof RedstoneSteps.Set set && !(owned(set.lever()) && block(set.lever()).getBlock() instanceof LeverBlock)) {
				refuse(local(set.lever()) + " isn't a lever the build placed: test_redstone flips only the build's own levers.");
				return;
			}
			if (step instanceof RedstoneSteps.Press press) {
				if (!(owned(press.button()) && block(press.button()).getBlock() instanceof ButtonBlock)) {
					refuse(local(press.button()) + " isn't a button the build placed: test_redstone presses only the build's own buttons.");
					return;
				}
				String near = job.machines().reactingNear(Positions.toBlockPos(frame.toWorld(press.button())));
				if (near != null) {
					refuse(local(press.button()) + " is a button within " + Tunables.MACHINE_POWER_REACH + " blocks of " + near + ", which isn't the"
							+ " build's: test_redstone won't press it, as that would power it.");
					return;
				}
			}
		}
		pistons = job.machines().pistons();
		blockedUntil = level.getGameTime() + Tunables.MACHINE_PLAYER_WAIT_TICKS;
		stage = Stage.PLAYERS;
	}

	/** No player where one of the pistons would push: wait for them, for a while. */
	private void players() {
		BlockPos piston = MachineRules.playerInReach(level, pistons, job.occupancy());
		if (piston != null) {
			job.testWaiting(piston);
			if (level.getGameTime() >= blockedUntil) {
				job.testWaiting(null);
				refuse("A player stands in the way of the piston at " + local(piston) + ": ask them to step aside, then test again.");
			}
			return;
		}
		job.testWaiting(null);
		blockedUntil = 0;
		List<BlockPos> components = new ArrayList<>();
		for (Pos at : journal.positions()) {
			BlockPos pos = Positions.toBlockPos(at);
			if (level.isLoaded(pos) && Redstone.component(level.getBlockState(pos))) {
				components.add(pos);
			}
		}
		// Placed with no updates, the build's dust isn't joined up yet: give its redstone the shape its neighbours give it.
		shapePass = new ShapePass(level, components, (pos, state) -> level.setBlock(pos, state, PlacementJob.FLAGS));
		stage = Stage.SHAPE;
	}

	private void start(long deadline) {
		if (!powerUp.run(deadline)) {
			job.testWaiting(powerUp.waitingFor());
			return;
		}
		job.testWaiting(null);
		if (powerUp.gaveUp()) {
			refuse("A player stands in the way of one of the build's pistons: ask them to step aside, then test again.");
			return;
		}
		if (powerUp.started() > 0) {
			report.append("Started ").append(powerUp.started()).append(powerUp.started() == 1 ? " redstone piece" : " redstone pieces")
					.append(" first, as if placed by hand.\n");
		}
		began = level.getGameTime();
		stage = Stage.STEPS;
	}

	/** Runs steps until one waits; at the end, puts the levers back. A flip, a press and the put-back wait for the way to be clear. */
	private void steps() {
		while (next < steps.size()) {
			RedstoneSteps.Step step = steps.get(next);
			if ((step instanceof RedstoneSteps.Set || step instanceof RedstoneSteps.Press) && blocked()) {
				return;
			}
			switch (step) {
				case RedstoneSteps.Set set -> {
					flip(Positions.toBlockPos(frame.toWorld(set.lever())), set.on());
					report.append("set ").append(local(set.lever())).append(set.on() ? " on" : " off").append('\n');
				}
				case RedstoneSteps.Press press -> {
					BlockPos pos = Positions.toBlockPos(frame.toWorld(press.button()));
					BlockState state = level.getBlockState(pos);
					if (state.getBlock() instanceof ButtonBlock button) {
						button.press(state, level, pos, null);
					}
					report.append("pressed ").append(local(press.button())).append('\n');
				}
				case RedstoneSteps.Wait wait -> {
					if (waitUntil == 0) {
						waitUntil = level.getGameTime() + wait.ticks();
					}
					if (level.getGameTime() < waitUntil) {
						return;
					}
					waitUntil = 0;
					report.append("waited ").append(wait.ticks()).append(wait.ticks() == 1 ? " tick" : " ticks").append('\n');
				}
				case RedstoneSteps.Read read -> {
					List<String> each = new ArrayList<>();
					for (Pos spot : read.spots()) {
						each.add(local(spot) + " " + McWorldReader.describe(block(spot).rotate(back)).replace("minecraft:", ""));
					}
					report.append("after ").append(level.getGameTime() - began).append(" ticks: ").append(String.join("; ", each)).append('\n');
				}
			}
			next++;
		}
		if (!leversBefore.isEmpty() && blocked()) {
			return;
		}
		leversBefore.forEach(this::flip);
		if (!leversBefore.isEmpty()) {
			report.append(String.format(Locale.ROOT, "Put %d %s back as %s.", leversBefore.size(), leversBefore.size() == 1 ? "lever" : "levers",
					leversBefore.size() == 1 ? "it was" : "they were"));
		}
		settleUntil = level.getGameTime() + Tunables.TEST_SETTLE_TICKS;
		stage = Stage.SETTLE;
	}

	/**
	 * True while a player stands where one of the pistons would push, so the next change waits; past the wait, the test
	 * ends where it is.
	 */
	private boolean blocked() {
		BlockPos piston = MachineRules.playerInReach(level, pistons, job.occupancy());
		if (piston == null) {
			if (blockedUntil != 0) {
				blockedUntil = 0;
				job.testWaiting(null);
			}
			return false;
		}
		job.testWaiting(piston);
		if (blockedUntil == 0) {
			blockedUntil = level.getGameTime() + Tunables.MACHINE_PLAYER_WAIT_TICKS;
		} else if (level.getGameTime() >= blockedUntil) {
			end("A player stands in the way of the piston at " + local(piston) + ", so the test stopped before step " + (next + 1) + ".");
		}
		return true;
	}

	/**
	 * Ends the test early, for {@code why}: each lever goes back if no player is in a piston's way, else it's left to the
	 * build's end (or the next test), which puts it back once the way is clear.
	 */
	private void end(String why) {
		job.testWaiting(null);
		boolean clear = MachineRules.playerInReach(level, pistons, job.occupancy()) == null;
		Map<BlockPos, Boolean> left = new LinkedHashMap<>();
		leversBefore.forEach((pos, on) -> {
			BlockState state = level.getBlockState(pos);
			if (level.isLoaded(pos) && state.getBlock() instanceof LeverBlock && state.getValue(LeverBlock.POWERED) != on) {
				if (clear) {
					flip(pos, on);
				} else {
					left.put(pos, on);
				}
			}
		});
		job.putBackLater(left);
		String levers = left.isEmpty() ? "" : " Its levers stay as they are until the way is clear: "
				+ left.keySet().stream().map(this::local).collect(Collectors.joining(", ")) + ".";
		String done = report.isEmpty() ? "" : "\nWhat it did first:\n" + report.toString().strip();
		refuse(why + levers + done);
	}

	/** Flips the lever to {@code on} if it isn't already, remembering how it was the first time. */
	private void flip(BlockPos pos, boolean on) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof LeverBlock lever) {
			leversBefore.putIfAbsent(pos, state.getValue(LeverBlock.POWERED));
			if (state.getValue(LeverBlock.POWERED) != on) {
				lever.pull(state, level, pos, null);
			}
		}
	}

	private boolean owned(Pos local) {
		return job.machines().owned(Positions.toBlockPos(frame.toWorld(local)));
	}

	private BlockState block(Pos local) {
		return level.getBlockState(Positions.toBlockPos(frame.toWorld(local)));
	}

	private void refuse(String why) {
		result.complete(new Outcome(true, why));
	}

	private String local(BlockPos pos) {
		return local(frame.toLocal(Positions.toPos(pos)));
	}

	private static String local(Pos p) {
		return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
	}
}
