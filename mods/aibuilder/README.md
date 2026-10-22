# AI Builder

A mod for **Minecraft Java Edition 26.3** (Fabric) where you press **B**, type anything ("castle", "a cozy cabin
with a dock", "a Japanese garden with a koi pond"), and Claude designs it and builds it in front of you, block by
block, while you watch. It never moves you, ignores your inventory, and `/aibuild undo` puts the land back.

**Status: M5.** Real builds: type a prompt and Claude builds it, using Claude Code on your own Claude login (Pro or
Max). A panel in the top-left corner shows the build's plan as it goes: its phases ticking off, the blocks placed
and how much of its budget it has used. Claude checks its own work before it finishes (dark rooms, blocked doors,
floating torches, low ceilings, water that would spill) and dresses builds with signs, stocked chests, a lectern
with a book, banners and item frames. Lava never goes in next to anything that burns. `/aibuild test` builds a
demo house without Claude, `/aibuild survey` shows how Claude sees the land, and `/aibuild undo` takes any build
back, item frames included. The plan, milestone by milestone, is in [`PLAN.md`](PLAN.md).

## What you need

- **Claude Code**, installed and logged in with your Claude subscription: in a terminal, run `claude` once and log
  in. AI Builder finds it in the usual places (`~/.local/bin/claude` and the like) and never sees your login.
- Builds count against your plan's usage limits: at the plan's limit a build stops. AI Builder never builds on paid
  extra usage: it stops at the first sign of it, and if your account allows extra usage it stops already when the
  plan is about 95% used. Claude Code reports extra usage only after the step that used it, so for a hard guarantee
  keep extra usage turned off in your Claude account. A build also stops at 150 tool calls, 500,000 output tokens or
  45 minutes. Whatever it built stays, and `/aibuild undo` reverts it.
- Only prompts you type build: a command from a click (a link in a book or on a sign) can't start one.

![The demo house built by /aibuild test, facing the player, with the chat lines it printed](docs/demo-house.png)

![The status panel during a real build: the castle's nine phases, two ticked off, 22,006 blocks placed, 91 of 150 tool calls](docs/hud.png)

![Through the gate of "Stonehold Castle", built by Claude from "a medieval stone castle with a gatehouse" at Medium size: 22,504 blocks in 17 minutes, with a portcullis, banners and a welcome sign](docs/castle-gate.png)

## Using it

| Do | What happens |
|---|---|
| Press **B** in a world | The AI Builder screen opens (rebind it in Options → Controls → Key Binds → AI Builder) |
| Type what you want, pick a **Size**, press **Build** | Claude looks at the land, plans, then builds in front of you while the panel in the top-left corner ticks off its phases and the action bar shows the block count. It takes a few minutes; chat says `Done: …` with a clickable **[Undo]** at the end |
| Click a recent prompt (▲ ▼ scroll through the last 10) | It goes back in the box, ready to build again |
| `/aibuild <prompt>` in chat | Same as Build, at Auto size |
| `/aibuild test` | Builds the demo house 3 blocks in front of you, facing the way you face, slowly enough to watch (about 10 seconds). The action bar shows the phase and the block count; chat says `Done: …` with a clickable **[Undo]** |
| `/aibuild undo` | Takes back your newest build (repeat for older ones, up to 5), even after the game crashed or restarted. Anything you changed since, like a chest you filled, stays |
| `/aibuild cancel` | Stops the build at once (Claude too); what's placed stays and can be undone |
| `/aibuild status` | The phase, blocks placed and tool calls used so far |
| `/aibuild survey` | Reads the spot in front of you the way a build does and shows what Claude will "see": heights, water, trees and any player-made blocks, with the full text saved in `aibuilder/surveys/` |

The screen remembers your last 10 prompts and the size you picked last. Prompts can be up to 2,000 characters.
Only the world's owner can build. Builds never move you: a spot you're standing in waits until you step away.
Builds leave your own blocks alone: anything that doesn't look like natural terrain or plants (a house, a farm, a
village) counts as yours, and a build won't replace it unless you ask for that. It's a rule of thumb, so a log cabin
looks like trees to it; `/aibuild undo` is always there.

## When a build can't start or stops early

| Chat says | What it means |
|---|---|
| AI Builder needs Claude Code … | Claude Code isn't installed where AI Builder looks: install it from claude.com/claude-code |
| AI Builder runs on your Claude Code login … | Open a terminal, run `claude` and log in with your subscription |
| Your Claude usage limit is reached (resets …) | Your plan's usage window is used up; try again after the time it gives |
| Claude's paid extra usage would start now … | The plan's limit was reached and extra usage would have taken over: AI Builder never uses it |
| Claude Code started with more than the build tools … | Something (an API key in the environment, a plugin, another MCP server) got past AI Builder's settings, so it refused to build with it |
| Stopped at the 150 tool-call limit … | A cap stopped a build that ran too long; what it built stays |

## Trying it

From this folder:

```bash
./gradlew runClient
```

This launches a separate development copy of Minecraft with the mod loaded; your normal game and worlds aren't
touched. The first run downloads Minecraft and takes a few minutes.
