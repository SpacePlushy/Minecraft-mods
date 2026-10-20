# Drone Mod Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Fabric mod for Minecraft 26.3 adding a rideable quadcopter drone that flies like a DJI-style camera drone (tilt, momentum, active braking, altitude hold), creative-only.

**Architecture:** All flight physics live in a pure-Java `flight` package (no Minecraft imports) driven by unit tests. A thin `DroneEntity` (extends vanilla `VehicleEntity`, client-authoritative like boats) steps that model each tick and moves with vanilla collision. A client tick hook turns the rider's key mappings into a `DroneInput`; a blocky model + renderer shows tilt and spinning props. In-game behavior is proven with Fabric client gametests that also save screenshots.

**Tech Stack:** Java 25, Gradle 9.7.1 wrapper, Fabric Loom (`net.fabricmc.fabric-loom` 1.18-SNAPSHOT), Fabric Loader 0.19.5, Fabric API 0.164.0+26.3 (incl. client gametest API), JUnit 6.1.3, Python 3 stdlib (texture generation).

**Spec:** `docs/superpowers/specs/2026-10-19-drone-mod-design.md` — read it alongside this plan; §6 defines the per-tick physics order and §6.1 the tuning values.

## Global Constraints

- Minecraft `26.3`, Fabric Loader `0.19.5`, Fabric API `0.164.0+26.3`, Loom `1.18-SNAPSHOT`, Gradle `9.7.1`, Java `25` (`options.release = 25`), JUnit BOM `6.1.3`.
- No `mappings` line: 26.x is unobfuscated, use Mojang names (`Identifier`, not `ResourceLocation`).
- Mod id `drone`; package `io.palmisano.drone`; author `Frankie Palmisano`; entity and item id `drone:drone`; display name `Drone`; version `1.0.0`.
- Project root `mods/drone/`. All commands below run there unless stated.
- `src/main` never imports `net.minecraft.client.*`. `io.palmisano.drone.flight` imports nothing from Minecraft or Fabric.
- Every tunable flight number lives only in `FlightTuning.DEFAULT` with exactly the spec §6.1 values.
- Never touch `~/Library/Application Support/minecraft` (real install, "Frankie's World"). Test only via `./gradlew test`, `build`, `runClient`, `runClientGameTest`.
- Gradle needs `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (JDK installed 2026-10-19).
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Work on branch `drone-v1`; merge to `main` after Task 7.

## Review Focus

1. **Dismounting mid-air** — the drone must brake and hover where it was left (no fall, no drift) and visibly level out. → Task 6 gametest `dismountMidAirHovers`.
2. **Flying into a wall at full speed** — it stops at the wall without jitter or tunnelling and still accepts sideways input. → Task 3 unit `afterCollisionZeroesOnlyBlockedAxes` + Task 6 gametest `wallStopsDrone`.
3. **Unbounded player yaw** (after spinning the mouse, `getYRot()` can be 725°) — the drone turns the short way and never spins the long way round. → Task 2 unit `yawHandlesUnwrappedPlayerYaw`.
4. **Placing where there's no room** (e.g. a 1-wide corridor; the drone is 1.5 wide) — nothing spawns. → Task 5 gametest `noRoomNoDrone`.
5. **Opposing keys held together** (W+S, Space+Sprint) — cancel to zero input, no drift. → Task 3 unit `opposingKeysCancel`.

---

## File Map (`mods/drone/`)

| Path | Responsibility |
|---|---|
| `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Build |
| `CLAUDE.md` | Drone-specific notes for future sessions |
| `tools/gen_textures.py` | Regenerates all PNG assets from code (stdlib only) |
| `src/main/java/io/palmisano/drone/DroneMod.java` | `ModInitializer`: calls registries, adds item to creative tab |
| `.../drone/registry/ModItems.java`, `ModEntities.java` | Item and entity-type registration |
| `.../drone/flight/FlightTuning.java`, `FlightState.java`, `DroneInput.java`, `FlightModel.java` | Pure physics |
| `.../drone/entity/DroneEntity.java` | The vehicle |
| `.../drone/item/DroneItem.java` | Place-on-block |
| `src/main/resources/fabric.mod.json` | Mod metadata |
| `src/main/resources/assets/drone/{icon.png, lang/en_us.json, items/drone.json, models/item/drone.json, textures/item/drone.png, textures/entity/drone.png}` | Assets |
| `src/client/java/io/palmisano/drone/client/DroneClient.java` | `ClientModInitializer`: renderer, model layer, input tick |
| `.../client/DroneRenderState.java`, `DroneModel.java`, `DroneRenderer.java` | Rendering |
| `src/test/java/io/palmisano/drone/flight/FlightModelTest.java`, `DroneInputTest.java` | Unit tests |
| `src/gametest/java/io/palmisano/drone/test/{GameTestSupport, SmokeClientGameTest, DronePlacementClientGameTest, DroneFlightClientGameTest}.java` | Client gametests |
| `src/gametest/resources/fabric.mod.json` | Test mod (`drone-test`) metadata |

---

### Task 1: Toolchain and empty mod that launches

