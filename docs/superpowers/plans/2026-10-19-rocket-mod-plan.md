# Rocket Mod Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Fabric mod for Minecraft 26.3 adding a rideable rocket ship: hold Space for a 2 s ignition with white vapor rolling out across the ground, then liftoff on thrust, steer by tilting, and fall back under gravity when the engine is cut. Creative-only.

**Architecture:** Same shape as the drone. All physics live in a pure-Java `flight` package (no Minecraft imports): an engine state machine, thrust along the nose, gravity, drag, tilt and yaw (`RocketPhysics`), plus a pure `ExhaustPlanner` that decides each tick's particles and an `EngineEvent` enum that drives sounds. A thin `RocketEntity` (extends `VehicleEntity`, client-authoritative while ridden) steps the model, moves with vanilla collision and turns puffs into vanilla particles. A client tick hook maps the rider's keys to `RocketInput`. Client gametests prove launch, steering, landing and placement, and save screenshots.

**Tech Stack:** Java 25, Gradle 9.7.1 wrapper, Fabric Loom 1.18-SNAPSHOT, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3 (incl. client gametest API), JUnit 6.1.3, Python 3 stdlib (textures).

**Spec:** `docs/superpowers/specs/2026-10-19-rocket-mod-design.md`: §7 is the per-tick order and engine state machine, §7.1 the tuning values, §8 the exhaust table and sounds, §9 the entity and model.

## Global Constraints

- Minecraft `26.3`, Fabric Loader `0.19.5`, Fabric API `0.164.0+26.3`, Loom `1.18-SNAPSHOT`, Gradle `9.7.1`, Java `25`, JUnit BOM `6.1.3`. No `mappings` line (Mojang names).
- Mod id `rocket`; package `io.palmisano.rocket`; author `Frankie Palmisano`; entity and item id `rocket:rocket`; display name `Rocket`; version `1.0.0`; gametest mod id `rocket-test`.
- Project root `mods/rocket/`; commands below run there. Gradle needs `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (set inline if the shell lacks it).
- `src/main` never imports `net.minecraft.client.*`. `io.palmisano.rocket.flight` imports nothing from Minecraft or Fabric.
- Every tunable flight number lives only in `RocketTuning.DEFAULT` with exactly the spec §7.1 values.
- Never touch `~/Library/Application Support/minecraft`. Test only via `test`, `build`, `runClientGameTest`.
- Gametests read **server-side** positions, and wait on conditions (`ctx.waitFor`, `GameTestSupport.waitUntilServerAcceptsClicks`), never a fixed tick count before a simulated click.
- Commit messages `feat(rocket): …` ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Branch `rocket-v1`.

## Review Focus

1. **Dismounting mid-flight with Space still held**: the rocket must not keep burning; it keeps its momentum, falls and lands. → Task 7 gametest `dismountMidAirFallsAndLands`.
2. **Launching under a ceiling**: it stops against the ceiling without jitter or passing through, and falls back when Space is released. → Task 7 gametest `ceilingStopsLaunch`.
3. **Unbounded player yaw** (725° after spinning): the rocket turns the short way. → Task 3 unit `yawHandlesUnwrappedPlayerYaw`.
4. **Placing where there's no room** (1-wide corridor, or a 3-block-high ceiling for a 4.6-block rocket): nothing spawns. → Task 6 gametest `noRoomNoRocket`.
5. **Hard landing from altitude** (engine cut high up, ~2 blocks/tick at impact): it stops on the ground, doesn't tunnel, and a survival rider takes no damage. → Task 7 gametest `hardLandingDoesNotHurtRider`.

---

## File Map (`mods/rocket/`)

| Path | Responsibility |
|---|---|
| `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Build (copied from `mods/drone`, names changed) |
| `CLAUDE.md`, `README.md`, `docs/*.png` | Notes for Claude; player-facing readme with gametest screenshots |
| `tools/gen_textures.py` | Regenerates every PNG (stdlib only) |
| `src/main/java/io/palmisano/rocket/RocketMod.java` | `ModInitializer`: registries, creative tab |
| `.../rocket/registry/ModItems.java`, `ModEntities.java` | Registration |
| `.../rocket/flight/RocketTuning.java`, `Engine.java`, `RocketState.java`, `RocketInput.java`, `Vec.java`, `RocketPhysics.java` | Pure physics |
| `.../rocket/flight/Puff.java`, `ExhaustPlanner.java`, `EngineEvent.java` | Pure exhaust and engine events |
| `.../rocket/entity/RocketEntity.java` | The vehicle |
| `.../rocket/item/RocketItem.java` | Place on block |
| `src/main/resources/fabric.mod.json`, `assets/rocket/{icon.png, lang/en_us.json, items/rocket.json, models/item/rocket.json, textures/item/rocket.png, textures/entity/rocket.png}` | Metadata, assets |
| `src/client/java/io/palmisano/rocket/client/RocketClient.java`, `RocketRenderState.java`, `RocketModel.java`, `RocketRenderer.java` | Client |
| `src/test/java/io/palmisano/rocket/flight/RocketPhysicsTest.java`, `RocketInputTest.java`, `ExhaustPlannerTest.java`, `EngineEventTest.java`, `Pad.java` | Unit tests; `Pad` is the test-only ground harness |
| `src/gametest/java/io/palmisano/rocket/test/{GameTestSupport, SmokeClientGameTest, RocketPlacementClientGameTest, RocketFlightClientGameTest}.java`, `src/gametest/resources/fabric.mod.json` | Client gametests |

