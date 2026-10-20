# Minecraft Mods Workspace

Home for Frankie's custom Minecraft **Java Edition** mods. Many mods will live here; each is a
self-contained **Fabric** project under `mods/<modid>/`. First mod: `drone`.

## Layout

```
Minecraft-mods/
├── CLAUDE.md            this file: workspace-wide rules and facts
├── README.md            human intro + mod list (keep the table current)
├── docs/superpowers/
│   ├── specs/           YYYY-MM-DD-<topic>-design.md
│   └── plans/           YYYY-MM-DD-<topic>-plan.md
└── mods/
    └── <modid>/         independent Fabric project: own Gradle wrapper, own CLAUDE.md
```

There is deliberately **no shared Gradle build** and no `templates/` folder yet. Extract shared
pieces only once two or more mods show real duplication.

## Machine and game facts (discovered 2026-10-19)

- macOS on Apple M4 (arm64), 16 GB RAM. Homebrew at `/opt/homebrew`.
- Official Minecraft launcher; game dir `~/Library/Application Support/minecraft`.
  - Installed versions: `26.3` (release, needs Java 25) and `26.4-snapshot-4` (ignore).
  - Player username `SpacePlushy`; survival world **"Frankie's World"** in `saves/`.
  - The launcher's bundled Java (`runtime/java-runtime-epsilon`) is for playing only. Never build with it.
- **No system Java by default.** Development uses Homebrew `openjdk@25` (keg-only, installed
  2026-10-19, version 25.0.4.1) with
  `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`. If `java -version`
  fails, the JDK is missing or `JAVA_HOME` is unset.

## Hard rules

- **Never touch the real game install or worlds without asking first.** That covers the `mods/`
  folder, launcher profiles, installing Fabric Loader, and anything in `saves/`. Back up a world
  before the first time a mod runs in it.
- Develop and test with `./gradlew runClient` / `runClientGameTest`. They use a separate dev game
  directory (`mods/<modid>/run/`, git-ignored), so the real install stays untouched.
- Client-only code (rendering, input, `net.minecraft.client.*`) goes in `src/client/`. Shared code in
  `src/main/` must never import client classes, or the mod crashes on servers.

## Toolchain and versions

| Thing | Current | Where to check for updates |
|---|---|---|
| Minecraft | 26.3 | `curl -s https://meta.fabricmc.net/v2/versions/game` |
| Fabric Loader | 0.19.5 (stable) | `curl -s https://meta.fabricmc.net/v2/versions/loader/26.3` |
| Fabric API | 0.164.0+26.3 | `curl -s 'https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%2226.3%22%5D'` |
| Fabric Loom (Gradle plugin `net.fabricmc.fabric-loom`) | 1.18-SNAPSHOT as in Fabric's example mod; 1.19.2 is the latest release | `https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml` |
| Gradle (wrapper) | 9.7.1 | Fabric example mod's `gradle/wrapper/gradle-wrapper.properties` |
| Java | 25 (`options.release = 25`) | the version JSON's `javaVersion` |

The reference for a correct, current build setup is the official example mod:
`https://github.com/FabricMC/fabric-example-mod` (`build.gradle`, `gradle.properties`,
`settings.gradle`). When in doubt, diff against it.

## Minecraft 26.x specifics

- **Unobfuscated:** from 26.1 on, the game ships with Mojang's real names, so there's **no
  `mappings` line** in `build.gradle`. Docs and examples written for ≤1.21 (Yarn names like
  `PlayerEntity`, `MinecraftClient`) use different names than the Mojang ones (`Player`,
  `Minecraft`). Translate, don't copy blindly.
- To check that a vanilla class exists and where it lives, list the game jar:
  `unzip -l ~/Library/Application\ Support/minecraft/versions/26.3/26.3.jar | grep <Name>`.
- Prefer current Fabric docs (`https://docs.fabricmc.net/develop/`, or the context7 MCP) over memory.
  The APIs move every release.

## Per-mod commands (run inside `mods/<modid>/`)

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests and server gametests, produce the jar in `build/libs/` |
| `./gradlew test` | Unit tests only (JUnit 5): fast, no game launch |
| `./gradlew runClient` | Launch a dev Minecraft with the mod, for manual play |
| `./gradlew runClientGameTest` | Launch the real client headlessly-ish, run client gametests, save screenshots |

## How we work

- Use the **superpowers** skills: brainstorming → spec in `docs/superpowers/specs/` → writing-plans
  → plan in `docs/superpowers/plans/` → test-driven implementation → verification before claiming
  done.
- Put game logic (physics, rules, math) in **plain Java classes with no Minecraft imports**, and
  unit-test them test-first. Keep entities, renderers and registration thin.
- Verify in-game behavior with **Fabric client gametests**, and look at their screenshots before
  saying visuals are correct.
- Every mod has its own `mods/<modid>/CLAUDE.md` covering its architecture: where the logic lives, what
  is tunable, and any gotchas.

## Conventions

- Mod id = folder name: lowercase `[a-z0-9_]`, e.g. `drone`.
- Java package `io.palmisano.<modid>`; author `Frankie Palmisano`.
- Asset paths follow vanilla: `assets/<modid>/textures/entity/…`, `textures/item/…`, `lang/en_us.json`.
- Tunable numbers live in one record or constants class per mod, never scattered.

## Adding a new mod

1. Brainstorm and spec it (superpowers).
2. Create `mods/<modid>/` from Fabric's template generator (`https://fabricmc.net/develop/template/`)
   or by copying an existing mod's Gradle files and wrapper. Set the mod id, package, and versions
   from the table above.
3. Add the mod's own `CLAUDE.md`, add a row to `README.md`, and check that `./gradlew build` passes.

## Installing a mod into the real game (only when asked)

1. Back up the world folder in `~/Library/Application Support/minecraft/saves/`.
2. Install Fabric Loader for 26.3 with the official Fabric installer (client profile).
3. Copy the Fabric API jar and the mod's `build/libs/<modid>-<version>.jar` (not `-sources`) into
   `~/Library/Application Support/minecraft/mods/`.
4. Pick the `fabric-loader-26.3` profile in the launcher.
