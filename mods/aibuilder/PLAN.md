# AI Builder — Plan

The living plan the brief asks for: what's decided, where things stand, and how to try each milestone. It
changes whenever a decision changes, and the change log at the bottom says when and why.

- **Design, in full:** [`docs/superpowers/specs/2026-10-21-aibuilder-design.md`](../../docs/superpowers/specs/2026-10-21-aibuilder-design.md)
- **Frankie's original brief:** [`docs/superpowers/specs/2026-10-21-aibuilder-brief.md`](../../docs/superpowers/specs/2026-10-21-aibuilder-brief.md)
- **Milestone plans:** `docs/superpowers/plans/2026-10-21-aibuilder-m<N>-plan.md`, written one milestone at a time

## What it is

Press **B**, type anything, press Build. Claude Code, running on Frankie's own Claude Max login, gets the mod's
build tools (survey the land, fill, walls, roofs, stairs and more) and nothing else. It designs the build and
places it in front of you while you watch. `/aibuild undo` puts the land back.

## Decisions

The full table, with the reasons, is spec §2.

| Decision | Choice | Who |
|---|---|---|
| Platform | Java Edition, Fabric, Minecraft 26.3, Java 25 | Frankie |
| How Claude is called | Claude Code headless (`claude -p`) on Frankie's Max 20x login, through a small MCP server inside the mod. No API key | Frankie |
| Scope | Singleplayer first; the multiplayer rules come in M6 | Frankie |
| Paid extra usage | Never: a build stops at the plan limit | Frankie |
| Branch | `aibuilder-v1`, in the worktree `.claude/worktrees/aibuilder-v1` | Frankie |
| Subagents | Opus 5.5 for every subagent and task | Frankie |
| In-game model | `claude-opus-5-5` at effort `high`, configurable | Claude |
| Who can build | Only the world's owner: Anthropic's terms cover a subscription owner's own use | Claude |
| Safety | The in-game Claude gets build tools only (no shell, files, web or your other connectors), checked at every launch | Claude |
| Directions | Tool calls say forward/back/left/right, never north/east: the brief's frame is a mirror image of the compass | Claude |
| Undo | Saved with the world, last 5 builds per player; keeps anything you changed after the build, like a chest you filled | Claude |
| Ground and fluids | Builds stand on the floor under your feet (so the Nether and caves work); water and lava never flow, lava stays clear of anything flammable | Claude |
| Finishing | M3–M6 are finished without a stop for a play-test between them; Claude makes the remaining calls. Real Claude runs on Frankie's login are OK for the M4 spike, live tests and a showcase world built at the end. Frankie's real game and worlds stay untouched | Frankie (2026-10-22) |

## Milestones

Each milestone ends with a stop: you try it and tell me what's wrong before the next one starts.

| # | Status | What you'll be able to do | How to try it (`cd mods/aibuilder && ./gradlew runClient`) |
|---|---|---|---|
| M1 Skeleton | ✅ done, play-tested | Press B, type, Build, and chat echoes your prompt | Press B, type, Build; try `/aibuild hello`; rebind B in Options → Controls |
| M2 Placement engine | ✅ done, play-tested | `/aibuild test` builds a scripted house in front of you, with undo and cancel | Face each direction and run `/aibuild test`: door toward you, stairs the right way; `/aibuild undo`; `/aibuild cancel` half-way, then undo |
| M3 Survey | ✅ done, play-tested | `/aibuild survey` shows what Claude will "see" | Plains, a mountain, a beach, a forest |
| M4 Claude | ✅ done, play-tested | Real builds from your prompts | "small cabin", then "castle"; cancel one; undo; log out of Claude Code and read the message |
| M5 Quality | ✅ done, play-tested | Better builds: phases, self-check, the HUD, signs, chests, books, banners and item frames; lava kept clear of wood | Builds have depth, lit interiors, working doors; the panel top left; a sign or a chest in a build reads right |
| M6 Polish | ✅ done, play-tested | Config, refinement ("add a moat"), logs, error handling, permissions | "a stone castle", then "add a moat" next to it; edit `config/aibuilder.json`; turn the Wi-Fi off mid-build; a LAN friend is refused |
| M7 Builds that move | ✅ done: reviewed, fixed, live dragon passed | Moving parts: "a dragon with flapping wings", "a windmill", "a flag on a pole" | Ask for a dragon with flapping wings; then "make the wings flap faster" next to it; undo; `moving_parts: false` in the config |

