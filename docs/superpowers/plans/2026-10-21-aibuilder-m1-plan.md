# AI Builder M1 (Skeleton) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The AI Builder mod loads; pressing B opens a prompt screen (prompt box, size selector, Build/Cancel, recent prompts); Build or `/aibuild <prompt>` sends the prompt to the server, which echoes it in chat. No building yet.

**Architecture:** A new self-contained Fabric project, `mods/aibuilder/`, made from the drone mod's Gradle setup. The prompt rules (length limit, size tiers, history) are pure Java in `io.palmisano.aibuilder.core.request` and unit-tested first. One serverbound payload carries prompt and size. `BuildManager.submit` is the single server entry point for both the screen and the command; M2 replaces its echo with starting a build. Client gametests drive the real keybind, screen, packet and chat.

**Tech Stack:** Java 25, Gradle 9.7.1 wrapper, Fabric Loom 1.18-SNAPSHOT, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3 (networking, command, key mapping, message and client gametest modules), Gson (shipped with Minecraft), JUnit 6.1.3.

**Spec:** `docs/superpowers/specs/2026-10-21-aibuilder-design.md`. M1 covers §5.1 (screen; the refine toggle and "Stop build" come with refinement and real builds), §5.2 (`/aibuild <prompt>` only), §15 (owner-only check), and §18's M1 row. The brief is `2026-10-21-aibuilder-brief.md`.

## Global Constraints

- Minecraft `26.3`, Fabric Loader `0.19.5`, Fabric API `0.164.0+26.3`, Loom `1.18-SNAPSHOT`, Gradle `9.7.1`, Java `25`, JUnit BOM `6.1.3`. No `mappings` line (Mojang names).
- Mod id `aibuilder`; package `io.palmisano.aibuilder`; display name `AI Builder`; author `Frankie Palmisano`; version `1.0.0`; gametest mod id `aibuilder-test`.
- Project root `mods/aibuilder/` inside the worktree `/Users/spaceplushy/development/Minecraft-mods/.claude/worktrees/aibuilder-v1`; commands below run in `mods/aibuilder/`. Prefix Gradle with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` if the shell lacks it.
- `src/main` never imports `net.minecraft.client.*`. `io.palmisano.aibuilder.core..` imports nothing from Minecraft or Fabric (Gson and the JDK are fine).
- Prompt limit: 2,000 characters, counted as code points. Size tiers, in screen order: Auto, Small, Medium, Large, Epic.
- Keybind: default B, translation key `key.aibuilder.open` ("Open AI Builder"), category `aibuilder:main` ("AI Builder"), rebindable in Controls.
- Recent prompts: the last 10, newest first, in the client's `config/aibuilder-history.json`, together with the last size chosen.
- Only the world's owner can build: a player who isn't the singleplayer owner gets "Only this world's owner can use AI Builder." (the dedicated-server `allowed_players` rule comes in M6).
- Server gametests (`enableGameTests`) stay off until M2 brings the first placement test; M1 uses client gametests only.
- Never touch `~/Library/Application Support/minecraft`. Test only with `test`, `build` and `runClientGameTest`. Client gametests open a game window on Frankie's desktop.
- Gametests wait on conditions (`ctx.waitFor`, `waitForScreen`), never on a fixed tick count before an assertion. Look at every screenshot.
- Every subagent dispatched while executing this plan runs on Opus 5.5 (`model: "opus"`).
- Commit messages `feat(aibuilder): …` ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Branch `aibuilder-v1`.

## Review Focus

1. **B pressed while chat or another screen is open** must not open the builder, neither then nor after that screen closes. → Task 4, chat step.
2. **Resizing the window while typing** must keep the prompt and keep every widget on screen. → Task 4, resize step.
3. **An over-long prompt** (pasted text, another client) must get a chat message, never a disconnect; the box itself stops at 2,000. → Task 3 `a".repeat(2001)` step, Task 4 2,100-character step.
4. **Non-English text and emoji** must come back unchanged. → Task 2 `receivedKeepsNonAsciiIntact`, Task 3 Japanese-and-emoji step.
5. **A missing or corrupt history file** must never stop the screen from opening. → Task 2 `corruptJsonGivesEmpty`, Task 4 corrupt-file step.

---

## File Map (`mods/aibuilder/`)

