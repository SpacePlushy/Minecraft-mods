# Minecraft Mods Workspace

Home for Frankie's custom Minecraft **Java Edition** mods. Many mods will live here; each is a
self-contained **Fabric** project under `mods/<modid>/`. First mod: `drone`.

GitHub: `https://github.com/SpacePlushy/Minecraft-mods` (public). Frankie works on several machines;
everything needed to continue lives in this repo. Claude's per-machine memory doesn't carry over,
so keep durable facts **here**, not in memory.

## ▶ Current status and how to resume (updated 2026-10-23)

**Branch `main`** is GitHub's default and holds all finished work. Each mod is built on its own branch
(`<modid>-v1`, ...) and merged into `main` when done.

| Mod | State |
|---|---|
| `drone` | ✅ v1 done, reviewed, play-tested by Frankie, merged to `main`. Plan + ledger: `docs/superpowers/plans/2026-10-19-drone-mod-*`. Deferred minors are the `minor (deferred)` lines in the progress file. |
| `rocket` | 🧠 next: brainstorming a rideable rocket ship with launch vapor (superpowers:brainstorming → spec → plan) |
| `aibuilder` | ✅ v1 done: all six milestones, each reviewed by a fresh Opus 5.5 reviewer and fixed test-first, play-tested by Frankie, merged to `main` (pull request #1). Press B, type a prompt, and Claude Code (Frankie's Max login) builds it in the world. Spec `docs/superpowers/specs/2026-10-21-aibuilder-design.md`, living plan `mods/aibuilder/PLAN.md`, a plan and ledger per milestone in `docs/superpowers/plans/2026-10-2*-aibuilder-m<N>-*` (the deferred minors are their `minor (deferred)` lines). The showcase world Claude built live (`ShowcaseClientGameTest`) is `mods/aibuilder/run/saves/AI Builder Showcase` in the `aibuilder-v1` worktree on the machine it was built on (git-ignored: copy it out before removing that worktree); a copy is in Frankie's real saves as "AI Builder Showcase". The deferred minors that still held are fixed on branch `aibuilder-minors` (plan and ledger `docs/superpowers/plans/2026-10-22-aibuilder-minors-*`; the final review's own minors are deferred there), pull request #2. **v2** (builds that move, builds that work; spec `docs/superpowers/specs/2026-10-22-aibuilder-v2-design.md`) is on branch `aibuilder-v2`, cut from `aibuilder-minors`, in the same worktree: M7 (moving parts) done, reviewed, fixed, live dragon passed (ledger `docs/superpowers/plans/2026-10-22-aibuilder-m7-progress.md`); M8 (redstone that works) done, reviewed, fixed, live door passed (ledger `docs/superpowers/plans/2026-10-22-aibuilder-m8-progress.md`); M9 (gadgets, wiring, the calculator) done, reviewed, fixed, live calculator passed (ledger `docs/superpowers/plans/2026-10-22-aibuilder-m9-progress.md`). v2 is pull request #3 (stacked on #2: merge #2 first), waiting for Frankie's play-test. |

**To resume a plan that's mid-execution (any mod):**
1. Set up the machine (next section) and run `./gradlew build` in the mod's folder until it's green.
2. If the plan has a committed `docs/superpowers/plans/<date>-<topic>-progress.md`, restore it: run the
   `sdd-workspace` script from the superpowers `subagent-driven-development` skill with the plan file;
   it prints a git-ignored workspace dir; copy the progress file to `<workspace>/progress.md`.
3. Invoke **superpowers:executing-plans**. It resumes at the first task without a `Task <N>: complete` line.
4. Before switching machines mid-plan, copy `<workspace>/progress.md` back into `docs/`, commit and push.

## Setting up a machine (one time)

1. **Claude Code plugins:** `.claude/settings.json` enables **superpowers** (process skills:
   brainstorming → spec → plan → TDD execution → review) and **context7** (current library docs).
   Accept the install prompt, or install them from the plugin directory.
2. **JDK 25** (Minecraft 26.3 needs Java 25; the launcher's bundled Java can't build mods):
   - macOS: `brew install openjdk@25`, then
     `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` and
     `export PATH="$JAVA_HOME/bin:$PATH"` in `~/.zshrc`.
   - Windows / Linux: install Eclipse Temurin JDK 25 (adoptium.net) or the distro's OpenJDK 25, and set `JAVA_HOME`.
   - Check: `java -version` prints 25. **Gotcha:** Claude's Bash tool may start before your shell
     profile is updated. If `./gradlew` says "Unable to locate a Java Runtime", set `JAVA_HOME` inline
     on the command.
3. **Nothing else.** Each mod ships its own Gradle wrapper (checksum-verified 9.7.1). The first build
   downloads Gradle, Minecraft 26.3 and Fabric, which takes a few minutes.
4. Client gametests open a real game window, so they need a desktop session (not a headless SSH box).

## Layout

```
Minecraft-mods/
├── CLAUDE.md            this file: workspace rules, status, setup
├── README.md            human intro + mod list (keep the table current)
├── .claude/settings.json  project plugins (superpowers, context7)
├── docs/superpowers/
│   ├── specs/           YYYY-MM-DD-<topic>-design.md   (the "what" and "why")
│   └── plans/           YYYY-MM-DD-<topic>-plan.md     (the "how", task by task)
│                        YYYY-MM-DD-<topic>-progress.md (execution ledger: done tasks + rulings)
└── mods/
    └── <modid>/         independent Fabric project: own Gradle wrapper, CLAUDE.md, README.md
```

There is deliberately **no shared Gradle build** and no `templates/` folder yet. Extract shared
pieces only once two or more mods show real duplication.

## Hard rules

- **Never touch Frankie's real game install or worlds without asking first.** That covers the `mods/`
  folder, launcher profiles, installing Fabric Loader, and anything in `saves/` (their survival world is
  "Frankie's World"). Back up a world before the first time a mod runs in it.
  Game dir: macOS `~/Library/Application Support/minecraft`, Windows `%APPDATA%\.minecraft`,
  Linux `~/.minecraft`.
- Develop and test with `./gradlew runClient` / `runClientGameTest`. They use a separate dev game
  directory under the mod (git-ignored), so the real install stays untouched.
- Client-only code (rendering, input, `net.minecraft.client.*`) goes in `src/client/`. Shared code in
  `src/main/` must never import client classes, or the mod crashes on servers.

## Toolchain and versions

| Thing | Current | Where to check for updates |
|---|---|---|
| Minecraft | 26.3 | `curl -s https://meta.fabricmc.net/v2/versions/game` |
| Fabric Loader | 0.19.5 (stable) | `curl -s https://meta.fabricmc.net/v2/versions/loader/26.3` |
| Fabric API | 0.164.0+26.3 | `curl -s 'https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%2226.3%22%5D'` |
| Fabric Loom (Gradle plugin `net.fabricmc.fabric-loom`) | 1.18-SNAPSHOT (resolves to 1.18.4) | `https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml` |
| Gradle (wrapper) | 9.7.1 | Fabric example mod's `gradle/wrapper/gradle-wrapper.properties` |
| Java | 25 (`options.release = 25`) | the version JSON's `javaVersion` |
| JUnit | 6.1.3 (BOM) | Maven Central `org.junit:junit-bom` |

The reference for a correct, current build setup is the official example mod:
`https://github.com/FabricMC/fabric-example-mod`. When in doubt, diff against it.

## Minecraft 26.x specifics (learned the hard way)

- **Unobfuscated:** from 26.1 on, the game ships with Mojang's real names, so there's **no
  `mappings` line**. Docs written for ≤1.21 use Yarn names (`PlayerEntity`, `MinecraftClient`,
  `Identifier` vs `ResourceLocation`...). Translate, don't copy blindly. 26.3 uses `Identifier`.
- **Check real signatures with javap** instead of guessing: get the 26.3 client jar (in the launcher's
  `versions/26.3/26.3.jar`, or Loom's Gradle cache after a build), then run
  `javap -p -cp <jar> net.minecraft.world.entity.vehicle.VehicleEntity`. Add `-c` to read method bodies;
  vanilla (boats especially) is the best example code.
- **Rendering** uses `EntityRenderer.submit(state, poseStack, SubmitNodeCollector, CameraRenderState)`
  with a separate `EntityRenderState`. Draw with `collector.submitModel(model, state, pose, texture, light, overlay, outline)`.
- **Fabric API 26.x names:** `CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.X)` (was
  ItemGroupEvents), `ModelLayerRegistry`, `EntityRendererRegistry`, `ClientTickEvents`,
  `KeyMappingHelper`. Client gametest API: `fabric-client-gametest-api-v1` (`TestInput.holdKey`, `takeScreenshot`, ...).
- **Registration** needs keys up front: `Item.Properties().setId(key)`, `EntityType.Builder...build(key)`.
- **Client names that moved:** the open screen is `mc.gui.screen()` (no `mc.screen`); key types are
  `InputConstants.Type.KEYBOARD`/`MOUSE`; `InputConstants.isKeyDown(int)` takes no window.
  `KeyMappingHelper.getBoundKeyOf(mapping)` gives the bound key. With Toggle Sprint/Sneak on, the
  `ToggleKeyMapping`'s `isDown()` latches on a tap, so read the physical key when you need "held".
- **Vehicles:** `Entity.tick()` never applies block effects (lava, fire, cactus, cobwebs); a subclass must
  call `applyEffectsFromBlocks()` itself, as boats do. Smooth remote movement needs
  `createInterpolationHandler()` → `LinearInterpolationHandler.create(this, 3)` (default is no-op = snapping).
- **Server drops clicks silently** (`ServerGamePacketListenerImpl.handleUseItemOn`) until it has the
  client's "loaded" packet and its last teleport acknowledgement. In gametests, wait for those
  (see `mods/drone` `GameTestSupport.waitUntilServerAcceptsClicks`), never a fixed tick count.
- `jar`/`javap`: use the JDK 25 ones (`$JAVA_HOME/bin/...`); macOS's `/usr/bin` stubs fail without a system Java.
- Prefer current Fabric docs (`https://docs.fabricmc.net/develop/`, or the context7 MCP) over memory.

## Per-mod commands (run inside `mods/<modid>/`)

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests, produce the jar in `build/libs/` |
| `./gradlew test` | Unit tests only (JUnit): fast, no game launch |
| `./gradlew runClient` | Launch a dev Minecraft with the mod, for manual play |
| `./gradlew runClientGameTest` | Launch the real client, run client gametests, save screenshots to `build/run/clientGameTest/screenshots/`. **Look at them.** |

## How we work

- Use the **superpowers** skills: brainstorming → spec in `docs/superpowers/specs/` → writing-plans
  → plan in `docs/superpowers/plans/` → executing-plans (TDD per task, ledger of rulings) → final
  review → finishing-a-development-branch.
- **Every subagent, task, session and workflow agent runs on Opus 5.5** (Frankie, 2026-10-21). Pass the
  model explicitly (`model: "opus"`) on every dispatch, including superpowers implementer, reviewer and
  spec-check subagents. Never rely on an agent type's default model.
- Put game logic (physics, rules, math) in **plain Java classes with no Minecraft imports** and
  unit-test them test-first. Keep entities, renderers and registration thin.
- Verify in-game behavior with **Fabric client gametests**, and look at their screenshots before
  saying visuals are correct.
- Every mod has its own `mods/<modid>/CLAUDE.md` (architecture, tunables, gotchas) and a player-facing `README.md`.

## Conventions

- Mod id = folder name: lowercase `[a-z0-9_]`, e.g. `drone`.
- Java package `io.palmisano.<modid>`; author `Frankie Palmisano`.
- Asset paths follow vanilla: `assets/<modid>/textures/entity/…`, `textures/item/…`, `lang/en_us.json`.
- Tunable numbers live in one record or constants class per mod, never scattered.
- Commit messages: `feat(<modid>): ...`, ending with the Claude co-author line.

## Adding a new mod

1. Brainstorm and spec it (superpowers).
2. Create `mods/<modid>/` by copying `mods/drone`'s Gradle files and wrapper (or Fabric's template
   generator at `https://fabricmc.net/develop/template/`). Set the mod id, package, and versions from
   the table above.
3. Add the mod's own `CLAUDE.md` and `README.md`, add a row to the root `README.md`, and check that
   `./gradlew build` passes.

## Installing a mod into the real game (only when Frankie asks)

1. Back up the world folder in the game dir's `saves/`.
2. Install Fabric Loader for 26.3 with the official Fabric installer (client profile).
3. Copy the Fabric API jar (0.164.0+26.3) and the mod's `build/libs/<modid>-<version>.jar` (not
   `-sources`) into the game dir's `mods/`.
4. Pick the `fabric-loader-26.3` profile in the launcher.
