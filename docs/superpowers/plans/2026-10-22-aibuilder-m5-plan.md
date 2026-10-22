# AI Builder M5 (Quality) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Native, as M1–M4) to implement
> this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Better builds, and builds you can follow. Claude can check its own work (`check_build`), dress a build with
signs, chests, books, banners and item frames (`place_block_entity`), can't set a wooden build alight with lava, and
the player sees a HUD panel with the plan's phases ticking off while blocks go in with a soft sound.

**Architecture:** As M2–M4: pure `core` logic (fire risk, the problem list's text, block-entity descriptions)
behind small world-side readers and writers that run on the server tick within a time budget. The HUD is a clientbound
payload once a second, drawn by a client HUD element.

**Tech Stack:** as M4.

**Spec:** `docs/superpowers/specs/2026-10-21-aibuilder-design.md` — M5 covers §8's `check_build` and
`place_block_entity`, §9's lava rule and placement feel, §10's spawned entities (item frames), §5.3's HUD, and §13's
system prompt tuning. Not in M5: config, refinement, logs, permissions beyond the owner (M6).

## Global Constraints

- Everything in the M2–M4 plans' Global Constraints still holds (pure `core`, `Tunables`, 818 flags, relative
  directions, real Claude never in the suites, Opus 5.5 subagents, never Frankie's real game or worlds).
- Lava rule (§9): lava within 3 blocks sideways or 4 below a flammable block, or a flammable block that close to lava,
  is rejected (`lava_near_flammable`), counting the world and the build so far.
- `check_build` (§8): unsupported blocks (`canSurvive` false; falling blocks over air), dark walkable spots (block
  light 0 under a roof), doors you can't walk through, rooms lower than 2 blocks, fluid that would spill if disturbed
  (an open side, or air under a pool's edge). Positions are local; at most 60 listed, grouped by kind.
- `place_block_entity` (§8): sign text (front, back, color, glow), decorative container items, a lectern book,
  banner patterns, an item frame (an entity: facing, item). Undo removes the build's entities that still exist (§10).
- HUD (§5.3): top left, only for the player building: title, phase list with ticks, blocks placed, budget bar.

## Review Focus

1. **A `check_build` on a big build** (an Epic castle, 100k+ changed blocks) must not freeze the server: it reads over
   ticks, within the time budget. → Task 2 `checkBuildRunsOverTicks`.
2. **Lava placed before the wood** (a lava moat first, the wooden bridge after) is caught as well as the other order.
   → Task 1 `planksNextToLavaAreRejected`.
3. **Item frames and undo:** an item frame whose wall was undone first must not drop as an item. → Task 3
   `undoRemovesItemFramesFirst`.
4. **The HUD when the build ends, the player dies or changes dimension:** it goes away. → Task 4 `hudClearsAtTheEnd`.
5. **Sign and book text from the prompt** (quotes, emoji, very long lines) is stored as plain text, trimmed to what the
   game allows. → Task 3 `signTextIsPlainAndTrimmed`.

---

### Task 1: Lava stays clear of anything that burns (pure + world)

**Files:** Create `core/place/FireMask.java`; Modify `core/blocks/BlockInfo` (a `flammable` flag), `FakeCatalog`,
`world/McBlockCatalog` (`ignitedByLava`), `world/WorldSurvey` (masks), `core/tools/{WorldReader,ToolExecutor}`,
`RejectReason.LAVA_NEAR_FLAMMABLE`; Test `FireMaskTest`, `ToolExecutorTest`, `SurveyGameTest`.

- `FireMask(Box)`: `lava(Pos)`, `flammable(Pos)`, `isLava`, `isFlammable`, `clear(Pos)`; `danger(Pos lava, Pos other)`:
  |dx| ≤ 3, |dz| ≤ 3 and −1 ≤ dy ≤ 4 (other above or beside the lava). `WorldReader.fire()` (read with protection).
- The executor keeps the build's own lava and flammable positions (a write there replaces the world's) and rejects a
  lava entry near a flammable one, or a flammable entry near lava, with `lava_near_flammable`.
- [ ] Failing tests: `lavaNextToPlanksIsRejected`, `planksNextToLavaAreRejected`, `farApartIsFine`,
  `clearingTheWoodMakesRoom`, `stoneDoesntBurn`; gametest `lavaBesideTheWorldsWoodIsRejected`. Then implement, green,
  commit — `feat(aibuilder): lava never next to anything that burns`.

### Task 2: `check_build`

**Files:** Create `core/survey/BuildProblems.java` (records and text), `world/BuildCheck.java` (Tickable);
Modify `WorldReader`, `McWorldReader`, `ToolExecutor`, `tools.json`; Test `BuildProblemsTest`, `CheckBuildGameTest`.

- `BuildCheck` reads the build's changed positions (the journal) and their neighbours over ticks: unsupported
  (`!canSurvive`, or a `FallingBlock` over air), dark (a walkable spot under a roof with block light 0), doors (both
  sides of a closed door blocked at feet or head), low (a walkable spot under the build with less than 2 air), spills
  (a placed source next to air sideways or above air). `BuildProblems.encode(List<Problem>, int max)`: grouped by kind,
  local positions, "and N more", "No problems found." when empty.
- [ ] Failing tests: `problemsAreGroupedAndCapped`; gametests `aTorchOnAirIsUnsupported`, `aDarkRoomIsFound`,
  `aBlockedDoorIsFound`, `aLowCeilingIsFound`, `aPoolEdgeSpills`, `checkBuildRunsOverTicks`. Then implement, green,
  commit — `feat(aibuilder): check_build`.

### Task 3: `place_block_entity` and item frames

**Files:** Create `core/blocks/BlockEntityData.java` (sign, container, lectern, banner), `world/BlockEntityWriter.java`,
`world/ItemFrames.java`; Modify `Placement`, `PlacementJob`, `UndoJournal` (entities), `UndoStore` codec, `UndoRunner`,
`ToolExecutor`, `tools.json`; Test `BlockEntityDataTest`, `BlockEntityGameTest`.

- Sign: front and back up to 4 lines each, a dye color, glow; container: up to 27 `{item, count}` (no shulker boxes
  inside, the item registry checked); lectern: a written book (title, author, pages ≤ 50 × 800 chars); banner: up to 6
  `{pattern, color}`; item frame: an entity on the face of a block, facing out, holding an item, journaled by UUID and
  removed by undo before the blocks.
- [ ] Failing tests: `signTextIsPlainAndTrimmed`, `unknownItemsAreRejected`; gametests `signReadsBack`,
  `chestHoldsItsItems`, `lecternHasTheBook`, `bannerHasItsPatterns`, `itemFrameHoldsItsItem`,
  `undoRemovesItemFramesFirst`. Then implement, green, commit — `feat(aibuilder): signs, chests, books, banners, frames`.

### Task 4: The HUD

**Files:** Create `net/BuildStatusPayload.java` (clientbound), `client/StatusHud.java`; Modify `BuildManager` (send
once a second, empty at the end), `AiBuilderMod`, `AiBuilderClient`; Test `MessagesTest` (budget bar text) and the
client gametest's screenshot during `/aibuild test`.

- Payload: title, phases, phases done, placed, calls used/max, note; an empty title clears the HUD. Drawn top left,
  semi-transparent panel, phase ticks, a budget bar; only for the building player.
- [ ] Failing tests: `DemoBuildClientGameTest` waits for the HUD's state on the client mid-build and takes
  `aibuilder-hud.png` (look at it), and the HUD is gone after "Done:" (`hudClearsAtTheEnd`). Then implement, green,
  commit — `feat(aibuilder): HUD`.

### Task 5: Placement feel, the system prompt, and a live check

**Files:** Modify `PlacementJob` (a soft place sound at ≤ 2 positions a tick and a few particles), `builder_system.md`
(check_build before finish, place_block_entity for detail, the lava rule, what the M4 live build showed); docs.

- [ ] A live build (`AIBUILDER_LIVE=1`, Medium, "a medieval stone castle with a gatehouse"), screenshots looked at,
  what's wrong fixed test-first or in the prompt; docs; commit — `feat(aibuilder): placement feel and a tuned prompt`.

## After the tasks

One fresh Opus 5.5 reviewer reviews M5; Critical/Important fixed test-first; the ledger committed to
`docs/superpowers/plans/2026-10-22-aibuilder-m5-progress.md`; push. No stop (Frankie, 2026-10-22): go on to M6.
