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

## Architecture (as of M1)

```
B key ──► AiBuilderClient (END_CLIENT_TICK, drains OPEN_BUILDER clicks) ──► BuilderScreen
                                                                              │ Build
                                                    BuildPromptPayload(prompt, size) ──► server thread
/aibuild <prompt> ──► AiBuildCommand ─────────────────────────────┐                     │
                                                                  ▼                     ▼
                              BuildManager.submit(player, prompt, size): owner check, prompt check, reply
```

| Where | What |
|---|---|
| `core/request/` | **Pure**: `SizeTier`, `BuildPrompt` (2,000 code points, normalize/check), `PromptHistory` (last 10 + last size, JSON), `Messages` (chat copy). Unit-tested. |
| `BuildManager` | The one server entry point for prompts, from the packet and the command. M1 echoes; M2 starts a build here. |
| `BuildPermission` | Singleplayer owner only (M6 adds the dedicated-server allow list). |
| `net/BuildPromptPayload` | Serverbound packet. Wire limit 8,192 chars, far above the 2,000 prompt limit, so a long prompt gets a chat answer instead of a decode failure that drops the connection. |
| `client/BuilderScreen` | The screen. The typed text lives in a field and survives `init()` re-runs (resize, GUI scale). |
| `client/PromptHistoryStore` | `config/aibuilder-history.json`; write-temp-then-move; never throws. |
| `src/gametest/` | Client gametests: smoke, prompt to server, the screen. `GameTestSupport` captures chat with `ClientReceiveMessageEvents.GAME`. |

## Commands (run here, in `mods/aibuilder/`)

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests, produce the jar in `build/libs/` |
| `./gradlew test` | Unit tests only (JUnit): fast, no game launch |
| `./gradlew runClient` | Launch a dev Minecraft with the mod, for manual play |
| `./gradlew runClientGameTest` | Launch the real client, run the client gametests, save screenshots to `build/run/clientGameTest/screenshots/`. **Look at them.** |

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
