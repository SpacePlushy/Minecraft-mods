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

## Milestones

Each milestone ends with a stop: you try it and tell me what's wrong before the next one starts.

| # | Status | What you'll be able to do | How to try it (`cd mods/aibuilder && ./gradlew runClient`) |
|---|---|---|---|
| M1 Skeleton | ⏳ next | Press B, type, Build, and chat echoes your prompt | Press B, type, Build; try `/aibuild hello`; rebind B in Options → Controls |
| M2 Placement engine | ⬜ | `/aibuild test` builds a scripted house in front of you, with undo and cancel | Face each direction and run `/aibuild test`: door toward you, stairs the right way; `/aibuild undo`; `/aibuild cancel` half-way, then undo |
| M3 Survey | ⬜ | `/aibuild survey` shows what Claude will "see" | Plains, a mountain, a beach, a forest |
| M4 Claude | ⬜ | Real builds from your prompts | "small cabin", then "castle"; cancel one; undo; log out of Claude Code and read the message |
| M5 Quality | ⬜ | Better builds: phases, self-check, the HUD | Builds have depth, lit interiors, working doors |
| M6 Polish | ⬜ | Config, refinement ("add a moat"), logs, error handling, permissions | The brief's manual checklist |

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
- **Mixins.** Patches that change the game's own code as it loads. This design needs none so far, which keeps
  the mod sturdy across game updates.
- **Chunk tickets.** How the server decides which 16×16 chunks stay loaded. The mod holds a temporary one over
  the site while it builds.
- **GameTests.** Automated tests that run inside a real game world, headless or with a window.
- **MCP.** The Model Context Protocol, how Claude Code finds and calls tools. The mod runs a tiny MCP server on
  your own machine (127.0.0.1 only) whose tools are the build tools.

## Change log

- **2026-10-21:** Plan created from the brief and the brainstorming session. Spec written, checked by a fresh
  reviewer (undo, ground, fluid and refinement rules tightened; extra usage settled), and approved by Frankie.
