# AI Builder M8 (Redstone That Works) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Native, as M1–M7) to implement
> this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "a lever that opens a piston door" builds a door that opens when its lever is flipped and closes when it's
flipped back, as soon as the build ends: the build's redstone starts as if placed by hand, Claude tests its machine
before it finishes, and a machine moves and powers only the build's own blocks.

**Architecture:** As M1–M7: the rules and parsing in pure `core` where they can be, the world side thin. A power-up
pass at the end of the placement job (after the shape pass) runs each redstone component's `onPlace` and a neighbour
update, once, on the tick budget. Machine rules (`world/MachineRules`) say which pistons and components could move or
power something that isn't the build's; check_build reports them, tests refuse to run past them, and the end of the
build takes out any that are left. `test_redstone` runs a short experiment in the world over ticks. Undo puts the
build's levers back and lets the machine settle before restoring blocks.

**Tech Stack:** as M7 (Minecraft 26.3, Fabric, Java 25, JUnit 6).

**Spec:** `docs/superpowers/specs/2026-10-22-aibuilder-v2-design.md` §5.1, §5.2, §5.4 (M8), §5.5, §5.6 (and §3's
decisions). The v1 spec still holds.

## Global Constraints

- Everything in the M2–M7 plans' Global Constraints holds (pure `core`, numbers in `Tunables`, relative directions,
  818 placement flags, real Claude never in the suites, Opus 5.5 subagents, never Frankie's real game or worlds).
- Frankie's standing rules: no API key, no paid extra usage, only the world's owner builds, the mod never reads,
  stores or forwards Claude credentials, the MCP token never reaches a log.
- Nothing outside the build is ever pushed or powered by it, and a build never moves a player (spec §5.5, v1 §5.5).
- `test_redstone` limits: 64 steps, 400 ticks waited in all, 32 positions a read; only the build's own levers and
  buttons, inside the allowed box.
- Each task ends green: `./gradlew build` and `./gradlew runClientGameTest` in `mods/aibuilder`.

## Design calls this plan makes (the spec leaves them to it)

- **What a redstone component is** (`world/Redstone.component(BlockState)`): a state that `isSignalSource()`, or a
  block of a class that changes with power or emits it: dust, diodes (repeater, comparator), torches, pistons, lamps,
  doors, trapdoors, fence gates, note blocks, dispensers and droppers, hoppers, rails, copper bulbs, crafters, bells,
  observers, daylight detectors, targets, sculk sensors, TNT. **Reacting** (`Redstone.reacts`): a component that isn't
  only an input (not a lever, button, pressure plate, tripwire hook, daylight detector, target or sculk sensor).
- **The power-up pass** (`world/PowerUp`): over the build's journal positions in the order first changed, each one
  now holding a component that the machine rules allow: `state.onPlace(level, pos, AIR, false)`, then
  `level.updateNeighborsAt(pos, block, null)`; within `TICK_BUDGET_NANOS` a tick, at least one position a tick. A
  position is powered up once per build: the job keeps the set, so the pass before a test and the one at the end never
  repeat one (a clock powered twice would carry two pulses).
- **Players.** Before the pass, if a player stands in the reach of any of the build's pistons, the pass waits
  (`Tunables.MACHINE_PLAYER_WAIT_TICKS`, 600), the action bar saying where; still there then, the pass doesn't run and
  chat says the machine wasn't started ("flip a lever to start it"). `test_redstone` waits the same way, then refuses.
- **A piston's reach:** from its head's spot along its facing, the blocks it would push (up to 12, stopping at air)
  and the spot after the last; for a sticky piston, also the spot in front of the head that it pulls back. Each must be
  inside the allowed box and either air or the build's own block (journaled, still the block the build wrote), and
  none may be slime or honey.
- **Power outside:** a component of the build within 2 blocks (a 5×5×5 cube) of a reacting block that isn't the
  build's.
