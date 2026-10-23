# AI Builder: the deferred minors — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Native, as M1–M6) to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the minor findings the M1–M6 reviews deferred that still hold and are worth fixing, so v1's rough edges
are gone; leave the rest with a reason.

**Architecture:** As M1–M6: decisions in pure `core` classes (unit-tested first), thin world and client wiring
(server or client gametests). No new features.

**Tech Stack:** as M6 (Minecraft 26.3, Fabric, Java 25, JUnit 6).

**Spec:** `docs/superpowers/specs/2026-10-21-aibuilder-design.md`. The findings are the `minor (deferred)` lines of
`docs/superpowers/plans/2026-10-2*-aibuilder-m<N>-progress.md`, cited below as #1–#46 in this order: #1–#7 M1, #8–#19
M2, #20–#29 M3, #30–#37 M5, #38–#46 M4 (each ledger's lines as they appear), and M6/1–M6/11 for M6's.

## Global Constraints

- Everything in the M2–M6 plans' Global Constraints holds (pure `core`, numbers in `Tunables`, real Claude never in
  the suites, Opus 5.5 subagents, never Frankie's real game or worlds).
- Frankie's standing rules: no API key, no paid extra usage, only the world's owner builds, the mod never reads,
  stores or forwards Claude credentials, and the MCP token never reaches a log.
- Each task ends green: `./gradlew build` and `./gradlew runClientGameTest` in `mods/aibuilder`.

## Triage (what this plan doesn't do, and why)

Already fixed by a later milestone: #8 (finish summary, M4), #9 (a failed job stops its backend, M4), #10
(undo's sort keys, computed once), #11 first half (block ids cached per block), #14 and #27 (the cap counts real
changes, M4), #16 (owner by UUID and command source, M4; spectators never hold placements back), #17 (the floor over
water is its surface), #36 (docs, M6), #38 first half (stale build folders swept, M6), #39 (headers first, M6),
#40 (owner-only temp root, M6), #41 first half (a broken install's own line, M6), #43 (init rejections logged,
M6), #45 (the stream log scrubbed, M6), M6/8 (docs), and the obsolete #7 README line about `/aibuild undo`.

Left, with the reason (each a ledger ruling):
- #4 — the size cycle and the non-owner refusal are covered since (`SizeTierTest`, `BuilderScreenClientGameTest`,
  `PermissionGameTest`).
- #5 — the placeholder shows whenever the box isn't focused; vanilla hides it while typing, as everywhere.
- #7 parts — `awaitChat` not consuming lines (the counting helpers cover it); a prompt typed on a server without
  the mod (it says so at once; retyping costs little).
- #11 second half — undo.dat is saved and loaded on the server thread as all of Minecraft's saved data is.
- #13 — benign by the review's own account.
- #15 — sand that falls after placement: `check_build` reports unsupported falling blocks and Claude is told to
  support them; journaling a fall would mean following the entity.
- #18, #28, #46 — test gaps since narrowed (the block-entity sweep, the stop-mid-build and token-leak tests).
- #19 parts — a ticket chunk still loading on its first write (rare; the server loads it as vanilla would); a cancel
  during an undo says "Nothing to cancel." (undos take a few ticks).
- #22 — surveys share the ticket type with undo: a survey and an undo never overlap (each is refused while the other
  is busy) and a build's own tickets are another level.
- #23 — tool threads wait out a timeout when the server stops mid-survey: the process is killed at the stop, so
  nothing waits on them.
- #24 — protected regions' y range, positions in grouped rejections, protected cells in inspect_region: Claude reads
  the survey and the rejections well enough (the live builds).
- #38 second half — a Claude Code orphaned by a SIGKILL of the game can't build: its MCP server and token died with
  the game.
- #44 — live runs are kept to the live tests by each test opting out; `GameTestSupport.world` can't tell the live
  tests from the others without a flag of its own.
- M6/5 — a session's box keeps an undone change's area (it only grows), and a sixth refinement finds the first build
  protected once it falls out of the five-build undo history: rare, and Claude sees the rejections.

## Review Focus

1. **A history file or a prompt with odd text** (a lone surrogate, an 8,192+ character entry) must not drop the
   player's connection or break the history save. → Task 1.
2. **One block the game no longer knows** in undo.dat must cost only the builds that used it. → Task 2.
3. **A stranger on a server** (allowed or not) must not cancel someone else's build or read its status. → Task 6.
4. **A config file saved by Windows Notepad** (a byte-order mark) must load. → Task 6.
5. **Ctrl+B** (the narrator) must not open the builder. → Task 1.

---

### Task 1: Prompts, history and the B key (#1, #2, #3, #6, #7)

**Files:** `core/request/BuildPrompt`, `core/request/PromptHistory`, `client/BuilderScreen`, `client/AiBuilderClient`;
tests `BuildPromptTest`, `PromptHistoryTest`, `BuilderScreenClientGameTest`.

- [ ] Failing tests: `aLoneSurrogateIsDropped` (`normalize` drops unpaired surrogates, keeps pairs);
  `anEntryThatCantBeSentIsDropped` (`fromJson` keeps only entries `BuildPrompt.check` passes, normalized once);
  `withSizeNullKeepsTheSize`; the client test presses the physical B (`InputConstants.KEY_B`), shows that Ctrl+B
  opens nothing, and that a multi-line recent prompt's row ends with "…". Then implement (`BuilderScreen.build()`
  sends `normalize(draft)`; the key handler ignores B while Ctrl is down), green, commit
  `fix(aibuilder): prompts, history and the B key`.