| Path | Responsibility |
|---|---|
| `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Build, copied from `mods/drone` with the names changed |
| `CLAUDE.md`, `README.md`, `PLAN.md` | Notes for Claude; the player-facing readme; the living plan (exists) |
| `src/main/resources/fabric.mod.json`, `assets/aibuilder/lang/en_us.json` | Mod metadata; English strings |
| `src/main/java/io/palmisano/aibuilder/AiBuilderMod.java` | Main entrypoint: payload, receiver, command |
| `src/main/java/io/palmisano/aibuilder/core/request/{SizeTier,BuildPrompt,PromptHistory,Messages}.java` | Pure prompt rules, sizes, history, chat copy |
| `src/main/java/io/palmisano/aibuilder/net/BuildPromptPayload.java` | Serverbound prompt packet |
| `src/main/java/io/palmisano/aibuilder/{BuildManager,BuildPermission,AiBuildCommand}.java` | Server entry point, owner check, `/aibuild` |
| `src/client/java/io/palmisano/aibuilder/client/{AiBuilderClient,BuilderScreen,PromptHistoryStore}.java` | Keybind and tick hook, the screen, the history file |
| `src/test/java/io/palmisano/aibuilder/core/request/*Test.java` | Unit tests |
| `src/gametest/resources/fabric.mod.json`, `src/gametest/java/io/palmisano/aibuilder/test/*.java` | Client gametests and their support class |

---

### Task 1: Project scaffold and smoke test

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/resources/fabric.mod.json`, `src/main/resources/assets/aibuilder/lang/en_us.json`
- Create: `src/main/java/io/palmisano/aibuilder/AiBuilderMod.java`, `src/client/java/io/palmisano/aibuilder/client/AiBuilderClient.java`
- Create: `src/gametest/resources/fabric.mod.json`, `src/gametest/java/io/palmisano/aibuilder/test/SmokeClientGameTest.java`
- Create: `CLAUDE.md`, `README.md`; Modify: root `README.md` (mod table)

**Interfaces:**
- Produces: `AiBuilderMod.MOD_ID = "aibuilder"`, `static Identifier id(String path)`, `static final Logger LOG` (slf4j, named `aibuilder`); `AiBuilderClient implements ClientModInitializer` (empty body for now).

- [ ] **Step 1: Copy the build** from `../drone`: `settings.gradle` (`rootProject.name = 'aibuilder'`), `build.gradle` (the `loom.mods` entry renamed to `"aibuilder"`; `configureTests { modId = "aibuilder-test"; enableGameTests = false; enableClientGameTests = true }`), `gradle.properties` unchanged, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` unchanged (keep `gradlew` executable).

- [ ] **Step 2: Metadata.** `fabric.mod.json` exactly as drone's with `"id": "aibuilder"`, `"name": "AI Builder"`, `"description": "Type anything and Claude builds it in front of you."`, no `icon`, entrypoints `io.palmisano.aibuilder.AiBuilderMod` / `io.palmisano.aibuilder.client.AiBuilderClient`, same `depends`. Gametest `fabric.mod.json`: id `aibuilder-test`, name `AI Builder Tests`, `fabric-client-gametest` entrypoint `io.palmisano.aibuilder.test.SmokeClientGameTest`, depends `"aibuilder": "*"`. `en_us.json` starts as `{}` (Task 4 fills it).

- [ ] **Step 3: Write the smoke test.** `SmokeClientGameTest` (copy drone's): create a world, `waitForChunksRender()`, print and take screenshot `aibuilder-smoke`. `AiBuilderMod.onInitialize` logs `AI Builder loaded`.

- [ ] **Step 4: Build and run.**
  Run: `./gradlew build --console=plain -q && echo BUILD_OK` → `BUILD_OK`.
  Run: `./gradlew runClientGameTest --console=plain` → `BUILD SUCCESSFUL`; the log contains `AI Builder loaded`; open `build/run/clientGameTest/screenshots/aibuilder-smoke*.png` and check a world rendered.

- [ ] **Step 5: Docs skeleton.** `CLAUDE.md`: what the mod is (one paragraph), links to the spec, brief and `PLAN.md`, the source-set rules from Global Constraints, the per-mod commands table (as in the root `CLAUDE.md`). `README.md`: player-facing intro, "Status: M1 in progress", how to run (`./gradlew runClient`). Root `README.md`: add `| [**AI Builder**](mods/aibuilder/) | Press B, type anything ("castle", "a cozy cabin with a dock"), and Claude builds it in front of you | 26.3 | M1 in progress |`.

- [ ] **Step 6: Commit** — `feat(aibuilder): project scaffold, smoke gametest and docs skeleton`.

---

### Task 2: Prompt rules, size tiers, history and chat copy (pure)

**Files:**
- Create: `src/main/java/io/palmisano/aibuilder/core/request/SizeTier.java`, `BuildPrompt.java`, `PromptHistory.java`, `Messages.java`
- Test: `src/test/java/io/palmisano/aibuilder/core/request/SizeTierTest.java`, `BuildPromptTest.java`, `PromptHistoryTest.java`, `MessagesTest.java`

**Interfaces:**
- Produces:
  - `enum SizeTier { AUTO, SMALL, MEDIUM, LARGE, EPIC; String label(); static SizeTier parse(String name) }`: labels `Auto`, `Small`, `Medium`, `Large`, `Epic`; `parse` is case-insensitive on the constant name, `null` or unknown → `AUTO`.
  - `final class BuildPrompt { static final int MAX_CODE_POINTS = 2000; enum Problem { NONE, EMPTY, TOO_LONG } static String normalize(String raw); static Problem check(String normalized) }`: `normalize` = `strip()`, `null` → `""`; inner newlines kept.
  - `record PromptHistory(List<String> recent, SizeTier lastSize) { static final int MAX = 10; static PromptHistory empty(); PromptHistory withPrompt(String prompt); PromptHistory withSize(SizeTier size); String toJson(); static PromptHistory fromJson(String json) }`: immutable (`List.copyOf`); `empty()` = `([], AUTO)`; JSON shape `{"lastSize":"SMALL","recent":["castle", "..."]}`.
  - `final class Messages { static String received(String prompt, SizeTier size); static String problem(BuildPrompt.Problem problem, String prompt); static String ownerOnly() }`.

- [ ] **Step 1: Write the failing tests.**

```java
// SizeTierTest
@Test void labelsInScreenOrder() {
    assertEquals(List.of("Auto", "Small", "Medium", "Large", "Epic"),
            Arrays.stream(SizeTier.values()).map(SizeTier::label).toList());
}
@Test void parseIsLenientAndDefaultsToAuto() {
    assertEquals(SizeTier.MEDIUM, SizeTier.parse("medium"));
    assertEquals(SizeTier.EPIC, SizeTier.parse("EPIC"));
    assertEquals(SizeTier.AUTO, SizeTier.parse(null));
    assertEquals(SizeTier.AUTO, SizeTier.parse("huge"));
}

// BuildPromptTest
@Test void normalizeStripsOuterWhitespaceOnly() { assertEquals("castle\n with a moat", BuildPrompt.normalize("  castle\n with a moat \n")); }
@Test void normalizeTreatsNullAsEmpty() { assertEquals("", BuildPrompt.normalize(null)); }
@Test void blankIsEmpty() { assertEquals(Problem.EMPTY, BuildPrompt.check(BuildPrompt.normalize(" \n\t "))); }
@Test void exactlyTheLimitIsFine() { assertEquals(Problem.NONE, BuildPrompt.check("a".repeat(2000))); }
@Test void overTheLimitIsTooLong() { assertEquals(Problem.TOO_LONG, BuildPrompt.check("a".repeat(2001))); }
@Test void limitCountsCharactersNotUtf16Units() {
    assertEquals(Problem.NONE, BuildPrompt.check("🐟".repeat(2000)));
    assertEquals(Problem.TOO_LONG, BuildPrompt.check("🐟".repeat(2001)));
}

// MessagesTest
@Test void receivedEchoesPromptAndSize() { assertEquals("AI Builder got: castle (Small)", Messages.received("castle", SizeTier.SMALL)); }
@Test void receivedKeepsNonAsciiIntact() {
    assertEquals("AI Builder got: 日本庭園 with a koi pond 🐟 (Auto)", Messages.received("日本庭園 with a koi pond 🐟", SizeTier.AUTO));
}
@Test void emptyAsksForAPrompt() { assertEquals("Type something for AI Builder to build.", Messages.problem(Problem.EMPTY, "")); }
@Test void tooLongSaysHowLong() {
    assertEquals("That prompt is too long: 2,345 characters, the limit is 2,000.", Messages.problem(Problem.TOO_LONG, "a".repeat(2345)));
}
@Test void ownerOnlyIsPlain() { assertEquals("Only this world's owner can use AI Builder.", Messages.ownerOnly()); }

// PromptHistoryTest
@Test void newestFirst() { assertEquals(List.of("b", "a"), PromptHistory.empty().withPrompt("a").withPrompt("b").recent()); }
@Test void repeatMovesToFront() { assertEquals(List.of("a", "b"), PromptHistory.empty().withPrompt("a").withPrompt("b").withPrompt("a").recent()); }
@Test void keepsTheLastTen() {
    PromptHistory h = PromptHistory.empty();
    for (int i = 1; i <= 12; i++) h = h.withPrompt("p" + i);
    assertEquals(10, h.recent().size());
    assertEquals("p12", h.recent().getFirst());
    assertEquals("p3", h.recent().getLast());
}
@Test void blankIsIgnored() { assertEquals(PromptHistory.empty(), PromptHistory.empty().withPrompt("  ")); }
@Test void jsonRoundTrip() {
    PromptHistory h = PromptHistory.empty().withPrompt("castle").withPrompt("日本庭園\nline two").withSize(SizeTier.LARGE);
    assertEquals(h, PromptHistory.fromJson(h.toJson()));
}
@Test void corruptJsonGivesEmpty() {
    assertEquals(PromptHistory.empty(), PromptHistory.fromJson("{oops"));
    assertEquals(PromptHistory.empty(), PromptHistory.fromJson(null));
    assertEquals(PromptHistory.empty(), PromptHistory.fromJson("[1,2]"));
}
@Test void badEntriesAreSkipped() {
    PromptHistory h = PromptHistory.fromJson("{\"lastSize\":\"GIANT\",\"recent\":[\"ok\",3,null,\"  \",{\"x\":1}]}");
    assertEquals(List.of("ok"), h.recent());
    assertEquals(SizeTier.AUTO, h.lastSize());
}
@Test void moreThanTenStoredAreCut() {
    String json = "{\"recent\":[" + IntStream.rangeClosed(1, 12).mapToObj(i -> "\"p" + i + "\"").collect(Collectors.joining(",")) + "]}";
    assertEquals(10, PromptHistory.fromJson(json).recent().size());
}
```

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew test --console=plain` → compile failure (classes missing).

- [ ] **Step 3: Implement the four classes** with the signatures above. `withPrompt` normalizes, ignores blank, removes an equal entry, puts it first, cuts to `MAX`. `fromJson` uses Gson's `JsonParser`, catches every parse/shape failure (→ `empty()`), and keeps only string entries that aren't blank. `Messages.problem` formats the count with `String.format(Locale.ROOT, "%,d", prompt.codePointCount(0, prompt.length()))`.

- [ ] **Step 4: Run the tests.**
  Run: `./gradlew test --console=plain -q && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml`
  Expected: four lines, all `failures="0" errors="0"`, 21 tests in total.

- [ ] **Step 5: Commit** — `feat(aibuilder): prompt rules, size tiers and prompt history`.

---

### Task 3: Prompt packet, owner check, echo and `/aibuild`

**Files:**
- Create: `src/main/java/io/palmisano/aibuilder/net/BuildPromptPayload.java`, `BuildManager.java`, `BuildPermission.java`, `AiBuildCommand.java`
- Modify: `AiBuilderMod.java`
- Create: `src/gametest/java/io/palmisano/aibuilder/test/GameTestSupport.java`, `PromptServerClientGameTest.java`; Modify: gametest `fabric.mod.json` (add the entrypoint)

**Interfaces:**
- Consumes: Task 2's `SizeTier`, `BuildPrompt`, `Messages`.
- Produces:
  - `record BuildPromptPayload(String prompt, SizeTier size) implements CustomPacketPayload` with `static final Type<BuildPromptPayload> TYPE = new Type<>(AiBuilderMod.id("build_prompt"))` and `static final StreamCodec<RegistryFriendlyByteBuf, BuildPromptPayload> CODEC` = `StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_WIRE_CHARS), …prompt, SIZE_CODEC, …size, BuildPromptPayload::new)`; `MAX_WIRE_CHARS = 8192` (far above the 2,000 limit, so an over-long prompt gets a message instead of a decode failure that drops the connection); `SIZE_CODEC` = `ByteBufCodecs.VAR_INT` mapped to `SizeTier` by ordinal, out-of-range → `AUTO`.
  - `BuildPermission.canBuild(ServerPlayer player) → boolean`: true exactly when `MinecraftServer.isSingleplayerOwner(player.nameAndId())`.
  - `BuildManager.submit(ServerPlayer player, String rawPrompt, SizeTier size) → void`: not allowed → `Messages.ownerOnly()`; else normalize and check → `Messages.problem(…)`; else `Messages.received(prompt, size)`. Every reply is `player.sendSystemMessage(Component.literal(…))`.
  - `AiBuildCommand.register(CommandDispatcher<CommandSourceStack> dispatcher)`: `/aibuild <prompt>` (`StringArgumentType.greedyString()`, no `.requires`, so non-owners get the plain refusal) → `BuildManager.submit(source.getPlayerOrException(), prompt, SizeTier.AUTO)`.
  - Test support: `GameTestSupport.world(ctx) → TestSingleplayerContext` (new world, chunks rendered), `runPlayerCommand(ctx, String commandWithoutSlash)` (via `mc.player.connection.sendCommand`), `awaitChat(ctx, String exact)` (waits up to 100 ticks; on timeout fails listing what chat did receive), `check(boolean, String)`. Chat is captured once, in a static initializer, with `ClientReceiveMessageEvents.GAME` (non-overlay messages, `getString()`, into a `CopyOnWriteArrayList`).

- [ ] **Step 1: Write the failing gametest** `PromptServerClientGameTest.runTest`, in one world, in order:

```java
GameTestSupport.runPlayerCommand(ctx, "aibuild hello world");
GameTestSupport.awaitChat(ctx, "AI Builder got: hello world (Auto)");
send(ctx, "castle", SizeTier.LARGE);
GameTestSupport.awaitChat(ctx, "AI Builder got: castle (Large)");
send(ctx, "  \n ", SizeTier.SMALL);
GameTestSupport.awaitChat(ctx, "Type something for AI Builder to build.");
send(ctx, "a".repeat(2001), SizeTier.AUTO);
GameTestSupport.awaitChat(ctx, "That prompt is too long: 2,001 characters, the limit is 2,000.");
send(ctx, "日本庭園 with a koi pond 🐟", SizeTier.MEDIUM);
GameTestSupport.awaitChat(ctx, "AI Builder got: 日本庭園 with a koi pond 🐟 (Medium)");
GameTestSupport.runPlayerCommand(ctx, "aibuild still here"); // still connected after the over-long prompt
GameTestSupport.awaitChat(ctx, "AI Builder got: still here (Auto)");
```
where `send` is `ctx.runOnClient(mc -> ClientPlayNetworking.send(new BuildPromptPayload(prompt, size)))`.

- [ ] **Step 2: Run it to see it fail.** Run: `./gradlew runClientGameTest --console=plain` → compile failure, then (with stubs) a timeout on the first `awaitChat`.

- [ ] **Step 3: Implement** the four classes and register in `AiBuilderMod.onInitialize`: `PayloadTypeRegistry.serverboundPlay().register(BuildPromptPayload.TYPE, BuildPromptPayload.CODEC)`; `ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> BuildManager.submit(context.player(), payload.prompt(), payload.size()))` (Fabric runs it on the server thread); `CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> AiBuildCommand.register(dispatcher))`.

- [ ] **Step 4: Run all tests.** Run: `./gradlew build --console=plain -q && echo BUILD_OK && ./gradlew runClientGameTest --console=plain` → `BUILD_OK`, then `BUILD SUCCESSFUL` with both gametests passing.

- [ ] **Step 5: Commit** — `feat(aibuilder): prompt packet, owner check and /aibuild echo`.

---

### Task 4: Keybind, builder screen and prompt history; docs

**Files:**
- Modify: `AiBuilderClient.java`, `src/main/resources/assets/aibuilder/lang/en_us.json`
- Create: `src/client/java/io/palmisano/aibuilder/client/BuilderScreen.java`, `PromptHistoryStore.java`
- Create: `src/gametest/java/io/palmisano/aibuilder/test/BuilderScreenClientGameTest.java`; Modify: gametest `fabric.mod.json`
- Modify: `CLAUDE.md`, `README.md`, `PLAN.md`, root `README.md`, root `CLAUDE.md` (status row)

**Interfaces:**
- Consumes: `BuildPromptPayload`, `SizeTier`, `BuildPrompt`, `PromptHistory`, `GameTestSupport`.
- Produces:
  - `AiBuilderClient.OPEN_BUILDER` (`public static final KeyMapping`): `new KeyMapping("key.aibuilder.open", GLFW.GLFW_KEY_B, KeyMapping.Category.register(AiBuilderMod.id("main")))`, registered with `KeyMappingHelper.registerKeyMapping`. `END_CLIENT_TICK`: `while (OPEN_BUILDER.consumeClick())` open `new BuilderScreen(PromptHistoryStore.standard())` with `mc.gui.setScreen(…)`, only when `mc.player != null && mc.gui.screen() == null`.
  - `PromptHistoryStore(Path file)`, `static PromptHistoryStore standard()` (`FabricLoader.getInstance().getConfigDir().resolve("aibuilder-history.json")`), `PromptHistory load()` (missing or unreadable → `PromptHistory.empty()`), `void save(PromptHistory history)` (write a `.tmp` sibling, then `Files.move` with `REPLACE_EXISTING, ATOMIC_MOVE`). IO errors are logged with `AiBuilderMod.LOG`, never thrown.
  - `BuilderScreen extends Screen`, constructor `BuilderScreen(PromptHistoryStore store)`, title `gui.aibuilder.title`. Read-only accessors for tests: `String prompt()`, `SizeTier size()`, `boolean buildEnabled()`, `List<String> recentPrompts()`.
- Strings (`en_us.json`): `key.aibuilder.open` "Open AI Builder", `key.category.aibuilder.main` "AI Builder", `gui.aibuilder.title` "AI Builder", `gui.aibuilder.prompt` "What should Claude build?", `gui.aibuilder.placeholder` "A castle, a cozy cabin with a dock, a Japanese garden with a koi pond…", `gui.aibuilder.size` "Size", `gui.aibuilder.mode.new` "New build here", `gui.aibuilder.build` "Build", `gui.aibuilder.recent` "Recent prompts", `gui.aibuilder.no_server_mod` "This server doesn't have AI Builder.".
- Layout (26.3 screens draw with `extractRenderState`, so labels are `StringWidget`s, not drawn text): a centered column `W = min(320, width - 40)` wide. Title at y 12; prompt label at 30; `MultiLineEditBox` at 42, `clamp(height - 120, 36, 72)` tall, character limit 2,000, placeholder as above; 6 below the box, the size `CycleButton` (100 wide; values `SizeTier.values()`, value label `SizeTier.label()`, name `gui.aibuilder.size`, so it reads "Size: Auto") with the mode label "New build here" beside it; 26 below that, Build and Cancel (`CommonComponents.GUI_CANCEL`), each `(W - 4) / 2` wide; 30 below those, the "Recent prompts" label, then 12 below it one 20-tall button per recent prompt every 22 px, as many as end above `height - 8`, at most 10, labelled with the prompt's first line cut to the button width with "…". The label shows only when at least one button fits. (At the default 427×240 GUI size: box 72 tall, size row at 120, buttons at 146, label at 176, two prompt rows.)
- Behavior: `isPauseScreen()` is false (the world keeps running). The typed text lives in a field and is restored in `init()`, so a resize or GUI-scale change keeps it. Build is active only when the normalized text isn't empty. Build: if `ClientPlayNetworking.canSend(BuildPromptPayload.TYPE)` is false, show `gui.aibuilder.no_server_mod` in chat and close; otherwise send the raw text and the size, save `history.withPrompt(text).withSize(size)`, close. Changing the size saves it at once. A recent-prompt button puts its full prompt in the box and focuses it. The box has the initial focus. Esc closes.

- [ ] **Step 1: Write the failing gametest** `BuilderScreenClientGameTest.runTest`. Before the world: write `{oops` to `FabricLoader.getInstance().getConfigDir().resolve("aibuilder-history.json")`. `screen(ctx, f)` = `ctx.computeOnClient(mc -> f.apply((BuilderScreen) mc.gui.screen()))`. Then, in one world, in order:
  1. `pressKey(AiBuilderClient.OPEN_BUILDER)`, `waitForScreen(BuilderScreen.class)`: prompt `""`, `buildEnabled()` false, size `AUTO`, `recentPrompts()` empty (the corrupt file read as empty). Screenshot `aibuilder-screen-empty`.
  2. `typeChars("a big barn by the bay")`: prompt equals it, `buildEnabled()` true. Then `pressKey(GLFW.GLFW_KEY_B)` and wait 3 ticks: the screen is still the same `BuilderScreen` instance.
  3. Note the window size, `resizeWindow(640, 360)`, `waitFor` the window to be 640 wide, then `waitTick()` so the screen re-inits: prompt unchanged; every `AbstractWidget` in `children()` lies inside `0..width` × `0..height`. Screenshot `aibuilder-screen-small`; resize back.
  4. `clickScreenButton("gui.aibuilder.size")`: size `SMALL`. `clickScreenButton("gui.aibuilder.build")`: wait for no screen; `awaitChat(ctx, "AI Builder got: a big barn by the bay (Small)")`.
  5. Open again: size `SMALL`, `recentPrompts()` equals `List.of("a big barn by the bay")`, prompt `""`. `clickScreenButton("a big barn by the bay")`: prompt equals it. Screenshot `aibuilder-screen-recent`.
  6. `typeChars("x".repeat(2100))`: prompt length is 2,000. Esc; wait for no screen.
  7. `pressKey(o -> o.keyChat)`, `waitForScreen(ChatScreen.class)`, `pressKey(GLFW.GLFW_KEY_B)`, wait 3 ticks: still a `ChatScreen`. Esc, wait 5 ticks: no screen (no builder popped up after chat closed).
  8. Rebind: on the client, `OPEN_BUILDER.setKey(InputConstants.Type.KEYBOARD.getOrCreate(GLFW.GLFW_KEY_N))` and `KeyMapping.resetMapping()`; `pressKey(GLFW.GLFW_KEY_N)`, `waitForScreen(BuilderScreen.class)`; Esc; set it back to `getDefaultKey()` and `resetMapping()`.

  If the gametest source set can't see `src/client` classes, add `client.output` to its compile and runtime classpaths in `build.gradle`.

- [ ] **Step 2: Run it to see it fail.** Run: `./gradlew runClientGameTest --console=plain` → fails at step 1 (no screen opens).

- [ ] **Step 3: Implement** `AiBuilderClient`, `PromptHistoryStore`, `BuilderScreen` and the strings as specified above.

- [ ] **Step 4: Run everything and look.** Run: `./gradlew build --console=plain -q && echo BUILD_OK && ./gradlew runClientGameTest --console=plain` → `BUILD_OK`, then `BUILD SUCCESSFUL` with all three gametests passing. Open the three `aibuilder-screen-*` screenshots: the widgets don't overlap, text isn't clipped, the small one still shows the prompt box and both buttons.

- [ ] **Step 5: Docs and status.** `CLAUDE.md`: the M1 architecture (the request package, `BuildManager.submit` as the one server entry point, payload, screen), plus any 26.3 gotchas met on the way (for example: screens render through `extractRenderState`; label text uses `StringWidget`; `clickScreenButton` matches a `Button` by its text and a `CycleButton` by its name). `README.md`: the B key, the screen, `/aibuild <prompt>`, the screenshot `docs/screen.png` (copied from `aibuilder-screen-recent`), "Status: M1, the prompt reaches the server; building starts in M2". `PLAN.md`: M1 row → "✅ built, waiting for Frankie's test", change-log line. Root `README.md` status cell → "M1 built, waiting for Frankie's test". Root `CLAUDE.md` `aibuilder` row: M1 built; next is Frankie's M1 test, then the M2 plan.

- [ ] **Step 6: Commit** — `feat(aibuilder): B opens the builder screen with size and recent prompts`.

---

## After the tasks

1. One fresh reviewer on Opus 5.5 reviews the whole branch diff since `3700984` against this plan and the spec's M1 scope; fix what it finds test-first.
2. Stop for Frankie's M1 test (spec §18): `cd mods/aibuilder && ./gradlew runClient`, create a world, press B, type a prompt, Build → chat says `AI Builder got: …`; try `/aibuild hello`; rebind B in Options → Controls → Key Binds → AI Builder. M2's plan is written only after Frankie's go-ahead.
