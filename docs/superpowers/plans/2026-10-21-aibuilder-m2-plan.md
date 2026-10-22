# AI Builder M2 (Placement Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/aibuild test` builds a scripted demo house in front of the player through the real tool layer and placement engine: local frame and relative directions, validated blocks, geometry primitives, tick-batched placement without physics, then a shape pass; `/aibuild undo` puts the land back exactly (saved with the world); `/aibuild cancel` stops a build that's under way.

**Architecture:** Everything with logic is pure Java under `io.palmisano.aibuilder.core` and unit-tested first: the frame (`core.frame`), blocks and validation against a `BlockCatalog` interface (`core.blocks`), primitives (`core.shapes`), placement order and the generic undo journal (`core.place`), the tool layer with a `BuildSink` interface (`core.tools`), the backend interface plus `ScriptedBackend` (`core.agent`), and the ground rule (`core.site`). A thin Minecraft layer (`world.*`) implements the catalog from the registry, turns resolved blocks into rotated `BlockState`s, places them from a server-tick job, restores them, holds chunk tickets and saves journals as `SavedData`. Server gametests (headless) prove placement and undo in a 16×12×16 arena; one client gametest screenshots the demo house.

**Tech Stack:** Java 25, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3 (lifecycle events, server gametest API, client gametest API), Gson and DataFixerUpper codecs (shipped with Minecraft), JUnit 6.1.3, Python 3 stdlib (arena generator).