**Files:**
- Create: `mods/drone/settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/java/io/palmisano/drone/DroneMod.java`, `src/client/java/io/palmisano/drone/client/DroneClient.java`, `src/main/resources/fabric.mod.json`
- Create: `src/gametest/java/io/palmisano/drone/test/SmokeClientGameTest.java`, `src/gametest/resources/fabric.mod.json`

**Interfaces:**
- Produces: `DroneMod implements ModInitializer` with `public static final String MOD_ID = "drone"` and `public static Identifier id(String path)` (→ `Identifier.fromNamespaceAndPath(MOD_ID, path)`); `DroneClient implements ClientModInitializer`; working `./gradlew build` and `./gradlew runClientGameTest`.

- [ ] **Step 1: Set JAVA_HOME (ask first)**

Ask Frankie before editing `~/.zshrc`. With a yes, append:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```
With a no, prefix every Gradle command in this plan with `JAVA_HOME=…`. Verify: `java -version` prints `25.0.4.1`.

- [ ] **Step 2: Gradle wrapper**

Download `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` from `https://raw.githubusercontent.com/FabricMC/fabric-example-mod/HEAD/`. `chmod +x gradlew`.
Verify the jar: `shasum -a 256 gradle/wrapper/gradle-wrapper.jar` must equal `curl -sL https://services.gradle.org/distributions/gradle-9.7.1-wrapper.jar.sha256`. If it doesn't match, stop and report. Don't run an unverified wrapper.

- [ ] **Step 3: Build files**

- `settings.gradle`: the Fabric `pluginManagement` repositories from the example mod; `rootProject.name = 'drone'`.
- `gradle.properties`: `org.gradle.jvmargs=-Xmx2G`, `org.gradle.parallel=true`, `org.gradle.configuration-cache=true`, `minecraft_version=26.3`, `loader_version=0.19.5`, `loom_version=1.18-SNAPSHOT`, `fabric_api_version=0.164.0+26.3`, `junit_version=6.1.3`, `version=1.0.0`, `group=io.palmisano`.
- `build.gradle`: the example mod's file minus `maven-publish`, the `publishing` block, and the LICENSE copy, plus:
```gradle
loom {
	splitEnvironmentSourceSets()
	mods { "drone" { sourceSet sourceSets.main; sourceSet sourceSets.client } }
}
fabricApi {
	configureTests {
		createSourceSet = true
		modId = "drone-test"
		enableGameTests = false        // no server gametests; keeps `build` fast
		enableClientGameTests = true
		eula = true                    // dev test client only
	}
}
dependencies {
	// minecraft, fabric-loader, fabric-api exactly as the example mod
	testImplementation platform("org.junit:junit-bom:${project.junit_version}")
	testImplementation "org.junit.jupiter:junit-jupiter"
	testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}
test { useJUnitPlatform() }
```

- [ ] **Step 4: Mod metadata and entrypoints**

`fabric.mod.json`: the example's structure with `id` `drone`, `name` `Drone`, `description` `A rideable quadcopter that flies like a real camera drone.`, `authors` `["Frankie Palmisano"]`, `icon` `assets/drone/icon.png`. Entrypoints: `main` `io.palmisano.drone.DroneMod`, `client` `io.palmisano.drone.client.DroneClient`. No `mixins`, no `contact`, no `license`. `depends` copied from the example (`fabricloader >=0.19.5`, `minecraft ~26.3`, `java >=25`, `fabric-api *`). `DroneMod.onInitialize()` and `DroneClient.onInitializeClient()` are empty for now.

- [ ] **Step 5: Smoke client gametest**

`src/gametest/resources/fabric.mod.json`: `id` `drone-test`, `environment` `*`, entrypoint `fabric-client-gametest`: `["io.palmisano.drone.test.SmokeClientGameTest"]`, `depends` `{"drone": "*"}`.
`SmokeClientGameTest implements FabricClientGameTest`: open `context.worldBuilder().create()` in try-with-resources, call `singleplayer.getConnection().waitForChunksRender()`, call `context.takeScreenshot("drone-smoke")`, and print the returned path.

- [ ] **Step 6: Verify**

Run: `./gradlew build` → `BUILD SUCCESSFUL`. The first run downloads Minecraft, so allow 10+ minutes and run it in the background.
Run: `./gradlew runClientGameTest` → a game window opens, a world loads, the task ends `BUILD SUCCESSFUL`, and `drone-smoke.png` exists at the printed path.

- [ ] **Step 7: Commit**

```bash
git checkout -b drone-v1
git add mods/drone
git commit -m "feat(drone): scaffold Fabric 26.3 project with smoke client gametest"
```

---

### Task 2: Flight model — heading, tilt, thrust, braking

**Files:**
- Create: `src/main/java/io/palmisano/drone/flight/FlightTuning.java`, `FlightState.java`, `DroneInput.java`, `FlightModel.java`
- Test: `src/test/java/io/palmisano/drone/flight/FlightModelTest.java`

