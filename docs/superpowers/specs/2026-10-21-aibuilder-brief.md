> Frankie's original request for the AI Builder mod, pasted into the brainstorming session on 2026-10-21 and
> kept here word for word. The decisions and design are in `2026-10-21-aibuilder-design.md`; where the two
> differ, the design wins.

# AI Builder: a Minecraft mod where Claude builds anything I type

## The vision

I want a Minecraft mod where I press a key, a text box opens, and I type anything: "castle," "a cozy cabin with a dock," "a 40-block wizard tower with a spiral staircase," "a Japanese garden with a koi pond." Claude then surveys the terrain around me, designs the build, and constructs it right there near where I'm standing, placing blocks in the live world while I watch.

Think of it as autopilot for building. It **never moves or controls my player**. It only builds. It **ignores my inventory entirely** and can use any block registered in the game, including blocks from other installed mods.

Claude is the creative director. It decides the layout, materials, style, scale, and details. Short prompts should be read generously and ambitiously. "Castle" should mean curtain walls, corner towers, a gatehouse, a keep, battlements, and furnished interiors. It should never mean a stone box.

---

## Confirm these with me before writing code

Ask me these up front, give your recommendation for each, then write the agreed plan to `PLAN.md` before implementing anything.

1. **Edition:** Java Edition (recommended, since Bedrock add-ons can't do this well).
2. **Mod loader:** Fabric (recommended) or NeoForge. Target a single version.
3. **Minecraft version:** the latest stable version the chosen loader supports. Check current versions and the required JDK version rather than relying on your training data.
4. **How Claude is called at runtime** (the most important decision):
   - **Option A: Direct Claude API.** The mod calls the Anthropic Messages API with tool use and runs the agent loop itself. It's self-contained in one jar and needs an Anthropic API key, billed per token.
   - **Option B: Claude Code as the in-game brain.** The mod hosts a local MCP server (localhost only) that exposes the build tools. When I submit a prompt, a small bridge launches Claude Code in headless mode (`claude -p` with an MCP config pointing at the mod) or uses the Claude Agent SDK. Claude then connects to the MCP server and builds. Verify the current CLI flags and auth behavior in the docs.
   - Recommendation: put both behind an `AiBackend` interface so either can be used, and implement the one I pick first.
5. **Scope:** singleplayer first (recommended), with dedicated-server support added later behind an op-permission check?

---

## In-game experience

- **Keybind** (default `B`, rebindable in Controls) opens a screen with:
  - a multi-line text box for the prompt,
  - a size selector (Small / Medium / Large / Epic) with Medium as the default unless the prompt implies a size,
  - Build and Cancel buttons,
  - a short list of my recent prompts I can click to reuse.
- **Chat commands** as an alternative:
  - `/aibuild <prompt>`
  - `/aibuild undo`
  - `/aibuild cancel`
  - `/aibuild status`
- **Live status** in the action bar or chat as it works, for example: "Surveying terrain…", "Designing: castle (gothic, 48×40, stone brick + dark oak)…", "Building walls (2/7)…", and finally "Done: 6,214 blocks placed. `/aibuild undo` to revert."
- **Animated construction.** Blocks appear progressively, phase by phase, so I can watch it build. Subtle particles or sounds are a nice touch.
- **Follow-up refinement.** If my next prompt is "make the towers taller" or "add a moat," it modifies the last build instead of starting over. Keep per-player session context: the last prompt, the design brief, the build's bounding box, and the origin.
- **Player safety.** The build site is chosen in front of me based on my facing direction, a few blocks away. Never place blocks inside my hitbox or trap me. Never move or control the player.

---

## Architecture

- **Sided code.**
  - Client: keybind, GUI screen, status HUD.
  - Server: survey, AI orchestration, block placement, undo. This runs on the integrated server in singleplayer.
  - Custom networking packets carry the prompt (client to server) and status updates (server to client).
- **Never block the game thread.**
  - AI calls run on a background executor.
  - World reads (scan snapshots) and writes happen only on the server thread, scheduled through the server's task queue.
  - Snapshot the scan region on the server thread, then serialize and compress it off-thread.
- **Placement queue.**
  - Each build is a job that places a configurable number of blocks per tick (for example 200–1000) so the game never stutters.
  - Order placement bottom-up and structural-first, so gravity blocks (sand, gravel) and attached blocks (torches, doors, ladders, lanterns, signs) always have support when placed.
- **Block-update handling.** Choose block-update flags carefully so bulk placement doesn't cause lag spikes or mid-build physics (water flowing, sand falling, redstone firing). Make sure lighting and neighbor shapes (fences, panes, walls connecting) are correct when the build finishes.
- **Chunks.** Force-load the chunks in the build area for the duration of the job.
- **Undo.**
  - Before changing any position, record its original block state and block entity data (NBT).
  - `/aibuild undo` restores them exactly.
  - Keep the last N builds per player.
- **Validation.**
  - Validate every block ID and block-state property against the live registry.
  - Never crash on bad input. Reject or repair invalid entries and report them back to Claude so it can correct itself.
  - Blocks from other installed mods work automatically through the registry.

---

## The tools Claude can call

Define these once as the single source of truth. The same definitions should generate the Option A tool schemas and the Option B MCP server.

### Local coordinate frame

This is a big quality win. Give Claude a local frame anchored at a build origin chosen in front of the player:

- `+x` = player's right
- `+z` = forward (away from the player)
- `+y` = up, with `y=0` at ground level at the origin

Claude never deals with absolute coordinates or facing math. The mod converts local coordinates to world coordinates and rotates directional block states (stairs, doors, logs, banners, and so on) to match. Use the game's built-in block-state rotation.

### Read tools

- `get_context`: player position and facing, dimension, biome, time of day, weather, the chosen build origin, and max allowed dimensions for the selected size.
- `survey_area(radius)`: a compact terrain summary with:
  - a relative heightmap grid,
  - surface block types,
  - water and lava,
  - trees,
  - likely player-made blocks, flagged as protected.

  It must be token-efficient: use a palette with indices, run-length encoding, and summaries, not raw JSON per block.
- `inspect_region(box)`: exact block states inside a small box. Cap the volume.
- `search_blocks(query)`: find valid block IDs matching a term ("oak," "glass," "lantern") along with their state properties.

### Write tools

All write tools use local coordinates, are validated, and are queued for tick-batched placement.

- `set_blocks([{pos, block, state}])`: individual placements.
- `fill(box, block, mode)`: `mode` is `solid`, `hollow`, `outline`, or `replace:<filter>`.
- **Geometry primitives**, so Claude doesn't have to list thousands of coordinates:
  - `line`
  - `wall`
  - `cylinder` (hollow option, any axis)
  - `sphere` / `dome`
  - `cone`
  - `arch`
  - `roof` (gable / hip / flat, built correctly from stairs and slabs with overhang)
  - `staircase` (straight and spiral)
- `clear(box)`: sets the region to air but respects protected blocks unless the user explicitly asked for removal.
- `terraform(box, target_height, surface_block)`: level or fill ground for foundations.
- `place_block_entity`: signs with text, chests or barrels with decorative contents, lecterns, banners with patterns, item frames.
- `checkpoint(phase_name)`: flushes queued placements and pushes a progress update to the player.
- `finish(summary)`: ends the build and reports to the player.

### Write-tool responses

Every write tool returns the number of blocks queued plus any rejected entries with reasons. Claude should prefer primitives and batching over long individual block lists.

---

## Build pipeline (what the in-game Claude does)

1. **Survey.** Call `get_context` and `survey_area`.
2. **Design brief.** Decide style, footprint, height, and a palette of 4–8 blocks with roles (primary, secondary, accent, trim, roof, glass, floor, light), then a phase list. Show a one-line summary to the player.
3. **Prepare the site.** Clear or terraform only what's needed. Adapt to terrain where that looks better: stilts on slopes, docks over water, building into a hillside.
4. **Build in phases.**
   1. foundation
   2. structure and walls
   3. floors
   4. roof
   5. doors and windows
   6. exterior detail (trim, pillars, depth)
   7. interior (furniture, lighting, decor)
   8. landscaping (paths, gardens, exterior lighting)
5. **Verify and fix.** Re-inspect key regions and fix problems:
   - floating blocks,
   - roof gaps,
   - stairs facing the wrong way,
   - doors that can't open,
   - unlit interiors where mobs could spawn,
   - rooms with less than 2 blocks of headroom.
6. **Report.** Give a summary, the block count, and the undo hint.

Enforce a configurable cap on tool iterations and tokens per build. If the cap is hit, finish gracefully with what's built so far and tell the player.

---

## Building-quality system prompt for the in-game Claude

Put this in an easily editable file such as `src/main/resources/prompts/builder_system.md` so I can tune it without recompiling logic. It should cover:

- **Ambition.** Interpret prompts generously and ambitiously, but stay within the size limits.
- **Depth.** No flat walls. Add depth with pillars, recessed windows, protruding trim, buttresses, and 1-block roof overhangs, using stairs, slabs, walls, fences, and trapdoors as detail blocks.
- **Palette.** Use coherent palettes with texture variation, for example stone bricks mixed with cracked, mossy, and andesite.
- **Shape.** Use interesting silhouettes: varied heights, asymmetry where it fits the style, and proper roof pitch.
- **Biome-appropriate materials.** Sandstone in deserts, spruce in taigas, and so on, unless the prompt says otherwise.
- **Functionality.**
  - working doors,
  - stairs or ladders between floors,
  - 2+ block headroom,
  - interiors lit well enough to prevent mob spawns.
- **Correct block states.** Stair facing and half, slab type, door upper and lower halves, two-block beds, connected fences and panes.
- **Respect the world.** Never destroy existing player builds unless asked, and blend the build into the surrounding terrain.

---

## Safety, performance, and limits

- **No freezes, ever.** The game must never freeze, and all heavy work happens off-thread.
- **Size limits.** Configurable max build volume per size tier (for example Small 24³, Medium 48³, Large 80³, Epic 128³) and a max block count per build.
- **Cancel.** Stops the queue immediately, and a partial build can still be undone.
- **Protected blocks.** Use a heuristic to detect likely player-made blocks (non-natural blocks such as planks, bricks, glass, crafted items) and avoid overwriting them. Document it as a heuristic.
- **Multiplayer.**
  - configurable op permission level,
  - one active build per player,
  - per-player rate limiting.
- **Secrets.**
  - Read the API key from config or an environment variable.
  - Never log it, never commit it, and git-ignore local config.
  - If the key is missing, show a clear in-game message explaining how to set it.
- **Errors.** Retry API and network failures with backoff, and report them clearly in game. A failure mid-build must leave the world in a consistent, undoable state.
- **Logs.** Write a per-build log with the prompt, tool calls, rejections, timing, and token usage (never the key) for debugging and prompt tuning.

---

## Configuration

Use a config file such as `config/aibuilder.json` with:

| Setting | What it controls |
|---|---|
| `backend` | `api` or `claude_code` |
| `model` | which Claude model to use; configurable, with a current model as the default |
| `api_key_env` | name of the environment variable holding the API key |
| `size_limits` | max volume for each size tier |
| `blocks_per_tick` | placement speed |
| `max_tool_iterations` | cap on agent-loop steps per build |
| `max_tokens_per_build` | token budget per build |
| `require_op` / `op_level` | multiplayer permissions |
| `undo_history_size` | how many builds per player can be undone |
| `animate_build` | progressive vs. instant placement |
| `protect_player_blocks` | whether the protected-block heuristic is on |

---

## Testing

- **Unit tests (JUnit):**
  - geometry primitive expansion,
  - local-to-world coordinate transforms and block-state rotation for all four facings,
  - block ID and state validation,
  - undo snapshot and restore,
  - tool argument parsing.
- **Mock AI backend.** It replays a recorded tool-call script, so the whole pipeline (survey → placement → undo) can be tested offline with no API calls or cost.
- **In-world tests.** Use the loader's GameTest framework for block placement, rotation, and undo.
- **Manual test checklist:**
  - flat plains, steep mountain, shoreline/ocean, desert, dense forest, the Nether,
  - cancel mid-build, undo after cancel,
  - invalid block names,
  - a very long or vague prompt,
  - missing API key,
  - a network drop mid-build.

---

## Milestones

Stop after each milestone, tell me how to run it (for example `./gradlew runClient`), and tell me exactly what to test before moving on.

1. **Skeleton.** The mod loads, the keybind opens the text box, the prompt reaches the server and echoes in chat.
2. **Placement engine.** `/aibuild test` places a hardcoded test structure using local coordinates, with rotation, tick-batched placement, undo, and cancel.
3. **Survey.** The read tools are implemented, and `/aibuild survey` prints the compressed terrain summary for debugging.
4. **Claude integration.** The chosen backend runs the full tool loop, and the first real build is made from a prompt.
5. **Quality pass.** System prompt tuning, the phase/verify/fix steps, and the live status HUD.
6. **Polish.** Config, permissions, error handling, follow-up refinement prompts, and per-build logs.

**Stretch goals:**
- footprint preview outline with Confirm/Cancel before building,
- sending a screenshot to Claude for visual self-critique and touch-ups,
- saving finished builds as structure files to re-place later,
- a build history/gallery screen.

---

## How I want you to work

- **Check current docs.** Fabric/NeoForge APIs, Loom/Gradle setup, Minecraft mappings, and the Anthropic API, Agent SDK, and Claude Code CLI all change frequently. Use documentation tools or web search if available instead of relying on memory.
- **Maintain `PLAN.md`.** Write it first and keep it updated as decisions change.
- **Explain modding concepts.** Briefly explain Minecraft-modding concepts (mixins, registries, client vs. server code, networking, block states) the first time they come up.
- **Keep the code testable.** Keep the AI backend, the tool layer, and the placement engine cleanly separated so each can be tested on its own.