- **Rule breakers at the end.** check_build reports them (`piston`, `powers_outside`); `test_redstone` refuses while
  there are any, naming them; at the build's end, before the pass, any still left are taken out (put back as they
  were, as undo would) and chat says how many and where, so nothing the player owns is ever moved or powered.
- **Undo of machines.** The journal records each lever the build placed with its `powered` value as placed
  (`UndoJournal.lever(Pos, boolean)`, saved as `levers`, none in older saves). Undo first flips each lever that's
  still the build's back to its placed value (`LeverBlock.pull`), waits `Tunables.UNDO_SETTLE_TICKS` (40) when any
  flipped, then restores positions as v1 does.
- **Dispensers.** An item dispenses on its own when `DispenserBlock.getDispenseMethod(level, stack)` (an invoker)
  answers anything but the default behaviour; place_block_entity refuses such an item in a dispenser (`banned_item`
  with the reason); a dropper takes anything else the build may place.

## Review Focus

1. **A machine next to the player's things**: a build lever beside the player's door or TNT, a build piston beside a
   player's wall: nothing of theirs moves, lights or powers, whatever Claude does. → Task 1 rule tests, Task 2
   `aRuleBreakerIsTakenOut`.
2. **A player in a piston door's opening when the build ends**: they are never pushed. → Task 2
   `thePowerUpWaitsForAPlayerInAPistonsWay`.
3. **A clock**: it runs after the build, at the speed it was built for, and a test before the end doesn't double it.
   → Task 2 `aClockRunsOnceAfterATest`.
4. **Undo of a machine left running or open** leaves nothing behind (no piston head, no moved block). → Task 3
   `undoOfAnOpenPistonDoorClosesItFirst`.
5. **A refinement of a machine** powers up only what it changed, and its tests see the whole machine. → Task 4
   `aRefinementsTestSeesTheWholeMachine`.

---

### Task 1: Machine rules and check_build's two problems

**Files:** Create `world/Redstone.java`, `world/MachineRules.java`; Modify `core/survey/BuildProblems` (kinds `PISTON`,
`POWERS_OUTSIDE`), `world/BuildCheck` (reports them), `world/McWorldReader`; Test `MachineGameTest` (new; register it).

- [ ] Failing tests (server gametests): `aPistonPushingAPlayersBlockIsAProblem`, `aPistonThatWouldPushSlimeIsAProblem`,
  `aPistonPushingOutOfTheBoxIsAProblem`, `aLeverBesideTheWorldsTntIsAProblem`, `aPistonDoorOfTheBuildsOwnIsFine` (a 2×2
  sticky piston door with its lever: check_build finds neither problem). Then implement, green, commit
  `feat(aibuilder): what a machine may touch`.

### Task 2: The power-up pass

**Files:** Create `world/PowerUp.java`; Modify `world/PlacementJob` (the pass after the shape pass; rule breakers taken
out first; the player wait; the set of positions powered up), `BuildManager` (the action bar while waiting; the chat
lines for taken-out pieces and an unstarted machine), `core/request/Messages`, `core/Tunables`; Test `MachineGameTest`.