**Interfaces:**
- Produces:
  - `record FlightTuning(double maxTilt, double tiltResponse, double drag, double topSpeed, double brakeGain, double maxClimb, double maxDescend, double verticalResponse, double yawResponse, double maxYawRate, double settleSpeed)`. It has `double maxHorizontalAccel()` = `topSpeed * drag / (1 - drag)` and `static final FlightTuning DEFAULT` = `(25, 0.20, 0.05, 0.40, 40, 0.25, 0.20, 0.15, 0.35, 10, 0.002)`. Angles are in degrees, speeds in blocks/tick, responses per tick.
  - `record FlightState(double vx, double vy, double vz, double yaw, double pitchTilt, double rollTilt)`, plus `static FlightState hovering(double yaw)` (all zero except yaw) and `FlightState withYaw(double yaw)`.
  - `record DroneInput(double forward, double strafe, double vertical, double targetYaw)`. The compact constructor clamps the first three to [−1, 1]. It has `static DroneInput hover(double yaw)` → `(0, 0, 0, yaw)`. Task 3 adds `fromKeys`.
  - `final class FlightModel`: `FlightModel(FlightTuning t)`, `FlightState step(FlightState s, DroneInput in)`. Task 3 adds `afterCollision`.
- Frame conventions (spec §6): yaw 0 faces +Z. World forward = `(−sin yaw, 0, cos yaw)`. World right = `(−cos yaw, 0, −sin yaw)`. `strafe` > 0 means right, and `rollTilt` > 0 means right side down.

All tests use `T = FlightTuning.DEFAULT`, `M = new FlightModel(T)`, and a helper `FlightState run(FlightState s, DroneInput in, int ticks)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void defaultTuningMatchesSpec() {            // pins spec §6.1; retuning edits only this test
    assertEquals(25, T.maxTilt()); assertEquals(0.20, T.tiltResponse()); assertEquals(0.05, T.drag());
    assertEquals(0.40, T.topSpeed()); assertEquals(40, T.brakeGain()); assertEquals(10, T.maxYawRate());
    assertEquals(0.35, T.yawResponse()); assertEquals(0.002, T.settleSpeed());
    assertEquals(0.25, T.maxClimb()); assertEquals(0.20, T.maxDescend()); assertEquals(0.15, T.verticalResponse());
    assertEquals(0.0211, T.maxHorizontalAccel(), 1e-4);
}
@Test void noInputFromRestStaysPut() {
    var s = run(FlightState.hovering(0), DroneInput.hover(0), 100);
    assertEquals(FlightState.hovering(0), s);
}
@Test void fullForwardConvergesToTopSpeedAlongHeading() {
    var s = run(FlightState.hovering(0), new DroneInput(1, 0, 0, 0), 200);
    assertEquals(T.maxTilt(), s.pitchTilt(), 0.01);
    assertEquals(T.topSpeed(), s.vz(), 0.01);       // yaw 0 → +Z
    assertEquals(0, s.vx(), 1e-9);
}
@Test void forwardFollowsYaw() {                    // yaw 90 → forward is −X
    var s = run(FlightState.hovering(90), new DroneInput(1, 0, 0, 90), 200);
    assertEquals(-T.topSpeed(), s.vx(), 0.01);
    assertEquals(0, s.vz(), 0.01);
}
@Test void topSpeedTakesMomentum() {                // ~55% after 1 s (simulated 0.221)
    var s = run(FlightState.hovering(0), new DroneInput(1, 0, 0, 0), 20);
    assertTrue(s.vz() > 0.18 && s.vz() < 0.26, "vz=" + s.vz());
}
@Test void strafeRightMovesAlongRightAxis() {      // yaw 0 → right is −X
    var s = run(FlightState.hovering(0), new DroneInput(0, 1, 0, 0), 200);
    assertEquals(T.maxTilt(), s.rollTilt(), 0.01);
    assertEquals(-T.topSpeed(), s.vx(), 0.01);
}
@Test void releaseBrakesWithBackTiltThenSettles() {
    var s = run(FlightState.hovering(0), new DroneInput(1, 0, 0, 0), 200);
    double minPitch = 0;
    for (int i = 0; i < 60; i++) { s = M.step(s, DroneInput.hover(0)); minPitch = Math.min(minPitch, s.pitchTilt()); }
    assertTrue(minPitch < -5, "brake lean " + minPitch);   // simulated ≈ −8.4°
    assertEquals(FlightState.hovering(0), s);              // simulated settle at tick 52
}
@Test void yawTurnIsRateLimited() {
    var s = M.step(FlightState.hovering(0), DroneInput.hover(170));
    assertEquals(10, s.yaw(), 1e-9);
}
@Test void yawTakesShortestPath() {                // 350 → 10 turns +20 · 0.35 = +7
    var s = M.step(FlightState.hovering(350), DroneInput.hover(10));
    assertEquals(7, wrap(s.yaw() - 350), 1e-9);
}
@Test void yawHandlesUnwrappedPlayerYaw() {        // Review Focus 3
    var s = M.step(FlightState.hovering(0), DroneInput.hover(725));
    assertEquals(1.75, wrap(s.yaw()), 1e-9);
    s = run(s, DroneInput.hover(725), 50);
    assertEquals(0, wrap(s.yaw() - 725), 0.01);
}
```
`wrap(d)` is a test helper that maps to (−180, 180].

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test` → compilation fails (`FlightModel` etc. don't exist).

- [ ] **Step 3: Implement the four types**

`step` follows spec §6 steps 1–4, 6 and 7 in that order. Step 5 (vertical) passes `vy` through unchanged here; Task 3 replaces it. Write a private `static double wrapDegrees(double)` in `FlightModel`. Thrust per axis is `maxHorizontalAccel() * sin(rad(tilt)) / sin(rad(maxTilt))`. The settle step assigns a literal `0.0`, never a computed `-0.0`: record `equals` compares doubles with `Double.compare`, so `-0.0 != 0.0` would break the equality tests.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test` → all `FlightModelTest` tests PASS.

