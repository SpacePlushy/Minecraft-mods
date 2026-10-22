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

## Commands (run here, in `mods/aibuilder/`)

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests, produce the jar in `build/libs/` |
| `./gradlew test` | Unit tests only (JUnit): fast, no game launch |
| `./gradlew runClient` | Launch a dev Minecraft with the mod, for manual play |
| `./gradlew runClientGameTest` | Launch the real client, run the client gametests, save screenshots to `build/run/clientGameTest/screenshots/`. **Look at them.** |

## Gotchas

- **Gametests while the Mac is locked:** a vsync'd buffer swap waits for a display refresh that never comes while
  the screen is locked or asleep, so the test client froze on its first frame (render thread stuck in
  `SDL_GL_SwapWindow`). The gametest source set's `GlSurfaceVsyncMixin` forces swap interval 0. Fabric's
  `restoreDefaultGameOptions` resets options before every test, so editing `options.txt` doesn't help.
- macOS has no `timeout` command; to bound a gametest run, background it and kill the client
  (`pkill -f fabric.client.gametest`) after a deadline.
