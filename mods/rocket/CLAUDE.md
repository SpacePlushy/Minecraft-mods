# Rocket mod — notes for Claude

Rideable rocket ship for Minecraft Java 26.3 (Fabric). Design: `docs/superpowers/specs/2026-10-19-rocket-mod-design.md`.
Plan: `docs/superpowers/plans/2026-10-19-rocket-mod-plan.md`. Read the root `CLAUDE.md` first. The drone
(`mods/drone`) has the same architecture; this mod copies its patterns rather than sharing code.

## Architecture

```
keyboard/mouse ──► RocketClient (END_CLIENT_TICK) ──► rocket.setInput(RocketInput.fromKeys(...))
                                                          │
                      RocketEntity.tick() on the authoritative side
                      (rider's client when ridden, server when unridden)
                                                          │
   RocketPhysics.step(state, input, onGround) ──► move(SELF) ──► RocketPhysics.afterCollision
                                                          │
     rider's client only: ExhaustPlanner.plan ──► vanilla particles · EngineEvent ──► vanilla sounds
                                                          │
                    vanilla vehicle-move packets sync position/yaw to the server (like boats)
```

| Where | What |
|---|---|
| `src/main/java/.../flight/` | **Pure Java**, no Minecraft imports. `RocketTuning` (all constants), `RocketState`, `RocketInput`, `Engine`, `Vec`, `RocketPhysics` (step, afterCollision, observe, nose), `ExhaustPlanner` + `Puff`, `EngineEvent`. Unit-tested. |
| `src/main/java/.../entity/RocketEntity.java` | The vehicle: authority branch, observer branch, collision, interpolation (3 steps), effects (particle mapping in `particle()`, sounds in `playEngineSounds`), dismount spot, no fall damage. |
| `src/main/java/.../item/RocketItem.java` | Stand on the clicked face, facing the player's look; fails with no room. |
| `src/main/java/.../registry/` | `ModEntities.ROCKET` (1.375 × 4.625 hitbox, rider at y 2.0, z −0.25), `ModItems.ROCKET`. |
| `src/client/java/.../client/` | `RocketClient` (renderer, layer, input tick), `RocketModel`, `RocketRenderer`, `RocketRenderState`. |
| `src/test/java/` | JUnit tests for the flight package; `Pad` is a test-only floor at altitude 0. |
| `src/gametest/java/` | Client gametests: smoke, placement (incl. no-room, cockpit view), flight (launch, abort, steer, engine cut, ceiling, dismount, hard landing). They save screenshots. |
| `tools/gen_textures.py` | Regenerates every PNG (`python3 -I tools/gen_textures.py`). Its `PARTS` table must match `RocketModel.createLayer()`. |

## Conventions that bite

- **Frames:** yaw 0 faces +Z; forward = (−sin yaw, cos yaw), right = (−cos yaw, −sin yaw). `pitchTilt > 0` tips the nose forward, `rollTilt > 0` tips it right. Thrust is along `RocketPhysics.nose(yaw, pitch, roll)`.
- **Engine:** `OFF` → (Space on the ground) `IGNITION` → 40 ticks → `BURNING`; releasing during ignition aborts; Space in the air lights `BURNING` at once; landed at idle for 20 ticks → `OFF`. `engineTicks` means "ticks in ignition" in `IGNITION` and "consecutive landed-idle ticks" in `BURNING`.
- **Ground:** `step(..., onGround)` zeroes a downward `vy` on the ground itself, so the rocket never sinks into the pad between collisions; tilt can't build up on the ground.
- **Model space:** pixels, Y down, origin at the **cabin center** (44 px above the base), which is also the tilt pivot so the seated rider stays inside a tilted rocket. Front = −Z = the window.
- **Rider inside the hitbox:** `canBePickedFromInside()` is false, or the rider's crosshair sticks on the rocket and a creative left-click removes it mid-flight. The model culls back faces (`RenderTypes.entityCutoutCull`), so the rider sees out of the cabin. `positionRider` turns the rider's body with the rocket so the seated legs stay inside.
- **Effects run only on the simulating client** (the rider's). The server and other players never know the engine state, so they see no flame or vapor (a spec non-goal).
- **Hazards:** like the drone, `applyEffectsFromBlocks()` is never called, so lava, fire, cactus and cobwebs do nothing.
- **Gametests:** wait on conditions (`waitUntilServerAcceptsClicks`, `waitForRocketInHand`, `ctx.waitFor`), never fixed tick counts before a click. Keep flights over the stone floor (x −10..10, z −10..30) or landings miss the pad.

## Tuning the feel

Change numbers **only** in `RocketTuning.DEFAULT`, then update `RocketPhysicsTest.defaultTuningMatchesSpec`
and the spec's §7.1 table. Several behavioral tests pin values simulated from the spec (liftoff tick, altitude
at tick 100, coast height); re-simulate and update them when tuning. Particle looks: `RocketEntity.particle()`.
Sounds: `RocketEntity.playEngineSounds()`.

## Commands (run in `mods/rocket/`)

| Command | Notes |
|---|---|
| `./gradlew test` | Unit tests, ~2 s |
| `./gradlew build` | Compile + unit tests + jar in `build/libs/rocket-1.0.0.jar` |
| `./gradlew runClientGameTest` | Opens a real game window (~1.5 min). Screenshots in `build/run/clientGameTest/screenshots/`. **Look at them.** |
| `./gradlew runClient` | Dev game for manual play. Creative world → Tools & Utilities tab → Rocket. |