- [ ] **Step 5: Commit**

```bash
git add mods/drone/src/main/java/io/palmisano/drone/flight mods/drone/src/test
git commit -m "feat(drone): pure flight model with tilt, thrust, braking and heading"
```

---

### Task 3: Flight model — altitude hold, collision, key mapping

**Files:**
- Modify: `src/main/java/io/palmisano/drone/flight/FlightModel.java`, `DroneInput.java`
- Test: `src/test/java/io/palmisano/drone/flight/FlightModelTest.java`, create `DroneInputTest.java`

**Interfaces:**
- Consumes: Task 2 types.
- Produces:
  - `FlightState FlightModel.afterCollision(FlightState s, double intendedX, double intendedY, double intendedZ, double actualX, double actualY, double actualZ)` zeroes the velocity component of each axis where `|actual − intended| > 1e-7`.
  - `static DroneInput DroneInput.fromKeys(boolean forward, boolean back, boolean left, boolean right, boolean up, boolean down, double lookYaw)`: `forward` = fwd − back, `strafe` = right − left, `vertical` = up − down, `targetYaw` = `lookYaw`.

- [ ] **Step 1: Write the failing tests**

In `FlightModelTest`:
```java
@Test void climbConvergesToMaxClimb() {
    assertEquals(T.maxClimb(), run(FlightState.hovering(0), new DroneInput(0, 0, 1, 0), 100).vy(), 0.005);
}
@Test void descendConvergesToMaxDescend() {
    assertEquals(-T.maxDescend(), run(FlightState.hovering(0), new DroneInput(0, 0, -1, 0), 100).vy(), 0.005);
}
@Test void releasingVerticalHoldsAltitude() {
    var s = run(FlightState.hovering(0), new DroneInput(0, 0, 1, 0), 100);
    s = run(s, DroneInput.hover(0), 60);
    assertEquals(0, s.vy());
}
@Test void afterCollisionZeroesOnlyBlockedAxes() {          // Review Focus 2
    var s = new FlightState(0.3, 0.1, 0.2, 0, 10, 0);
    var r = M.afterCollision(s, 0.3, 0.1, 0.2, 0.0, 0.1, 0.2);
    assertEquals(0, r.vx()); assertEquals(0.1, r.vy()); assertEquals(0.2, r.vz());
    assertEquals(10, r.pitchTilt());                          // tilt untouched
}
@Test void afterCollisionStopsVerticalOnGround() {
    var r = M.afterCollision(new FlightState(0, -0.2, 0, 0, 0, 0), 0, -0.2, 0, 0, 0, 0);
    assertEquals(0, r.vy());
}
```
In `DroneInputTest`:
```java
@Test void fromKeysMapsEachKey() {
    assertEquals(new DroneInput(1, 0, 0, 30), DroneInput.fromKeys(true, false, false, false, false, false, 30));
    assertEquals(new DroneInput(-1, 0, 0, 30), DroneInput.fromKeys(false, true, false, false, false, false, 30));
    assertEquals(new DroneInput(0, -1, 0, 30), DroneInput.fromKeys(false, false, true, false, false, false, 30));
    assertEquals(new DroneInput(0, 1, 0, 30), DroneInput.fromKeys(false, false, false, true, false, false, 30));
    assertEquals(new DroneInput(0, 0, 1, 30), DroneInput.fromKeys(false, false, false, false, true, false, 30));
    assertEquals(new DroneInput(0, 0, -1, 30), DroneInput.fromKeys(false, false, false, false, false, true, 30));
}
@Test void opposingKeysCancel() {                            // Review Focus 5
    assertEquals(DroneInput.hover(30), DroneInput.fromKeys(true, true, true, true, true, true, 30));
}
@Test void constructorClamps() {
    assertEquals(new DroneInput(1, -1, 1, 0), new DroneInput(2, -3, 5, 0));
}
@Test void hoverHasNoStickInput() {
    assertEquals(new DroneInput(0, 0, 0, 45), DroneInput.hover(45));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test` → compile errors for `afterCollision` and `fromKeys`, and the vertical tests fail.

- [ ] **Step 3: Implement**