---

### Task 1: Scaffold the rocket project

**Files:** everything under Build in the file map; `RocketMod.java`, `RocketClient.java`, `fabric.mod.json`; `SmokeClientGameTest.java`, `GameTestSupport.java`, gametest `fabric.mod.json`.

**Interfaces:**
- Produces: `RocketMod.MOD_ID = "rocket"`, `RocketMod.id(String path) → Identifier`; `RocketClient implements ClientModInitializer`; `GameTestSupport` (package-private, in `io.palmisano.rocket.test`) with `flatArena(ClientGameTestContext) → TestSingleplayerContext`, `check(boolean, String)`, `waitUntilServerAcceptsClicks(ctx, sp, double x, double z)`. Arena exactly as the drone's (stone floor at y 99; superflat leaves the sky empty; a taller air `fill` would exceed vanilla's 32768-block command limit), player at `(0.5, 100, 0.5)` facing +Z, creative.

- [ ] **Step 1: Copy the build** from `mods/drone`: `settings.gradle` (`rootProject.name = 'rocket'`), `gradle.properties` (unchanged), `build.gradle` (mod name `"rocket"` in `loom.mods`, `modId = "rocket-test"`), `gradlew`, `gradlew.bat`, `gradle/wrapper/*`. Verify `shasum -a 256 gradle/wrapper/gradle-wrapper.jar` is `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` (the drone's verified 9.7.1 jar).
- [ ] **Step 2: Metadata and entrypoints** as the drone's with `id` `rocket`, `name` `Rocket`, `description` `A rideable rocket ship: hold Space to ignite, launch on a cloud of vapor, and steer it through the sky.`, `icon` `assets/rocket/icon.png`, entrypoints `io.palmisano.rocket.RocketMod` / `io.palmisano.rocket.client.RocketClient` (both empty for now). Gametest `fabric.mod.json`: `id` `rocket-test`, entrypoint `fabric-client-gametest` `["io.palmisano.rocket.test.SmokeClientGameTest"]`, `depends` `{"rocket": "*"}`.
- [ ] **Step 3: Gametest support.** Copy the drone's `GameTestSupport` (incl. `waitUntilServerAcceptsClicks`), change the package, and drop the drone-specific helpers. `SmokeClientGameTest`: `flatArena`, screenshot `rocket-smoke`, print the path.
- [ ] **Step 4: Verify.** `./gradlew build` → `BUILD SUCCESSFUL`. `./gradlew runClientGameTest` → `BUILD SUCCESSFUL`, `rocket-smoke.png` printed. Look at it (flat stone arena).
- [ ] **Step 5: Commit** `feat(rocket): scaffold Fabric 26.3 project with smoke client gametest`.

---

### Task 2: Engine, throttle, thrust and gravity

**Files:** Create `flight/RocketTuning.java`, `Engine.java`, `RocketState.java`, `RocketInput.java`, `Vec.java`, `RocketPhysics.java`; tests `RocketPhysicsTest.java`, `Pad.java`.

**Interfaces:**
- Produces:
  - `record RocketTuning(double gravity, double maxThrust, double drag, double throttleResponse, int ignitionTicks, int shutdownTicks, double maxTilt, double tiltResponse, double yawResponse, double maxYawRate, double groundFriction, double settleSpeed, double vaporHeight)`, `static final RocketTuning DEFAULT = new RocketTuning(0.04, 0.065, 0.02, 0.10, 40, 20, 20, 0.08, 0.20, 5, 0.5, 0.003, 8)`.
  - `enum Engine { OFF, IGNITION, BURNING }`.
  - `record RocketState(double vx, double vy, double vz, double yaw, double pitchTilt, double rollTilt, double throttle, Engine engine, int engineTicks)`; `static RocketState resting(double yaw)` (all zero, `OFF`); `RocketState withYaw(double yaw)`. `engineTicks`: in `IGNITION` the ticks since it started (0 on the starting tick); in `BURNING` the consecutive ticks landed at idle; 0 when `OFF`.
  - `record RocketInput(double forward, double strafe, boolean throttleUp, double targetYaw)` (forward/strafe clamped to [−1, 1]); `static RocketInput idle(double yaw)`.
  - `record Vec(double x, double y, double z)`.
  - `final class RocketPhysics(RocketTuning)`: `RocketState step(RocketState s, RocketInput in, boolean onGround)`; `static Vec nose(double yaw, double pitchTilt, double rollTilt)` (unit vector, spec §7 step 5).
- Test harness `Pad` (test source set): tracks altitude `y` above a floor at 0; `Pad.fly(RocketPhysics m, RocketState s, RocketInput in, int ticks)` steps with `onGround = (y == 0)`, then moves `y += vy` clipped at 0, zeroing `vy` when clipped; exposes `y`, `state`, and `liftoffTick` (first tick `y > 0.01`, else −1).

- [ ] **Step 1: Write the failing tests** in `RocketPhysicsTest` (expected values are hand-derived or from the spec's simulation):
  - `defaultTuningMatchesSpec`: every §7.1 value (`gravity 0.04`, `maxThrust 0.065`, `drag 0.02`, `throttleResponse 0.10`, `ignitionTicks 40`, `shutdownTicks 20`, `maxTilt 20`, `tiltResponse 0.08`, `yawResponse 0.20`, `maxYawRate 5`, `groundFriction 0.5`, `settleSpeed 0.003`, `vaporHeight 8`).
  - `restingWithNoInputStaysPut`: 100 steps of `idle(0)` on the ground → equals `resting(0)`.
  - `ignitionLastsIgnitionTicksThenBurns`: Space held on the ground: steps 1–40 → `IGNITION`, `throttle == 0`, `vy <= 0`; step 41 → `BURNING`.
  - `releasingDuringIgnitionAborts`: 10 steps held, then 1 released → `OFF`; then 1 held → `IGNITION` with `engineTicks == 0`.
  - `launchClimbsToTerminal` (via `Pad`): Space held from rest: `liftoffTick` in [52, 54]; `y` at tick 100 in (16, 19); after 400 ticks `vy` = 1.225 ± 0.01 (= (0.065 − 0.04)·0.98/0.02).
  - `releaseCoastsUpThenFalls`: airborne `BURNING`, `throttle 1`, `vy 1.0`, released: altitude gain before falling in (15, 19); `vy < 0` within 30 ticks.
  - `freeFallConvergesToGravityTerminal`: airborne `OFF`, 600 steps → `vy` = −1.96 ± 0.01.
  - `midAirRelightIsInstant`: airborne `OFF`, one step with Space → `BURNING`.
  - `landedIdleShutsDown`: `BURNING`, `throttle 0`, on the ground, released: after 19 steps `BURNING`, after 20 `OFF`.
  - `noseIsUnitAndFollowsTilt`: `nose(0, 0, 0)` = (0, 1, 0); `nose(0, 20, 0)`: z = sin 20° = 0.342, x = 0, length 1; `nose(90, 20, 0)`: x = −0.342.
- [ ] **Step 2: Run** `./gradlew test` → compile failure, then (after stubs) these tests FAIL.
- [ ] **Step 3: Implement** the records and `RocketPhysics.step` steps 3–7 of spec §7 (engine state machine, throttle, thrust along `nose`, gravity, drag) plus the ground rule: when `onGround`, `vy = max(vy, 0)` after drag, so a rocket on the pad never sinks between collisions. Yaw and tilt stay unchanged in this task (Task 3).
- [ ] **Step 4: Run** `./gradlew test` → all PASS.
- [ ] **Step 5: Commit** `feat(rocket): pure flight model with engine ignition, throttle, thrust and gravity`.

---

### Task 3: Yaw, tilt, ground friction, collision, observer and key mapping

**Files:** Modify `RocketPhysics.java`, `RocketInput.java`; tests `RocketPhysicsTest.java`, `RocketInputTest.java`.

**Interfaces:**
- Consumes: Task 2 types.
- Produces: `RocketPhysics.afterCollision(RocketState s, double intendedX, double intendedY, double intendedZ, double actualX, double actualY, double actualZ) → RocketState`; `RocketPhysics.observe(RocketState s, double dx, double dy, double dz, double yaw) → RocketState`; `RocketInput.fromKeys(boolean forward, boolean back, boolean left, boolean right, boolean up, double lookYaw)`.

- [ ] **Step 1: Write the failing tests:**
  - `yawIsRateLimited`: from `resting(0)`, target 170 → yaw 5. `yawTakesShortestPath`: 350 → target 10 → yaw 354 (= 350 + 20·0.2). `yawHandlesUnwrappedPlayerYaw` (Review Focus 3): target 725 from 0 → first step +1.0; converges to 5 mod 360 within 100 steps.
  - `tiltFollowsKeysAndSelfRights`: airborne, `forward 1` for 100 steps → `pitchTilt` = 20 ± 0.1; then 100 steps of 0 → |pitchTilt| < 0.1. Same for `strafe` → `rollTilt`.
  - `noTiltOnTheGround`: on the ground in `IGNITION` with `forward 1` → `pitchTilt` stays 0.
  - `tiltedThrustPushesAlongHeading`: airborne, `BURNING`, Space held, `forward 1`, yaw 0, 200 steps → `vz > 0.5`, |vx| < 1e-9; yaw 90 → `vx < −0.5`.
  - `groundFrictionStopsSliding`: on the ground, `vx 0.5`, `idle` → after 20 steps `vx == 0.0` exactly.
  - `landingStopsTheFall` (via `Pad`): `OFF` at y 20 → ends at y 0, `vy == 0`.
  - `afterCollisionZeroesOnlyBlockedAxes`: intended (0.3, 0.1, 0.2), actual (0, 0.1, 0.2) → vx 0, vy 0.1, vz 0.2, tilt untouched.
  - `observeTracksSeenMovement`: 20 observations of dz 0.3 → vz 0.3 ± 1e-4; `observeRidesOutAMissedPacket`: then one of 0 → vz > 0.1; `observeTreatsTeleportAsRest`: one of dz 50 → vz 0; `observeMeansEngineOff`: a `BURNING` state observed → `OFF`, throttle 0, tilt shrinks toward 0.
  - `RocketInputTest`: `fromKeysMapsEachKey` (W → forward 1, S → −1, D → strafe 1, A → −1, Space → throttleUp, lookYaw → targetYaw); `opposingKeysCancel` (W+S → 0, A+D → 0); `constructorClamps` (2 → 1, −3 → −1); `idleHasNoInput`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** spec §7 steps 1, 2 and 8. `afterCollision` as the drone's (axis blocked when |actual − intended| > 1e-7). `observe` as the drone's `FlightModel.observe` (private constants: smoothing 0.5, level-out factor 0.8, teleport when the move is longer than 8 blocks), plus engine `OFF`, throttle 0, `engineTicks` 0.
- [ ] **Step 4: Run** → all PASS.
- [ ] **Step 5: Commit** `feat(rocket): heading, tilt, ground friction, collision, observer and key mapping`.

---

### Task 4: Exhaust planner and engine events

**Files:** Create `flight/Puff.java`, `ExhaustPlanner.java`, `EngineEvent.java`; tests `ExhaustPlannerTest.java`, `EngineEventTest.java`.

**Interfaces:**
- Consumes: `RocketState`, `RocketTuning`, `RocketPhysics.nose`.
- Produces:
  - `record Puff(Kind kind, double dx, double dy, double dz, double vx, double vy, double vz)` with nested `enum Kind { VAPOR, BILLOW, FLAME, TRAIL }`. Offsets from the nozzle (the rocket's base center), world frame.
  - `final class ExhaustPlanner(RocketTuning)`: `List<Puff> plan(RocketState s, double nozzleHeight, RandomGenerator random)`; `nozzleHeight` = `Double.POSITIVE_INFINITY` when no ground within 16 blocks.
  - `enum EngineEvent { IGNITION_STARTED, LIFTOFF, ABORTED, SHUTDOWN }` with `static Optional<EngineEvent> between(Engine before, Engine after)`.

- [ ] **Step 1: Write the failing tests** (fixed seed: `new java.util.Random(42)`):
  - `offEmitsNothing`.
  - `ignitionEmitsVaporAndBillow`: counts VAPOR 6, BILLOW 2, FLAME 0, TRAIL 0.
  - `vaporRollsOutAlongTheGround`: ignition, `nozzleHeight 0`: every VAPOR has `dy == 0.1`, horizontal speed in [0.25, 0.5], points away from the axis (`dx·vx + dz·vz > 0`), and `vy` in [0.02, 0.05].
  - `flameCountFollowsThrottle`: `BURNING`, height ∞: throttle 0 → 1 FLAME, 0 TRAIL; throttle 1 → 6 FLAME, 3 TRAIL.
  - `noVaporHighUp`: throttle 1, height 20 → 0 VAPOR, 0 BILLOW.
  - `vaporNearTheGroundFadesWithHeight`: throttle 1: height 0 → 8 VAPOR + 1 BILLOW; height 4 → 4 VAPOR; throttle 0.2 at height 0 → 0 VAPOR. Vapor `dy` = −height + 0.1.
  - `flameShootsOppositeTheNose`: upright → every FLAME `vy < 0`; yaw 0, pitchTilt 20 → mean FLAME `vz < 0`.
  - `EngineEventTest`: OFF→IGNITION `IGNITION_STARTED`; IGNITION→BURNING and OFF→BURNING `LIFTOFF`; IGNITION→OFF `ABORTED`; BURNING→OFF `SHUTDOWN`; X→X empty.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** spec §8's table. Vapor: random angle, start 0.7 blocks out from the axis at `dy = −height + 0.1`, speed 0.25–0.5 outward, `vy` 0.02–0.05. Billow: within ±0.5 horizontally at `−height + 0.2`, drift ±0.03, `vy` 0.02. Flame: jitter ±0.15 around the nozzle, velocity `−nose · (0.3–0.5)` ± 0.05. Trail: jitter ±0.2, velocity `−nose · (0.1–0.2)`. Counts use `Math.round`.
- [ ] **Step 4: Run** → all PASS.
- [ ] **Step 5: Commit** `feat(rocket): exhaust planner for launch vapor, flame and smoke; engine events`.

---

### Task 5: Textures, item model and names

**Files:** Create `tools/gen_textures.py` and its outputs `textures/entity/rocket.png` (128×128), `textures/item/rocket.png` (16×16), `icon.png` (128×128, the item art scaled 8×); `items/rocket.json`, `models/item/rocket.json`, `lang/en_us.json`.

**Interfaces:**
- Produces: the UV layout `RocketModel` uses (Task 6). Parts, in pixels (W × H × D), with their texture origin chosen by the script and documented in its header: `nozzle` 12×6×12 (gray), `body` 22×26×22 (white, red band at the top), `cabin` 22×24×22 (white, blue 12×10 window on the front face), `nose1` 16×8×16, `nose2` 10×6×10, `nose3` 4×4×4 (red), `fin` 8×14×2 (red, one texture for all four).
- Lang: `item.rocket.rocket` and `entity.rocket.rocket` = `Rocket`. Item definition and model as the drone's (`minecraft:item/generated`, layer0 `rocket:item/rocket`).

- [ ] **Step 1: Write** `gen_textures.py` reusing the drone script's `Image`, `rgb`, `darker` and `box_faces` helpers; each face gets its color with a darker 1 px edge. Item art: a vertical red-nosed white rocket with fins and a flame.
- [ ] **Step 2: Run** `python3 -I tools/gen_textures.py`; verify sizes with `file` (128×128, 16×16, 128×128).
- [ ] **Step 3: Verify** `./gradlew build` → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit** `feat(rocket): generated textures, item model and names`.

---

### Task 6: Rocket exists: entity, item, rendering, placement

**Files:** Create `entity/RocketEntity.java`, `item/RocketItem.java`, `registry/ModEntities.java`, `ModItems.java`; client `RocketRenderState.java`, `RocketModel.java`, `RocketRenderer.java`; modify `RocketMod.java`, `RocketClient.java`; gametest `RocketPlacementClientGameTest.java` (+ entrypoint).

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces: `ModEntities.ROCKET` (`EntityType<RocketEntity>`, `sized(1.375f, 4.625f)`, `passengerAttachments(new Vec3(0, 2.0, -0.25))`, `clientTrackingRange(10)`); `ModItems.ROCKET` (stacks to 1); `RocketEntity.setInput(RocketInput)`, `getPitchTilt(float partialTick)`, `getRollTilt(float)`; `RocketModel.LAYER`. In `GameTestSupport`: `ARENA = new AABB(-10, 95, -10, 10, 320, 30)`, `serverRockets(sp, AABB) → List<RocketEntity>`, `serverRocketPos(sp) → Vec3` (asserts exactly one in `ARENA`), `waitForRocketInHand(ctx)`.

Entity behavior in this task (spec §6.1, §9): `getDefaultGravity() = 0` (physics owns gravity); `isLocalInstanceAuthoritative()` → `step(state.withYaw(getYRot()), in, onGround())`, `move(MoverType.SELF, v)`, `afterCollision`, `setYRot`, `setDeltaMovement`; otherwise `observe` from the movement seen since last tick, `setDeltaMovement(ZERO)`. Input = rider's latest input if ridden, else `RocketInput.idle(getYRot())`. Interpolation handler `LinearInterpolationHandler.create(this, 3)`. `causeFallDamage` → false. Never calls `applyEffectsFromBlocks()`. One passenger (a `Player` controls it). Interact → `startRiding`. Dismount beside when there's room (copy the drone's `getDismountLocationForPassenger`). `positionRider` also sets a living passenger's `yBodyRot` to the rocket's yaw, so the seated legs stay inside the cabin. Drop/pick item `ModItems.ROCKET`. Empty save data. `RocketItem` copies `DroneItem`.

Model (spec §9): origin at the cabin center (2.75 blocks above the base), pixels, Y down, front = −Z. Root part `rocket` holds: `nozzle` box at y 38..44; `body` 12..38; `cabin` −12..12; `nose1` −20..−12; `nose2` −26..−20; `nose3` −30..−26; four `fin`s spanning y 24..38, from 11 to 19 px out on ±X and ±Z. `setupAnim`: `rocket.xRot = pitchTilt` (nose to −Z, i.e. forward), `rocket.zRot = −rollTilt`. Renderer: `translate(0, 2.75, 0)`, rotate `180 − yRot` about Y, `scale(−1, −1, 1)`, submit; shadow radius 0.8.

- [ ] **Step 1: Write the failing gametest** `RocketPlacementClientGameTest` (in `fabric.mod.json`):
  - `placeAndMount`: `give`, look at floor block (0, 99, 2) (pitch 35), `waitForRocketInHand`, Use → exactly 1 server rocket in the arena; `ride @p mount` → player riding; third-person screenshots `rocket-parked` and side view `rocket-parked-side`.
  - `noRoomNoRocket` (Review Focus 4): stone walls at x 5 and x 7 (y 100–106) → corridor x 6: Use on (6, 99, 2) → no rocket. Then a 3-high ceiling (`fill -3 103 3 3 103 8 stone`), Use on (0, 99, 5) → no rocket. Wait with `waitUntilServerAcceptsClicks` and `waitForRocketInHand` before each Use.
  - `showcase`: three unridden rockets at yaw 0, 90, 180 a few blocks ahead; screenshot `rocket-showcase` with the HUD hidden.
- [ ] **Step 2: Run** `./gradlew runClientGameTest` → FAIL (no `rocket:rocket` item).
- [ ] **Step 3: Implement** the entity, item, registration (Tools & Utilities tab), model, renderer, and register the layer and renderer in `RocketClient`.
- [ ] **Step 4: Run** `./gradlew build && ./gradlew runClientGameTest` → PASS. **Look at every screenshot:** an upright white rocket with a red nose and fins, a blue window facing the camera for yaw 180, the rider hidden inside the cabin, fins at the base.
- [ ] **Step 5: Commit** `feat(rocket): rocket entity, placement item, model and renderer`.

---

### Task 7: Launch: rider controls, vapor, sounds and flight tests

**Files:** Modify `RocketClient.java`, `RocketEntity.java`; gametest `RocketFlightClientGameTest.java` (+ entrypoint); create `CLAUDE.md`, `README.md`, `docs/` screenshots; modify the root `README.md` (mod row) and root `CLAUDE.md` (status).

**Interfaces:**
- Consumes: Tasks 2–6.
- Produces: the playable mod.

Client input: END_CLIENT_TICK, when the local player rides a `RocketEntity`: `setInput(RocketInput.fromKeys(forward, backward, left, right, jump, player.getYRot()))` from `player.input.keyPresses`. Effects in `RocketEntity.tick()` on the client that simulates (`level().isClientSide() && isLocalInstanceAuthoritative()`): `ExhaustPlanner.plan(state, nozzleHeight, random)` → `level().addParticle` at the base position + offset (`VAPOR`→`CLOUD`, `BILLOW`→`CAMPFIRE_COSY_SMOKE`, `FLAME`→`FLAME`, `TRAIL`→`LARGE_SMOKE`); `nozzleHeight` = distance from the base down to the first collision block within 16 (`level().clip`, else ∞). Sounds per spec §8 via `level().playLocalSound(...)` on `EngineEvent.between(before.engine(), after.engine())` and the ignition/burn cadences (ignition: `engineTicks % 10 == 0`; burning: `tickCount % 8 == 0`).

- [ ] **Step 1: Write the failing gametests** in `RocketFlightClientGameTest` (rocket summoned on the floor at (0, 100, 4), player mounted, yaw 0; positions read server-side):
  - `launchWaitsForIgnitionThenClimbs`: hold Jump; at +30 ticks the rocket is still at y 100 (third-person screenshot `rocket-ignition`: vapor must be visible); at +90 ticks y > 103; screenshot `rocket-flying`; keep holding to +160 → y > 120.
  - `abortDuringIgnition`: hold Jump 20 ticks, release, wait 60 → y still 100.
  - `steeringMovesForward`: launch, hold Jump + W for 60 ticks after liftoff → z increased by > 3 and screenshot `rocket-tilted-side` from the side.
  - `engineCutFallsAndLands`: launch to y > 115, release, wait for the rocket to stop: y back at 100 and still at +20 ticks.
  - `ceilingStopsLaunch` (Review Focus 2): stone ceiling `fill -2 110 2 2 110 6 stone` over the pad; hold Jump 160 ticks → rocket top (y + 4.625) ≤ 110 + 1e-3 every 10 ticks; release → back at y 100.
  - `dismountMidAirFallsAndLands` (Review Focus 1): launch to y > 115 with Jump held, hold Shift (keep Jump held) until dismounted → server rocket lands at y 100 within 200 ticks.
  - `hardLandingDoesNotHurtRider` (Review Focus 5): `gamemode survival`, rocket summoned at y 150, rider mounted, no input → lands at y 100; player health still 20. Restore creative.
- [ ] **Step 2: Run** → FAIL (no input mapping: the rocket never ignites).
- [ ] **Step 3: Implement** the client input hook, particles and sounds.
- [ ] **Step 4: Run** `./gradlew build && ./gradlew runClientGameTest` → PASS. **Look at the screenshots:** white clouds rolling out at the base during ignition; flame and smoke below the rocket in flight; visible tilt in the side view. If a particle reads badly, swap its mapping (ledger it).
- [ ] **Step 5: Docs.** `mods/rocket/CLAUDE.md` (architecture table, frames, engine state machine, effects mapping, tuning rule, commands, gotchas inherited from the drone), `mods/rocket/README.md` (what it is, controls, how to run, two screenshots copied to `mods/rocket/docs/`), a `Rocket` row in the root `README.md`, root `CLAUDE.md` status (rocket: ready for Frankie's play-test).
- [ ] **Step 6: Commit** `feat(rocket): rider controls, launch vapor, exhaust, sounds and flight tests` (+ `docs(rocket): …`).

---

### Task 8: Play-test and tune with Frankie

- [ ] **Step 1:** Frankie runs `cd mods/rocket && ./gradlew runClient`, makes a creative world, takes the Rocket from Tools & Utilities, and launches. Ask one question at a time: ignition length, liftoff/climb speed, how hard it is to land, steering, the vapor and sounds.
- [ ] **Step 2:** Tune only `RocketTuning.DEFAULT` (and the particle/sound mapping for looks), keeping `defaultTuningMatchesSpec` and spec §7.1 in sync. `./gradlew build && ./gradlew runClientGameTest` green.
- [ ] **Step 3:** superpowers:finishing-a-development-branch: merge `rocket-v1` into `main`, push.
