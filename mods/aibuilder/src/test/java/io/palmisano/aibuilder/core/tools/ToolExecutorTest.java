package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.FireMask;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.place.FireMask;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {
	static final String BRIEF = "{\"title\":\"test house\",\"style\":\"plain\"}";

	static final Box ALLOWED = Box.of(-8, -2, 0, 7, 15, 15);

	private final RecordingSink sink = new RecordingSink();
	private final FakeWorld world = new FakeWorld();

	private ToolExecutor executor(int budget, long maxChangedBlocks) {
		return new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED),
				new LocalFrame(new Pos(0, 64, 0), Facing.SOUTH), ALLOWED, SizeTier.SMALL, maxChangedBlocks, sink, new Budget(budget), world);
	}

	private ToolExecutor briefed() {
		ToolExecutor tools = executor(150, 100_000);
		tools.call("submit_brief", json(BRIEF));
		return tools;
	}

	static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void writesNeedABriefFirst() {
		ToolResult result = executor(150, 100_000).call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("submit_brief"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void fillQueuesWorldPositionsThroughTheFrame() {
		ToolResult result = briefed().call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertFalse(result.isError(), result.text());
		assertTrue(result.text().startsWith("Queued 2 blocks."), result.text());
		assertEquals(List.of(new Pos(0, 64, 0), new Pos(-1, 64, 0)),
				sink.batches.getFirst().stream().map(Placement::pos).toList());
	}

	@Test
	void rejectedEntriesAreReportedAndTheRestGoThrough() {
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":["
				+ "{\"pos\":[0,0,1],\"block\":\"stone\"},"
				+ "{\"pos\":[1,0,1],\"block\":\"oak_stair\"},"
				+ "{\"pos\":[0,0,-1],\"block\":\"stone\"}]}"));
		assertEquals(1, sink.queuedCount());
		assertTrue(result.text().contains("unknown_block") && result.text().contains("minecraft:oak_stairs")
				&& result.text().contains("out_of_bounds"), result.text());
	}

	@Test
	void outsideTheBoxIsRejected() {
		ToolResult result = briefed().call("fill", json("{\"box\":[0,14,0,0,20,0],\"block\":\"stone\"}"));
		assertEquals(2, sink.queuedCount());
		assertTrue(result.text().startsWith("Queued 2 blocks.") && result.text().contains("out_of_bounds"), result.text());
	}

	@Test
	void aCallThatQueuesNothingIsAnError() {
		ToolResult result = briefed().call("fill", json("{\"box\":[0,30,0,1,30,0],\"block\":\"stone\"}"));
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().startsWith("Queued 0 blocks.") && result.text().contains("out_of_bounds"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	/** A player's own blocks inside the box stay; the rest of the call goes through (spec §11). */
	@Test
	void protectedCellsAreRejected() {
		world.protection = new ProtectionMask(ALLOWED);
		world.protection.protect(new Pos(0, 0, 1));
		ToolResult result = briefed().call("fill", json("{\"box\":[0,0,0,0,0,2],\"block\":\"stone\"}"));
		assertEquals(List.of(new Pos(0, 64, 0), new Pos(0, 64, 2)), sink.batches.getFirst().stream().map(Placement::pos).toList());
		assertTrue(result.text().contains("1 block — protected"), result.text());
		// What Claude reads when a player's house is in the way: build around it; the bypass is for the player's own ask.
		assertTrue(result.text().contains("build around it (include_protected is only for when the player asked to remove it)"), result.text());
		assertFalse(result.text().contains("clear with include_protected removes it"), result.text());
	}

	@Test
	void clearCanIncludeProtectedButNeverUnbreakable() {
		world.protection = new ProtectionMask(ALLOWED);
		world.protection.protect(new Pos(0, 0, 1));
		world.protection.unbreakable(new Pos(0, 0, 2));
		ToolExecutor tools = briefed();
		tools.call("clear", json("{\"box\":[0,0,0,0,0,2]}"));
		assertEquals(1, sink.queuedCount());
		ToolResult all = tools.call("clear", json("{\"box\":[0,0,0,0,0,2],\"include_protected\":true}"));
		assertEquals(List.of(new Pos(0, 64, 0), new Pos(0, 64, 1)), sink.batches.get(1).stream().map(Placement::pos).toList());
		assertTrue(all.text().contains("can't be broken"), all.text());
	}

	/** Once the build has cleared a protected block, the spot is the build's own: later calls may write there. */
	@Test
	void aBuildMayOverwriteItsOwnBlocks() {
		world.protection = new ProtectionMask(ALLOWED);
		world.protection.protect(new Pos(0, 0, 1));
		ToolExecutor tools = briefed();
		tools.call("clear", json("{\"box\":[0,0,1,0,0,1],\"include_protected\":true}"));
		ToolResult fill = tools.call("fill", json("{\"box\":[0,0,1,0,0,1],\"block\":\"stone\"}"));
		assertEquals("Queued 1 block.", fill.text());
	}

	@Test
	void numbersMustBeWholeAndInRange() {
		ToolExecutor tools = briefed();
		ToolResult fraction = tools.call("fill", json("{\"box\":[0,0,0,1.5,0,0],\"block\":\"stone\"}"));
		assertTrue(fraction.isError() && fraction.text().contains("bad_argument") && fraction.text().contains("box"), fraction.text());
		ToolResult huge = tools.call("fill", json("{\"box\":[0,0,0,3000000000,0,0],\"block\":\"stone\"}"));
		assertTrue(huge.isError() && huge.text().contains("bad_argument"), huge.text());
		assertEquals(0, sink.queuedCount());
	}

	/** A shape can't be bigger than the whole allowed box, so one that is is a mistake, refused before it's built. */
	@Test
	void aShapeBiggerThanTheAllowedBoxIsRefusedWhole() {
		ToolExecutor tools = briefed();
		ToolResult sphere = tools.call("sphere", json("{\"center\":[0,5,8],\"radius\":60,\"block\":\"stone\"}"));
		assertTrue(sphere.isError() && sphere.text().contains("too_many"), sphere.text());
		ToolResult fill = tools.call("fill", json("{\"box\":[-50,0,0,50,50,50],\"block\":\"stone\"}"));
		assertTrue(fill.isError() && fill.text().contains("too_many"), fill.text());
		assertEquals(0, sink.queuedCount());
	}

	/** A bed's head goes where it faces, a door's top half above it: both are checked as the block is (PR review). */
	@Test
	void aBedsHeadNeverReplacesAProtectedBlock() {
		world.protection = new ProtectionMask(ALLOWED);
		world.protection.protect(new Pos(0, 0, 2));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,1],\"block\":\"red_bed\",\"state\":{\"facing\":\"forward\"}}]}"));
		assertTrue(result.text().contains("protected"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void aDoorsTopHalfStaysInTheBox() {
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,15,1],\"block\":\"oak_door\"}]}"));
		assertTrue(result.text().contains("out_of_bounds"), result.text());
		assertEquals(0, sink.queuedCount());
		ToolResult lower = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,14,1],\"block\":\"oak_door\"}]}"));
		assertTrue(!lower.isError(), lower.text());
		assertEquals(1, sink.queuedCount());
	}

	/** The lava rule (spec §9) holds for the head too: lava 3 past the head, 4 past the foot. */
	@Test
	void aBedsHeadKeepsClearOfLava() {
		world.fire = new FireMask(Box.of(-12, -6, -4, 11, 19, 19));
		world.fire.lava(new Pos(0, 0, 5));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,1],\"block\":\"red_bed\",\"state\":{\"facing\":\"forward\"}}]}"));
		assertTrue(result.text().contains("lava_near_flammable"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void aLongTitleIsCut() {
		ToolResult result = executor(150, 100_000).call("submit_brief", json("{\"title\":\"" + "Castle ".repeat(60) + "\",\"style\":\"plain\"}"));
		assertTrue(!result.isError(), result.text());
		String title = sink.brief.title();
		int length = title.codePointCount(0, title.length());
		assertTrue(length <= Tunables.TITLE_MAX && length > Tunables.TITLE_MAX - 8, title);
		assertTrue(title.endsWith("Castle…"), title);
	}

	@Test
	void aBriefWithAMalformedPhaseIsABadArgument() {
		ToolResult result = executor(150, 100_000).call("submit_brief",
				json("{\"title\":\"test house\",\"style\":\"plain\",\"phases\":[{\"name\":\"walls\"}]}"));
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("bad_argument") && result.text().contains("phases"), result.text());
	}

	@Test
	void setBlocksStopsAtTwoThousand() {
		String blocks = IntStream.range(0, 2001).mapToObj(i -> "{\"pos\":[0,0,1],\"block\":\"stone\"}").collect(Collectors.joining(","));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[" + blocks + "]}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("too_many"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	/** Lava never goes in next to anything that burns, the world's or the build's own (spec §9). */
	@Test
	void lavaNextToPlanksIsRejected() {
		world.fire = new FireMask(ALLOWED);
		world.fire.flammable(new Pos(2, 0, 2));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}"));
		assertTrue(result.isError() && result.text().contains("lava_near_flammable"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	/** The other order: a lava moat first, the wooden bridge after (M5 Review Focus). */
	@Test
	void planksNextToLavaAreRejected() {
		ToolExecutor tools = briefed();
		assertFalse(tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}")).isError());
		ToolResult bridge = tools.call("fill", json("{\"box\":[1,0,2,5,0,2],\"block\":\"oak_planks\"}"));
		assertTrue(bridge.text().contains("3 blocks — lava_near_flammable"), bridge.text());
		assertEquals(1 + 2, sink.queuedCount(), "the planks 4 and 5 away go in");
	}

	@Test
	void theWorldsLavaCountsForWood() {
		world.fire = new FireMask(ALLOWED);
		world.fire.lava(new Pos(-4, 0, 8));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[-4,2,9],\"block\":\"oak_planks\"},"
				+ "{\"pos\":[0,0,8],\"block\":\"oak_planks\"}]}"));
		assertTrue(result.text().contains("#0 lava_near_flammable"), result.text());
		assertEquals(1, sink.queuedCount());
	}

	@Test
	void farApartIsFine() {
		ToolExecutor tools = briefed();
		assertFalse(tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}")).isError());
		assertFalse(tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[4,0,2],\"block\":\"oak_planks\"},"
				+ "{\"pos\":[0,5,2],\"block\":\"oak_planks\"}]}")).isError());
		assertEquals(3, sink.queuedCount());
	}

	@Test
	void clearingTheWoodMakesRoom() {
		world.fire = new FireMask(ALLOWED);
		world.fire.flammable(new Pos(1, 0, 2));
		ToolExecutor tools = briefed();
		tools.call("clear", json("{\"box\":[1,0,2,1,0,2]}"));
		ToolResult lava = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}"));
		assertFalse(lava.isError(), lava.text());
	}

	@Test
	void stoneDoesntBurn() {
		ToolExecutor tools = briefed();
		assertFalse(tools.call("fill", json("{\"box\":[1,0,2,3,0,2],\"block\":\"stone\"}")).isError());
		assertFalse(tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}")).isError());
	}

	@Test
	void earlierBlocksOfTheSameCallCount() {
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"},"
				+ "{\"pos\":[1,1,2],\"block\":\"oak_planks\"}]}"));
		assertTrue(result.text().contains("#1 lava_near_flammable"), result.text());
		assertEquals(1, sink.queuedCount());
	}

	/**
	 * Two entries for one spot: the last is the one placed and the one the lava rule judges (placement sorts a batch,
	 * fluids last, so the lava would otherwise end up over the planks while the rule took the spot for planks).
	 */
	@Test
	void twoEntriesForOneSpotKeepTheLast() {
		ToolExecutor tools = briefed();
		ToolResult result = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"},"
				+ "{\"pos\":[0,0,2],\"block\":\"oak_planks\"}]}"));
		assertEquals("Queued 1 block. 1 entry was replaced by a later one for the same spot.", result.text());
		assertEquals(List.of("minecraft:oak_planks"), sink.batches.getFirst().stream().map(p -> p.block().info().id()).toList());
		ToolResult lava = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[1,0,2],\"block\":\"lava\"}]}"));
		assertTrue(lava.text().contains("lava_near_flammable"), "judged as planks: " + lava.text());
	}

	@Test
	void modesTakeAnyCase() {
		ToolExecutor tools = briefed();
		ToolResult replace = tools.call("fill", json("{\"box\":[0,0,1,1,0,1],\"block\":\"stone\",\"mode\":\"Replace\",\"filter\":\"air\"}"));
		assertFalse(replace.isError(), replace.text());
		ToolResult straight = tools.call("staircase", json("{\"style\":\"Straight\",\"from\":[0,0,3],\"direction\":\"Forward\","
				+ "\"steps\":2,\"block\":\"oak_stairs\"}"));
		assertFalse(straight.isError(), straight.text());
		ToolResult spiral = tools.call("staircase", json("{\"style\":\"SPIRAL\",\"center\":[0,0,8],\"radius\":2,\"from_y\":0,"
				+ "\"to_y\":3,\"block\":\"oak_stairs\"}"));
		assertFalse(spiral.isError(), spiral.text());
	}

	/** Lava where a log was replaces the log: its own spot doesn't count. */
	@Test
	void lavaCanReplaceWhatBurns() {
		world.fire = new FireMask(ALLOWED);
		world.fire.flammable(new Pos(0, 0, 2));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}"));
		assertFalse(result.isError(), result.text());
	}

	/** A replace-mode fill may leave the old block: the wood still counts. */
	@Test
	void aReplaceFillKeepsTheWood() {
		world.fire = new FireMask(ALLOWED);
		world.fire.flammable(new Pos(1, 0, 2));
		ToolExecutor tools = briefed();
		tools.call("fill", json("{\"box\":[1,0,2,1,0,2],\"block\":\"stone\",\"mode\":\"replace\",\"filter\":\"minecraft:dirt\"}"));
		ToolResult lava = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}"));
		assertTrue(lava.text().contains("lava_near_flammable"), lava.text());
	}

	/** Lava just outside the allowed box still sets the build's wood alight (the world's mask reaches past it). */
	@Test
	void lavaOutsideTheBoxCounts() {
		world.fire = FireMask.around(ALLOWED);
		world.fire.lava(new Pos(-10, 0, 2));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[{\"pos\":[-8,0,2],\"block\":\"oak_planks\"}]}"));
		assertTrue(result.text().contains("lava_near_flammable"), result.text());
	}

	/** place_block_entity's data rides with its block (spec §8). */
	@Test
	void aSignGoesInWithItsText() {
		ToolResult result = briefed().call("place_block_entity", json("{\"pos\":[0,0,2],\"block\":\"oak_sign\",\"sign\":{\"front\":[\"Hi\"]}}"));
		assertFalse(result.isError(), result.text());
		Placement p = sink.batches.getFirst().getFirst();
		assertEquals("minecraft:oak_sign", p.block().info().id());
		assertEquals("Hi", ((BlockEntityData.Sign) p.data()).front().getFirst());
	}

	@Test
	void signTextOnStoneIsRefused() {
		ToolResult result = briefed().call("place_block_entity", json("{\"pos\":[0,0,2],\"block\":\"stone\",\"sign\":{\"front\":[\"Hi\"]}}"));
		assertTrue(result.isError() && result.text().contains("not_a_block_entity"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void anUnknownItemIsABadValue() {
		ToolResult result = briefed().call("place_block_entity", json("{\"pos\":[0,0,2],\"block\":\"chest\",\"container\":{\"items\":[{\"item\":\"gold_bar\"}]}}"));
		assertTrue(result.isError() && result.text().contains("bad_value") && result.text().contains("minecraft:gold_bar"), result.text());
	}

	/** An item frame is an entity, not a block: no block goes in, and its facing turns with the build (spec §10). */
	@Test
	void anItemFrameIsAnEntityOnAFace() {
		ToolResult result = briefed().call("place_block_entity", json("{\"pos\":[0,1,2],\"item_frame\":{\"facing\":\"back\",\"item\":\"diamond\"}}"));
		assertFalse(result.isError(), result.text());
		Placement p = sink.batches.getFirst().getFirst();
		assertNull(p.block());
		assertEquals(new BlockEntityData.ItemFrame("north", "minecraft:diamond", false), p.data());
		assertEquals(new Pos(0, 65, 2), p.pos());
	}

	@Test
	void itemFramesStayInTheBoxAndOffWhatsProtected() {
		world.protection = new ProtectionMask(ALLOWED);
		world.protection.protect(new Pos(0, 1, 2));
		ToolExecutor tools = briefed();
		ToolResult outside = tools.call("place_block_entity", json("{\"pos\":[0,1,40],\"item_frame\":{\"facing\":\"back\"}}"));
		assertTrue(outside.isError() && outside.text().contains("out_of_bounds"), outside.text());
		ToolResult onProtected = tools.call("place_block_entity", json("{\"pos\":[0,1,2],\"item_frame\":{\"facing\":\"back\"}}"));
		assertTrue(onProtected.isError() && onProtected.text().contains("protected"), onProtected.text());
		assertEquals(0, sink.queuedCount());
	}

	/** A call refused by the pending guard placed nothing, so its lava isn't remembered. */
	@Test
	void aRefusedCallLeavesNoLava() {
		ToolExecutor tools = briefed();
		sink.pending = Tunables.PENDING_MAX;
		assertTrue(tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,0,2],\"block\":\"lava\"}]}")).isError());
		sink.pending = 0;
		ToolResult planks = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[1,0,2],\"block\":\"oak_planks\"}]}"));
		assertFalse(planks.isError(), planks.text());
	}

	/** The cap counts real changes, which only the placement job sees; once it's reached, writes are refused. */
	@Test
	void capReachedRejectsWrites() {
		ToolExecutor tools = briefed();
		sink.capReached = true;
		ToolResult result = tools.call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("too_many: this build has reached its limit of 100,000 changed blocks; call finish"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	/** A queue far ahead of placement is refused whole, before it fills the game's memory. */
	@Test
	void pendingGuardRefusesAHugeQueue() {
		ToolExecutor tools = briefed();
		sink.pending = Tunables.PENDING_MAX - 1;
		ToolResult refused = tools.call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertTrue(refused.isError() && refused.text().contains("still waiting to be placed; call checkpoint, then go on"), refused.text());
		assertEquals(0, sink.queuedCount());
		sink.pending = Tunables.PENDING_MAX - 2;
		assertFalse(tools.call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}")).isError());
		assertEquals(2, sink.queuedCount());
	}

	/** Positions refused by the pending guard stay protected: nothing was placed there. */
	@Test
	void aRefusedQueueReleasesNothing() {
		world.protection = new ProtectionMask(ALLOWED);
		world.protection.protect(new Pos(0, 0, 1));
		ToolExecutor tools = briefed();
		sink.pending = Tunables.PENDING_MAX;
		tools.call("clear", json("{\"box\":[0,0,1,0,0,1],\"include_protected\":true}"));
		sink.pending = 0;
		ToolResult fill = tools.call("fill", json("{\"box\":[0,0,1,0,0,1],\"block\":\"stone\"}"));
		assertTrue(fill.text().contains("protected"), fill.text());
	}

	/**
	 * Claude Code may send a turn's calls at once: a checkpoint waiting for placement mustn't hold the others up, or
	 * they'd reach the 120 s MCP timeout and still run after Claude was told they failed (M4 review).
	 */
	@Test
	void aCheckpointDoesntHoldUpOtherCalls() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		RecordingSink slow = new RecordingSink() {
			@Override
			public int checkpoint(String phase) {
				entered.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return super.checkpoint(phase);
			}
		};
		ToolExecutor tools = new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED),
				new LocalFrame(new Pos(0, 64, 0), Facing.SOUTH), ALLOWED, SizeTier.SMALL, 100_000, slow, new Budget(150), world);
		tools.call("submit_brief", json(BRIEF));
		CompletableFuture<ToolResult> waiting = CompletableFuture.supplyAsync(() -> tools.call("checkpoint", json("{\"phase\":\"walls\"}")));
		assertTrue(entered.await(5, TimeUnit.SECONDS), "the checkpoint never started waiting");
		ToolResult context = CompletableFuture.supplyAsync(() -> tools.call("get_context", json("{}"))).get(5, TimeUnit.SECONDS);
		assertFalse(context.isError(), context.text());
		ToolResult fill = CompletableFuture.supplyAsync(() -> tools.call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}")))
				.get(5, TimeUnit.SECONDS);
		assertFalse(fill.isError(), fill.text());
		assertFalse(waiting.isDone(), "the checkpoint answered before placement caught up");
		release.countDown();
		assertTrue(waiting.get(5, TimeUnit.SECONDS).text().startsWith("Checkpoint walls"));
	}

	/** A checkpoint answers within the MCP timeout even when placement is behind, and says so. */
	@Test
	void checkpointSaysWhatsStillGoingIn() {
		ToolExecutor tools = briefed();
		sink.pending = 1500;
		ToolResult result = tools.call("checkpoint", json("{\"phase\":\"walls\"}"));
		assertTrue(result.text().contains("1,500 blocks are still going in; call checkpoint again to wait for them"), result.text());
	}

	@Test
	void checkpointWaitsForTheSinkAndReports() {
		ToolResult result = briefed().call("checkpoint", json("{\"phase\":\"walls\"}"));
		assertEquals(List.of("walls"), sink.checkpoints);
		assertEquals("Checkpoint walls: 42 blocks placed so far.", result.text());
	}

	@Test
	void finishEndsTheBuild() {
		ToolExecutor tools = briefed();
		tools.call("finish", json("{\"summary\":\"a small house\"}"));
		assertTrue(tools.finished());
		assertEquals("a small house", sink.finishedWith);
		ToolResult after = tools.call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertTrue(after.isError());
		assertEquals("This build is finished.", after.text());
	}

	@Test
	void budgetFootersAppearNearTheEnd() {
		ToolExecutor tools = executor(20, 100_000);
		String fill = "{\"box\":[0,0,0,0,0,0],\"block\":\"stone\"}";
		tools.call("submit_brief", json(BRIEF));
		for (int call = 2; call <= 15; call++) {
			tools.call("fill", json(fill));
		}
		assertTrue(tools.call("fill", json(fill)).text().endsWith("Budget: 4 tool calls left."));
		tools.call("fill", json(fill));
		tools.call("fill", json(fill));
		assertTrue(tools.call("fill", json(fill)).text().endsWith("Budget nearly spent: finish now."));
		tools.call("fill", json(fill));
		ToolResult over = tools.call("fill", json(fill));
		assertTrue(over.isError());
		assertEquals("Budget reached: call finish now.", over.text());
		tools.call("finish", json("{\"summary\":\"done\"}"));
		assertTrue(tools.finished());
	}

	@Test
	void badArgumentsNameTheArgument() {
		ToolResult result = briefed().call("fill", json("{\"block\":\"stone\"}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("bad_argument") && result.text().contains("box"), result.text());
	}

	@Test
	void roofProblemsComeBackAsBadArgument() {
		ToolResult result = briefed().call("roof", json("{\"box\":[0,4,1,4,4,3],\"style\":\"gable\",\"stairs\":\"oak_stairs\",\"ridge\":\"z\"}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("bad_argument"), result.text());
	}
}