## Good to know

- Builds take minutes, not seconds: Claude thinks before each step. They count against your Max plan's usage
  limits, never your wallet: at the plan limit a build stops, even if paid extra usage is on. Caps (150 tool
  calls, 45 minutes) stop a runaway build.
- Nothing touches your real game folder or worlds: everything runs in the dev client (`runClient`).

## Modding concepts used here

- **Client and server.** Even singleplayer runs a hidden server inside the game (the *integrated server*). The
  server owns the world; the client draws it, plays sounds and reads your keys. So the keybind, screen and HUD
  are client code (`src/client`), while building, undo and the Claude connection are server code (`src/main`).
  Server code must never touch client classes, or the mod crashes on a real server.
- **Registries.** The game's master lists of blocks, items, entities and so on, keyed by ids like
  `minecraft:oak_stairs`. Other mods add their blocks to the same lists, so checking Claude's block names
  against the registry covers modded blocks for free.
- **Block states.** A block plus its settings, like `oak_stairs[facing=north, half=bottom]`. Every placed block
  is a state. Turning a build to face another way means turning those direction settings, which the game can
  do itself (`BlockState.rotate`).
- **Ticks.** The server updates the world 20 times a second, with 50 ms per tick for everything, so the mod
  places a few hundred blocks per tick rather than thousands at once.
- **Block updates.** Placing a block normally pokes its neighbors: water flows, sand falls, fences connect. The
  mod places with the pokes off and tidies the shapes once at the end.
- **Networking (payloads).** Client and server talk only in packets. The mod adds two small ones: your prompt
  (client to server) and the status (server to client, M5).
- **Mixins.** Patches that change the game's own code as it loads. The mod has one, and only reads with it (who
  sent a command, so a command block can't speak for the player); the tests have one more (vsync off, so they run
  while the Mac is locked). Nothing changes how the game plays, which keeps the mod sturdy across game updates.
- **Chunk tickets.** How the server decides which 16×16 chunks stay loaded. The mod holds a temporary one over
  the site while it builds.
- **GameTests.** Automated tests that run inside a real game world, headless or with a window.
- **MCP.** The Model Context Protocol, how Claude Code finds and calls tools. The mod runs a tiny MCP server on
  your own machine (127.0.0.1 only) whose tools are the build tools.

## Change log

- **2026-10-21:** Plan created from the brief and the brainstorming session. Spec written, checked by a fresh
  reviewer (undo, ground, fluid and refinement rules tightened; extra usage settled), and approved by Frankie.
- **2026-10-21:** M1 built: B opens the screen (prompt box, size, Build/Cancel, recent prompts), and the prompt
  reaches the server from the screen or `/aibuild`. Client gametests now run even while the Mac is locked (vsync is
  off in tests). Waiting for Frankie's test before the M2 plan.
- **2026-10-22:** M2 built from `docs/superpowers/plans/2026-10-21-aibuilder-m2-plan.md`: the write tools
  (`tools.json`), block validation, shapes, roofs and stairs, tick-batched placement with the finalize pass, exact
  undo saved with the world, and `/aibuild test|undo|cancel|status`. Server gametests check the demo house facing
  all four ways, sand, water, fences, two-block blocks, waiting for a player, undo with contents and far away, and
  saving. Rulings made along the way are in the M2 progress ledger. Waiting for Frankie's M1 + M2 test.
