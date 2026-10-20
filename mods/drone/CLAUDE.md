# Drone mod — notes for Claude

Rideable quadcopter for Minecraft Java 26.3 (Fabric). Design: `docs/superpowers/specs/2026-10-19-drone-mod-design.md`.
Plan: `docs/superpowers/plans/2026-10-19-drone-mod-plan.md`. Read the root `CLAUDE.md` first.

## Architecture

```
keyboard/mouse ──► DroneClient (END_CLIENT_TICK) ──► drone.setInput(DroneInput.fromKeys(...))
                                                          │
                       DroneEntity.tick() on the authoritative side
                       (rider's client when ridden, server when unridden)
                                                          │
             FlightModel.step(state, input) ──► move(SELF, velocity) ──► FlightModel.afterCollision
                                                          │
                    vanilla vehicle-move packets sync position/yaw to the server (like boats)
```

| Where | What |
|---|---|
| `src/main/java/.../flight/` | **Pure Java physics**: no Minecraft imports. `FlightTuning` (all constants), `FlightState`, `DroneInput`, `FlightModel`. Unit-tested. |
| `src/main/java/.../entity/DroneEntity.java` | The vehicle: authority branch, collision, dismount spot, prop spin (render-only constants `PROP_*`). |
| `src/main/java/.../item/DroneItem.java` | Place on the clicked face; fails with no room. |
| `src/main/java/.../registry/` | `ModEntities.DRONE` (1.5 × 0.5 hitbox), `ModItems.DRONE`. |
| `src/client/java/.../client/` | `DroneClient` (renderer, model layer, input tick), `DroneModel`, `DroneRenderer`, `DroneRenderState`. |
| `src/test/java/` | JUnit tests for the flight package. |
| `src/gametest/java/` | Fabric client gametests: smoke, placement, flight. They save screenshots. |
| `tools/gen_textures.py` | Regenerates every PNG (`python3 -I tools/gen_textures.py`). UVs must match `DroneModel.createLayer()`. |

## Conventions that bite

- **Frames:** yaw 0 faces +Z. World forward = (−sin yaw, cos yaw); world right = (−cos yaw, −sin yaw). `pitchTilt > 0` = nose down, `rollTilt > 0` = right side down.
- **Model space:** pixels, Y down, origin at the body center, front = −Z, right = −X. Red motors are the front, green the back.
- **Input lag:** input set at END_CLIENT_TICK is used on the *next* authoritative tick, the same one-tick lag as vanilla boats.
- **Descend** uses the player's **Sprint** binding; **dismount** is vanilla Shift, decided server-side.
- **Records:** `FlightState` equality uses `Double.compare`, so `-0.0 != 0.0`. The settle step assigns a literal `0.0`.

## Tuning the feel

Change numbers **only** in `FlightTuning.DEFAULT`. Then update `FlightModelTest.defaultTuningMatchesSpec` and the spec's §6.1 table to match. Behavioral tests read values from `FlightTuning.DEFAULT`, so they follow along.

## Commands (run in `mods/drone/`)

| Command | Notes |
|---|---|
| `./gradlew test` | Unit tests, ~2 s |
| `./gradlew build` | Compile + unit tests + jar in `build/libs/drone-1.0.0.jar` |
| `./gradlew runClientGameTest` | Opens a real game window (~1 min). Needs a display. Screenshots land in `build/run/clientGameTest/screenshots/NNNN_<name>.png`. **Look at them** after visual changes. |
| `./gradlew runClient` | Dev game for manual play. Creative world → Tools & Utilities tab → Drone. |