Implement spec §6 step 5 (vertical easing toward `vertical · maxClimb` or `vertical · maxDescend`) and the vertical half of step 7 (snap `vy` to 0). Add `afterCollision` and `fromKeys` with the signatures above.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test` → all tests in both classes PASS, including Task 2's.

- [ ] **Step 5: Commit**

```bash
git add mods/drone/src/main/java/io/palmisano/drone/flight mods/drone/src/test
git commit -m "feat(drone): altitude hold, collision response and key-to-input mapping"
```

---

### Task 4: Textures, item model, names

**Files:**
- Create: `tools/gen_textures.py`
- Create (generated): `src/main/resources/assets/drone/textures/entity/drone.png`, `textures/item/drone.png`, `icon.png`
- Create: `src/main/resources/assets/drone/items/drone.json`, `models/item/drone.json`, `lang/en_us.json`

**Interfaces:**
- Produces: the entity texture UV layout below, which Task 5's `DroneModel` uses verbatim. Model space is in pixels, Y points down, the origin is the body center, front = −Z, right = −X.

| Part | `texOffs(u,v)` | Box `addBox(x,y,z, w,h,d)` | Pose | Color |
|---|---|---|---|---|
| `body` | (0,0) | (−4,−2,−4, 8,4,8) | origin | `#3A3D42`; top face (u 8–15, v 0–7) lighter panel `#5A5F66` (Task 5 ruling: was 12×4×12, which hid the arms) |
| `arm_a` / `arm_b` | (0,16) | (−10,−1,−1, 20,2,2) | yRot +45° / −45° | `#2B2D31` |
| `motor_front_left` / `_right` | (0,20) | (−1.5,−3,−1.5, 3,3,3) | offset (+7,−1,−7) / (−7,−1,−7) | red `#D02020` |
| `motor_back_left` / `_right` | (12,20) | same | offset (+7,−1,+7) / (−7,−1,+7) | green `#20B040` |
| `skid_left` / `_right` | (24,20) | (−0.5,0,−6, 1,2,12) | offset (+4,2,0) / (−4,2,0) | `#1E1E1E` |
| `prop_*` (4) | (0,34) | (−5,−1,−1, 10,1,2) | offset (±7,−4,±7) over each motor | `#C8CCD0` |

A box's UV footprint is `2·(w+d)` wide and `d+h` tall, starting at `texOffs`. All parts fit inside 64×64.

- [ ] **Step 1: Write `gen_textures.py`**

Use only `zlib` and `struct` to write RGBA PNGs. For the entity texture, fill each table row's footprint with its color, add a 1-px darker edge on each face, and use the lighter body-top panel. The item texture (16×16) is a top-down X of dark arms, four light-gray prop discs, red front dots, and a transparent background. `icon.png` is the item texture upscaled ×8 (nearest neighbor) to 128×128. Output paths are relative to the script's parent directory.

- [ ] **Step 2: Generate and check**

Run: `python3 -I tools/gen_textures.py`. Then `file src/main/resources/assets/drone/textures/entity/drone.png` should report `64 x 64`, the item texture `16 x 16`, and the icon `128 x 128`. Open the item PNG with the Read tool and confirm it reads as a drone.

- [ ] **Step 3: JSON assets**

- `items/drone.json`: `{"model":{"type":"minecraft:model","model":"drone:item/drone"}}`
- `models/item/drone.json`: `{"parent":"minecraft:item/generated","textures":{"layer0":"drone:item/drone"}}`
- `lang/en_us.json`: `{"item.drone.drone":"Drone","entity.drone.drone":"Drone"}`

- [ ] **Step 4: Verify**

