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

## Architecture (as of M6)

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
| `core/request/` | **Pure**: `SizeTier` (size, block cap), `BuildPrompt` (outer whitespace and lone surrogates go: the box, counting UTF-16 units, can cut an emoji in half at its limit, and the half would go out as "?"; Build sends the prompt normalized), `PromptHistory` (a stored entry a prompt couldn't be, blank or over 2,000, is skipped), `Messages` (all chat copy; a prompt it echoes is scrubbed of keys), `HudUpdate` (the panel: send, clear or nothing). |
| `core/config/` | **Pure**: `AiBuilderConfig` (`config/aibuilder.json`, spec §14: every key with its default; a bad value falls back with a warning; `_`-keys are comments) and `ConfigFile` (written whole with the defaults on the first run, never rewritten). `BuildManager.config()` reads it again whenever the file changes, so edits apply to the next build. The demo (`/aibuild test`) keeps its own caps. |
| `core/frame/` | **Pure**: `Facing` (yaw snap), `LocalFrame` (local ↔ world), `Directions` (forward/left… ↔ the template's compass). |
| `core/blocks/` | **Pure**: `Validator` (unknown, banned, compass words, bad property/value, with suggestions) over a `BlockCatalog`; `Footprint` (the other half a door, tall plant or bed writes, so the tools check it as they check the block: in the box, breakable, not player-made, clear of lava). |
| `core/shapes/` | **Pure**: `Shapes` (fill, wall, line, cylinder, sphere, cone, arch), `Roofs`, `Staircases` → local blocks. |
| `core/place/` | **Pure**: `PlacementOrder` (passes, bottom-up), `UndoJournal` (first original wins; restore only if unchanged), `UndoHistory` (a build goes in as it starts and is trimmed to undo_history_size only once it has something to undo, so an empty build never costs an older one its undo). |
| `core/tools/` | **Pure**: `ToolExecutor` (all 21 tools; `tools.json` is the catalog; set_blocks keeps one entry per spot, the last given, since placement sorts a batch and an earlier lava would end up over a later block; word arguments take any case; get_context gives the config's site_gap and, on a refinement, the build and the box its changes fill; the player is nowhere when they're offline or in another dimension; survey_area says it's the site as the build began and where the player stands; inspect_region refuses a text over `INSPECT_MAX_CHARS`), `WorldReader` (what the read tools and protection read through), `ServerHop` (a world read runs on the server thread or fails, never inline on a stopping server), `Budget`, `PhaseProgress`, `BuildSink`. |
| `core/survey/` | **Pure**: `TerrainSnapshot`/`Column` and `SurveyEncoder` (survey_area's compact text, coarsened to stay under 30,000 characters), `RegionEncoder` (inspect_region; neither uses `=` as a palette key), `BuildProblems` (check_build's answer: grouped by kind, at most 60 positions shared out), `Darkness` (check_build's "indoors": little sky light under a sky; without one, a block within `CHECK_ROOF_SCAN` (16) above, as the Nether's ceiling is over every spot). |
| `world/BuildCheck` | check_build's look over the build, over ticks within the budget (at least one position a tick): each changed position and the spots on and beside it. Unsupported (`!canSurvive`, a falling block over a drop), dark (2 high over ground a zombie may spawn on, vanilla's `isValidSpawn`: not glass, leaves or a bottom slab; indoors by `Darkness`; block light 0, after the light engine catches up), doors (solid at head height on either side, or higher than the 0.6 step at the feet: a carpet, a bottom slab or a closed trapdoor is walked over), low (1 high under a solid build block, stairs and leaves aside), spill (a source with an open side or air below). Reads only loaded chunks; reports only inside the allowed box. One read at a time per build (`McWorldReader.check`): a retry while one reads waits on it. |
| `core/place/ProtectionMask` | **Pure**: protected (probably player-made) and unbreakable spots in the allowed box; a write there is rejected `protected`. |
| `core/blocks/BlockEntityData` | **Pure**: place_block_entity's data, parsed and checked (`ItemCatalog`: items, stack sizes, banner patterns; `BlockInfo.entity()` the kind a block takes): sign text (4 lines a side, plain text: `§` codes and control characters dropped, 384 characters a line), up to 27 stacks (nothing that holds items), a book (50 pages × 800, title 32), 6 banner layers, an item frame (relative facing). It rides on `Placement.data`; an item frame is a placement with no block. |
| `world/BlockEntityWriter`, `ItemFrames` | The data written right after its block goes in (`PlacementJob.dress`: a spot the build hadn't changed is journaled first); a lectern gets `has_book`. Item frames hang only where they survive and are journaled by UUID (`UndoJournal.Spawned`, saved as `entities`, optional for old saves); undo takes them down first, without drops, unless they hold the player's item. |
| `core/place/FireMask`, `FireRule` | **Pure**: lava and what burns (vanilla's `ignitedByLava`) in and around the allowed box. Lava within 3 blocks sideways, from 1 below to 4 above, of anything that burns is rejected `lava_near_flammable`, and so is anything that burns that close to lava, counting the world and the build so far (spec §9). A replace-mode fill may leave the old block, so both count there. |
| `core/agent/` | **Pure**: `AiBackend`/`BuildHandle`/`BuildEvents`, `ScriptedBackend`; Claude Code's side: `StreamEvents` (stream-json, never throws), `InitCheck` (build tools, our server, no API key, built-in plugins only), `UsageLimits` (stop at the plan limit or at paid extra usage), `ClaudeLaunch` (flags, environment, mcp.json; `binaryFirst`: the binary's folder first on the child's PATH, as an npm install's claude runs `env node` and node sits beside it), `ClaudeLocator` (`configuredProblem`: a claude_path that won't run is warned about), `AuthStatus`, `McpHandler`/`McpSessions` (JSON-RPC, tokens, origins). |
| `backend/` | JDK only, no Minecraft: `McpHttpServer` (127.0.0.1, 8 threads; `McpHandler.refusal` runs on the headers, a web `Origin` or no live token, before any body is read), `ClaudeCodeBackend` (the process, its threads, caps, cancel, `stopAll`; `exited()` is the process itself ending, not its output, which a child of it may hold open; builds write their token and system prompt only in a temp root that's a real folder of this user's, made owner-only, else the same name with the user's after it (Claude Code files sessions under its working folder, so this keeps refinements resumable from one game to the next), else a fresh private one; build folders a day old, left by a crash, are swept at each launch; a retry with no HTTP status reads "Can't reach Claude", and a final `api_error` with none "Lost the connection to Claude"), `ClaudeChecks` (find it, check the login, remember a pass 5 min; `auth status` that prints no JSON answer is a broken install, `Messages.claudeBroken` with stderr's error line from `AuthStatus.why`, not "log in"), `LogWriter` (a log's lines, appended on a thread of its own; nothing after it's closed). |
| `core/agent/BuildLog`, `Scrub` | The build log (spec §16), `logs/aibuilder/<date>_<time>_<player>_<slug>.jsonl` (the slug from the scrubbed prompt, so a pasted key never names a file) with the raw stream beside it as `….stream.jsonl`. **Pure** `BuildLog` makes the lines: `request` (prompt, size, mode, model, effort, session), `claude` (Claude Code's version, at its start), `tool` (arguments with arrays over 20 cut to "… N more", the answer's first line cut to 300, `ToolResult.rejections`, ms), `usage` (each turn's tokens), `outcome`, `totals`. `Scrub` takes out the build's token, bearer tokens and `sk-ant-…` keys, with no word boundary in front (in JSON a key on its own line follows `\n`); `BuildLog` scrubs every text before it's serialized, then the line. One `Scrub` a build: the backend tells it the token as it opens it and runs the stream log and Claude Code's stderr through it; the summary in chat goes through it too. `ToolExecutor.listen` reports each call; `BuildEvents.started`/`usage` the rest; the outcome and totals are written once the process has ended, so the last turn counts. Claude builds only. |
| `resources/aibuilder/prompts/builder_system.md` | The in-game Claude's system prompt (`config/aibuilder/builder_system.md` replaces it). |
| `TestHooks` | The gametests' seam: the fake Claude Code's command and scenario, and a config in place of the file's (`PermissionGameTest`'s two allowed players). Nothing in the mod sets it. |
| `core/site/` | **Pure**: `GroundFinder` (the origin's floor, nearest the feet), `SurveyFloor` (a survey column's: climbs a mountain to the box top, reads water on raised ground to its surface, "no floor" over a drop), `BuildBox` (allowed box per tier, clipped to the world), `SiteSquare` (the columns the site survey reads: the allowed box and 8 past it, at least the tier's own site, so a refinement's grown box is protected and fire-checked to its edge). |
| `world/` | Minecraft side: `McBlockCatalog`, `StateFactory` (rotate, second halves, persistent leaves), `PlacementJob`, `ShapePass`, `JobScheduler`, `BuildTickets`, `UndoStore` (read a build at a time: one naming a block the game no longer has is left out with a warning, not the whole store), `UndoRunner`, `Sites`, `Occupancy`, `ProtectionHeuristic` (natural = vanilla terrain/growth tags + a short list), `WorldSurvey` (columns and the protection mask, read over ticks), `McWorldReader` (the real `WorldReader`: starts the site survey when a build starts; region and fact reads hop to the server thread). |
| `BuildManager` | One per server: one job at a time (build or undo), owner only; refine or new; the Claude Code check (on a worker, once the last Claude build's process itself has ended, `BuildHandle.exited()`: one process at a time, so a session is never resumed from two; the demo doesn't count), then the build; status in the action bar; the end message per outcome; saves on stop and ends every Claude Code process; `/aibuild survey` (encoded and written off the server thread, a file of its own each time). A build is stopped only by its owner, and `/aibuild status` asks the same permission as building. |
| `core/request/Refinement`, `BuildSession`, `world/SessionStore` | Refinement (spec §5.4). **Pure** `Refinement.decide`: the player's last build refines when it's in their dimension and they stand within 48 blocks (sideways) of its changes, unless `/aibuild new` or the screen's toggle; `Refinement.prompt` tells Claude to call get_context first, and what the player undid. `BuildSession` (frame, size tier, box of changes, Claude Code session id, prompts, title) is saved per player with the world (`SessionStore`). A refinement resumes the session (`--resume`, every flag again), keeps its size tier, gets the caps afresh and the box `BuildBox.refined` (the changes grown by 16, down by at most W/4). A build that changed nothing leaves the session as it was; undoing the first build ends it, undoing a change marks it undone; the demo ends it. The record catches up every 5 s while a build runs and when the server stops, so a quit or a crash mid-build still leaves a build to refine. A refinement whose session Claude Code doesn't have (it exits before its start line: the world came from another computer, or the history was cleaned up) ends the record (`BuildHandle.sessionLost`), and the next prompt builds new. A brief's title is cut to 64 characters. |
| `net/BuildModePayload` | The screen's mode line: the screen asks when it opens (`BuildModePayload.Query`), the server answers with the title a prompt from there would refine, or "" for a new build. |
| `BuildPermission`, `core/request/BuildRules`, `RateLimit` | Who may build (spec §15): singleplayer and LAN, the owner only by UUID whatever the config says; a server, `allowed_players` (name any case, or UUID) with `op_level` while `require_op` (the level from `server.getProfilePermissions(nameAndId)`). Only commands they type (`mixin/CommandSourceStackAccessor`). `rate_limit_per_hour` per player over a sliding hour, checked before Claude Code is looked for. |
| `net/BuildPromptPayload` | Serverbound packet: the prompt, its size and `forceNew` (the screen's toggle). Wire limit 8,192 chars, far above the 2,000 prompt limit, so a long prompt gets a chat answer instead of a decode failure that drops the connection. |
| `client/BuilderScreen` | The screen; B opens it, but not with Ctrl or Cmd held (Ctrl+B, Cmd+B on macOS, is the narrator's shortcut; `AiBuilderClient`). A recent prompt of more than one line shows its first with "…". The typed text lives in a field and survives `init()` re-runs (resize, GUI scale). The mode button says "Refining: <title>" or "New build here" and toggles between them; the rows under the box leave room for its n/2,000 counter. While the player's build runs (`StatusHud.current()`), Build is "Stop build" (it sends `/aibuild cancel`, typed) and the status shows in place of the prompt's label (spec §5.1). |
| `net/BuildStatusPayload`, `core/request/BuildStatus`, `client/StatusHud` | The HUD (spec §5.3): once a second the owner gets the brief's title and phases, the phase under way, blocks placed and tool calls (Claude's text as plain text, no § codes or control characters, cut to `HUD_TEXT_MAX` (48) characters and `HUD_PHASES_MAX` (12) phases); `BuildStatus.CLEAR` at the end and while they're dead or in another dimension (`HudUpdate`). The client draws it top left (before the chat layer) and drops it after `HUD_STALE_SECONDS` (5) without news or in another world. |
| `client/PromptHistoryStore` | `config/aibuilder-history.json`; write-temp-then-move; never throws. |
| `src/gametest/` | Client gametests (smoke, prompt to server, the screen, the demo build, a whole build with the fake Claude Code: refinement, one process at a time, the logs, a lost connection, a lost session, a world closed mid-build; and, gated, the live build and the showcase world) and server gametests (`PlacementGameTest`, `UndoGameTest`, `PermissionGameTest`, `SiteGameTest`, `SurveyGameTest`, `CheckBuildGameTest`, `BlockEntityGameTest`, `RefinementGameTest`, run in the `aibuilder-test:arena` structure through `ArenaBuilds`). `PermissionGameTest.onlyTheBuildsOwnerCancelsIt` runs in a test environment of its own (`aibuilder-test:alone`), so its build never overlaps the tests that ask the manager whether anything runs. `GameTestSupport` captures chat and the action bar with `ClientReceiveMessageEvents.GAME`. `TestSetup` (the test mod's main entrypoint) points every gametest run at the fake Claude Code. |
| `src/test/resources/fake-claude/FakeClaude.java` | A fake `claude` (JDK only, `java FakeClaude.java …`): `auth status` and a `-p` run with real MCP calls, by `FAKE_CLAUDE_SCENARIO`. It holds a lock file per session while it runs and refuses a session another process holds (exit 4), so a test catches two processes on one session. Its `leak` scenario prints its token and a key-shaped string (stream, stderr, summary) for the log tests. `src/test/resources/stream/` holds the M4 spike's real recordings. |

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
| `python3 tools/stream_to_script.py <x.stream.jsonl>` | Turn a build's stream log into a replay script for `ScriptedBackend` (`src/test/resources/scripts/castle-m5.json` is the M5 live castle, replayed by `ScriptedBackendTest.aRealBuildReplays`) |
| `python3 tools/gen_arena.py` | Rewrite the server gametests' arena (`src/gametest/resources/data/aibuilder-test/gametest/structure/arena.snbt`) |
| `AIBUILDER_LIVE=1 ./gradlew runClientGameTest --no-daemon` | One live build with the **real** Claude Code on the owner's login (uses their plan's usage), and no other test: `AIBUILDER_LIVE_PROMPT`, `AIBUILDER_LIVE_SIZE`, and `AIBUILDER_LIVE_REFINE` for a follow-up typed beside it; screenshots in `build/run/clientGameTest/screenshots`, the build logs and streams in `build/run/clientGameTest/logs/aibuilder/` |
| `AIBUILDER_LIVE=1 AIBUILDER_SHOWCASE=1 ./gradlew runClientGameTest --no-daemon` | The showcase world (`ShowcaseClientGameTest`): live builds, each in a biome that suits it, the world copied after each build into `run/saves/AI Builder Showcase` (the dev client's saves, so `./gradlew runClient` lists it); its caps are raised for the run and put back; `AIBUILDER_SHOWCASE_ONLY=castle,temple` picks some. It writes `tour.json` (each build's spot, facing and size) into the copy |
| `… AIBUILDER_SHOWCASE_TOUR=1 ./gradlew runClientGameTest --no-daemon` (with the two above) | No building and no Claude: a copy of the saved showcase world is opened with the HUD hidden and 10 chunks drawn, and each build in `tour.json` is pictured from the front-left, above and walking up (`tour-*.png`) |

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
- Entity type constants are `EntityTypes.ZOMBIE` and the like (plural), not `EntityType.ZOMBIE`.
- **Server gametests are batched by test environment** and the batches run one after another: a test that changes
  shared state (the manager's build) gets `@GameTest(environment = "aibuilder-test:alone")`, defined in
  `data/aibuilder-test/test_environment/alone.json` (vanilla's `default.json`, copied).
- A fake key event in a client gametest carries no modifiers (`TestInput` builds it with 0), so a Ctrl+key shortcut
  doesn't fire; `InputConstants.isKeyDown` does see the held key. `holdControl()` holds Cmd on macOS.
- Gson's reader skips a byte-order mark at the start of the text: a config saved by Windows Notepad reads as it is.
- `List.copyOf` of an empty list is the one shared empty list: two answers compared by identity need content.
- A torch on air pops off in the shape pass at a build's end: a check after the build finds a low ceiling, not it.
