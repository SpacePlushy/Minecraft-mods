# AI Builder M6 (Polish) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Native, as M1–M5) to implement
> this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The mod a player keeps: a config file, the multiplayer rules, refinement ("add a moat" changes the castle
you just built), a log of every build, and clear messages when something goes wrong.

**Architecture:** As M2–M5: decisions in pure `core` classes (the config with its defaults and checks, who may
build, the rate limit, refine-or-new, the refined box, the log lines and the scrubber), thin world-side wiring
(`BuildManager`, two `SavedData`s, the payloads). Refinement resumes the last build's Claude Code session
(`--resume`) with a new build token, frame and grown box.

**Tech Stack:** as M5.

**Spec:** `docs/superpowers/specs/2026-10-21-aibuilder-design.md` — M6 covers §14 config, §15 permissions and
multiplayer, §5.4 refinement (and §5.1's mode line, §5.2's `/aibuild new`), §16 logs, and §18's M6 checks. The
deferred minors of the M3/M4 ledgers that land in these areas are folded into the tasks that touch them.

## Global Constraints

- Everything in the M2–M5 plans' Global Constraints still holds (pure `core`, `Tunables` for the numbers, 818
  flags, relative directions, real Claude never in the suites, Opus 5.5 subagents, never Frankie's real game or
  worlds).
- Config (§14): `config/aibuilder.json`, created with defaults on first run; a bad value falls back to its default
  with a log warning. Keys: `backend` (`claude_code` | `scripted`), `model`, `effort`, `claude_path`,
  `size_limits`, `blocks_per_tick`, `animate_build`, `max_tool_iterations`, `max_tokens_per_build`,
  `max_build_minutes`, `require_op`, `op_level`, `allowed_players`, `rate_limit_per_hour`, `undo_history_size`,
  `protect_player_blocks`, `site_gap`, `banned_blocks`. No API key anywhere.
- Permissions (§15): singleplayer, the owner only (opened to LAN: still only the host); dedicated server: players
  in `allowed_players` who also have `op_level` (2) while `require_op` (true). The config's comment says
  `allowed_players` should hold only the subscription's owner. One build at a time per world;
  `rate_limit_per_hour` (20).
- Refinement (§5.4): automatic when the player is within 48 blocks of their last build in the same dimension;
  `/aibuild new` always builds new. The session record (prompts, brief title, frame, box of changes, Claude Code
  session id) is saved with the world. A refinement's allowed box is everything the build has changed so far
  grown by 16 on every side (down by at most W/4), clamped to the world; caps apply per prompt; each prompt is its
  own undo entry. One Claude Code process at a time: a session is never resumed from two processes.
- Logs (§16): `logs/aibuilder/<date>_<time>_<player>_<slug>.jsonl`: the request (prompt, size, mode, model,
  Claude Code version), every tool call (name, arguments with long arrays shortened, result summary, rejections,
  time), usage, the outcome, totals; the raw stream next to it. Never a token or credential: the logger scrubs
  bearer tokens and anything shaped like an Anthropic key.

## Review Focus

1. **A refinement while the last build's Claude Code process is still shutting down** (a cancel, then a prompt at
   once) must not resume the session from two processes. → Task 3 `aPromptWaitsForTheLastProcess`.
2. **A hand-edited config with nonsense** (strings for numbers, negative caps, an unknown backend, a banned list of
   non-ids, a missing file, invalid JSON) must load as defaults for those keys and keep working. → Task 1
   `badValuesFallBack`, `brokenJsonIsAllDefaults`.
3. **The MCP token in the logs:** neither the build log nor the stream log may ever hold it, nor a key-shaped
   string from a prompt. → Task 4 `logsNeverHoldTheToken`.
4. **A refinement after the refined build was undone** must build new rather than resume a session whose blocks
   are gone. → Task 3 `undoingTheLastBuildEndsItsSession`.
5. **A dedicated-server player not on the list, or without op,** gets the refusal, and no Claude Code check or
   process runs for them. → Task 2 `strangersAreRefusedBeforeAnyCheck`.

---

### Task 1: The config file

**Files:** Create `core/config/AiBuilderConfig.java` (record, defaults, `load(String json)` →
`Loaded(config, warnings)`, `defaultsJson()`), `world/ConfigFile.java` (read or create `config/aibuilder.json`,
log warnings); Modify `BuildManager`, `ClaudeChecks` (`claude_path`), `ClaudeSettings` (from config), `Sites`
(`site_gap`), `UndoStore` (`undo_history_size`), `McWorldReader` (`protect_player_blocks`), `Validator` use
(`banned_blocks`); Test `AiBuilderConfigTest`.

**Interfaces:** Produces `AiBuilderConfig` with accessors named as the keys in camelCase
(`maxToolIterations()`, `allowedPlayers()`, `rateLimitPerHour()`, `sizeLimit(SizeTier)`…), `AiBuilderConfig.DEFAULT`,
and `ConfigFile.load(Path configDir) → AiBuilderConfig` (never throws).