### Task 2: Undo data and placement (#12, #19, #33)

**Files:** `world/UndoStore` (a list codec that decodes each build on its own and skips one that fails, with a
warning), `core/tools/ToolExecutor` (replace mode and staircase words in any case; one entry per spot, the last given),
`world/PlacementJob` (`idle()` removed); tests `UndoGameTest`, `ToolExecutorTest`.

- [ ] Failing tests: `aBuildThatCantBeReadIsSkipped` (a saved store with a good build and one naming an unknown block
  loads with the good one); `modesTakeAnyCase`; `twoEntriesForOneSpotKeepTheLast` (lava then planks at one spot:
  planks, judged as planks). Then implement, green, commit `fix(aibuilder): undo data and placement`.

### Task 3: Survey and region tools (#20, #21, #25, #26, #29)

**Files:** `core/survey/RegionEncoder`, `core/survey/SurveyEncoder` (no `=` key; the region text capped at
`Tunables.INSPECT_MAX_CHARS`), `core/tools/ToolExecutor` (a survey says it's the site as the build began; get_context
says when the player isn't there instead of inventing a spot), `BuildManager` (`/aibuild survey` encodes and writes
off the server thread, a unique file name, a failure line instead of a stuck "Reading…"); tests `RegionEncoderTest`,
`SurveyEncoderTest`, `ToolExecutorTest`.

- [ ] Failing tests: `equalsIsNeverAKey` (both encoders, past 60 kinds); `aRegionTooLongToReadIsRefused`;
  `aSurveySaysWhenItWasRead`; `noPlayerIsNoPosition`. Then implement, green, commit
  `fix(aibuilder): survey and region tools`.

### Task 4: check_build (#30, #31, #32)

**Files:** `world/BuildCheck` (a door is blocked at the feet only by collision above the 0.6 step; in a dimension
without sky light a spot is indoors only under a block within `Tunables.CHECK_ROOF_SCAN` above, and only floors mobs
can spawn on count), `core/survey/Darkness` (pure: the indoor decision), `world/McWorldReader` (one check in flight per
build: a second call waits on the first); tests `DarknessTest`, `CheckBuildGameTest`.

- [ ] Failing tests: `aCarpetDoesntBlockADoor`; `ceilingDimensionsLookUpABoundedWay` (pure);
  `twoChecksAtOnceShareOneRead`. Then implement, green, commit `fix(aibuilder): check_build`.

### Task 5: The status panel and the tools' words (#34, #35, #37)

**Files:** `core/request/BuildStatus` (§ codes and control characters dropped; its limits and the client's stale time
in `Tunables`), `core/request/HudUpdate` (pure: send, clear or nothing, from alive, same world and shown),
`BuildManager.Build.hud`, `client/StatusHud`, `resources/aibuilder/tools.json` (a book page shows about 250
characters); tests `BuildStatusTest`, `HudUpdateTest`.

- [ ] Failing tests: `formattingCodesAreDropped`; `deadOrElsewhereTakesItDown`, `backAgainPutsItUp`. Then implement,
  green, commit `fix(aibuilder): the status panel`.

### Task 6: Backend, permissions, logs and config (#41, #42, M6/1–4, M6/6, M6/7, M6/9–11)

**Files:** `core/agent/ClaudeLaunch` (the binary's folder first on the child's PATH, for an npm install started with
the launcher's short PATH), `backend/ClaudeCodeBackend` and `BuildHandle` (`exited()`: the process itself has ended,
not its stdout; one process at a time waits on it, and only Claude builds count), `BuildManager` (cancel only your own
build; `/aibuild status` checks permission; `get_context` on a refinement names the build and its box of changes and
uses the config's `site_gap`; the prompt echo scrubbed; `busyLine`'s dead branch gone), `core/agent/BuildLog` (the
file name from the scrubbed prompt), `backend/LogWriter` (nothing after close), `core/agent/ClaudeLocator` (a
`claude_path` that isn't an executable is warned about), `core/config/AiBuilderConfig` (a byte-order mark is skipped;
the comment says to list players by UUID); README (a `CLAUDE_CODE_OAUTH_TOKEN` login isn't passed on; UUIDs over names);
tests `ClaudeLaunchTest`, `ClaudeCodeBackendTest`, `PermissionGameTest`, `BuildLogTest`, `LogWriterTest`,
`ClaudeLocatorTest`, `AiBuilderConfigTest`, `MessagesTest`, `ToolExecutorTest`.

- [ ] Failing tests: `theBinarysFolderComesFirstOnPath`; `exitedIsTheProcessNotItsOutput`;
  `onlyTheBuildsOwnerCancelsIt`, `strangersDontSeeTheStatus`; `aRefinementsContextNamesItsBuild`,
  `theSiteGapIsTheConfigs`; `aKeyNeverNamesTheFile`; `aKeyInThePromptIsntEchoed`; `nothingIsWrittenAfterClose`;
  `aMissingClaudePathIsSaidSo`; `aByteOrderMarkIsSkipped`. Then implement, green, docs (README, the mod's CLAUDE.md,
  PLAN.md's "needs no mixins" line and the shape pass's "250 blocks or 3 ms"), commit
  `fix(aibuilder): backend, permissions, logs and config`.

## After the tasks

One fresh Opus 5.5 reviewer reviews the branch; Critical and Important findings fixed test-first; the ledger committed
to `docs/superpowers/plans/2026-10-22-aibuilder-minors-progress.md`; a pull request to `main` for Frankie to merge.
