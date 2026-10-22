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

## Architecture (as of M2)

```
B key ──► AiBuilderClient ──► BuilderScreen ──Build──► BuildPromptPayload ──┐
/aibuild <prompt> ──────────────────────────────────────────────────────────┤──► BuildManager.submit: M1's echo (M4: Claude)
/aibuild test|undo|cancel|status ──► AiBuildCommand ──► BuildManager (one per server, server thread)
                                                              │ test: Sites.ahead (frame) + BuildBox (allowed box)
   worker thread:  ScriptedBackend ──calls──► ToolExecutor ──batches──► McBuildSink ──enqueue──► PlacementJob
   (M4: Claude)    replays demo_house.json    validate, shapes,          checkpoint waits    server tick (JobScheduler):
                                              local → world              for the job         818 writes + journal, then
                                                                                             ShapePass (finalize)
   job ends ──► UndoStore.push (SavedData) ──► chat "Done: N blocks placed. [Undo]"
   /aibuild undo ──► UndoStore.pop ──► UndoRunner (server tick): restore what still holds the build's block, finalize
```

| Where | What |
|---|---|
| `core/request/` | **Pure**: `SizeTier` (size, block cap), `BuildPrompt`, `PromptHistory`, `Messages` (all chat copy). |
| `core/frame/` | **Pure**: `Facing` (yaw snap), `LocalFrame` (local ↔ world), `Directions` (forward/left… ↔ the template's compass). |
| `core/blocks/` | **Pure**: `Validator` (unknown, banned, compass words, bad property/value, with suggestions) over a `BlockCatalog`. |
| `core/shapes/` | **Pure**: `Shapes` (fill, wall, line, cylinder, sphere, cone, arch), `Roofs`, `Staircases` → local blocks. |
| `core/place/` | **Pure**: `PlacementOrder` (passes, bottom-up), `UndoJournal` (first original wins; restore only if unchanged), `UndoHistory`. |
| `core/tools/` | **Pure**: `ToolExecutor` (the write tools; `tools.json` is the catalog), `Budget`, `PhaseProgress`, `BuildSink`. |
| `core/agent/` | **Pure**: `AiBackend`, `ScriptedBackend` (replays a script on a worker thread). M4 adds Claude Code. |
| `core/site/` | **Pure**: `GroundFinder` (the floor nearest the feet), `BuildBox` (allowed box per tier, clipped to the world). |
| `world/` | Minecraft side: `McBlockCatalog`, `StateFactory` (rotate, second halves, persistent leaves), `PlacementJob`, `ShapePass`, `JobScheduler`, `BuildTickets`, `UndoStore`, `UndoRunner`, `Sites`, `Occupancy`. |
| `BuildManager` | One per server: one job at a time (build or undo), owner only; status in the action bar; saves on stop. |
| `BuildPermission` | Singleplayer owner only (M6 adds the dedicated-server allow list). |
| `net/BuildPromptPayload` | Serverbound packet. Wire limit 8,192 chars, far above the 2,000 prompt limit, so a long prompt gets a chat answer instead of a decode failure that drops the connection. |
| `client/BuilderScreen` | The screen. The typed text lives in a field and survives `init()` re-runs (resize, GUI scale). |
| `client/PromptHistoryStore` | `config/aibuilder-history.json`; write-temp-then-move; never throws. |
| `src/gametest/` | Client gametests (smoke, prompt to server, the screen, the demo build) and server gametests (`PlacementGameTest`, `UndoGameTest`, `PermissionGameTest`, run in the `aibuilder-test:arena` structure through `ArenaBuilds`). `GameTestSupport` captures chat with `ClientReceiveMessageEvents.GAME`. |

**Directions (spec §7).** Tool input says forward/back/left/right/up/down, never compass names. They map to the
compass as if the player faced south (forward → south, right → west), then vanilla `BlockState.rotate` turns the
state: SOUTH `NONE`, WEST `CLOCKWISE_90`, NORTH `CLOCKWISE_180`, EAST `COUNTERCLOCKWISE_90`. Only properties whose
values are compass directions translate; `axis` and `rotation` go straight to the rotate.

**Threads.** The backend runs on its own thread and only calls `ToolExecutor` (synchronized). Batches cross to the
server thread through `PlacementJob.enqueue` (locked); a checkpoint blocks the backend's thread on `whenIdle()`.
Everything that touches the world, the journal, `UndoStore` or chat runs on the server thread: callbacks from the
backend hop over with `server.execute`.

**Placement.** Writes use `/fill … strict`'s flags (818), so nothing reacts while a build goes up; then
`ShapePass` runs `Block.updateFromNeighbourShapes` over every changed block and its neighbours, skipping anything
holding a fluid (a waterlogged block would start flowing) and anything next to an unloaded chunk (reading it would
load it on the spot). Both are spread over ticks at 250 blocks or 3 ms a tick.

## Commands (run here, in `mods/aibuilder/`)

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests, produce the jar in `build/libs/` |
| `./gradlew test` | Unit tests only (JUnit): fast, no game launch |
| `./gradlew runClient` | Launch a dev Minecraft with the mod, for manual play |
| `./gradlew runGameTest` | Headless server gametests (placement, undo, permissions) in the arena structure |
| `./gradlew runClientGameTest` | Launch the real client, run the client gametests, save screenshots to `build/run/clientGameTest/screenshots/`. **Look at them.** |
| `python3 tools/gen_arena.py` | Rewrite the server gametests' arena (`src/gametest/resources/data/aibuilder-test/gametest/structure/arena.snbt`) |

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
  `AbstractBedBlock` (the new straw bed too); the id is still `minecraft:red_bed`.
- `BlockState.is(Block / TagKey / …)` and `ItemStack.is(Item)` come from `TypedInstance`, not the classes themselves.
- Signs: `sign.getText(SignTextSlot.FRONT).asMutable().setLine(0, text).asImmutable()`, then `setText(…, slot)`.
- DFU 10 has no `Codec.unit`; use `MapCodec.unit(supplier).codec()`. `SavedDataType(id, constructor, codec, null)`.
- Brigadier tries only the literal when a word matches one, so `/aibuild test …` never reaches the prompt argument.
