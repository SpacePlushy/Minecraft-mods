# AI Builder M7 (Builds That Move) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Native, as M1–M6) to implement
> this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "a dragon with flapping wings" builds a dragon whose wings beat: any shape can be a named moving part made
of vanilla block displays, parts swing, spin and bob about a pivot, nest in one another, keep moving after a reload,
and go with `/aibuild undo`.

**Architecture:** As M1–M6: the decisions in pure `core` (a new `core/anim`: motions, poses, the display transform
and the motion check, with its own vector and quaternion types), thin world wiring (an accessor mixin for the display
setters, spawning through the placement job, an engine on the server tick, saved data). No client code: vanilla
draws block displays and glides between poses.

**Tech Stack:** as M6 (Minecraft 26.3, Fabric, Java 25, JUnit 6).

**Spec:** `docs/superpowers/specs/2026-10-22-aibuilder-v2-design.md` §4 (and §3's decisions). The v1 spec still holds.

## Global Constraints

- Everything in the M2–M6 plans' Global Constraints holds (pure `core`, numbers in `Tunables`, relative directions,
  818 placement flags, real Claude never in the suites, Opus 5.5 subagents, never Frankie's real game or worlds).
- Frankie's standing rules: no API key, no paid extra usage, only the world's owner builds, the mod never reads,
  stores or forwards Claude credentials, the MCP token never reaches a log.
- Moving parts are vanilla `block_display` entities (`EntityTypes.BLOCK_DISPLAY`). The mixin only exposes private
  setters (`@Invoker`); it changes no game behaviour. No client mod, no client code.
- A pose is a pure function of `gameTime − start` (ticks). A new pose every `ANIMATION_STEP_TICKS` = 4, for the time
  one step ahead, gliding over 4 ticks.
- Limits: `period` 10..2,400 ticks (negative for a spin the other way), angles −180..180, `distance` 0..16, at most
  8 motions a part, 32 parts a build, parents 4 deep, no cycles. `max_moving_blocks` default 2,000 (0..10,000);
  `moving_parts` default `true`.
- The sign rule, in every facing: a positive angle turns clockwise as you look along the axis (about `forward`, a
  part's top moves to the right).
- Each task ends green: `./gradlew build` and `./gradlew runClientGameTest` in `mods/aibuilder`.

## Design calls this plan makes (the spec leaves them to it)

- **Coordinates.** A pivot is in block units where an integer is a block's centre: `[0, 5, 3]` is the centre of
  block (0, 5, 3), `[0.5, 5, 3]` the face between it and (1, 5, 3). Poses are worked out in world-aligned axes
  relative to the frame's origin (`v = A·local + ½`, with `A` the facing's integer matrix, a reflection, from
  `LocalFrame`), so the numbers stay small (floats are exact enough) and rotations are right-handed world
  rotations: clockwise looking along the axis, whatever the facing.
- **Motions.** `swing`: θ(t) = from + (to − from)·(1 − cos 2π(t/period + phase))/2 (starts at `from`, `to` at half a
  period). `spin`: θ(t) = 360°·(t/period + phase). `bob`: d(t) = direction·distance·(1 − cos 2π(t/period + phase))/2.
  A part's motions apply in the order given: its rotations about the pivot, then its bobs. A child's pose is its
  own, then its parent's (`parent ∘ child`).
- **A display's transformation.** A display stands at its block's world corner `e` (relative `v_e = A·b`). For the
  part's rigid motion `w → R·w + t`, the display gets left rotation `R` and translation `R·v_e + t − v_e`, scale 1.
- **Spawning.** A part's blocks are `Placement`s with a `part` name, spawned by the placement job in the attached
  pass as displays at their block's corner, with the block state `StateFactory` gives the block (so facings turn as
  blocks do), view range `Tunables.DISPLAY_VIEW_RANGE` (4, about 256 blocks), and the tag `aibuilder`. They're
  journaled as `UndoJournal.Spawned` with a new `kind` (`item_frame`, the old saves' default, or `display`). Doors,
  beds and tall plants (two-block footprints) can't be in a part (`bad_argument`).
- **What counts.** Displays change no block: they don't count against the changed-blocks cap, don't claim protected
  spots, skip the lava rule; they count against `max_moving_blocks`, and a build that spawned displays has changed
  something (its session and undo entry stay). They join the session's box of changes.
- **Saved data.** `AnimationStore` (overworld saved data, like `UndoStore`): per moving build, an id, its
  dimension, frame (origin, facing), start game time, and parts (name, pivot, motions, parent, displays as UUID and
  local block). A stored build that can't be read is left out with a warning.

## Review Focus

1. **A facing other than south.** The local frame is a mirror image of the world's axes: a wing must flap the way
   the sign rule says in all four facings. → Task 1 `theSignRuleHoldsInEveryFacing`.
2. **A world reloaded mid-flap.** The motion carries on in step from the same game time; no display is left frozen
   or doubled. → Task 4 `motionCarriesOnAfterReload`.
3. **Undo of a moving build, and of a refinement that changed a motion.** Every display goes, none is left frozen,
   and the motion before the refinement comes back. → Task 3 `undoTakesTheDisplaysDown`, Task 6
   `undoingARefinementPutsTheMotionBack`.
4. **A big moving build** (thousands of displays) mustn't stutter the server: the engine spreads within the tick
   budget. → Task 4 `aBigPartSpreadsOverTicks`.
5. **A fast spin** must turn the way it's told: the client glides the short way between two poses, so a step must turn
   less than half a turn (period ≥ 10 with step 4 is at most 144°). → Task 1 `aFastSpinStepsUnderHalfATurn`.

---

### Task 1: Poses (pure)

**Files:** Create `core/anim/{Vec3,Quat,Rigid,Motion,MovingPart,Poses}.java`; Modify `core/Tunables` (the limits,
`ANIMATION_STEP_TICKS`); Test `PosesTest`, `MotionTest`.

**Interfaces (produces):**
- `Vec3(double x, y, z)` with `plus`, `minus`, `times(double)`; `Quat(double w, x, y, z)` with
  `axisAngle(Vec3 unitAxis, double degrees)`, `times(Quat)`, `rotate(Vec3)`; `Rigid(Quat r, Vec3 t)` with
  `apply(Vec3)`, `then(Rigid outer)` (outer ∘ this), `IDENTITY`.
- `sealed interface Motion permits Swing, Spin, Bob`: `Swing(String axis, double from, double to, int period,
  double phase)`, `Spin(String axis, int period, double phase)`, `Bob(String direction, double distance, int period,
  double phase)`; `static Motion parse(JsonObject)` throws `IllegalArgumentException` with the player-facing reason.
- `MovingPart(String name, Vec3 pivot /* local, block-centre units */, List<Motion> motions, String parent /* "" */)`.
- `Poses.rigid(String part, Map<String, MovingPart> parts, double ticks, Facing facing)` → `Rigid` in
  world-aligned axes relative to the origin; `Poses.display(Rigid pose, Pos localBlock, Facing facing)` →
  `DisplayPose(Vec3 translation, Quat rotation)`; `Poses.stepDegrees(Motion)` (the most a step turns).

- [ ] Failing tests: `swingStartsAtFromAndReachesToAtHalfAPeriod`, `aSpinQuarterTurns`, `bobGoesOutAndBack`,
  `phaseShiftsTheCycle`, `aChildRidesItsParent`, `aPivotOutsideThePartCirclesIt`, `theSignRuleHoldsInEveryFacing`
  (a point above the pivot, turned +90° about `forward`, lands on the pivot's right in all four facings),
  `aDisplaysCornersGoWhereTheBlocksWould` (the display transform sends its block's eight corners where the part's
  rigid motion does), `aFastSpinStepsUnderHalfATurn`, `limitsAreRefusedWithTheirReason` (period 9, angle 181,
  distance 17, two kinds in one motion, an unknown axis word). Then implement, green, commit
  `feat(aibuilder): poses for moving parts`.

### Task 2: Moving parts in the tools (pure)

**Files:** Modify `core/place/Placement` (a `part` name, "" for blocks), `core/tools/{ToolExecutor,BuildSink}`,
`core/config/AiBuilderConfig` (`max_moving_blocks`, `moving_parts`), `core/blocks/RejectReason` (as needed),
`resources/aibuilder/tools.json` (`part` on the ten write tools, `animate`); Test `ToolExecutorTest`,
`MovingPartsTest`, `AiBuilderConfigTest`, `ReadToolsTest` (the tool count).

**Interfaces:** consumes Task 1's `Motion.parse`, `MovingPart`. Produces `BuildSink.animate(MovingPart)` (the world
side stores it), `ToolExecutor.parts()` (the build's part names with their display counts and motions, for
`get_context` and `check_motion`), `ToolExecutor.movingBudget(int)`.

- A write with `part`: the same checks as blocks (validator, allowed box, unbreakable and protected spots), no lava
  rule, no claims, a two-block footprint refused; queued as `Placement`s with the part; counted against the moving
  budget (refused whole, `too_many`, with how many are left); not against the changed-blocks cap.
- `animate`: the part must have displays (this build's, or the session's: Task 6); `pivot` within the allowed box
  grown by 16; 1..8 motions; `parent` a known part, no cycle, depth ≤ 4; replaces the part's motions; answers with a
  one-line description of the motion.
- [ ] Failing tests: `aPartIsDisplaysNotBlocks`, `aPartKeepsOffProtectedSpots`, `aPartSkipsTheLavaRule`,
  `aBedCantBeAPart`, `theMovingBudgetRefusesPastIt`, `displaysDontCountAsChangedBlocks`, `animateNeedsAPart`,
  `animateRefusesACycle`, `animateReplacesTheMotion`, `aPivotFarAwayIsRefused`, config `movingPartsSettings`
  (defaults, bounds), tool count. Then implement, green, commit `feat(aibuilder): moving parts in the tools`.

### Task 3: Displays in the world, and undo

**Files:** Create `mixin/DisplayAccessor.java`, `mixin/BlockDisplayAccessor.java` (invokers: `setTransformation`,
`setTransformationInterpolationDuration`, `setTransformationInterpolationDelay`, `setViewRange`, `setBlockState`),
`world/Displays.java` (spawn at a block corner with a state, tag, view range; take down); Modify `aibuilder.mixins.json`,
`world/PlacementJob` (spawn a part's placements, journal them), `core/place/UndoJournal` (`Spawned.kind`),
`world/UndoStore` (codec: `kind` optional, `item_frame` by default), `world/UndoRunner` (displays first, as frames),
`world/McBuildSink`; Test `MovingBuildGameTest` (new), `UndoGameTest`.

- [ ] Failing tests (server gametests): `aPartSpawnsDisplaysNotBlocks` (a scripted build with a 3×1×2 part: six
  displays at those corners with those states, the blocks there unchanged), `aPartsFacingsTurnAsBlocksDo` (stairs in
  a part built facing east look as the same stairs as blocks), `undoTakesTheDisplaysDown`, `anOldSaveStillReads`
  (an `UndoStore` saved without `kind` reads its frames as frames). Then implement, green, commit
  `feat(aibuilder): moving parts are displays in the world`.

### Task 4: The engine and saved motion

**Files:** Create `world/AnimationStore.java` (saved data; skipping codec, as `UndoStore`), `world/AnimationEngine.java`
(server tick: every step, each loaded moving build's displays get the pose one step ahead; spread over ticks within
`Tunables.TICK_BUDGET_NANOS`); Modify `world/McBuildSink` (parts and displays into the store), `AiBuilderMod` (tick
hook), `BuildManager` (`moving_parts`); Test `MovingBuildGameTest`.

- [ ] Failing tests: `theWingMovesOverTime` (a display's transformation at two game times matches `Poses`),
  `aFrozenWorldDoesntMove` (`moving_parts: false`: no change over a step), `motionCarriesOnAfterReload` (the store's
  codec round trip, and the engine poses displays from it), `aBigPartSpreadsOverTicks` (with a 1 ns budget, 64
  displays take 64 ticks or more and all get posed), `aStoredBuildThatCantBeReadIsSkipped`. Then implement, green,
  commit `feat(aibuilder): parts move`.

### Task 5: `check_motion`

**Files:** Create `core/anim/MotionCheck.java` (pure: each part's box at rest and at its motion's ends, or quarter
turns for a spin; the blocks it sweeps through, sampled `Tunables.CHECK_MOTION_SAMPLES` (32) times over its longest
period); Modify `core/tools/{WorldReader,ToolExecutor}` (`WorldReader.solid(List<Pos>)`), `world/McWorldReader`,
`tools.json`; Test `MotionCheckTest`, `ToolExecutorTest`, `MovingBuildGameTest`.

- [ ] Failing tests: `checkMotionSaysWhereAWingGoes`, `aWingThroughTheBodyIsSaidSo`, `aSpinIsCheckedAtQuarterTurns`,
  `noPartsIsSaidSo`; gametest `checkMotionReadsTheWorld`. Then implement, green, commit `feat(aibuilder): check_motion`.

### Task 6: Refinement

**Files:** Modify `core/request/{BuildSession,Refinement}` (a session's parts), `world/SessionStore`,
`core/tools/ToolExecutor` (`get_context` lists the session's parts and motions; `animate` and `part` reach them),
`core/place/UndoJournal` (the motions a refinement replaced), `world/{UndoStore,UndoRunner,AnimationStore}`;
Test `RefinementTest`, `ToolExecutorTest`, `RefinementGameTest`.

- [ ] Failing tests: `aRefinementsContextListsItsParts`, `aRefinementAnimatesAnEarlierPart`,
  `aRefinementAddsToAnEarlierPart`; gametest `undoingARefinementPutsTheMotionBack`. Then implement, green, commit
  `feat(aibuilder): refining moving builds`.

### Task 7: What Claude is told, a picture, and a live dragon

**Files:** Modify `resources/aibuilder/prompts/builder_system.md` (moving parts: when, a solid body as blocks, the
budget, always `check_motion`), `tools.json` wording and examples (a wing about its shoulder, a windmill, a dragon
circling a tower through a parent), `src/test/resources/fake-claude/FakeClaude.java` (scenario `flap`),
`README.md`, `CLAUDE.md`, `PLAN.md`; Create `src/gametest/.../MovingBuildClientGameTest.java`.

- [ ] Failing test: `MovingBuildClientGameTest` (the `flap` scenario builds a body and a hinged wing; screenshots at
  phase 0 and phase ½ show the wing down and up; look at them). Then implement, green, commit
  `feat(aibuilder): what Claude knows about moving parts`.
- [ ] Live: `AIBUILDER_LIVE=1 AIBUILDER_LIVE_PROMPT="a dragon with flapping wings"`, pictured at several moments; the
  screenshots go to Frankie. It passes (spec §5.6) when screenshots half a flap apart differ at the wings and Claude
  called `check_motion`. At most three attempts, each failure fixed in the tools or the prompt first, never by
  loosening the check. Ledger what came out.

## After the tasks

One fresh Opus 5.5 reviewer reviews the milestone; Critical and Important findings fixed test-first; the ledger
committed to `docs/superpowers/plans/2026-10-22-aibuilder-m7-progress.md`; then M8's plan.
