# AI Builder M3 (Survey) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Native, as M1 and M2) to implement
> this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Claude can look before it builds. The read tools (`get_context`, `survey_area`, `inspect_region`,
`search_blocks`) work, `terraform` levels a site, player-made blocks are protected from writes, and
`/aibuild survey` prints what Claude would see.

**Architecture:** The world is read on the server thread by a `WorldSurvey` job (spread over ticks, like
placement) into a pure `TerrainSnapshot` and a `ProtectionMask`; everything else (encoding, protection checks,
terraform's expansion) is pure `core` code behind a `WorldReader` interface the `ToolExecutor` calls from the
backend's thread. Region copies for `inspect_region` hop to the server thread and back with a future.

**Tech Stack:** as M2 (Fabric 26.3, Java 25, JUnit 6, server and client gametests).

**Spec:** `docs/superpowers/specs/2026-10-21-aibuilder-design.md` — M3 covers §8's read tools, `clear`'s
`include_protected`, `terraform` and the `protected` reason, §11 (survey and protection) and §5.2's
`/aibuild survey`. Not in M3: refinement's survey of the last build (M6), check_build (M5), the lava rule (M5).

## Global Constraints

- Everything in the M2 plan's Global Constraints still holds (versions, pure `core`, `Tunables`, 818 flags,
  directions as if facing south, Opus 5.5 subagents, never Frankie's real game).
- Frankie (2026-10-22): no play-test stop at the end; Claude makes the remaining calls and records them as rulings.
- Survey text at most 30,000 characters (§11; Claude Code swaps results over 50,000 characters for a file path).
- `inspect_region` at most 4,096 blocks per call; `survey_area` radius default W/2 + 8, at most 96 (§8).
- Directions in every read result use the relative words (forward/back/left/right/up/down), never compass names.
- Protection (§11): a block is probably player-made unless it's natural terrain or growth. Air is never
  protected. Blocks the same player's earlier AI builds left (found in their journals, still unchanged) aren't
  protected. Unbreakable blocks (bedrock, barriers…) can never be replaced, even with `include_protected`
  (M2 review, deferred minor: a `clear` at y −64 would open the void).

## Review Focus

1. **A site with no ground in range or a flooded site** (all air below, deep ocean, lava sea): the survey and
   terraform must still read sensibly and never crash. → Task 1 `oceanAndVoidColumnsReadSensibly`, Task 5
   `surveyOverWaterAndLava`.
2. **Rough terrain that would blow the 30,000-character cap** must coarsen, never truncate mid-row.
   → Task 1 `roughTerrainCoarsensToFit`.
3. **A player's own house inside the allowed box** must survive a build that fills over it. → Task 3
   `protectedCellsAreRejected`, Task 5 `aPlayersBlocksSurviveAFill`.
4. **Reads while the world changes** (a read tool called mid-build): results reflect the world at copy time and
   never touch the world off the server thread. → Task 5 `inspectRegionSeesWhatTheBuildPlaced`.
5. **A huge `inspect_region` or `survey_area` request** must be refused or capped, not hang. → Task 4
   `inspectRegionIsCappedAt4096`, `surveyRadiusIsCapped`.

---

## File Map (`mods/aibuilder/`)

| Path | Responsibility |
|---|---|
| `core/survey/{Column,TerrainSnapshot,SurveyEncoder}.java` | Pure survey data and its compact text |
| `core/survey/RegionEncoder.java` | `inspect_region` text: palette and layers |
| `core/blocks/BlockSearch.java` | `search_blocks` over a `BlockCatalog` |
| `core/place/ProtectionMask.java` | Which local positions are protected; the build's own writes release them |
| `core/tools/{WorldReader,WorldFacts}.java`, `ToolExecutor`, `tools.json` | Read tools, terraform, protection in the write path |
| `world/{ProtectionHeuristic,WorldSurvey,McWorldReader}.java` | Reading the world on the server thread |
| `BuildManager`, `AiBuildCommand`, `Messages` | `/aibuild survey`; builds read the site first |
| `src/gametest/.../SurveyGameTest.java` | Survey, protection and reads in a real world |

---

### Task 1: The survey snapshot and its text (pure)

**Files:** Create `core/survey/{Column,TerrainSnapshot,SurveyEncoder}.java`; Test `core/survey/SurveyEncoderTest.java`.

**Interfaces — Produces:**
- `record Column(int floor, int top, int ceiling, String floorBlock, int water, int lava, boolean tree, int protectedCount, String protectedBlock)`:
  local y of the floor (§5.4's ground rule from the origin's height), of the highest non-air block, of a ceiling
  above the floor inside the box (`Column.NONE` if none); water/lava depth at the floor (0 if dry); logs or
  leaves above the floor; protected blocks in the column inside the allowed box and the most common one.
- `record TerrainSnapshot(int minX, int minZ, int width, int depth, List<Column> columns)`: local x/z grid,
  row-major by z; `Column at(int x, int z)`.
- `SurveyEncoder.encode(TerrainSnapshot snapshot, Box allowed, int maxChars) → String`: a header (area, the
  local frame reminder); summaries (floor range and average, slope as the average height step between
  neighbours, water coverage % and deepest, lava, ceilings, tree clusters and protected regions as local boxes
  with counts and main blocks, clusters = 4-connected columns); a surface palette (one-character keys, most
  common first); two maps, surface keys and floor heights, one row per z, cells left (x low) to right,
  run-length encoded (`a7` / `3x7`). If the text is longer than `maxChars`, both maps use 2×2 cells (most common
  key, rounded mean height), then 3×3, …, until it fits; the header says the cell size.

- [ ] **Step 1: Write the failing tests:** `flatGrassReadsAsOneRun` (5×3 grass at 0 → the surface rows are
  `a5`, the heights `0x5`, the summary says floor 0..0, slope 0); `aHillShowsInHeightsAndSlope`;
  `waterCoverageAndDepth` (a pond of 4 columns, depth 3 → "water 4 of 15 columns (27%), deepest 3");
  `clustersBecomeBoxes` (two tree groups → two boxes; a protected 2×2 house → one box with count and main block);
  `oceanAndVoidColumnsReadSensibly`; `roughTerrainCoarsensToFit` (a 97×97 random-height snapshot with
  `maxChars` 30,000 → output ≤ 30,000, says "2×2 cells" or coarser, every row complete).
- [ ] **Step 2–4:** run to fail, implement, run green (`./gradlew test`).
- [ ] **Step 5: Commit** — `feat(aibuilder): survey snapshot and its compact text`.

### Task 2: Region text and block search (pure)

**Files:** Create `core/survey/RegionEncoder.java`, `core/blocks/BlockSearch.java`; Test both.

**Interfaces — Produces:**
- `RegionEncoder.encode(Box local, List<String> states) → String`: `states` in x-fastest, then z, then y order,
  each `id[prop=value,…]` with directions already relative (or `minecraft:air`); a palette (`.` = air, then
  one character per state, most common first), then one block of rows per y (`y 3:`), one row per z, x left to
  right.
- `BlockSearch.search(BlockCatalog catalog, String query, int limit) → List<BlockInfo>`: ids containing every
  word of the query (space or `_` separated, namespace ignored), shortest id first; when nothing matches, the
  catalog's closest names (`BlockNames.suggest`). `BlockSearch.describe(BlockInfo) → String`: `id: prop
  v1|v2, …` with compass values shown as relative words (`Directions.toLocalToken`).

- [ ] **Step 1: Failing tests:** `regionShowsLayersWithAPalette`; `regionStatesKeepRelativeDirections`;
  `searchFindsEveryWord` ("oak stair" → `minecraft:oak_stairs` first); `searchFallsBackToSuggestions`;
  `describeUsesRelativeDirections` (oak_stairs facing → `forward|back|left|right`).
- [ ] **Step 2–5:** fail, implement, green, commit — `feat(aibuilder): region text and block search`.

### Task 3: Protection in the write path (pure)

**Files:** Create `core/place/ProtectionMask.java`; Modify `ToolExecutor` (and its tests), `tools.json`
(`clear.include_protected`).

**Interfaces — Produces:**
- `final class ProtectionMask { static ProtectionMask none(); ProtectionMask(Box box); void protect(Pos local);
  void unbreakable(Pos local); boolean isProtected(Pos local); boolean isUnbreakable(Pos local); void release(Pos local); int count(); }`
  (a `BitSet` over the box; outside the box nothing is protected).
- `ToolExecutor` takes a `WorldReader` (Task 4) whose `protection()` gives the mask. Every write cell on a
  protected position is rejected `protected: a block that looks player-made (<id>)`; `clear` with
  `include_protected: true` may remove protected (never unbreakable) blocks; an unbreakable position is always
  rejected. Queued positions are released, so a build can overwrite its own blocks.

- [ ] **Step 1: Failing tests:** `protectedCellsAreRejected`; `clearCanIncludeProtectedButNeverUnbreakable`;
  `aBuildMayOverwriteItsOwnBlocks`.
- [ ] **Step 2–5:** fail, implement, green, commit — `feat(aibuilder): protected blocks stay out of the write path`.

### Task 4: The read tools and terraform (pure)

**Files:** Create `core/tools/{WorldReader,WorldFacts}.java`; Modify `ToolExecutor`, `tools.json`,
`ToolExecutorTest`, `ToolCatalogTest`; Test `core/tools/ReadToolsTest.java`.

**Interfaces — Produces:**
- `interface WorldReader { TerrainSnapshot terrain(int radius); List<String> states(Box local); WorldFacts facts(); ProtectionMask protection(); }`
  (blocking calls with a timeout; `WorldReader.EMPTY` = flat ground at 0 with grass, nothing protected, for tests
  that don't read).
- `record WorldFacts(String dimension, String biome, String time, String weather, String groundBlock, Pos player)`.
- Tools (catalog and handlers): `get_context()` → the frame and direction rules, the facts, the size tier and
  allowed box, the budget left; `survey_area(radius?)` → `SurveyEncoder` (radius default W/2 + 8, max 96,
  centred on the box's footprint); `inspect_region(box)` → `RegionEncoder`, refused with `too_many` over 4,096
  blocks; `search_blocks(query, limit?)` (limit default 20, max 50); `terraform(box, target_y, surface_block,
  fill_block?)` → over the box's footprint (clipped to the allowed box), using the snapshot's floor and top:
  above `target_y` everything up to the column's top becomes air, `target_y` gets `surface_block`, below it
  down to the old floor gets `fill_block` (default `minecraft:dirt`); protected blocks stay.
- `ToolExecutor`'s constructor gains `WorldReader world` (last); the tier for `get_context` comes from a new
  `SizeTier tier` argument.

- [ ] **Step 1: Failing tests:** `everyCatalogToolHasAHandlerAndBack` (now 19 tools); `contextTellsTheFrameAndTheBox`;
  `surveyUsesTheSnapshot`; `surveyRadiusIsCapped`; `inspectRegionIsCappedAt4096`; `searchListsProperties`;
  `terraformCutsAndFills` (a fake terrain with a hill and a dip → exact cells); `readToolsNeedNoBrief`.
- [ ] **Step 2–5:** fail, implement, green, commit — `feat(aibuilder): read tools and terraform`.

### Task 5: Reading the real world (server gametests)

**Files:** Create `world/{ProtectionHeuristic,WorldSurvey,McWorldReader}.java`,
`src/gametest/.../SurveyGameTest.java`; Modify `ArenaBuilds` (a real `McWorldReader`), gametest `fabric.mod.json`.

**Interfaces — Produces:**
- `ProtectionHeuristic.isNatural(BlockState)`: air, fluids and vanilla terrain/growth tags (stone kinds, dirt,
  sand, gravel, clay, mud, ores, logs, leaves, plants, flowers, saplings, snow, ice, corals, kelp…) plus a short
  list; `isUnbreakable(BlockState)` (destroy speed < 0).
- `WorldSurvey implements Tickable`: `WorldSurvey(ServerLevel, LocalFrame, Box allowed, int radius, Set<Pos> ownBlocks)`;
  reads columns within the time budget per tick; `CompletableFuture<Survey> result()` with
  `record Survey(TerrainSnapshot terrain, ProtectionMask protection)`.
- `McWorldReader implements WorldReader`: starts a `WorldSurvey` when first asked (or at build start), copies
  `inspect_region` states on the server thread (`server.execute` + future, 10 s timeout), facts from the level
  (dimension id, biome id at the origin, day time as words, weather, ground block, the player's local position).

- [ ] **Step 1: Failing gametests:** `surveyReadsTheArena` (floor 0 everywhere, stone, no water);
  `surveyOverWaterAndLava`; `aPlayersBlocksSurviveAFill` (planks placed before → fill stone over them → planks
  stay, the rest stone); `inspectRegionSeesWhatTheBuildPlaced`; `earlierBuildsAreNotProtected`.
- [ ] **Step 2–5:** fail, implement, green (server gametests), commit — `feat(aibuilder): survey and protection read from the world`.

### Task 6: `/aibuild survey`, builds read first, docs

**Files:** Modify `BuildManager` (`survey(ServerPlayer)`; `startTest` passes an `McWorldReader`),
`AiBuildCommand` (`survey` literal), `Messages`, `DemoBuildClientGameTest` (survey line), docs (`CLAUDE.md`,
`README.md`, `PLAN.md`, root rows).

- `/aibuild survey`: owner only; reads the site in front of the player as a build would (Auto), then chat gets the
  survey's summary lines and "Full survey (N characters) saved to aibuilder/surveys/<time>.txt" (game folder);
  the log gets the full text.

- [ ] **Step 1: Failing test:** `DemoBuildClientGameTest` runs `/aibuild survey` first → chat shows a line
  starting "Survey:"; the file exists.
- [ ] **Step 2–5:** fail, implement, green (all three suites), docs, commit — `feat(aibuilder): /aibuild survey`.

## After the tasks

One fresh Opus 5.5 reviewer reviews M3; Critical/Important fixed test-first; the ledger committed to
`docs/superpowers/plans/2026-10-22-aibuilder-m3-progress.md`; push. No stop (Frankie, 2026-10-22): go on to M4.
