# AI Builder

A mod for **Minecraft Java Edition 26.3** (Fabric) where you press **B**, type anything ("castle", "a cozy cabin
with a dock", "a Japanese garden with a koi pond"), and Claude designs it and builds it in front of you, block by
block, while you watch. It never moves you, ignores your inventory, and `/aibuild undo` puts the land back.

**Status: M2.** The building machinery works: `/aibuild test` builds a small demo house in front of you through
the same tools Claude will use, and `/aibuild undo` takes it back. Your prompts still only get an echo; Claude
starts building them in M4. The plan, milestone by milestone, is in [`PLAN.md`](PLAN.md).

![The demo house built by /aibuild test, facing the player, with the chat lines it printed](docs/demo-house.png)

## Using it

| Do | What happens |
|---|---|
| Press **B** in a world | The AI Builder screen opens (rebind it in Options → Controls → Key Binds → AI Builder) |
| Type what you want, pick a **Size**, press **Build** | Chat says `AI Builder got: …` (real builds come in M4) |
| Click a recent prompt (▲ ▼ scroll through the last 10) | It goes back in the box, ready to build again |
| `/aibuild <prompt>` in chat | Same as Build, at Auto size |
| `/aibuild test` | Builds the demo house 3 blocks in front of you, facing the way you face. The action bar shows the phase and the block count; chat says `Done: …` with a clickable **[Undo]** |
| `/aibuild undo` | Takes back your newest build (repeat for older ones, up to 5). Anything you changed since, like a chest you filled, stays |
| `/aibuild cancel` | Stops the build at once; what's placed stays and can be undone |
| `/aibuild status` | The phase, blocks placed and tool calls used so far |

The screen remembers your last 10 prompts and the size you picked last. Prompts can be up to 2,000 characters.
Only the world's owner can build. Builds never move you: a spot you're standing in waits until you step away.

## Trying it

From this folder:

```bash
./gradlew runClient
```

This launches a separate development copy of Minecraft with the mod loaded; your normal game and worlds aren't
touched. The first run downloads Minecraft and takes a few minutes.