**Spec:** `docs/superpowers/specs/2026-10-21-aibuilder-design.md`. M2 covers §5.2 (`/aibuild test|undo|cancel|status`), §5.3 (chat and action bar for these), §5.4 (site, allowed box; refinement is M6), §5.5, §7, §8 (the write tools below, rejections, banned blocks, two-block blocks, persistent leaves), §9, §10, §12.1 (`AiBackend`, `ScriptedBackend`), §12.3 (tool-call cap), §17.1–3. **Not in M2:** read tools, protection heuristic and `terraform` (M3, with the survey); `place_block_entity`, `check_build`, the lava rule and the placement sound/particle "feel" (M5); `config/aibuilder.json` (M6: M2's numbers live in `core.Tunables`); refinement (M6). The M1 echo for `/aibuild <prompt>` and the screen's Build stays until M4.

## Global Constraints

- Versions, mod id, package, author and commit format as in M1. Every subagent runs on Opus 5.5 (`model: "opus"`).
- `io.palmisano.aibuilder.core..` imports nothing from Minecraft or Fabric (Gson and the JDK are fine); `src/main` never imports `net.minecraft.client.*`.
- Every tunable number lives in `core.Tunables` (workspace rule), with these values: `BLOCKS_PER_TICK = 250`, `TICK_BUDGET_NANOS = 3_000_000`, `SITE_GAP = 3`, `MAX_TOOL_CALLS = 150`, `SET_BLOCKS_MAX = 2000`, `UNDO_HISTORY = 5`, `GROUND_SEARCH_UP = 16`, `GROUND_SEARCH_DOWN = 64`, `STATUS_INTERVAL_TICKS = 20`, `CHECKPOINT_TIMEOUT_SECONDS = 120`.
- Directions (§7): tool input uses `forward` (+z), `back` (−z), `right` (+x), `left` (−x), `up`, `down`; they map to the compass as if the player faced south (forward → south, back → north, right → west, left → east), then vanilla `BlockState.rotate`: SOUTH `NONE`, WEST `CLOCKWISE_90`, NORTH `CLOCKWISE_180`, EAST `COUNTERCLOCKWISE_90`. Only properties whose vanilla values are compass directions translate (`facing`, rail `shape`, and properties *named* `north/south/east/west`); `hinge`, chest `type`, stair `shape` (`inner_left`…) keep their values. `axis` and `rotation` pass through unchanged before the vanilla rotate. Compass names in input are rejected.
- Placement flags `Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS` (818), as vanilla `/fill … strict`.
- Banned (§8): every block whose `getDestroySpeed` is −1, plus `minecraft:tnt`, `spawner`, `trial_spawner`, `fire`, `soul_fire`, `piston_head`.
- Server gametests on (`enableGameTests = true`); they run headless with `./gradlew runGameTest`. Each uses `@GameTest(structure = "aibuilder-test:arena")`: 16×12×16 with a stone floor at y 0. Server gametests never go through the owner check (a test server has no singleplayer owner); they drive the engine directly.
- Real Claude is never called. Never touch `~/Library/Application Support/minecraft`. Gametests wait on conditions; look at every screenshot.

## Review Focus

1. **Facing exactly between two directions** (yaw 44.9° vs 45.1°, negative and wrapped yaws like −90° or 810°) must snap predictably. → Task 1 `yawSnapsToTheNearestQuarter`.
2. **A site near the world's height limits** must clip the allowed box and reject positions outside the world, never crash. → Task 10 `worldLimitsClipTheBox`, Task 7 `outsideTheBoxIsRejected`.
3. **Undo far from the player** (chunks unloaded) must still restore everything. → Task 9 `UndoRunner` holds tickets; Task 9 `undoReachesFarPositions` gametest.
4. **Undo after quitting and reloading the world** must still work. → Task 9 `journalSurvivesSaving`.
5. **Commands with nothing to act on, and a second build while one runs**, must answer plainly and never start a second job. → Task 10 `nothingToCancelOrUndo`, `oneBuildAtATime`.

---

## File Map (`mods/aibuilder/`)

| Path | Responsibility |
|---|---|
| `src/main/java/io/palmisano/aibuilder/core/Tunables.java` | Every M2 number |
| `core/frame/{Facing,Pos,LocalFrame,Directions}.java` | Facing from yaw, local ↔ world positions, relative ↔ compass tokens |
| `core/blocks/{BlockSpec,BlockInfo,PlacementPass,BlockCatalog,BlockNames,ResolvedBlock,RejectReason,Rejection,Validation,Validator}.java` | Block specs, the catalog interface, validation with reasons and suggestions |
| `core/shapes/{Box,LocalBlock,FillMode,Axis,ArchStyle,RoofStyle,Shapes,Roofs,Staircases}.java` | Primitives → local blocks |
| `core/place/{Placement,PlacementOrder,BlockEquivalence,UndoJournal,RestoreDecision,UndoHistory}.java` | Order of placement, the undo journal, per-player history |
| `core/tools/{ToolDef,ToolCatalog,ToolResult,Brief,BuildSink,Budget,ToolExecutor}.java`, `src/main/resources/aibuilder/tools.json` | The write tools |
| `core/agent/{AiBackend,BuildRequest,BuildHandle,BuildOutcome,ScriptedBackend}.java`, `src/main/resources/aibuilder/scripts/demo_house.json` | Backends; the demo script |
| `core/site/{Cell,ColumnProbe,GroundFinder,BuildBox}.java` | The ground rule and the allowed box |
| `core/request/{SizeTier,Messages}.java` | Modify: tier sizes and caps; new chat lines |
| `world/{McBlockCatalog,StateFactory,Occupancy,PlacementJob,JobScheduler,BuildTickets,McBuildSink,UndoStore,UndoRunner}.java` | The Minecraft side |
| `BuildManager.java`, `AiBuildCommand.java`, `AiBuilderMod.java` | Modify: per-server build state, subcommands, registration |
| `tools/gen_arena.py`, `src/gametest/resources/data/aibuilder-test/gametest/structure/arena.snbt` | The server-gametest arena |
| `src/gametest/java/io/palmisano/aibuilder/test/{ArenaBuilds,PlacementGameTest,UndoGameTest,PermissionGameTest,DemoBuildClientGameTest}.java` | Gametests |

---

### Task 1: Frame and directions (pure)

**Files:** Create `core/Tunables.java`, `core/frame/Facing.java`, `Pos.java`, `LocalFrame.java`, `Directions.java`; Test `src/test/java/io/palmisano/aibuilder/core/frame/{FacingTest,LocalFrameTest,DirectionsTest}.java`.

**Interfaces — Produces:**
- `record Pos(int x, int y, int z)`.
- `enum Facing { NORTH, EAST, SOUTH, WEST; static Facing fromYaw(float yaw); int forwardX(); int forwardZ(); int rightX(); int rightZ(); int quarterTurnsFromSouth(); }`: Minecraft yaw 0 = south, 90 = west, 180 = north, −90 = east; `fromYaw` = `floorMod(round(yaw / 90), 4)` → SOUTH, WEST, NORTH, EAST. Forward (x, z): SOUTH (0, 1), WEST (−1, 0), NORTH (0, −1), EAST (1, 0); right is forward turned clockwise seen from above: SOUTH (−1, 0), WEST (0, −1), NORTH (1, 0), EAST (0, 1). Quarter turns: SOUTH 0, WEST 1, NORTH 2, EAST 3.
- `record LocalFrame(Pos origin, Facing facing) { Pos toWorld(Pos local); Pos toLocal(Pos world); }`: world = origin + x·right + y·up + z·forward.
- `final class Directions { static String toTemplateToken(String token); static String toLocalToken(String token); static String toTemplateValue(String value); static String toLocalValue(String value); static boolean isCompassToken(String token); static boolean mentionsCompass(String nameOrValue); }`: tokens are `_`-separated; non-direction tokens pass through.

- [ ] **Step 1: Write the failing tests.**

```java
// FacingTest
@Test void yawSnapsToTheNearestQuarter() {
    assertEquals(Facing.SOUTH, Facing.fromYaw(0f));
    assertEquals(Facing.SOUTH, Facing.fromYaw(44.9f));
    assertEquals(Facing.WEST, Facing.fromYaw(45.1f));
    assertEquals(Facing.NORTH, Facing.fromYaw(180f));
    assertEquals(Facing.NORTH, Facing.fromYaw(-180f));
    assertEquals(Facing.EAST, Facing.fromYaw(-90f));
    assertEquals(Facing.EAST, Facing.fromYaw(270f));
    assertEquals(Facing.WEST, Facing.fromYaw(810f));
}

// LocalFrameTest — expectations hand-derived from Minecraft's axes (+X east, +Z south)
@Test void rightAndForwardFollowTheFacing() {
    Pos o = new Pos(10, 64, 20);
    assertEquals(new Pos(9, 64, 20), new LocalFrame(o, Facing.SOUTH).toWorld(new Pos(1, 0, 0)));
    assertEquals(new Pos(10, 64, 21), new LocalFrame(o, Facing.SOUTH).toWorld(new Pos(0, 0, 1)));
    assertEquals(new Pos(10, 64, 19), new LocalFrame(o, Facing.WEST).toWorld(new Pos(1, 0, 0)));
    assertEquals(new Pos(9, 64, 20), new LocalFrame(o, Facing.WEST).toWorld(new Pos(0, 0, 1)));
    assertEquals(new Pos(11, 64, 20), new LocalFrame(o, Facing.NORTH).toWorld(new Pos(1, 0, 0)));
    assertEquals(new Pos(10, 64, 19), new LocalFrame(o, Facing.NORTH).toWorld(new Pos(0, 0, 1)));
    assertEquals(new Pos(10, 64, 21), new LocalFrame(o, Facing.EAST).toWorld(new Pos(1, 0, 0)));
    assertEquals(new Pos(11, 64, 20), new LocalFrame(o, Facing.EAST).toWorld(new Pos(0, 0, 1)));
    assertEquals(new Pos(10, 67, 20), new LocalFrame(o, Facing.EAST).toWorld(new Pos(0, 3, 0)));
}
@Test void toLocalUndoesToWorld() { /* every facing, points (0,0,0), (3,-2,5), (-4,7,-1): toLocal(toWorld(p)) == p */ }

// DirectionsTest
@Test void tokensMapAsIfThePlayerFacedSouth() {
    assertEquals("south", Directions.toTemplateToken("forward"));
    assertEquals("north", Directions.toTemplateToken("back"));
    assertEquals("west", Directions.toTemplateToken("right"));
    assertEquals("east", Directions.toTemplateToken("left"));
    assertEquals("up", Directions.toTemplateToken("up"));
    assertEquals("bottom", Directions.toTemplateToken("bottom"));
}
@Test void joinedValuesTranslateTokenByToken() {
    assertEquals("north_south", Directions.toTemplateValue("back_forward"));
    assertEquals("ascending_west", Directions.toTemplateValue("ascending_right"));
    assertEquals("back_forward", Directions.toLocalValue("north_south"));
}
@Test void compassWordsAreSpotted() {
    assertTrue(Directions.mentionsCompass("east"));
    assertTrue(Directions.mentionsCompass("ascending_north"));
    assertFalse(Directions.mentionsCompass("forward"));
    assertFalse(Directions.mentionsCompass("inner_left"));
}
```

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew test --console=plain` → compile failure.
- [ ] **Step 3: Implement** the five files with the signatures and values above (`Tunables` gets all Global Constraints numbers now).
- [ ] **Step 4: Run the tests.** Run: `./gradlew test --console=plain -q && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml` → all `failures="0" errors="0"`.
- [ ] **Step 5: Commit** — `feat(aibuilder): local frame and relative directions`.

---

### Task 2: Blocks, catalog and validation (pure)

**Files:** Create `core/blocks/*.java` (see File Map); Test `src/test/java/io/palmisano/aibuilder/core/blocks/{ValidatorTest,BlockNamesTest,FakeCatalog}.java`.

**Interfaces — Consumes:** `Directions`. **Produces:**
- `record BlockSpec(String id, Map<String, String> state)` (state in relative words; `static BlockSpec of(String id)` = empty state).
- `enum PlacementPass { STRUCTURE, PARTIAL, FALLING, FLUID, ATTACHED }` (placement order).
- `record BlockInfo(String id, Map<String, List<String>> properties, boolean unbreakable, PlacementPass pass)`: `properties` in vanilla names and values.
- `interface BlockCatalog { Optional<BlockInfo> find(String id); Collection<String> ids(); }`.
- `final class BlockNames { static String normalize(String id); static List<String> suggest(Collection<String> ids, String query, int max); }`: `normalize` lowercases, strips, adds `minecraft:` when there's no namespace. `suggest` ranks ids whose path contains the query path (or the reverse) first, then by edit distance on the path; ties by id.
- `record ResolvedBlock(BlockInfo info, Map<String, String> state)` (vanilla names and values, before rotation).
- `enum RejectReason { UNKNOWN_BLOCK, BAD_PROPERTY, BAD_VALUE, COMPASS_DIRECTION, BANNED_BLOCK, OUT_OF_BOUNDS, TOO_MANY, BAD_ARGUMENT }`; `record Rejection(RejectReason reason, String detail)`; wire names are the lower-case enum names.
- `sealed interface Validation permits Validation.Valid, Validation.Invalid` with `record Valid(ResolvedBlock block)` and `record Invalid(Rejection rejection)`.
- `final class Validator { Validator(BlockCatalog catalog, Set<String> bannedIds); static final Set<String> DEFAULT_BANNED; Validation validate(BlockSpec spec); }`. Order: unknown id (detail lists up to 5 `suggest` results) → banned (`unbreakable` or listed) → any compass word in a property name or value → translate: a name that's a relative word becomes its compass word; a value is translated with `toTemplateValue` **only when** that vanilla property's possible values mention a compass word → unknown property (detail lists the block's property names in relative words) → value not allowed (detail lists the allowed values, translated back with `toLocalValue` when the property is directional).

- [ ] **Step 1: Write the failing tests.** `FakeCatalog` holds, in vanilla terms: `minecraft:oak_stairs` {facing: north/south/west/east, half: top/bottom, shape: straight/inner_left/inner_right/outer_left/outer_right, waterlogged: true/false} PARTIAL; `minecraft:oak_door` {facing ×4, half: upper/lower, hinge: left/right, open, powered} ATTACHED; `minecraft:oak_log` {axis: x/y/z} STRUCTURE; `minecraft:rail` {shape: north_south, east_west, ascending_east, ascending_west, ascending_north, ascending_south, south_east, south_west, north_west, north_east} ATTACHED; `minecraft:vine` {north, east, south, west, up: true/false} ATTACHED; `minecraft:stone` {} STRUCTURE; `minecraft:bedrock` {} unbreakable; `minecraft:tnt` {unstable: true/false}.

```java
@Test void stairsFacingForwardBecomeSouth() → validate(spec("oak_stairs", facing=forward, half=bottom)) is Valid with state {facing=south, half=bottom}
@Test void handednessIsNotADirection() → oak_door {facing=right, hinge=left} → {facing=west, hinge=left}
@Test void stairCornersKeepTheirShape() → oak_stairs {facing=left, shape=inner_left} → {facing=east, shape=inner_left}
@Test void railShapesTranslateTokenByToken() → rail {shape=back_forward} → {shape=north_south}
@Test void vineSidesTranslateByName() → vine {right=true} → {west=true}
@Test void namespaceIsOptional() → "OAK_LOG" → Valid, info().id() == "minecraft:oak_log"
@Test void unknownBlockSuggestsNames() → "oak_stair" → Invalid(UNKNOWN_BLOCK), detail contains "minecraft:oak_stairs"
@Test void compassNamesAreRejectedWithAHint() → oak_stairs {facing=east} → COMPASS_DIRECTION, detail contains "forward"
@Test void unknownPropertyListsTheRealOnes() → oak_stairs {color=red} → BAD_PROPERTY, detail contains "facing" and "half"
@Test void badValueListsAllowedValuesInRelativeWords() → oak_stairs {facing=up} → BAD_VALUE, detail contains "forward" and does not contain "south"
@Test void unbreakableAndListedBlocksAreBanned() → bedrock and tnt → BANNED_BLOCK
// BlockNamesTest
@Test void closestNameComesFirst() → suggest(ids, "oak_stair", 5).getFirst() == "minecraft:oak_stairs"; suggest(ids, "stone", 5).getFirst() == "minecraft:stone"
```

- [ ] **Step 2: Run them to see them fail.** `./gradlew test --console=plain` → compile failure.
- [ ] **Step 3: Implement** the classes as specified.
- [ ] **Step 4: Run the tests** (Task 1's command) → all green.
- [ ] **Step 5: Commit** — `feat(aibuilder): block validation with reasons and suggestions`.

---

### Task 3: Boxes, fills, lines and walls (pure)

**Files:** Create `core/shapes/{Box,LocalBlock,FillMode,Shapes}.java`; Test `core/shapes/ShapesTest.java`.

**Interfaces — Produces:**
- `record Box(Pos min, Pos max) { static Box of(int x1, int y1, int z1, int x2, int y2, int z2); boolean contains(Pos p); long volume(); }` (corners in any order, inclusive).
- `record LocalBlock(Pos pos, BlockSpec block, String onlyReplacing)` (`onlyReplacing` null, a block id or `#tag`).
- `enum FillMode { SOLID, HOLLOW, OUTLINE }`.
- `final class Shapes { static List<LocalBlock> fill(Box box, BlockSpec block, FillMode mode); static List<LocalBlock> replace(Box box, BlockSpec block, String filter); static List<LocalBlock> line(Pos from, Pos to, BlockSpec block); static List<LocalBlock> wall(List<int[]> points, int y, int height, BlockSpec block, int thickness); }`. HOLLOW = the shell gets `block`, the inside gets `minecraft:air`. `line` is a 3D DDA with `Math.round`, both ends included, no duplicates. `wall` draws `line`s in the x–z plane between consecutive points at every y in `y .. y+height-1`, no duplicate corners; each extra unit of `thickness` adds a copy offset one block to the right of the walking direction (right of direction (dx, dz) is (dz, −dx): walking +z, right is +x; walking +x, right is −z).

- [ ] **Step 1: Write the failing tests.**

```java
@Test void boxCornersInAnyOrder() → Box.of(3,0,3, 1,2,1): min (1,0,1), max (3,2,3), volume 27
@Test void solidFillCoversTheBox() → fill(Box.of(0,0,0,1,1,1), stone, SOLID): 8 blocks, all stone
@Test void hollowFillMakesAShellAndClearsTheInside() → 3×3×3 HOLLOW: 26 stone, (1,1,1) is minecraft:air
@Test void outlineLeavesTheInsideAlone() → 3×3×3 OUTLINE: 26 blocks, none at (1,1,1)
@Test void replaceCarriesTheFilter() → replace(Box.of(0,0,0,1,0,0), stone, "#minecraft:dirt"): 2 blocks, both onlyReplacing "#minecraft:dirt"
@Test void straightLineIncludesBothEnds() → line((0,0,0),(3,0,0)): x = 0,1,2,3
@Test void diagonalLineStepsEvenly() → line((0,0,0),(2,2,2)): (0,0,0),(1,1,1),(2,2,2)
@Test void wallFollowsThePolylineWithoutDuplicateCorners() → wall([[0,0],[2,0],[2,2]], 1, 2, stone, 1): 10 blocks at y 1..2 over columns (0,0),(1,0),(2,0),(2,1),(2,2)
@Test void thickWallGrowsToTheRightOfTravel() → wall([[0,0],[2,0]], 1, 1, stone, 2): (0..2, 1, 0) and (0..2, 1, -1)
```

- [ ] **Step 2: Run to fail** → compile failure. **Step 3: Implement.** **Step 4: Run** → green. **Step 5: Commit** — `feat(aibuilder): boxes, fills, lines and walls`.

---

### Task 4: Cylinders, spheres, cones and arches (pure)

**Files:** Modify `core/shapes/Shapes.java`; Create `Axis.java`, `ArchStyle.java`; Test `core/shapes/RoundShapesTest.java`.

**Interfaces — Produces:** `enum Axis { X, Y, Z }`, `enum ArchStyle { ROUND, POINTED }`; `Shapes.cylinder(Pos center, int radius, int height, BlockSpec block, Axis axis, boolean hollow)`, `Shapes.sphere(Pos center, int radius, BlockSpec block, boolean hollow, boolean dome)`, `Shapes.cone(Pos base, int radius, int height, BlockSpec block, boolean hollow)`, `Shapes.arch(Pos from, Pos to, int rise, BlockSpec block, int thickness, ArchStyle style)`. A disc of radius r holds the cells with `dx² + dz² < (r + 0.5)²`; a ball, `dx² + dy² + dz² < (r + 0.5)²`; hollow = minus the radius r − 1 shape. A cylinder's height runs along `axis` starting at `center`. Cone layer k (0 at the base) has radius `floor(r·(h − k)/h)`. An arch lies in the vertical plane through `from` and `to`, which must share y and one of x or z (else `IllegalArgumentException`); its curve starts and ends at the feet, peaks at `rise` above the midpoint, is computed for the half from `from` to the middle and mirrored, and joins consecutive columns with 2D lines so it has no gaps. ROUND is a half-ellipse; POINTED is two circle arcs of radius = span, each centred on the opposite foot, scaled to `rise`. `thickness` copies go to the right of the from → to direction.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void cylinderOfRadiusOneIsAThreeByThreeColumn() → cylinder((0,0,0), 1, 2, stone, Y, false): 18 blocks
@Test void cylinderRadiusTwoDropsTheCorners() → radius 2, height 1: 21 blocks; (2,0,1) present, (2,0,2) absent
@Test void hollowCylinderKeepsTheRing() → radius 2, height 1, hollow: 12 blocks
@Test void cylinderAlongXLiesOnItsSide() → axis X, radius 1, height 3: 27 blocks with x in 0..2
@Test void sphereOfRadiusTwo() → 81 blocks; (2,0,0), (0,2,0), (0,0,2) present; (2,2,0) absent
@Test void hollowSphereIsAShell() → radius 2 hollow: 62 blocks
@Test void domeKeepsTheUpperHalf() → radius 2 dome: 51 blocks, none with y < 0
@Test void coneNarrowsToAPoint() → base (0,0,0), radius 2, height 3: 31 blocks; the y=2 layer is the single block (0,2,0)
@Test void roundArchSpansAndPeaks() → arch((0,0,0),(4,0,0), 2, stone, 1, ROUND): has (0,0,0), (4,0,0), (2,2,0); mirror-symmetric about x=2; consecutive blocks along the curve are at most 1 apart in x and y
@Test void pointedArchPeaksInTheMiddle() → arch((0,0,0),(6,0,0), 4, stone, 1, POINTED): has (3,4,0); symmetric about x=3; gap-free; its column at x=1 is higher than ROUND's at x=1 with the same span and rise
@Test void slantedArchIsRefused() → arch((0,0,0),(3,0,2), …) throws IllegalArgumentException
```

- [ ] **Step 2: Run to fail. Step 3: Implement. Step 4: Run** → green. **Step 5: Commit** — `feat(aibuilder): cylinders, spheres, cones and arches`.

---

### Task 5: Roofs and staircases (pure)

**Files:** Create `core/shapes/{RoofStyle,Roofs,Staircases}.java`; Test `core/shapes/{RoofsTest,StaircasesTest}.java`.

**Interfaces — Produces:**
- `enum RoofStyle { GABLE, HIP, FLAT }`; `Roofs.roof(Box box, RoofStyle style, BlockSpec stairs, BlockSpec slab, Axis ridge, int overhang, BlockSpec gable)` (`slab`, `ridge`, `gable` may be null; null `ridge` = along the longer horizontal side, X on a tie). The roof starts at `box.min().y()` over the box's footprint grown by `overhang` (0–2) on every side. GABLE with ridge Z: layer k (y = base + k) has stairs at x = minX′ + k facing `right` and at maxX′ − k facing `left`, half `bottom`, for every z of the grown footprint, until they meet; an odd width ends in one row of `slab` (type `bottom`, required, else `IllegalArgumentException`). Ridge X is the same turned (stairs at z ends facing `forward` and `back`). `gable` fills, at the box's own two end faces (not the overhang), every cell strictly between that layer's two stairs. HIP: layer k is the ring k in from the grown footprint's edge, stairs facing inward (back edge `forward`, front edge `back`, left edge `right`, right edge `left`; corners take their z edge's facing, the finalize pass fixes their corner shape); a final line or point becomes `slab`s. FLAT: one layer of `slab` (type `bottom`) over the grown footprint (slab required).
- `Staircases.straight(Pos from, String direction, int steps, int width, BlockSpec stairs)`: step i at `from + i·direction + i·up`, facing `direction`, half `bottom`; extra width to the right of `direction`. `Staircases.spiral(Pos center, int radius, int fromY, int toY, BlockSpec stairs, boolean clockwise)`: one step per level from `fromY` to `toY` round the ring of cells at Chebyshev distance `radius` from the centre column (the centre column is left for Claude's pillar), starting at the cell straight `back` of the centre; clockwise seen from above goes back → left → forward → right; each step faces the direction of travel to the next cell.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void gableRowsClimbToTheRidge() → roof(Box.of(0,0,0,4,0,2), GABLE, oak_stairs, oak_slab, Z, 0, null): 15 blocks; y0: x0 facing right, x4 facing left (z 0..2); y1: x1 right, x3 left; y2: x2 slab type bottom
@Test void evenWidthsMeetWithoutASlab() → Box x 0..3: y0 x0 right, x3 left; y1 x1 right, x2 left; no slab; slab may be null
@Test void overhangWidensTheEaves() → width 5, overhang 1: y0 stairs at x -1 and x 5, z from -1 to 3; the y3 layer is the slab row at x 2
@Test void gableEndsFillBetweenTheSlopes() → width 5, gable oak_planks: at z 0 and z 2, y0 x 1..3 and y1 x 2 are oak_planks; nothing at y2 besides the slab
@Test void oddGableWithoutASlabIsRefused() → throws IllegalArgumentException
@Test void hipRoofSlopesOnAllSides() → 5×5 HIP: 25 blocks; y0 16 stairs (z0 edge facing forward, z4 back, x0 right, x4 left), y1 8 stairs, y2 one slab at (2,2,2)
@Test void flatRoofIsOneLayerOfSlabs() → Box.of(0,3,0,2,3,2) FLAT with slab: 9 slabs at y 3
@Test void straightStairsClimbTowardTheirDirection() → straight((0,0,0), "forward", 3, 1, oak_stairs): (0,0,0), (0,1,1), (0,2,2), all facing forward, half bottom
@Test void wideStairsGrowToTheRight() → width 2, direction forward: also (1,0,0), (1,1,1), (1,2,2)
@Test void spiralGoesRoundAndUp() → spiral((0,0,0), 1, 0, 7, oak_stairs, true): 8 steps; first three (0,0,-1) facing left, (-1,1,-1) facing forward, (-1,2,0) facing forward; y rises by 1 each step; no step in the centre column; all 8 ring cells used once
```

- [ ] **Step 2: Run to fail. Step 3: Implement. Step 4: Run** → green. **Step 5: Commit** — `feat(aibuilder): roofs and staircases`.

---

### Task 6: Placement order, undo journal and history (pure)

**Files:** Create `core/place/*.java`; Test `core/place/{PlacementOrderTest,UndoJournalTest,UndoHistoryTest}.java`.

**Interfaces — Consumes:** `Pos`, `ResolvedBlock`, `PlacementPass`. **Produces:**
- `record Placement(Pos pos, ResolvedBlock block, String onlyReplacing)` (world position).
- `PlacementOrder.sort(List<Placement>) → List<Placement>`: by `block.info().pass()` order, then y ascending, otherwise stable.
- `BlockEquivalence.same(String blockIdA, String blockIdB) → boolean`: equal ids, or both in {dirt, grass_block, podzol, mycelium, dirt_path, coarse_dirt, rooted_dirt}, or both in {water, ice, frosted_ice} (all `minecraft:`).
- `enum RestoreDecision { RESTORE, KEEP }`.
- `final class UndoJournal<S, D> { void beforeWrite(Pos pos, S original, D originalData); void afterWrite(Pos pos, String blockId, int dataHash); List<Pos> positions(); S original(Pos pos); D originalData(Pos pos); String writtenBlock(Pos pos); int writtenDataHash(Pos pos); int size(); RestoreDecision decide(Pos pos, String currentBlockId, int currentDataHash); }`: `beforeWrite` keeps only the first original per position; `afterWrite` keeps the latest; `positions()` in first-write order; `decide` restores when `BlockEquivalence.same(current, written)` and the data hashes match.
- `final class UndoHistory<J> { UndoHistory(int max); void push(UUID player, J entry); Optional<J> pop(UUID player); int size(UUID player); }`: newest first; beyond `max` the oldest is dropped.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void passesComeInOrderBottomUpWithinEach() → in: [ATTACHED y1, STRUCTURE y2, STRUCTURE y1, FLUID y0, FALLING y3, PARTIAL y1] → out: STRUCTURE y1, STRUCTURE y2, PARTIAL y1, FALLING y3, FLUID y0, ATTACHED y1
@Test void equalKeysKeepClaudesOrder()
@Test void firstWriteKeepsTheOriginal() → beforeWrite(p, "grass", null), afterWrite(p, "minecraft:stone", 0), beforeWrite(p, "stone", null) → original(p) == "grass"
@Test void lastWriteIsWhatCounts() → afterWrite(p, stone, 0) then afterWrite(p, oak_door, 0) → writtenBlock(p) == "minecraft:oak_door"
@Test void sameBlockIsRestoredWhateverItsToggles() → written oak_door, current oak_door → RESTORE
@Test void aDifferentBlockIsKept() → written stone, current oak_planks → KEEP
@Test void dirtLikeBlocksCountAsOne() → written dirt, current grass_block → RESTORE
@Test void waterAndIceCountAsOne() → written water, current ice → RESTORE
@Test void changedContentsAreKept() → written chest hash 11, current chest hash 12 → KEEP
@Test void historyIsNewestFirstAndCapped() → UndoHistory(5), push 1..6 for one player → pop 6, 5, 4, 3, 2, then empty
@Test void playersHaveSeparateHistories()
```

- [ ] **Step 2–5:** run to fail, implement, run green, commit — `feat(aibuilder): placement order and undo journal`.

---

### Task 7: Write tools and the scripted backend (pure)

**Files:** Create `core/tools/*.java`, `core/agent/*.java`, `src/main/resources/aibuilder/tools.json`; Modify `core/request/SizeTier.java` (Task 10 uses it; add here: `int size()` 24/48/80/128 with AUTO = 80, `int maxChangedBlocks()` 15,000 / 100,000 / 350,000 / 1,000,000 with AUTO = 350,000); Test `core/tools/{ToolExecutorTest,ToolCatalogTest}.java`, `core/agent/ScriptedBackendTest.java`, `core/request/SizeTierTest.java` (extend).

**Interfaces — Consumes:** `Validator`, `LocalFrame`, `Box`, `Shapes`, `Roofs`, `Staircases`, `Placement`. **Produces:**
- `tools.json`: an array of `{name, description, input_schema}` for `submit_brief`, `set_blocks`, `fill`, `line`, `wall`, `cylinder`, `sphere`, `cone`, `arch`, `roof`, `staircase`, `clear`, `checkpoint`, `finish`, with the argument names and meanings of spec §8 (local coordinates, `box` = `[x1,y1,z1,x2,y2,z2]`, `pos` = `[x,y,z]`, `block` + optional `state`). Descriptions under 4,096 characters; property names `[A-Za-z0-9_.-]{1,64}`; JSON Schema 2020-12.
- `record ToolDef(String name, String description, JsonObject inputSchema)`; `ToolCatalog.load() → ToolCatalog` (from the resource), `List<ToolDef> tools()`, `Set<String> names()`.
- `record ToolResult(String text, boolean isError)`; `record Brief(String title, String style, int width, int height, int depth, Map<String, String> palette, List<String> phases)`.
- `interface BuildSink { void brief(Brief brief); void queue(List<Placement> batch); int checkpoint(String phase); void finish(String summary); }` (`checkpoint` blocks until everything queued is placed and returns blocks placed so far).
- `final class Budget { Budget(int maxToolCalls); void count(); boolean exhausted(); String footer(); }`: after counting, with less than 25% left the footer is "Budget: N tool calls left."; below 10% "Budget nearly spent: finish now."; else "".
- `final class ToolExecutor { ToolExecutor(Validator validator, LocalFrame frame, Box allowed, long maxChangedBlocks, BuildSink sink, Budget budget); static Set<String> handledTools(); ToolResult call(String name, JsonObject args); boolean finished(); }`: counts the call; past the budget → error "Budget reached: call finish now." (still accepts `finish`); writes before `submit_brief` → error naming `submit_brief`; after `finish` → error "This build is finished."; each write tool expands its shape, validates every block (a shape's block once), rejects positions outside `allowed` (`out_of_bounds`), maps local → world through `frame`, and queues one batch; `set_blocks` over `Tunables.SET_BLOCKS_MAX` entries → error `too_many`, nothing queued; more than `maxChangedBlocks` queued in total → the rest rejected `too_many`; a shape method's `IllegalArgumentException` or a malformed argument → error `bad_argument` naming the argument. Result text: "Queued N blocks." plus, when any were rejected, "Rejected M:" and up to 10 lines `#index reason: detail`, plus the budget footer. `clear` = `fill` with air (protection arrives in M3). `checkpoint` → "Checkpoint <phase>: N blocks placed so far." `finish` → `sink.finish(summary)`.
- `interface AiBackend { BuildHandle start(BuildRequest request, ToolExecutor tools); }`; `record BuildRequest(String prompt, SizeTier size)`; `interface BuildHandle { void cancel(); CompletableFuture<BuildOutcome> outcome(); }`; `enum BuildOutcome { FINISHED, CANCELLED, FAILED }`.
- `final class ScriptedBackend implements AiBackend { ScriptedBackend(String scriptJson, Executor worker); }`: script `{"calls": [{"tool": "...", "args": {...}}, …]}`; runs the calls in order on `worker`, checks for cancel before each call, completes FINISHED after `finish` or the last call (calling `finish` itself if the script didn't), FAILED if a call throws.

- [ ] **Step 1: Write the failing tests** (a `RecordingSink` collects batches; `FakeCatalog` from Task 2 extended with `minecraft:air`; frame origin (0, 64, 0) facing SOUTH; allowed `Box.of(-8,-2,0, 7,15,15)`).

```java
@Test void everyCatalogToolHasAHandlerAndBack() → ToolCatalog.load().names() equals ToolExecutor.handledTools()
@Test void writesNeedABriefFirst() → fill before submit_brief → isError, text contains "submit_brief"
@Test void fillQueuesWorldPositionsThroughTheFrame() → brief, fill box [0,0,0,1,0,0] stone → one batch with world positions (0,64,0) and (-1,64,0); text starts "Queued 2 blocks."
@Test void rejectedEntriesAreReportedAndTheRestGoThrough() → set_blocks [good stone, "oak_stair", stone at z -1] → 1 queued; text contains "unknown_block", "minecraft:oak_stairs", "out_of_bounds"
@Test void outsideTheBoxIsRejected() → fill box reaching y 20 → the cells above y 15 rejected out_of_bounds, the rest queued
@Test void setBlocksStopsAtTwoThousand() → 2,001 entries → isError, text contains "too_many", nothing queued
@Test void changedBlockCapCutsOffTheRest() → maxChangedBlocks 5, fill of 8 → 5 queued, 3 rejected too_many
@Test void checkpointWaitsForTheSinkAndReports() → sink returns 42 → text "Checkpoint walls: 42 blocks placed so far."
@Test void finishEndsTheBuild() → after finish: finished() true; a further fill → isError "This build is finished."
@Test void budgetFootersAppearNearTheEnd() → Budget(20): the 16th call's text ends "Budget: 4 tool calls left."; the 19th "Budget nearly spent: finish now."; the 21st (a fill) isError "Budget reached: call finish now."; finish still works
@Test void badArgumentsNameTheArgument() → fill without "box" → isError, text contains "bad_argument" and "box"
@Test void roofProblemsComeBackAsBadArgument() → roof odd gable without slab → isError "bad_argument"
// ScriptedBackendTest (worker = Runnable::run unless the test needs a thread)
@Test void runsEveryCallInOrderAndFinishes() → 3-call script → the executor saw brief, fill, finish; outcome FINISHED
@Test void aScriptWithoutFinishIsFinishedForIt() → sink.finish was called once; outcome FINISHED
@Test void cancelStopsBeforeTheNextCall() → a sink whose checkpoint blocks on a latch; cancel(); release → outcome CANCELLED; the call after the checkpoint never ran
// SizeTierTest additions
@Test void autoBuildsLikeLarge() → AUTO.size() == LARGE.size() == 80; AUTO.maxChangedBlocks() == 350_000; SMALL.size() == 24
```

- [ ] **Step 2–4:** run to fail, implement, run green.
- [ ] **Step 5: Commit** — `feat(aibuilder): write tools and the scripted backend`.

---

### Task 8: Placing in the world (server gametests)

**Files:** Create `world/{McBlockCatalog,StateFactory,Occupancy,PlacementJob,JobScheduler,BuildTickets,McBuildSink}.java`, `tools/gen_arena.py`, `src/gametest/resources/data/aibuilder-test/gametest/structure/arena.snbt`, `src/main/resources/aibuilder/scripts/demo_house.json`, `src/gametest/java/io/palmisano/aibuilder/test/{ArenaBuilds,PlacementGameTest}.java`; Modify `build.gradle` (`enableGameTests = true`), gametest `fabric.mod.json` (`"fabric-gametest": [...]`), `AiBuilderMod.java` (ticket type, scheduler).

**Interfaces — Consumes:** Tasks 1–7. **Produces:**
- `McBlockCatalog implements BlockCatalog`: from `BuiltInRegistries.BLOCK` (ids, `getStateDefinition().getProperties()` with `Property.getName(value)` for values); `unbreakable` = default state `getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0`; `static PlacementPass pass(Block block)`: `LiquidBlock` → FLUID; `FallingBlock` → FALLING; empty collision shape, or a `DoorBlock`, `BedBlock`, `LadderBlock`, `LanternBlock`, `CarpetBlock` → ATTACHED; full-cube collision → STRUCTURE; else PARTIAL. Built once, lazily, on first use.
- `StateFactory.states(ResolvedBlock block, Rotation rotation, BlockPos pos) → List<Map.Entry<BlockPos, BlockState>>`: default state with each vanilla property set by `Property.getValue(String)`, then `rotate(rotation)`; leaves get `persistent=true`; a door's lower half adds the upper half above, a bed's foot adds the head one step along its facing, a `DoublePlantBlock`'s lower half adds the upper above.
- `interface Occupancy { boolean occupied(BlockPos pos); static Occupancy players(ServerLevel level); }` (`players` = any `Player` entity's box intersects the block).
- `final class PlacementJob implements Tickable { PlacementJob(ServerLevel level, Rotation rotation, UndoJournal<BlockState, CompoundTag> journal, Occupancy occupancy, int blocksPerTick, long nanosPerTick); void enqueue(List<Placement> batch); boolean tick(); int placed(); boolean idle(); CompletableFuture<Integer> whenIdle(); void cancel(); int finish(); }`: `tick` returns true once the job is finished or cancelled; `enqueue` is thread-safe and sorts the batch with `PlacementOrder`; `tick` (server thread) places up to the budget in order: skips a placement whose `onlyReplacing` doesn't match the current block (id or `#tag`); holds back an occupied position (retried every tick); otherwise `journal.beforeWrite` (old state + `saveWithFullMetadata` of any block entity), `level.setBlock(pos, state, 818)`, `journal.afterWrite` (block id + hash of the new block entity's `saveWithFullMetadata`, 0 if none). `finish` gives up on still-occupied positions (returns their count) and runs the finalize pass: for every changed position and its 6 neighbours, skipping fluids, `Block.updateFromNeighbourShapes`; a changed result is journaled and set with 818. `cancel` drops everything not yet placed.
- `JobScheduler`: a server-tick registry (`END_SERVER_TICK`) of `Tickable`s (`boolean tick()` returns true when done); PlacementJob runs through it.
- `BuildTickets`: `TicketType TYPE` registered as `aibuilder:build` with `new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION)` (no persist flag); `hold(ServerLevel, Set<ChunkPos>)` and `release(...)` via `ServerChunkCache.addTicketWithRadius(TYPE, pos, 1)` / `removeTicketWithRadius`; `covering(BlockPos min, BlockPos max) → Set<ChunkPos>`.
- `McBuildSink implements BuildSink`: `McBuildSink(PlacementJob job, Consumer<Brief> onBrief, Consumer<String> onFinish)`; `queue` → `job.enqueue`; `checkpoint` → waits on `job.whenIdle()` up to `Tunables.CHECKPOINT_TIMEOUT_SECONDS`; `brief`/`finish` → the callbacks (Task 10's status).
- `demo_house.json` (local frame; `submit_brief` first; a `checkpoint` after each phase; `finish` last), about 400 blocks: foundation `fill [-4,0,2, 4,0,10] stone_bricks`; a `dirt_path` strip `fill [-1,0,0, 1,0,1]`; walls `wall [[-4,2],[4,2],[4,10],[-4,10],[-4,2]] y 1 height 4 oak_planks`; door `set_blocks oak_door {facing: forward, half: lower}` at (0,1,2); lintel `fill [-1,4,2, 1,4,2] oak_log {axis: x}`; glass panes at (-2,2,2), (2,2,2), (-4,2,6), (4,2,6); roof `roof [-4,5,2, 4,5,10] gable spruce_stairs spruce_slab ridge z overhang 1 gable_block oak_planks`; inside: `red_bed {facing: forward}` foot at (3,1,6), a standing `lantern` at (0,1,6) (the attic above is open, so nothing to hang from), `oak_fence` at (-3,1,9) and (-2,1,9); outside, in one `set_blocks`: `grass_block` at (-3,0,0) and (3,0,0) and a `poppy` on each at (-3,1,0) and (3,1,0) (the pass order places the grass first; a poppy on the arena's stone would pop off). It must fit Small's box and the arena.
- `ArenaBuilds` (gametest support): `start(GameTestHelper helper, Facing facing, String scriptJson, Occupancy occupancy, int blocksPerTick) → ArenaBuild` with the frame origin at the arena's centre (8, 0, 8) moved back 4 along `facing`, allowed box `Box.of(-7,-1,0, 7,11,15)`, a real `PlacementJob` and `ScriptedBackend`; `ArenaBuild` exposes `world(Pos local) → BlockPos`, `done()` (outcome complete, job finished and finalized), `cancel()`, `job()`, `journal()`. `tools/gen_arena.py` writes `arena.snbt` (size [16, 12, 16], stone at y 0, DataVersion as in Fabric's `empty.snbt`).

- [ ] **Step 1: Write the failing gametests** (`@GameTest(structure = "aibuilder-test:arena", maxTicks = 400)`, each `succeedWhen` the build is `done()` and its checks pass).

```java
// One per facing: demoHouseFacingSouth/West/North/East. Expected world directions, derived by hand:
//                    door faces   left-eave stairs face   lintel axis
//   SOUTH            SOUTH        WEST                    X
//   WEST             WEST         NORTH                   Z
//   NORTH            NORTH        EAST                    X
//   EAST             EAST         SOUTH                   Z
// checks: door lower half at local (0,1,2) (oak_door, half lower, facing as above), upper half at (0,2,2);
// spruce_stairs at local (-5,5,6) facing as above; oak_log at local (0,4,2) with the axis above;
// red_bed head at local (3,1,7); poppy at (-3,1,0) still there after the finalize pass.
@GameTest void sandWaitsForItsSupport() → script: brief; set_blocks sand at (0,3,4) over air; checkpoint; set_blocks stone at (0,2,4) and (0,1,4); finish → after done(): sand still at (0,3,4)
@GameTest void waterNeverFlows() → script: water at (0,1,4) with open sides; finish → 40 ticks after done(): (1,1,4), (-1,1,4), (0,1,5), (0,1,3) are still air
@GameTest void fencesConnectAfterFinalize() → oak_fence at (-1,1,4), (0,1,4), (1,1,4) facing SOUTH → middle fence has east=true and west=true
@GameTest void blocksWaitForAPlayerToMove() → script: brief; line of stone (-1,1,4)..(1,1,4); checkpoint; finish; Occupancy reports local (0,1,4) occupied until tick 10 → at tick 5 it's air while (-1,1,4) and (1,1,4) are stone (the checkpoint is still waiting); after done() it's stone
@GameTest void twoBlockBlocksGetTheirOtherHalf() → oak_door lower → upper above; red_bed foot → head; tall_grass lower → upper
@GameTest void leavesStayPersistent() → oak_leaves → persistent=true
```

- [ ] **Step 2: Run to fail.** Run: `./gradlew runGameTest --console=plain` → compile failure, then (with stubs) failing checks.
- [ ] **Step 3: Implement** the classes, the arena generator and the script.
- [ ] **Step 4: Run all tests.** Run: `./gradlew build --console=plain -q && ./gradlew runGameTest --console=plain && ./gradlew runClientGameTest --console=plain -q` → `BUILD SUCCESSFUL`; every server gametest passed; M1's client gametests still pass.
- [ ] **Step 5: Commit** — `feat(aibuilder): tick-batched placement with a shape pass`.

---

### Task 9: Undo (server gametests)

**Files:** Create `world/{UndoStore,UndoRunner}.java`, `src/gametest/java/io/palmisano/aibuilder/test/UndoGameTest.java`; Modify gametest `fabric.mod.json`.

**Interfaces — Consumes:** `UndoJournal`, `UndoHistory`, `PlacementOrder`, `BuildTickets`, `JobScheduler`. **Produces:**
- `record StoredBuild(ResourceKey<Level> dimension, String title, UndoJournal<BlockState, CompoundTag> journal)`.
- `UndoStore extends SavedData`: `static UndoStore get(MinecraftServer server)` = `server.overworld().getDataStorage().computeIfAbsent(TYPE)` with `TYPE = new SavedDataType<>(AiBuilderMod.id("undo"), UndoStore::new, CODEC, null)`; `void push(UUID player, StoredBuild build)` (keeps `Tunables.UNDO_HISTORY`, marks dirty); `Optional<StoredBuild> pop(UUID player)`; `static final Codec<UndoStore> CODEC` storing positions as `BlockPos.asLong`, original states with `BlockState.CODEC`, block-entity data with `CompoundTag.CODEC`, written ids and hashes.
- `final class UndoRunner implements Tickable { UndoRunner(ServerLevel level, UndoJournal<BlockState, CompoundTag> journal, int blocksPerTick); CompletableFuture<UndoResult> result(); }` with `record UndoResult(int restored, int kept)`: holds tickets for every chunk the journal touches until done; restores ordered by `McBlockCatalog.pass(original.getBlock())`, then y; per position, `journal.decide` with the current block id and block-entity hash → RESTORE sets the original with 818 and loads its data into the new block entity (`loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag))`, `setChanged`, `level.sendBlockUpdated`); KEEP counts. Ends with the same finalize pass as `PlacementJob.finish`.

- [ ] **Step 1: Write the failing gametests.**

```java
@GameTest void undoRestoresEverythingIncludingContents() → before the build: a chest with 3 diamonds at local (0,1,4) and an oak_sign with front text "hello" at (1,1,4); script: fill [-1,1,3, 1,2,5] stone; after done(): UndoRunner → the chest has 3 diamonds, the sign reads "hello", every journal position holds its original state; result restored == journal size, kept == 0
@GameTest void togglesStillUndo() → build places oak_door at (0,1,4) and a lever at (2,1,4); then open the door and power the lever → undo → both positions air again; kept == 0
@GameTest void aFilledChestIsKept() → build places a chest at (0,1,4); then put 1 apple in it → undo → chest still there holding the apple; kept == 1
@GameTest void cancelMidBuildThenUndo() → blocksPerTick 5, fill of 200 stone; cancel at tick 3 → 0 < placed < 200; undo → every changed position back to air
@GameTest void undoReachesFarPositions() → a journal built by hand for 3 positions 200 blocks east of the arena (original air, written stone), those positions really set to stone first; UndoRunner → all 3 are air again, restored == 3
@GameTest void journalSurvivesSaving() → encode an UndoStore holding one StoredBuild with UndoStore.CODEC to NBT and decode it: same dimension, positions, original states, block-entity tags, written ids and hashes
```

- [ ] **Step 2–4:** run to fail (`./gradlew runGameTest --console=plain`), implement, run green (Task 8's Step 4 command).
- [ ] **Step 5: Commit** — `feat(aibuilder): exact undo saved with the world`.

---

### Task 10: Site, build manager, `/aibuild test|undo|cancel|status`; docs

**Files:** Create `core/site/{Cell,ColumnProbe,GroundFinder,BuildBox}.java`, Test `core/site/{GroundFinderTest,BuildBoxTest}.java`; Modify `BuildManager.java` (becomes per-server state), `AiBuildCommand.java`, `AiBuilderMod.java`, `core/request/Messages.java` (+ `MessagesTest`); Create `src/gametest/java/io/palmisano/aibuilder/test/{PermissionGameTest,DemoBuildClientGameTest}.java`; Modify docs (`CLAUDE.md`, `README.md`, `PLAN.md`, root `README.md` and `CLAUDE.md` rows).

**Interfaces — Produces:**
- `enum Cell { OPEN, GROUND, WATER }`; `interface ColumnProbe { Cell at(int y); }`; `GroundFinder.find(ColumnProbe column, int feetY) → int`: if the cell at `feetY` is GROUND, climb while the cell above is GROUND (at most `GROUND_SEARCH_UP`) and return the top GROUND y; if it's OPEN, go down (at most `GROUND_SEARCH_DOWN`) to the first GROUND or WATER and return it (for WATER, the top water cell); otherwise `feetY − 1`. The Minecraft probe: WATER when the fluid is water, OPEN for air, an empty collision shape, leaves, logs or `minecraft:snow`, GROUND otherwise.
- `BuildBox.allowed(SizeTier tier, int originY, int minY, int topY) → Box` (local): x `−W/2 .. W/2 − 1`, z `0 .. W − 1`, y `−W/4 .. W − 1`, clipped to `minY − originY .. topY − originY`, with W = `tier.size()`; `minY` and `topY` are the lowest and highest buildable y, both inclusive (`level.getMinY()`, `level.getMaxY()` in 26.3).
- `BuildManager` (one per server, created on `SERVER_STARTED`, cancelled and saved on `SERVER_STOPPING`): `enum Outcome { STARTED, REFUSED, BUSY, NOTHING, DONE }`; `Outcome startTest(ServerPlayer player)` (owner check; one build per server; facing from yaw; origin `SITE_GAP` ahead of the feet with `GroundFinder`; Auto box; `ScriptedBackend` with `demo_house.json`; tickets held for the box); `Outcome cancel(ServerPlayer)`, `Outcome undo(ServerPlayer)`, `void status(ServerPlayer)`. On finish: place the rest, finalize, `UndoStore.push`, release tickets, chat `Messages.done(placed, skipped)` + " [Undo]" (skipped = positions a player still stood in); on cancel: "Stopped: N blocks placed. [Undo]"; on failure: "Stopped: something went wrong (<message>). [Undo]". `[Undo]` is a `ClickEvent.RunCommand("/aibuild undo")`. While running, the action bar every `STATUS_INTERVAL_TICKS`: "Building <phase> · N blocks". The brief's chat line: "Designing: <title> (<style>, <width>×<depth>, <first two palette blocks in insertion order, ids made readable, joined with " + ">)…". `Messages` gains `designing(Brief)`, `done(int placed, int skipped)` and `undone(int restored, int kept)`, with "1 block" / "N blocks" and thousands separators.
- `/aibuild test`, `/aibuild undo`, `/aibuild cancel`, `/aibuild status` (literals; `/aibuild <prompt>` keeps M1's echo). Replies: "A build is already running: /aibuild cancel stops it." · "Nothing to cancel." · "Nothing to undo." · "Undone: N blocks restored." + " Kept M blocks you changed." when M > 0 · "Nothing is building." / "Building <phase> · N blocks placed." A prompt that starts with `test`, `undo`, `cancel` or `status` reaches the subcommand, so it has to come from the screen (Brigadier tries a matching literal only).

- [ ] **Step 1: Write the failing tests.**

```java
// GroundFinderTest (column given as a map y → Cell, OPEN elsewhere)
@Test void onGrassTheGroundIsUnderTheFeet() → GROUND ≤ 63, feet 64 → 63
@Test void aHillAheadClimbsOut() → GROUND ≤ 70, feet 64 → 70
@Test void aDropAheadFindsTheFloorBelow() → GROUND only at 50, feet 64 → 50
@Test void waterIsTheSurface() → GROUND ≤ 59, WATER 60..62, feet 64 → 62
@Test void underACavernRoofTheFloorWins() → GROUND ≤ 60, OPEN 61..70, GROUND 71..80, feet 61 → 60
@Test void nothingInRangeFallsBack() → all OPEN, feet 64 → 63
@Test void deepInRockFallsBack() → GROUND everywhere, feet 64 → 63
// BuildBoxTest
@Test void tierSizesTheAllowedBox() → MEDIUM, origin 64, limits -64..319 → Box.of(-24,-12,0, 23,47,47)
@Test void worldLimitsClipTheBox() → MEDIUM, origin 300, top 319 → top y 19; origin -60, min -64 → bottom y -4
// MessagesTest additions
@Test void briefLineNamesTheBuild() → Brief("castle","gothic",48,40,44, LinkedHashMap{primary: minecraft:stone_bricks, roof: minecraft:dark_oak_stairs, glass: minecraft:glass}, …) → "Designing: castle (gothic, 48×44, stone bricks + dark oak stairs)…"
@Test void doneLineMentionsSkippedBlocksOnlyWhenThereAreSome() → done(6214, 0) == "Done: 6,214 blocks placed."; done(10, 1) == "Done: 10 blocks placed. Skipped 1 block where a player stood."
@Test void undoLineMentionsKeptBlocksOnlyWhenThereAreSome() → undone(120, 0) == "Undone: 120 blocks restored."; undone(1234, 2) == "Undone: 1,234 blocks restored. Kept 2 blocks you changed."; undone(1, 1) == "Undone: 1 block restored. Kept 1 block you changed."
// PermissionGameTest (server gametests: a test server has no singleplayer owner, so every mock player is a non-owner)
@GameTest void aNonOwnerIsRefused() → mock server player → BuildPermission.canBuild false; startTest, cancel and undo all REFUSED; no job registered with JobScheduler
// DemoBuildClientGameTest (singleplayer: the player is the owner; covers STARTED, BUSY, DONE and NOTHING)
// tp to a flat spot facing south; /aibuild test → chat "Designing: …" then "Done: …"; on the server the door's world position holds oak_door;
// a second /aibuild test during the build → "A build is already running: /aibuild cancel stops it."; screenshot aibuilder-demo-house;
// /aibuild undo → "Undone: …"; the door position is air; screenshot aibuilder-demo-undone; /aibuild undo again → "Nothing to undo."; /aibuild cancel → "Nothing to cancel."
```

- [ ] **Step 2–4:** run to fail, implement, run green: `./gradlew build --console=plain -q && ./gradlew runGameTest --console=plain && ./gradlew runClientGameTest --console=plain` → all `BUILD SUCCESSFUL`. Look at both demo screenshots (house in front, door toward the camera, roof stairs sloping right; then the land back).
- [ ] **Step 5: Docs and status.** `CLAUDE.md`: the M2 architecture (packages, threads: scripted backend on a worker, job on the server tick), the direction rule, and 26.3 gotchas met. `README.md`: `/aibuild test`, `undo`, `cancel`, `status`, the house screenshot. `PLAN.md`: M2 row "✅ built, waiting for Frankie's test" and a change-log line. Root `README.md` and `CLAUDE.md` rows.
- [ ] **Step 6: Commit** — `feat(aibuilder): /aibuild test builds the demo house, with undo and cancel`.

---

## After the tasks

1. One fresh reviewer on Opus 5.5 reviews the branch since this plan's commit; fix Critical/Important test-first; commit the ledger to `docs/superpowers/plans/2026-10-21-aibuilder-m2-progress.md`; push.
2. Stop for Frankie's M1 + M2 test: `cd /Users/spaceplushy/development/Minecraft-mods/.claude/worktrees/aibuilder-v1/mods/aibuilder && ./gradlew runClient`, create a world, and `/aibuild test` facing each of the four directions (house in front, door toward you, roof stairs the right way), `/aibuild undo` after each, then `/aibuild test` and `/aibuild cancel` half-way, then undo.