Run: `./gradlew build` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add mods/drone/tools mods/drone/src/main/resources/assets
git commit -m "feat(drone): generated textures, item model and names"
```

---

### Task 5: Drone exists — entity, item, rendering, placement

**Files:**
- Create: `src/main/java/io/palmisano/drone/registry/ModItems.java`, `ModEntities.java`, `entity/DroneEntity.java`, `item/DroneItem.java`
- Modify: `src/main/java/io/palmisano/drone/DroneMod.java`, `src/client/java/io/palmisano/drone/client/DroneClient.java`
- Create: `src/client/java/io/palmisano/drone/client/DroneRenderState.java`, `DroneModel.java`, `DroneRenderer.java`
- Create: `src/gametest/java/io/palmisano/drone/test/GameTestSupport.java`, `DronePlacementClientGameTest.java`; Modify: `src/gametest/resources/fabric.mod.json` (add entrypoint)

**Interfaces:**
- Consumes: `FlightModel`, `FlightState`, `DroneInput`, `FlightTuning.DEFAULT` (Tasks 2–3); the UV table (Task 4).
- Produces:
  - `ModEntities.DRONE`: `EntityType<DroneEntity>`, built as `EntityType.Builder.of(DroneEntity::new, MobCategory.MISC).sized(1.5f, 0.5f).passengerAttachments(0.5f).clientTrackingRange(10).build(key)`, with key `ResourceKey.create(Registries.ENTITY_TYPE, DroneMod.id("drone"))`. It also has `static void init()`.
  - `ModItems.DRONE`: `Item`, created as `new DroneItem(new Item.Properties().setId(key).stacksTo(1))`. It also has `static void init()`.
  - `DroneEntity(EntityType<? extends DroneEntity>, Level)`, `void setInput(DroneInput)`, `float getPitchTilt(float partialTick)`, `float getRollTilt(float partialTick)`, `float getPropAngle(float partialTick)`.
  - `GameTestSupport.flatArena`, `check`, `serverDrones`, `serverDronePos` (Step 1).
  - `DroneModel.LAYER = new ModelLayerLocation(DroneMod.id("drone"), "main")`, `static LayerDefinition createLayer()`.

- [ ] **Step 1: Write the failing gametest**

`GameTestSupport` holds helpers shared by both gametest classes:
- `static TestSingleplayerContext flatArena(ClientGameTestContext ctx)`: creates the world, then runs `gamemode creative @a`, `fill -10 99 -10 10 99 30 minecraft:stone`, `fill -10 100 -10 10 115 30 minecraft:air`, and `tp @a 0.5 100 0.5 0 0`, then `waitForChunksRender()`.
- `static void check(boolean ok, String msg)`: throws `AssertionError`.
- `static List<DroneEntity> serverDrones(TestSingleplayerContext sp, AABB box)`: uses `computeOnServer(s -> s.overworld().getEntitiesOfClass(DroneEntity.class, box))`. Use it only for counting.
- `static Vec3 serverDronePos(TestSingleplayerContext sp)`: the `position()` of the single drone in `new AABB(-10, 95, -10, 10, 120, 30)`, read inside `computeOnServer` (server entities must only be touched on the server thread).

`DronePlacementClientGameTest implements FabricClientGameTest` (register it in the gametest `fabric.mod.json`):
```java
// placeAndMount
try (var sp = GameTestSupport.flatArena(ctx)) {
    sp.getServer().runCommand("give @a drone:drone");
    ctx.runOnClient(mc -> { mc.player.getInventory().setSelectedSlot(0); mc.player.setXRot(35f); mc.player.setYRot(0f); });  // looks at floor block (0,99,2)
    ctx.waitTicks(2);
    ctx.getInput().pressKey(o -> o.keyUse);
    ctx.waitTicks(5);
    var drones = GameTestSupport.serverDrones(sp, new AABB(-3, 99, -1, 3, 102, 6));
    check(drones.size() == 1, "expected 1 drone, got " + drones.size());
    sp.getServer().runCommand("ride @p mount @e[type=drone:drone,limit=1]");
    ctx.waitTicks(5);
    check(ctx.computeOnClient(mc -> mc.player.getVehicle() instanceof DroneEntity), "player not riding");
    ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
    ctx.waitTicks(5);
    System.out.println("Screenshot: " + ctx.takeScreenshot("drone-parked"));
}
// noRoomNoDrone — Review Focus 4: 1-wide corridor at x=6
try (var sp = GameTestSupport.flatArena(ctx)) {
    sp.getServer().runCommand("fill 5 100 0 5 102 10 minecraft:stone");
    sp.getServer().runCommand("fill 7 100 0 7 102 10 minecraft:stone");
    sp.getServer().runCommand("tp @a 6.5 100 0.5 0 35");      // looks at floor block (6,99,2)
    sp.getServer().runCommand("give @a drone:drone");
    ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
    ctx.waitTicks(5);
    ctx.getInput().pressKey(o -> o.keyUse);
    ctx.waitTicks(5);
    check(GameTestSupport.serverDrones(sp, new AABB(4, 99, -1, 8, 103, 11)).isEmpty(), "drone spawned with no room");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew runClientGameTest` → compile failure (`DroneEntity` missing).

- [ ] **Step 3: Registration and item**

`DroneMod.onInitialize()` calls `ModItems.init()` and `ModEntities.init()`, then registers `CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(out -> out.accept(ModItems.DRONE))`. Register with `Registry.register(BuiltInRegistries.ITEM / ENTITY_TYPE, key, value)`.

`DroneItem.useOn(UseOnContext)`:
- On the client, return `InteractionResult.SUCCESS`.
- On the server, create via `ModEntities.DRONE.create(level, EntitySpawnReason.SPAWN_ITEM_USE)`.
- `snapTo` the bottom-center of `clickedPos.relative(clickedFace)` with yaw `ctx.getRotation()` and pitch 0.
- If `!level.noCollision(drone, drone.getBoundingBox())`, return `InteractionResult.FAIL`.
- Otherwise `addFreshEntity`, call `stack.consume(1, player)`, and return `SUCCESS`.

- [ ] **Step 4: `DroneEntity`**

Overrides:
- `getControllingPassenger()` returns the first passenger if it is a `Player`, otherwise `null`.
- `canAddPassenger(e)` returns `getPassengers().isEmpty()`.
- `interact(player, hand, location)`: `PASS` if `player.isSecondaryUseActive()`. On the server, return `player.startRiding(this) ? SUCCESS : PASS`. On the client, return `SUCCESS`.
- `getDropItem()` and `getPickResult()` use `ModItems.DRONE`.
- `isPickable()` returns `!isRemoved()`. `isPushable()` returns `false`. `getDefaultGravity()` returns `0.0`.
- `readAdditionalSaveData` / `addAdditionalSaveData` are empty; vanilla saves position and yaw.

Fields:
- `FlightModel flight` (DEFAULT) and `FlightState state = FlightState.hovering(0)`.
- `DroneInput input`.
- Previous-tick copies `pitchTiltO`, `rollTiltO`, `propAngleO`, plus `propAngle`. Getters lerp between previous and current by `partialTick`.

`tick()`:
```java
super.tick();
pitchTiltO = state.pitchTilt(); rollTiltO = state.rollTilt(); propAngleO = propAngle;
if (isLocalInstanceAuthoritative()) {
    DroneInput in = getControllingPassenger() != null && input != null ? input : DroneInput.hover(getYRot());
    state = flight.step(state.withYaw(getYRot()), in);          // withYaw: picks up loads and authority hand-overs
    Vec3 intended = new Vec3(state.vx(), state.vy(), state.vz());
    Vec3 before = position();
    move(MoverType.SELF, intended);
    Vec3 actual = position().subtract(before);
    state = flight.afterCollision(state, intended.x, intended.y, intended.z, actual.x, actual.y, actual.z);
    setYRot((float) state.yaw());
    setDeltaMovement(state.vx(), state.vy(), state.vz());
} else {                                                         // spec §5.1: visual tilt levels out
    state = new FlightState(0, 0, 0, getYRot(), state.pitchTilt() * 0.8, state.rollTilt() * 0.8);
    setDeltaMovement(Vec3.ZERO);
}
if (level().isClientSide()) propAngle += PROP_BASE + PROP_PER_TILT * (Math.abs(state.pitchTilt()) + Math.abs(state.rollTilt())) + PROP_PER_CLIMB * Math.max(0, state.vy());
```
Render-only constants: `PROP_BASE = 45` °/tick, `PROP_PER_TILT = 1.5`, `PROP_PER_CLIMB = 120`. When `propAngle` passes 36000, subtract 36000 from both `propAngle` and `propAngleO`.

Dismount: step off beside the drone if there's room, otherwise use vanilla's default (on top):
```java
@Override public Vec3 getDismountLocationForPassenger(LivingEntity p) {
    Vec3 e = getCollisionHorizontalEscapeVector(getBbWidth() * Mth.SQRT_OF_TWO, p.getBbWidth(), p.getYRot());
    Vec3 beside = new Vec3(getX() + e.x, getY(), getZ() + e.z);
    return DismountHelper.canDismountTo(level(), beside, p, Pose.STANDING) ? beside : super.getDismountLocationForPassenger(p);
}
```

- [ ] **Step 5: Model and renderer**

`DroneRenderState extends EntityRenderState` has public floats `yRot`, `pitchTilt`, `rollTilt`, `propAngle`.

`DroneModel extends EntityModel<DroneRenderState>`:
- `createLayer()` builds a `frame` part (`PartPose.ZERO`) containing every row of the Task 4 table, then `LayerDefinition.create(mesh, 64, 64)`.
- `setupAnim(state)` calls `super.setupAnim(state)`, then sets `frame.xRot = rad(pitchTilt)` (nose down) and `frame.zRot = −rad(rollTilt)` (right side down, since right = −X).
- Prop `yRot = ±rad(propAngle)`: `front_left` and `back_right` use +, `front_right` and `back_left` use − (counter-rotating pairs, like a real quad).

`DroneRenderer extends EntityRenderer<DroneEntity, DroneRenderState>`:
- Constructor bakes `DroneModel.LAYER` and sets `shadowRadius = 0.6f`.
- `createRenderState()` returns a new `DroneRenderState`.
- `extractRenderState(entity, state, pt)` calls `super`, then copies `getYRot(pt)` and the three getters.
- `submit(state, pose, collector, camera)`:
  - `pushPose`, then `translate(0, 0.25, 0)` (the body center is 4 px above the hitbox bottom)
  - `rotateDegrees(Axis.YP, 180 − state.yRot)`, then `scale(−1, −1, 1)`
  - `collector.submitModel(model, state, pose, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor)`
  - `popPose`, then `super.submit(...)`
- `TEXTURE = DroneMod.id("textures/entity/drone.png")`.

`DroneClient.onInitializeClient()` calls `ModelLayerRegistry.registerModelLayer(DroneModel.LAYER, DroneModel::createLayer)` and `EntityRendererRegistry.register(ModEntities.DRONE, DroneRenderer::new)`.

- [ ] **Step 6: Run the gametest and look at the screenshot**

Run: `./gradlew runClientGameTest` → `BUILD SUCCESSFUL`. Open `drone-parked.png` with the Read tool and check:
- the drone is visible under the player, with the X arms and four props
- the red motors face away from the camera (the player faces +Z, the same way as the drone)
- the player sits on top rather than inside it

If the player sits inside or too high, adjust `passengerAttachments`. If green leads, rotate the renderer's yaw by 180°. Re-run until the screenshot is correct.

- [ ] **Step 7: Commit**

```bash
git add mods/drone/src
git commit -m "feat(drone): drone entity, item placement, quadcopter model and renderer"
```

---

### Task 6: Drone flies — rider input and flight gametests

**Files:**
- Modify: `src/client/java/io/palmisano/drone/client/DroneClient.java`
- Create: `src/gametest/java/io/palmisano/drone/test/DroneFlightClientGameTest.java`; Modify: `src/gametest/resources/fabric.mod.json`
- Create: `mods/drone/CLAUDE.md`; Modify: `README.md` (workspace root; status → "Playable")

**Interfaces:**
- Consumes: `DroneEntity.setInput`, `getPitchTilt` (Task 5); `DroneInput.fromKeys` (Task 3); `GameTestSupport.flatArena`, `check`, `serverDronePos` (Task 5).

- [ ] **Step 1: Write the failing flight gametests**

`DroneFlightClientGameTest`. Each scenario uses `flatArena`, then `summon drone:drone 0 101 4`, then `ride @p mount @e[type=drone:drone,limit=1]`, then `runOnClient(mc -> mc.player.setYRot(0f))`. In the sketch below, `drone.getX/Y/Z()` and "drone pos" mean `GameTestSupport.serverDronePos(sp)` read at that moment, and `clientDrone` means the rider's vehicle read inside `computeOnClient`. Assertions:
```java
// flyForward: hold W 60 ticks
z0 = drone.getZ(); holdKey(o -> o.keyUp); waitTicks(30);
check(computeOnClient(mc -> ((DroneEntity) mc.player.getVehicle()).getPitchTilt(1f)) > 10, "no forward tilt");
takeScreenshot("drone-flying");               // third-person, as in Task 5
waitTicks(30); releaseKey(o -> o.keyUp); waitTicks(5);
check(drone.getZ() - z0 > 5, "moved " + (drone.getZ() - z0));      // simulated ≈ 13
check(Math.abs(drone.getX()) < 0.5, "drifted sideways");
// climbAndHold: hold Jump 40 ticks, release, wait 40
check(y1 - y0 > 4, "climbed " + (y1 - y0));                          // simulated ≈ 8.3
check(Math.abs(y3 - y2) < 0.05, "not holding altitude");             // y2 at +20 ticks, y3 at +40 ticks after release
// wallStopsDrone — Review Focus 2: fill -5 100 12 5 106 12 stone; hold W 100 ticks
check(drone.getZ() < 12 - 0.75 + 0.01, "passed the wall");
check(computeOnClient(mc -> mc.player.getVehicle().getDeltaMovement().z) == 0, "still pushing into wall");
// dismountMidAirHovers — Review Focus 1: fly 30 ticks forward, release, pressKey(o -> o.keyShift)
check(computeOnClient(mc -> mc.player.getVehicle() == null), "did not dismount");
waitTicks(80); p1 = drone pos; waitTicks(20); p2 = drone pos;
check(p1.distanceTo(p2) < 0.01, "drone drifting after dismount");
check(Math.abs(clientDrone.getPitchTilt(1f)) < 0.5, "tilt did not level out");
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew runClientGameTest` → `flyForward` fails with `no forward tilt` (no input reaches the drone yet).

- [ ] **Step 3: Wire rider input**

In `DroneClient.onInitializeClient()`, register `ClientTickEvents.END_CLIENT_TICK`. When `mc.player != null && mc.player.getVehicle() instanceof DroneEntity d`, read `Input k = mc.player.input.keyPresses` and call `d.setInput(DroneInput.fromKeys(k.forward(), k.backward(), k.left(), k.right(), k.jump(), k.sprint(), mc.player.getYRot()))`.

- [ ] **Step 4: Run the gametests and look at the screenshot**

Run: `./gradlew runClientGameTest` → all scenarios PASS, plus the Task 1 and Task 5 tests. Open `drone-flying.png` and check that the drone visibly leans nose-down in the direction of travel. If it leans backwards, flip the sign of `frame.xRot`.

- [ ] **Step 5: Docs**

`mods/drone/CLAUDE.md` (keep it short) covers:
- the architecture and data flow (spec §5.1 diagram)
- "tune feel only in `FlightTuning.DEFAULT`, then update `defaultTuningMatchesSpec` and spec §6.1"
- the frame conventions
- the input path (END_CLIENT_TICK → `setInput` → next authoritative tick)
- the prop render constants live in `DroneEntity`
- how to run tests and where screenshots land

In the root `README.md`, change the drone row's status to `Playable (v1)`.

- [ ] **Step 6: Full verification and commit**

Run: `./gradlew build && ./gradlew runClientGameTest` → both `BUILD SUCCESSFUL`.
```bash
git add mods/drone README.md
git commit -m "feat(drone): rider controls and in-game flight tests"
```

---

### Task 7: Play-test and tune with Frankie

**Files:**
- Modify (only if tuning changes): `src/main/java/io/palmisano/drone/flight/FlightTuning.java`, `FlightModelTest.defaultTuningMatchesSpec`, spec §6.1 table

- [ ] **Step 1: Hand over**

Ask Frankie to run `cd mods/drone && ./gradlew runClient`, make a creative world, grab the Drone from Tools & Utilities, and fly. Ask how it feels on top speed, braking drift, climb rate and turn speed.

- [ ] **Step 2: Tune**

Change only `FlightTuning.DEFAULT`. Then update `defaultTuningMatchesSpec` and the spec §6.1 table to the new values. Run `./gradlew build && ./gradlew runClientGameTest` → green. Repeat until Frankie is happy.

- [ ] **Step 3: Merge**

```bash
git commit -am "tune(drone): flight feel after play-test"   # only if something changed
git checkout main && git merge --ff-only drone-v1
```
Then offer, without doing it unasked, to install Fabric Loader, Fabric API and the drone jar into the real launcher for "Frankie's World" (root `CLAUDE.md` → "Installing a mod into the real game"; back up the world first).