- [ ] Failing tests: `defaultsWhenMissing`, `badValuesFallBack` (each bad key named in a warning, the rest kept),
  `brokenJsonIsAllDefaults`, `theDefaultFileRoundTrips` (with its `_comment` lines about `allowed_players` and
  Anthropic's terms), `bannedBlocksMustBeIds`, `animateBuildOffPlacesFaster`. Then implement, wire, green, commit —
  `feat(aibuilder): config file`.

### Task 2: Who may build, and how often

**Files:** Create `core/request/BuildRules.java` (`decide(...)` → `Allowed | Refused(reason)`), `core/request/RateLimit.java`
(per player, sliding hour); Modify `BuildPermission`, `BuildManager` (every entry point), `Messages`; Test
`BuildRulesTest`, `RateLimitTest`, `PermissionGameTest`.

**Interfaces:** Consumes Task 1's `requireOp()`, `opLevel()`, `allowedPlayers()` (names or UUIDs, any case for
names), `rateLimitPerHour()`.

- [ ] Failing tests: `singleplayerOwnerOnly`, `lanGuestsAreRefused`, `dedicatedNeedsTheListAndOp`,
  `opNotRequiredWhenOff`, `theTwentyFirstPromptInAnHourWaits` (message says when), `strangersAreRefusedBeforeAnyCheck`
  (gametest: a mock player on a non-singleplayer rule set gets the refusal and `TestHooks` sees no Claude check).
  Then implement, green, commit — `feat(aibuilder): permissions and the rate limit`.

### Task 3: Refinement

**Files:** Create `core/request/Refinement.java` (refine-or-new), `core/request/BuildSession.java` (record), `world/SessionStore.java`
(`SavedData`, per player, codec), `net/BuildModePayload.java` (clientbound: the title being refined, or ""), Modify
`core/site/BuildBox` (`refined(changed, tier, minY, maxY)`), `ClaudeLaunch` (`--resume`), `ClaudeCodeBackend`
(resume), `BuildManager` (mode, session record, busy until the last process has exited), `AiBuildCommand`
(`/aibuild new`), `BuildPromptPayload` (`forceNew`), `BuilderScreen` (mode line and toggle); Test
`RefinementTest`, `BuildBoxTest`, `ClaudeLaunchTest`, `ClaudeBuildClientGameTest` (two prompts).

**Interfaces:** `Refinement.decide(BuildSession last, boolean forceNew, Pos playerWorld, String dimension) → Optional<BuildSession>`;
`BuildBox.refined(Box changedLocal, SizeTier tier, int minY, int maxY) → Box`; `ClaudeLaunch.arguments(…, UUID session, boolean resume)`.

- [ ] Failing tests: `withinFortyEightBlocksRefines`, `fartherOrElsewhereIsNew`, `newAlwaysBuildsNew`,
  `refinedBoxGrowsSixteenDownAtMostAQuarter`, `resumeUsesTheSession` (`--resume <uuid>`, no `--session-id`, every
  other flag again), `undoingTheLastBuildEndsItsSession`, `aPromptWaitsForTheLastProcess`, `sessionsSurviveASave`;
  client gametest: "a hut" then "add a door" with the fake Claude Code — the second run sees `--resume` with the
  first's session id and its allowed box is the grown one; the screen shows "Refining: <title>" and the toggle
  turns it into "New build here". Then implement, green, commit — `feat(aibuilder): refinement`.

### Task 4: Build logs

**Files:** Create `core/agent/BuildLog.java` (JSON lines: request, tool call, usage, outcome, totals; arrays over
20 shortened), `core/agent/Scrub.java`; Modify `ToolExecutor` (a call listener), `ClaudeCodeBackend` (stream log
scrubbed, usage to the log), `BuildManager` (one log per build, written off the server thread); Test
`BuildLogTest`, `ScrubTest`, `ClaudeBuildClientGameTest`.

- [ ] Failing tests: `argumentsAreShortened`, `everyCallIsALine`, `scrubsBearerTokensAndKeys`,
  `logsNeverHoldTheToken` (the client gametest reads both logs and searches for the run's token). Then implement,
  green, commit — `feat(aibuilder): build logs`.

### Task 5: When things go wrong, and the M6 checks

**Files:** Modify `ClaudeChecks` (a "couldn't run" line with the start error or stderr, distinct from "not logged
in"), `ClaudeCodeBackend` (init rejections to the log; stale build folders swept at start; the temp root owner-only),
`McpHttpServer` (Origin and bearer checked before the body is read), `FakeClaude.java` (an `offline` scenario:
retries, then an API error); Test `ClaudeChecksTest`, `McpHttpServerTest`, `ClaudeCodeBackendTest`,
`ClaudeBuildClientGameTest`.

- [ ] Failing tests: `aBrokenInstallIsNotALogout`, `headersAreCheckedFirst`, `staleBuildFoldersGo`,
  `offlineMidBuildEndsCleanly` (chat shows the retries, then the failure line; what was placed is undoable). Then a
  live refinement on Frankie's login ("a stone castle", then "add a moat"), screenshots looked at; docs (README, the
  mod's CLAUDE.md, PLAN.md, the root CLAUDE.md row); commit — `feat(aibuilder): error handling and M6 checks`.

## After the tasks

One fresh Opus 5.5 reviewer reviews M6; Critical/Important fixed test-first; the ledger committed to
`docs/superpowers/plans/2026-10-22-aibuilder-m6-progress.md`; push. Then the showcase world (Frankie, 2026-10-22).
