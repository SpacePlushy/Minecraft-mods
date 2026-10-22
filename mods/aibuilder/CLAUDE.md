# AI Builder mod — notes for Claude

Press B, type anything, and Claude builds it in front of the player: Claude Code (Frankie's own Max login,
headless `claude -p`) calls build tools that this mod serves over a localhost MCP server. Minecraft Java 26.3
(Fabric). Read the root `CLAUDE.md` first.

- **Design:** `docs/superpowers/specs/2026-10-21-aibuilder-design.md` (Frankie's original brief is next to it).
- **Living plan and milestone status:** `PLAN.md` in this folder.
- **Milestone plans and ledgers:** `docs/superpowers/plans/2026-10-21-aibuilder-m<N>-*`.

## Rules

- `io.palmisano.aibuilder.core..` is pure Java: nothing from Minecraft or Fabric (Gson and the JDK are fine).
  It's where the logic lives, unit-tested test-first.
- `src/main` never imports `net.minecraft.client.*`; client-only code lives in `src/client`.
- Only the world's owner can build (spec §15): never route other players' prompts through Frankie's subscription.
- Every subagent runs on Opus 5.5 (root `CLAUDE.md`).

## Architecture (as of M4)

```
B key ──► AiBuilderClient ──► BuilderScreen ──Build──► BuildPromptPayload ──┐
/aibuild <prompt> ──────────────────────────────────────────────────────────┤──► BuildManager.submit (owner, typed, not busy)
/aibuild test|undo|cancel|status|survey ──► AiBuildCommand ──► BuildManager (one per server, server thread)
   submit: Sites.ahead (frame) + BuildBox ──► worker: ClaudeChecks (find claude, `auth status --json`) ──► Build
   Build: McWorldReader (site survey) + PlacementJob + McBuildSink + ToolExecutor ──► backend.start
     ClaudeCodeBackend: build dir (mcp.json, builder_system.md) ──► `claude -p …` (ClaudeLaunch), prompt on stdin
       stdout reader: StreamEvents → InitCheck (opens the tools) · notes · tokens → Budget · UsageLimits · result
       Claude Code ──HTTP 127.0.0.1/mcp, bearer token──► McpHttpServer ──► McpHandler ──► Run.call ──► ToolExecutor
     ScriptedBackend (/aibuild test): replays demo_house.json through the same ToolExecutor
   ToolExecutor ──batches──► McBuildSink ──enqueue──► PlacementJob (server tick: 818 writes + journal, cap on real
   changes, then ShapePass) ── job ends ──► chat: Claude's summary, "Done: N blocks placed. [Undo]"
   /aibuild undo ──► UndoStore ──► UndoRunner (server tick): restore what still holds the build's block, finalize
```

| Where | What |
|---|---|
| `core/request/` | **Pure**: `SizeTier` (size, block cap), `BuildPrompt`, `PromptHistory`, `Messages` (all chat copy). |
| `core/frame/` | **Pure**: `Facing` (yaw snap), `LocalFrame` (local ↔ world), `Directions` (forward/left… ↔ the template's compass). |
| `core/blocks/` | **Pure**: `Validator` (unknown, banned, compass words, bad property/value, with suggestions) over a `BlockCatalog`. |
| `core/shapes/` | **Pure**: `Shapes` (fill, wall, line, cylinder, sphere, cone, arch), `Roofs`, `Staircases` → local blocks. |
| `core/place/` | **Pure**: `PlacementOrder` (passes, bottom-up), `UndoJournal` (first original wins; restore only if unchanged), `UndoHistory`. |
| `core/tools/` | **Pure**: `ToolExecutor` (all 19 tools; `tools.json` is the catalog), `WorldReader` (what the read tools and protection read through), `ServerHop` (a world read runs on the server thread or fails, never inline on a stopping server), `Budget`, `PhaseProgress`, `BuildSink`. |
| `core/survey/` | **Pure**: `TerrainSnapshot`/`Column` and `SurveyEncoder` (survey_area's compact text, coarsened to stay under 30,000 characters), `RegionEncoder` (inspect_region), `BuildProblems` (check_build's answer: grouped by kind, at most 60 positions shared out). |
| `world/BuildCheck` | check_build's look over the build, over ticks within the budget (at least one position a tick): each changed position and the spots on and beside it. Unsupported (`!canSurvive`, a falling block over a drop), dark (2 high over a full top face, roofed by the no-leaves heightmap, block light 0, after the light engine catches up), doors (solid at feet or head on either side), low (1 high under a solid build block, stairs and leaves aside), spill (a source with an open side or air below). Reads only loaded chunks; reports only inside the allowed box. |
| `core/place/ProtectionMask` | **Pure**: protected (probably player-made) and unbreakable spots in the allowed box; a write there is rejected `protected`. |
| `core/blocks/BlockEntityData` | **Pure**: place_block_entity's data, parsed and checked (`ItemCatalog`: items, stack sizes, banner patterns; `BlockInfo.entity()` the kind a block takes): sign text (4 lines a side, plain text: `§` codes and control characters dropped, 384 characters a line), up to 27 stacks (nothing that holds items), a book (50 pages × 800, title 32), 6 banner layers, an item frame (relative facing). It rides on `Placement.data`; an item frame is a placement with no block. |
| `world/BlockEntityWriter`, `ItemFrames` | The data written right after its block goes in (`PlacementJob.dress`: a spot the build hadn't changed is journaled first); a lectern gets `has_book`. Item frames hang only where they survive and are journaled by UUID (`UndoJournal.Spawned`, saved as `entities`, optional for old saves); undo takes them down first, without drops, unless they hold the player's item. |
| `core/place/FireMask`, `FireRule` | **Pure**: lava and what burns (vanilla's `ignitedByLava`) in and around the allowed box. Lava within 3 blocks sideways, from 1 below to 4 above, of anything that burns is rejected `lava_near_flammable`, and so is anything that burns that close to lava, counting the world and the build so far (spec §9). A replace-mode fill may leave the old block, so both count there. |
| `core/agent/` | **Pure**: `AiBackend`/`BuildHandle`/`BuildEvents`, `ScriptedBackend`; Claude Code's side: `StreamEvents` (stream-json, never throws), `InitCheck` (build tools, our server, no API key, built-in plugins only), `UsageLimits` (stop at the plan limit or at paid extra usage), `ClaudeLaunch` (flags, environment, mcp.json), `ClaudeLocator`, `AuthStatus`, `McpHandler`/`McpSessions` (JSON-RPC, tokens, origins). |
| `backend/` | JDK only, no Minecraft: `McpHttpServer` (127.0.0.1, 8 threads), `ClaudeCodeBackend` (the process, its threads, caps, cancel, `stopAll`), `ClaudeChecks` (find it, check the login, remember a pass 5 min). |
| `resources/aibuilder/prompts/builder_system.md` | The in-game Claude's system prompt (`config/aibuilder/builder_system.md` replaces it). |
| `TestHooks` | The gametests' seam: the fake Claude Code's command and scenario. Nothing in the mod sets it. |
| `core/site/` | **Pure**: `GroundFinder` (the origin's floor, nearest the feet), `SurveyFloor` (a survey column's: climbs a mountain to the box top, reads water on raised ground to its surface, "no floor" over a drop), `BuildBox` (allowed box per tier, clipped to the world). |
| `world/` | Minecraft side: `McBlockCatalog`, `StateFactory` (rotate, second halves, persistent leaves), `PlacementJob`, `ShapePass`, `JobScheduler`, `BuildTickets`, `UndoStore`, `UndoRunner`, `Sites`, `Occupancy`, `ProtectionHeuristic` (natural = vanilla terrain/growth tags + a short list), `WorldSurvey` (columns and the protection mask, read over ticks), `McWorldReader` (the real `WorldReader`: starts the site survey when a build starts; region and fact reads hop to the server thread). |
| `BuildManager` | One per server: one job at a time (build or undo), owner only; the Claude Code check, then the build; status in the action bar; the end message per outcome; saves on stop and ends every Claude Code process; `/aibuild survey`. |
| `BuildPermission` | Singleplayer owner only, by UUID, and only commands they type (`mixin/CommandSourceStackAccessor`); M6 adds the dedicated-server allow list. |
| `net/BuildPromptPayload` | Serverbound packet. Wire limit 8,192 chars, far above the 2,000 prompt limit, so a long prompt gets a chat answer instead of a decode failure that drops the connection. |
| `client/BuilderScreen` | The screen. The typed text lives in a field and survives `init()` re-runs (resize, GUI scale). |
| `net/BuildStatusPayload`, `core/request/BuildStatus`, `client/StatusHud` | The HUD (spec §5.3): once a second the owner gets the brief's title and phases, the phase under way, blocks placed and tool calls (Claude's text cut to 48 characters, 12 phases); `BuildStatus.CLEAR` at the end and while they're dead or in another dimension. The client draws it top left (before the chat layer) and drops it after 5 s without news or in another world. |
| `client/PromptHistoryStore` | `config/aibuilder-history.json`; write-temp-then-move; never throws. |
| `src/gametest/` | Client gametests (smoke, prompt to server, the screen, the demo build, a whole build with the fake Claude Code) and server gametests (`PlacementGameTest`, `UndoGameTest`, `PermissionGameTest`, `SiteGameTest`, `SurveyGameTest`, run in the `aibuilder-test:arena` structure through `ArenaBuilds`). `GameTestSupport` captures chat with `ClientReceiveMessageEvents.GAME`. `TestSetup` (the test mod's main entrypoint) points every gametest run at the fake Claude Code. |
| `src/test/resources/fake-claude/FakeClaude.java` | A fake `claude` (JDK only, `java FakeClaude.java …`): `auth status` and a `-p` run with real MCP calls, by `FAKE_CLAUDE_SCENARIO`. `src/test/resources/stream/` holds the M4 spike's real recordings. |

**Real Claude is never in the suites (spec §17.5).** Every gametest run uses the fake; `AIBUILDER_LIVE=1` (an
environment variable on the Gradle command) makes the client gametests use the real Claude Code on the owner's login,
and then only the live test runs.

**Directions (spec §7).** Tool input says forward/back/left/right/up/down, never compass names. They map to the
compass as if the player faced south (forward → south, right → west), then vanilla `BlockState.rotate` turns the
state: SOUTH `NONE`, WEST `CLOCKWISE_90`, NORTH `CLOCKWISE_180`, EAST `COUNTERCLOCKWISE_90`. Only properties whose
values are compass directions translate; `axis` and `rotation` go straight to the rotate.

**Threads.** The backend runs on its own thread and only calls `ToolExecutor` (synchronized). Batches cross to the
server thread through `PlacementJob.enqueue` (locked); a checkpoint blocks the backend's thread on `whenIdle()`.
Everything that touches the world, the journal, `UndoStore` or chat runs on the server thread: callbacks from the
backend hop over with `server.execute`.

**Placement.** Writes use `/fill … strict`'s flags (818), so nothing reacts while a build goes up. Placement runs at
250 blocks or 3 ms a tick (`/aibuild test` at 2 a tick, to be watchable and cancellable); then `ShapePass` runs
`Block.updateFromNeighbourShapes` over every changed block and its neighbours on the 3 ms budget, skipping anything
holding a fluid (a waterlogged block would start flowing) and anything next to an unloaded chunk (reading it would
load it on the spot).

**Undo.** A build's journal joins the player's undo history (`UndoStore`, saved with the world) when the build
starts and is marked dirty every tick, so a crash mid-build can still be undone; an undo leaves it there until it
has finished, and a spot already back to its original counts as restored. A block entity's data is hashed again
`BLOCK_ENTITY_SETTLE_TICKS` after it's placed (a hopper rewrites its cooldown on its first tick), and
`BlockEntityHash` leaves out data that changes by itself (a vault's timer). `UndoGameTest`'s sweep over every kind
of block entity catches a new kind that does.

**Tool limits.** Numbers must be whole and within ±1,000,000 (`ARGUMENT_LIMIT`), and a shape whose size, worked out
from its arguments, is more than the whole allowed box is refused as `too_many` before anything is built.

## Commands (run here, in `mods/aibuilder/`)

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests, produce the jar in `build/libs/` |
| `./gradlew test` | Unit tests only (JUnit): fast, no game launch |
| `./gradlew runClient` | Launch a dev Minecraft with the mod, for manual play |
| `./gradlew runGameTest` | Headless server gametests (placement, undo, permissions, survey) in the arena structure; `build` runs them too |
| `./gradlew runClientGameTest` | Launch the real client, run the client gametests, save screenshots to `build/run/clientGameTest/screenshots/`. **Look at them.** |
| `python3 tools/gen_arena.py` | Rewrite the server gametests' arena (`src/gametest/resources/data/aibuilder-test/gametest/structure/arena.snbt`) |
| `AIBUILDER_LIVE=1 ./gradlew runClientGameTest --no-daemon` | One live build with the **real** Claude Code on the owner's login (uses their plan's usage), and no other test: `AIBUILDER_LIVE_PROMPT`, `AIBUILDER_LIVE_SIZE`; screenshots in `build/run/clientGameTest/screenshots`, the stream in `build/run/clientGameTest/logs/aibuilder/` |

## Gotchas (26.3)

- **Gametests while the Mac is locked:** a vsync'd buffer swap waits for a display refresh that never comes while
  the screen is locked or asleep, so the test client froze on its first frame (render thread stuck in
  `SDL_GL_SwapWindow`). The gametest source set's `GlSurfaceVsyncMixin` forces swap interval 0. Fabric's
  `restoreDefaultGameOptions` resets options before every test, so editing `options.txt` doesn't help.
- macOS has no `timeout` command; to bound a gametest run, background it and kill the client
  (`pkill -f fabric.client.gametest`) after a deadline.
- **SDL, not GLFW:** there is no `org.lwjgl.glfw`. Key codes are SDL scancodes in `InputConstants.KEY_*`
  (B is 5, not 66).
- **Screens** draw through `extractRenderState(GuiGraphicsExtractor, …)`, not `render(GuiGraphics, …)`; for text
  labels add `StringWidget`s (`addRenderableOnly`) instead of drawing.
- `MultiLineEditBox` hides its placeholder while focused, and shows a `n/limit` counter under its right corner.
- Client-side chat line: `player.sendSystemMessage(Component)` (there's no `displayClientMessage`).
- `ClientGameTestContext.clickScreenButton(key)` resolves `key` as a translation (an unknown key is used as plain
  text), then presses a `Button` whose label equals that text, or a `CycleButton` whose *name* does.
- Auto GUI scale never makes the GUI smaller than 320×240 unless the window itself is: test the narrowest layout
  with a 640×480 window (GUI 320×240), not 640×360 (that's GUI scale 1, 640×360).
- **The server gametest server ticks far faster than real time** (hundreds of ticks a second). Anything that waits
  on another thread (the script's worker) must wait on a condition, never a fixed tick.
- Server gametest structures are SNBT under `data/<namespace>/gametest/structure/` (Fabric loads them; its own
  `empty.snbt` is the format reference). `@GameTest public void name(GameTestHelper)` in a class listed under the
  `fabric-gametest` entrypoint.
- `makeMockServerPlayerInLevel()` is marked for removal but is the only mock player with a connection (chat
  replies need one); `makeMockServerPlayer(GameType)` has none.
- **Chunk tickets:** the future from `addTicketAndLoadWithRadius(type, pos, 1)` completes only once the chunk
  *ticks*, which needs all 8 neighbours generated (seconds on fresh terrain). Undo uses radius 0 (loaded only).
- No `Blocks.RED_BED`: beds are `Blocks.BED`, a `ColorCollection` (`Blocks.BED.pick(DyeColor.RED)`), all
  `AbstractBedBlock` (the new straw bed too); the id is still `minecraft:red_bed`. Wool is one too (no `Blocks.WHITE_WOOL`).
- Once a server is stopping, `server.execute(task)` runs the task inline on the caller's thread: a world read from
  another thread must check it's on the server thread (`ServerHop`). `getChunkSource().getChunkNow(x, z)` reads a
  chunk only if it's loaded; `level.getBlockState` loads (or generates) it on the spot.
- In 26.3 `#minecraft:dirt` no longer holds grass, podzol or mycelium (they're `#grass_blocks`).
- `BlockState.is(Block / TagKey / …)` and `ItemStack.is(Item)` come from `TypedInstance`, not the classes themselves.
- Signs: `sign.getText(SignTextSlot.FRONT).asMutable().setLine(0, text).asImmutable()`, then `setText(…, slot)`.
- DFU 10 has no `Codec.unit`; use `MapCodec.unit(supplier).codec()`. `SavedDataType(id, constructor, codec, null)`.
- Brigadier tries only the literal when a word matches one, so `/aibuild test …` never reaches the prompt argument.
- **Lava has no collision shape** for an empty collision context, so `getCollisionShape(level, pos).isEmpty()` is
  true for it: check the fluid state first when telling ground from air.
- A vault's saved data holds a timer (`server_data.state_updating_resumes_at`) it rewrites about once a second.
- No `getDayTime()`: the time of day is `level.getOverworldClockTime() % 24000`. Fabric's ore tag is
  `ConventionalBlockTags.ORES` (vanilla has only some per-ore tags). `level.getHeight(WORLD_SURFACE, x, z) - 1` is
  a column's highest non-air block, without a scan.
- With `enableGameTests = true`, `./gradlew build` runs the server gametests too.
- **Server gametest structures get a barrier ceiling** unless the test says `@GameTest(skyAccess = true)`: every
  spot in the arena is "under a roof" (heightmaps, sky light). `CheckBuildGameTest` needs the sky.
- The light engine runs on its own thread: right after placement, block light may be stale.
  `getLightEngine().waitForPendingTasks(chunkX, chunkZ)` completes once what was queued for that chunk is done.
- **A game started from inside Claude Code** (`./gradlew runClient` from the desktop app's shell) inherits ~35
  `CLAUDE_*` variables and `ANTHROPIC_BASE_URL`; a child `claude` would join that session or its proxy. `ClaudeLaunch`
  drops every `ANTHROPIC_*` and `CLAUDE*` variable but `CLAUDE_CONFIG_DIR` (the M4 spike).
- A player's commands carry `player.commandSource()`, an object of its own, not the player; the stack's source is
  private (`mixin/CommandSourceStackAccessor`). `makeMockServerPlayerInLevel()`'s player always reports creative.
- Unit tests write log4j's `logs/` into the mod folder (git-ignored). Fabric marks gametest runs with the
  `fabric-api.gametest` / `fabric.client.gametest` properties, passed by Loom's run tasks.