- [ ] Failing tests: `aLeverOnLightsItsLampOnceTheBuildEnds`, `aTorchNotGateReadsRight` (a lever off → lamp on, on →
  off, after flipping it by hand), `waterAndSandStayPut` (in the same build as a lever and a lamp), `aClockRunsOnceAfterATest`
  (a repeater clock powered by a test, then by the end, pulses at its period), `thePowerUpWaitsForAPlayerInAPistonsWay`,
  `aRuleBreakerIsTakenOut` (the lever beside the world's TNT is put back as it was; the TNT stays unpowered). Then
  implement, green, commit `feat(aibuilder): redstone works when the build ends`.

### Task 3: Undo of machines

**Files:** Modify `core/place/UndoJournal` (`lever`, `levers()`), `world/{PlacementJob,UndoStore,UndoRunner}`; Test
`MachineGameTest`, `UndoGameTest`.

- [ ] Failing tests: `undoOfAnOpenPistonDoorClosesItFirst` (lever on, door open; undo: the lever goes back, the door
  closes, then every block is restored and no piston head or moved block is left), `leversSurviveASave` (UndoStore round
  trip; a journal saved before has none). Then implement, green, commit `feat(aibuilder): undoing a machine`.

### Task 4: `test_redstone`

**Files:** Create `core/tools/RedstoneSteps.java` (pure: parse and limits), `world/RedstoneTest.java` (runs the steps
over ticks; reads; puts levers back; settles); Modify `core/tools/{ToolExecutor,WorldReader}`, `world/McWorldReader`,
`resources/aibuilder/tools.json`; Test `RedstoneStepsTest`, `ToolExecutorTest` (tool count), `MachineGameTest`.

- Steps `{"set": [x, y, z], "on": true}`, `{"press": [x, y, z]}`, `{"wait": ticks}`, `{"read": [[x, y, z], …]}`; a read
  answers each position's block and state (directions relative, as inspect_region), after how many ticks. Waits for what's
  queued first (as check_build); runs the power-up pass over what isn't powered yet; refuses while there are rule
  breakers, naming them; puts every lever back as it was and lets the world settle `Tunables.TEST_SETTLE_TICKS` (20).
- [ ] Failing tests: `stepsAreParsedWithinTheirLimits` (65 steps, 401 ticks, 33 positions, a position outside the box,
  an unknown step), `testRedstoneFlipsALeverReadsALampAndPutsItBack`, `onlyTheBuildsOwnLeversFlip`,
  `aRefinementsTestSeesTheWholeMachine`, `aTestWaitsForAPlayerInAPistonsWay`. Then implement, green, commit
  `feat(aibuilder): test_redstone`.

### Task 5: What a dispenser may hold

**Files:** Create `mixin/DispenserBlockInvoker.java`; Modify `core/blocks/{ItemCatalog,BlockEntityData}` (a dispenser
refuses an item that dispenses on its own), `world/McItemCatalog`, `aibuilder.mixins.json`; Test `BlockEntityDataTest`,
`BlockEntityGameTest`.

- [ ] Failing tests: `aDispenserRefusesALavaBucket`, `aDispenserTakesCobblestone`, `aDropperTakesALavaBucket` (unit, a
  fake catalog), gametest `theGamesDispenseBehavioursAreKnown` (lava bucket, arrow, fire charge, bone meal and a
  minecart dispense on their own; cobblestone and a diamond don't). Then implement, green, commit
  `feat(aibuilder): what a dispenser may hold`.

### Task 6: What Claude is told, a picture, and a live door

**Files:** Modify `resources/aibuilder/prompts/builder_system.md` (machines: the power-up, the rules, test every case
that matters before finish and say what was tested), `tools.json` wording, `src/test/resources/fake-claude/FakeClaude.java`
(scenario `door`: a lever and a 2×2 sticky piston door, test_redstone with the lever on and off), `README.md`,
`CLAUDE.md`, `PLAN.md`; Create `src/gametest/.../MachineClientGameTest.java`; Modify `LiveBuildClientGameTest` (for a
build with levers: flip each lever, look at the build's pistons, flip back, look again; screenshots of both).

- [ ] Failing test: `MachineClientGameTest` (the `door` scenario: closed at the end, open with the lever flipped by the
  player, closed again; screenshots; look at them). Then implement, green, commit
  `feat(aibuilder): what Claude knows about machines`.
- [ ] Live: `AIBUILDER_LIVE=1 AIBUILDER_LIVE_PROMPT="a lever that opens a piston door"`. It passes (spec §5.6) when the
  test flips the lever and the door opens (every build piston changes its extension and the doorway's spots change
  between solid and air), and flipping it back closes it. At most three attempts, each failure fixed in the tools or
  the prompt first, never by loosening the check. Screenshots to Frankie; ledger what came out.

## After the tasks

One fresh Opus 5.5 reviewer reviews the milestone; Critical and Important findings fixed test-first; the ledger
committed to `docs/superpowers/plans/2026-10-22-aibuilder-m8-progress.md`; then M9's plan.