- **2026-10-22:** M2's final review (a fresh Opus 5.5 reviewer) found no critical problems and five important ones,
  all fixed test-first: tool numbers and shape sizes are bounded; a running build's undo record is saved with the
  world, so a crash mid-build can still be undone; hoppers and vaults undo; lava counts as a surface to build on;
  `/aibuild test` goes slowly enough to cancel half-way. Also: no build where the spot is outside the world's height.
  Twelve smaller findings are deferred and listed in the M2 ledger, several to settle before M4.
- **2026-10-22:** Frankie handed over the rest of the project: M3–M6 get built, reviewed and fixed without a stop
  between them, and Frankie tries it all after M6 (Decisions row "Finishing").
- **2026-10-22:** M3 built from `docs/superpowers/plans/2026-10-22-aibuilder-m3-plan.md`: the read tools
  (`get_context`, `survey_area`, `inspect_region`, `search_blocks`) and `terraform`, the survey (heights, water,
  lava, trees, ceilings, surface blocks; coarsened to stay under 30,000 characters), and protection: blocks that
  look player-made are refused unless a clear asks for them, while blocks left by your own earlier builds aren't
  protected. `/aibuild survey` shows the survey in chat and saves the full text.
- **2026-10-22:** M3's final review (a fresh Opus 5.5 reviewer) found one critical problem and seven important ones,
  all fixed test-first, plus two smaller ones raised because real terrain trips them all the time. One terraform call
  can no longer ask for millions of blocks; reads never touch the world off the server thread or load far chunks; the
  survey reads mountains, ponds on raised ground and drops correctly; a wider survey still shows protected blocks; a
  chest you filled stays protected even if a build placed it; a second terraform builds on the first; Claude is told
  to build around protected blocks; cocoa, bee nests, Nether fungus and other natural blocks no longer count as
  player-made; lily pads, bamboo and cactus aren't the ground. The smaller findings are in the M3 ledger.
- **2026-10-22:** M4 built from `docs/superpowers/plans/2026-10-22-aibuilder-m4-plan.md`, after a spike that ran
  Claude Code for real (`…-m4-spike.md`). Prompts now build: AI Builder checks Claude Code is installed and logged in
  with a subscription, launches it with only the build tools, serves those over a localhost MCP server, follows its
  stream (start check, notes, tokens, usage limits), and stops it at a cancel, a cap (150 calls, 500k tokens, 45
  minutes), the plan's limit, or where paid extra usage would begin. The first live build, a small cabin, took four
  minutes and came out well. Also: the owner is checked by UUID and command blocks can't speak for them, the change
  cap counts real changes, and every gametest uses a fake Claude Code.
- **2026-10-22:** M5 built from `docs/superpowers/plans/2026-10-22-aibuilder-m5-plan.md`: lava is kept clear of
  anything that burns (the world's too); `check_build` gives Claude the facts to fix (unsupported blocks, dark rooms,
  blocked doors, low ceilings, fluid that would spill), read over ticks; `place_block_entity` dresses builds with sign
  text, container items, a lectern's book, banner patterns and item frames, which undo takes down first; a status panel
  top left ticks off the plan's phases; blocks go in with a soft sound. The live check, "a medieval stone castle with
  a gatehouse" at Medium, came out well (22,504 blocks, 107 tool calls, 17 minutes; Claude ran `check_build` four
  times and fixed what it found). It showed two things, fixed: an overhang out in the open counted as a roof, and
  checkpoints that named two phases moved the panel only one.
