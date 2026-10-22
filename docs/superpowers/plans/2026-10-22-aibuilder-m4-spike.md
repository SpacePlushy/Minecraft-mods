# AI Builder M4 spike: Claude Code headless, checked for real (2026-10-22)

The spec (§12.2) marked several Claude Code facts *unverified* until a spike. This is that spike: four tiny real
runs of `claude -p` 2.1.306 on Frankie's Max login (OK'd by Frankie on 2026-10-22), against a 100-line Python MCP
stub, launched with the exact §12.2 flags, the prompt on stdin, from an empty folder, in a clean environment (only
`HOME`, `USER`, `LOGNAME`, `TMPDIR`, a system `PATH` and the five variables §12.2 adds). The recorded streams are
the test fixtures in `mods/aibuilder/src/test/resources/stream/` (local paths replaced).

| Run | What it did | Took |
|---|---|---|
| success | `get_context`, then `finish` | 9.7 s; `system/init` at 1.9 s, first model output at 4.3 s |
| slow_tool | one tool call that answers after 100 s, then `finish` | 105 s |
| cancelled | SIGINT 12 s in, while a 60 s tool call was pending | ended 0.3 s after the signal |
| logged_out | an empty `CLAUDE_CONFIG_DIR` (the real login untouched) | at once |

## What it settled

- **Plain JSON MCP replies work.** Claude Code's first request is `server/discover` (a newer MCP method, probed with
  protocol `2026-07-28`); answering it with JSON-RPC error -32601 makes it fall back to `initialize` with
  `protocolVersion` `2025-11-25`. Then `notifications/initialized` (202), a `GET /mcp` for an event stream (405 is
  fine), `tools/list`, and `tools/call` with `_meta` (`claudecode/toolUseId`, `progressToken`). Requests carry
  `Accept: application/json, text/event-stream`, the bearer header from `mcp.json`, and **no `Origin` header**.
- **`--restricted` works with the subscription login**: `apiKeySource` is `"none"`, and the only tools are the MCP
  server's. `--system-prompt-file` and `--max-turns` are accepted though `--help` doesn't list them.
- **`system/init`** has `tools` (exactly `mcp__aibuilder__*`; no `EndConversation` in 2.1.306), `mcp_servers`
  (`[{"name":"aibuilder","status":"connected","source":"dynamic"}]`), `apiKeySource`, `model`, `permissionMode`,
  `claude_code_version`, `skills: []`, `slash_commands: []`, a list of built-in `agents` (harmless: no tool can
  start one), and three **built-in plugins** (`path: "builtin"`, sources `cc-plugin-*@builtin`). So the init check
  allows built-in plugins and refuses any other.
- **Usage and extra usage are visible.** A `rate_limit_event.rate_limit_info` has `status` (`"allowed"`),
  `resetsAt` (epoch seconds), `rateLimitType` (`"five_hour"`), `overageStatus` (`"rejected"`, reason
  `"org_level_disabled"` on this account), `isUsingOverage` (`false`) and `unifiedWindows` with each window's
  utilization. `isUsingOverage: true` is the sign that paid extra usage has taken over, so the mod can stop a build
  there; the per-build caps aren't the only bound. **When it comes** (settled from the live build's log, M4 review):
  with the first response, and again whenever the state changes (there, after turn 12, when the five-hour window went
  from 5% to 6%), not every turn. So extra usage is reported only after the turn that used it: the mod stops at
  once, and, when the account allows extra usage at all (`overageStatus` not `rejected`), already when the fullest
  window is 95% used. A `rateLimitType` of `overage` stops it too.
- **Not logged in:** `claude auth status --json` exits 1 with `loggedIn: false`, `authMethod: "none"`. A `-p` run
  prints `system/init`, an assistant message with `"error": "authentication_failed"` and the text "Not logged in ·
  Please run /login", then a `result` with `is_error: true`, `terminal_reason: "api_error"`, and exits 1. Logged
  in: exit 0, `loggedIn: true`, `authMethod: "claude.ai"`, `subscriptionType: "max"`, even from a clean environment.
- **Slow tools are fine.** A 100 s tool call came back normally with `"timeout": 120000` in `mcp.json`; Claude Code
  emits `tool_progress` heartbeats every 30 s meanwhile. So a checkpoint must answer before 120 s (it waits at most
  90 s, then says what's still going in).
- **SIGINT** during a pending tool call ends the turn within a second: the pending call gets a "the user doesn't want
  to proceed" result, then `result` with `subtype: "error_during_execution"`, `is_error: true`,
  `terminal_reason: "aborted_tools"`, exit 0.
- **`result`** carries `subtype`, `is_error`, `num_turns`, `terminal_reason`, `usage`, `total_cost_usd`,
  `session_id`, `result` (the final text) and `api_error_status`. `stream_event` → `message_delta` carries each
  message's `usage.output_tokens`.

## What it changed

- **The environment.** The desktop app's shell (and so any game started from it, such as `./gradlew runClient`)
  carries about 35 `CLAUDE_*` variables (`CLAUDECODE`, host session ids, `CLAUDE_CODE_OAUTH_SCOPES`,
  `CLAUDE_CODE_SDK_HAS_HOST_AUTH_REFRESH`, …) and `ANTHROPIC_BASE_URL`. A child Claude Code would inherit them and
  could run as part of that other session or through its proxy. So the child's environment drops every
  `ANTHROPIC_*` variable and every `CLAUDE*` variable except `CLAUDE_CONFIG_DIR` (where a login can live), then
  adds the five of §12.2. It isn't just the five §12.2 names.
- **No `EndConversation`** in the init check's allow list is needed; it stays allowed in case a later version adds it.
- **The rate-limit rule.** Stop at once when `isUsingOverage` is true or `status` doesn't start with `allowed`;
  `allowed_warning` is only a warning.

## The first live build (M4 Task 8, 2026-10-22)

`AIBUILDER_LIVE=1 ./gradlew runClientGameTest --no-daemon` with "a small cozy cabin" at Small, on a superflat world,
through the whole mod (screen packet, checks, MCP server, backend, tools, placement, chat).

| | |
|---|---|
| Result | "Done: 937 blocks placed." with Claude's summary in chat; 4 min 15 s from launch to the end of the test |
| Claude's work | 37 tool calls (several per turn): get_context, survey_area, submit_brief, foundation, walls, log frame, a gable roof, a chimney with a campfire, a porch, windows, furniture, flower boxes, a path, a garden and a spruce tree; two checkpoints; three inspect_region checks; finish |
| Tokens | 23,957 output tokens; Claude Code's own list-price estimate $0.84 (the subscription pays nothing extra) |
| Usage | five-hour window 5% → 6%; seven-day 52% → 52% |
| Rejections | one: an invented `chopping_block_placeholder`, refused with suggestions; Claude carried on |
| What looked wrong | nothing in the build; the end-of-test camera framed it badly (fixed: the shots now face the middle of the site) |

The result's `subtype` is `error_during_execution`: after `finish` the job ends and the mod stops Claude Code (it
would otherwise spend tokens on a closing message), which Claude Code reports as an aborted turn.