- **2026-10-22:** M6 built from `docs/superpowers/plans/2026-10-22-aibuilder-m6-plan.md`: `config/aibuilder.json`
  (every setting; a bad value falls back to its default; edits apply to the next build); who may build (the owner
  only in singleplayer and on LAN, on a server the players in `allowed_players` with op) and at most 20 builds an
  hour; refinement: a prompt next to your last build changes it, in the same Claude Code session, with room to build
  16 blocks past it (`/aibuild new` and the screen's toggle build new instead); a log of every build, secrets
  scrubbed; and clear messages when Claude Code is broken or the connection drops. The live check, "a stone castle"
  at Medium (15,374 blocks, 131 tool calls, 11 minutes) and then "add a moat around the castle, with a drawbridge at
  the gate" typed beside it (5,381 blocks, 35 calls, 2 minutes), came out well: Claude picked up its own castle and
  dug a lined moat with lily pads and a drawbridge.
- **2026-10-22:** M6's final review (a fresh Opus 5.5 reviewer) found one critical problem and four important ones,
  all fixed test-first, plus one smaller one raised for what it does to a player: a refinement's grown box is now
  read to its edge, so blocks you made stay protected and lava stays clear of the world's wood there too (a moat dug
  around a castle reached past the old survey); a key pasted on its own line of the prompt is scrubbed from the
  logs; a `banned_blocks` list with no valid id keeps the defaults; a build's refine record survives quitting or a
  crash mid-build; a build whose Claude Code conversation is gone says so once, and the next prompt builds new; and
  long build titles are cut. Ten smaller findings are in the M6 ledger.
- **2026-10-22:** The showcase world, built live by Claude on real terrain (README, "The showcase world"): a Large
  castle (48,394 blocks) and its moat with a drawbridge (21,349), a temple in a cherry grove (16,990), a lighthouse on
  the rocks (4,294), a desert bazaar (10,507) and a wizard's tower in the dark forest (4,982), in 70 minutes. All six
  milestones are built; Frankie's play-test comes next, then the pull request to `main`.
- **2026-10-22:** The pull request's automated review found five more problems, all fixed test-first: a bed's head or a
  door's top half now passes the same checks as the block (the box, protection, the lava rule); a build that changes
  nothing no longer costs an older build its undo; a checkpoint may say a word twice; the screen has its "Stop build"
  and status line (spec §5.1); and the tunable numbers live in `Tunables`. Frankie played it, and AI Builder v1 is
  merged to `main` (pull request #1).
- **2026-10-22:** The milestones' deferred minor findings that still held are fixed on `aibuilder-minors`, test-first
  (plan `docs/superpowers/plans/2026-10-22-aibuilder-minors-plan.md`; its ledger, `…-minors-progress.md`, has every
  ruling and what's still deferred): odd prompts and history files, undo data a removed mod's block can't spoil,
  set_blocks keeping the last entry for a spot, a survey that says when it was read and where the player is,
  check_build's doors, Nether roofs and spawn ground, a plain-text status panel, Claude Code's folder on its PATH,
  waiting on Claude Code's process itself, a build that only its owner stops or follows, and no key in an echo or a
  log's name. A fresh Opus 5.5 review found two more, both fixed: B while sprinting on a Mac, and another player
  reading a build's status. Pull request #2.
- **2026-10-22:** AI Builder v2 begun (spec `docs/superpowers/specs/2026-10-22-aibuilder-v2-design.md`): builds that move
  (M7), builds that work (M8, M9). M7 is built from `docs/superpowers/plans/2026-10-22-aibuilder-m7-plan.md`, test-first:
  a `part` on the write tools makes block displays, `animate` gives a part its motion (swing, spin, bob, riding
  another part), the game moves them on its own and keeps them in step after a restart, `check_motion` tells Claude
  where each part goes and what it passes through, a follow-up next to a moving build changes its parts, and undo
  takes them away (undoing a follow-up gives the old motion back).
- **2026-10-22:** M7's live run, "a dragon with flapping wings" on Frankie's login, passed first time: a crimson dragon
  on a hoard rock whose wings flap and tail sways, with Claude checking the motion before it finished. A fresh Opus 5.5
  review found three important gaps, all fixed test-first: a big part spread over ticks now holds together, blocks can
  be taken out of a moving part (air with its name, or clear with its name), and undo reaches displays wherever they
  are. Its ledger is `docs/superpowers/plans/2026-10-22-aibuilder-m7-progress.md`. Also fixed: weathered copper now
  undoes. M8 (redstone that works) is under way.
