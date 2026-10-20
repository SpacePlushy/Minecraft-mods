# Drone Mod — Design Spec

- **Date:** 2026-10-19
- **Status:** Approved in brainstorming; awaiting written-spec review
- **Mod:** `mods/drone/` · mod id `drone` · package `io.palmisano.drone`
- **Target:** Minecraft Java 26.3 · Fabric Loader 0.19.5 · Fabric API `0.164.0+26.3` · Java 25

## 1. Intent

A **rideable quadcopter drone** that the player sits on and flies around the world, with the
handling of a **real consumer camera drone (DJI-style)**: it tilts into moves, builds momentum,
drifts and overshoots, actively brakes, holds altitude and self-levels when the keys are released.

- **For:** Frankie, playing single-player (including the existing world "Frankie's World").
- **Mode:** Creative only — no recipe, no fuel, no damage.
- **Success:** In a world, take the Drone from the creative inventory, place it, hop on, and fly it
  smoothly around the landscape with the feel described below.

This is the first mod in a multi-mod workspace (see §9).

## 2. Decisions (with reasons)

| Decision | Choice | Why |
|---|---|---|
| Drone style | Rideable (player sits on it) | Chosen by user over remote-control FPV |
| Game mode | Creative only | Chosen by user; removes recipe/fuel/damage scope |
| Flight feel | Realistic, DJI camera-drone mode | Chosen by user; realistic yet flyable on keyboard |
| Crashes | Bump and stop, never breaks | Chosen by user |
| Loader | Fabric | Lightweight, fastest iteration, best docs for a small mod |
| Workspace | One self-contained Gradle project per mod | Mods can move MC versions independently; no premature shared build |
| Sync model | Client-authoritative vehicle, like vanilla boats | Zero input lag; uses vanilla vehicle-move packets, no custom networking |

## 3. Non-goals (v1)

Recipes, fuel, durability/damage, multiplayer polish (other players see it move but not tilt),
FPV/remote camera, sounds, HUD (speed/altitude), variants/colors, custom creative tab, config file
or in-game settings screen. Each is a reasonable follow-up, none is needed for success.

## 4. Controls

| Input | Effect |
|---|---|
| Mouse look | Drone heading eases toward where the rider looks (max 200°/s) |
| W / S | Tilt nose down / up → accelerate forward / backward |
| A / D | Tilt left / right → strafe left / right |
| Space (Jump key) | Climb |
| Sprint key (Left Ctrl by default) | Descend — reuses the Sprint binding so there is no key conflict; rebinding Sprint rebinds descend |
| Sneak (Shift) | Dismount (vanilla behavior) |
| Right-click drone | Mount |
| Attack drone | Remove it (creative behavior, like boats) |

Any keybinding the user changes in Controls is respected, because input is read through the
player's key mappings, not raw keys.

## 5. Architecture

Each unit has one job and a narrow interface. Shared (`main` source set) code must never reference
client classes; client code lives in the `client` source set (Loom `splitEnvironmentSourceSets`).

| Unit | Source set | Responsibility | Depends on |
|---|---|---|---|
| `FlightTuning` | main | Immutable record of every tunable constant (§6.1), with a `DEFAULT` instance | nothing |
| `DroneInput` | main | Record: `forward`, `strafe`, `vertical` ∈ [−1, 1] and `targetYaw` (degrees). Factory `hover(yaw)` = no stick input, hold heading `yaw` | nothing |
| `FlightState` | main | Record: velocity `vx, vy, vz` (blocks/tick, world frame), `yaw` (deg), `pitchTilt`, `rollTilt` (deg, drone-local) | nothing |
| `FlightModel` | main | **Pure Java**, no Minecraft imports. `step(state, input) → state` (one tick of physics) and `afterCollision(state, intended, actual) → state` | the three records above |
| `DroneEntity` | main | Extends vanilla `VehicleEntity`. One passenger. Holds `FlightState` + latest `DroneInput`; each tick on the authoritative side runs `FlightModel.step`, calls `move(MoverType.SELF, …)`, then `afterCollision`. Mounting, dismount position, save/load | `FlightModel`, vanilla |
| `DroneItem` | main | Use-on-block → spawn a `DroneEntity` on top of the clicked face, facing the player's yaw | `ModEntities` |
| `ModEntities`, `ModItems`, `DroneMod` | main | Registration (entity type, item, Tools & Utilities creative-tab entry) and the `ModInitializer` | Fabric API |
| `DroneClient` | client | `ClientModInitializer`: registers renderer + model layer; on client tick, if the local player rides a `DroneEntity`, builds a `DroneInput` from the player's key mappings and look yaw and calls `drone.setInput(…)` | Fabric API, `DroneEntity` |
| `DroneRenderState`, `DroneModel`, `DroneRenderer` | client | Render-state snapshot (interpolated yaw, tilts, prop angle), blocky quadcopter model, renderer applying tilt + prop spin | vanilla rendering |

### 5.1 Data flow (one tick, rider present)

```
keyboard/mouse ──► DroneClient (client tick) ──► drone.setInput(DroneInput)
                                                      │
                     DroneEntity.tick() on the rider's client (authoritative)
                                                      │
              FlightModel.step(state, input) ──► move(SELF, velocity) ──► afterCollision
                                                      │
                     vanilla vehicle-move packet ──► integrated/dedicated server
```

- **Ridden:** the rider's client is authoritative (exactly like boats); the server accepts the
  vanilla vehicle-move packets and does not simulate.
- **Unridden:** the server is authoritative and steps the same `FlightModel` with `DroneInput.hover(currentYaw)`,
  so the drone brakes and hovers where it was left. Clients just interpolate the synced position.
- **Visual tilt on non-authoritative instances** (e.g. the client view of an unridden drone) eases
  toward 0° each tick, so a drone you hopped off visibly levels out instead of freezing mid-lean.

## 6. Flight model

Runs at 20 ticks/s. Velocity is in blocks/tick in the world frame; tilt is in degrees in the
drone's local frame (`pitchTilt` > 0 = nose down = accelerating forward; `rollTilt` > 0 = right side
down = accelerating right). Local ↔ world conversion uses the drone's `yaw` with Minecraft's
convention (yaw 0 faces +Z; forward = (−sin yaw, 0, cos yaw)).

Per tick, in order:

1. **Yaw.** `delta = wrapDegrees(targetYaw − yaw)` (shortest way round, so 350° → 10° turns +20°).
   `yaw += clamp(delta · yawResponse, ±maxYawRate)`.
2. **Target tilt.** For each horizontal axis (forward, strafe), independently:
   - Input ≠ 0 → `target = input · maxTilt`.
   - Input = 0 → **active brake:** `target = clamp(−localVelocity · brakeGain, ±maxTilt)`.
3. **Tilt lag.** `tilt += (target − tilt) · tiltResponse` for pitch and roll.
4. **Horizontal thrust.** Local acceleration per axis = `maxHorizontalAccel · sin(tilt) / sin(maxTilt)`
   (full tilt = full accel), rotated to world frame by `yaw` and added to `vx, vz`.
5. **Altitude hold.** `targetVy = vertical > 0 ? vertical · maxClimb : vertical · maxDescend`;
   `vy += (targetVy − vy) · verticalResponse`. No gravity term — the drone holds altitude by itself.
6. **Drag.** `vx, vz *= (1 − drag)`.
7. **Settle.** If both horizontal inputs are 0 and horizontal speed < `settleSpeed`, set `vx = vz = 0`
   and tilts to 0. Likewise `vy = 0` when vertical input is 0 and `|vy| < settleSpeed`.

**Collision (bump and stop).** After `move()`, `afterCollision(state, intended, actual)` zeroes the
velocity component on any axis where `|actual − intended| > 1e-7`. Wall → horizontal component
stops; ground or ceiling → `vy` stops. Descending on the ground therefore just stays parked.

### 6.1 Starting tuning values (`FlightTuning.DEFAULT`)

These are starting points to be tuned by feel after the first play-test.

| Constant | Value | Effect |
|---|---|---|
| `maxTilt` | 25° | Max lean from W/A/S/D |
| `tiltResponse` | 0.20 /tick | Reaches ~90% of target tilt in ~0.5 s |
| `drag` | 0.05 /tick | Momentum: ~55% of top speed after 1 s, ~85% after 2 s |
| `topSpeed` | 0.40 blocks/tick (8 blocks/s) | Steady-state speed at full tilt |
| `maxHorizontalAccel` | derived: `topSpeed · drag / (1 − drag)` ≈ 0.0211 | Makes steady state at full tilt equal `topSpeed` |
| `brakeGain` | 40 °/(block/tick) | Releasing at top speed: back-tilt peaks ≈8° after ~0.5 s, full stop after ~2.6 s, drifting a few blocks |
| `maxClimb` | 0.25 blocks/tick (5 blocks/s) | Space |
| `maxDescend` | 0.20 blocks/tick (4 blocks/s) | Descend key |
| `verticalResponse` | 0.15 /tick | Climb/sink rate easing |
| `yawResponse` | 0.35 /tick | Heading easing toward look |
| `maxYawRate` | 10 °/tick (200°/s) | Turn-rate cap |
| `settleSpeed` | 0.002 blocks/tick | Snap-to-zero threshold |

## 7. Entity, item and rendering

**Entity (`drone:drone`).** Hitbox 1.5 wide × 0.5 tall. Never takes gravity, never breaks
(creative). One passenger, seated on top of the body (vanilla sitting pose). The rider's camera and
body stay **upright** — only the drone model tilts (motion-sickness comfort). Vanilla
`DismountHelper` picks a safe dismount spot; dismounting mid-air drops the player (no fall damage in
creative) while the drone hovers. Saves with the world (position + yaw); velocity and tilt reset to a
hover on load.

**Item (`drone:drone`, "Drone").** Listed in vanilla **Tools & Utilities** creative tab. Use on a
block → spawn the drone on top of the clicked face, facing the player. If the drone would collide
with blocks or entities there, nothing spawns and the item use fails.

**Model.** Blocky quadcopter (16 px = 1 block): flat dark-gray body ~¾ block square; four arms in an
X; a motor at each arm tip with a two-blade propeller; two landing skids. **Red lights on the front
arms, green on the back** so the heading is readable from outside. Total span ~1.5 blocks.

**Renderer.** Rotates the model by interpolated yaw, then applies `pitchTilt` and `rollTilt` around
the body center. Propellers spin by a client-side angle that advances each frame by a base hover
speed plus a bonus proportional to `|pitchTilt| + |rollTilt|` and climb rate.

**Assets.** `textures/entity/drone.png` (64×64), `textures/item/drone.png` (16×16), item model and
item definition JSON, `lang/en_us.json` ("Drone"). Pixel art generated programmatically for v1;
replaceable later.

## 8. Edge cases

| Case | Behavior |
|---|---|
| Placing where there's no room | Nothing spawns |
| Flying into wall / ground / ceiling | That velocity component stops (§6) |
| Dismount mid-air | Player drops; drone brakes and hovers in place; can be re-mounted |
| Drone removed while ridden | Vanilla ejects the passenger |
| Rider logs out | Vanilla dismount |
| Chunk unload / world reload | Drone persists, reloads hovering |
| Mod later removed from a world | World loads; drone entities are discarded with a log warning |
| Water / lava | No special handling; it flies through like air (no gravity, no buoyancy) |

## 9. Workspace setup (part of this work)

```
Minecraft-mods/
├── CLAUDE.md          workspace guide for future sessions
├── README.md          short human intro
├── .gitignore
├── docs/superpowers/  specs/ and plans/ for every mod
└── mods/
    └── drone/         self-contained Fabric project (own Gradle wrapper, own CLAUDE.md)
```

- **JDK 25** via Homebrew `openjdk@25` (keg-only, no sudo); `JAVA_HOME` pointed at it. Minecraft's
  bundled runtime is not used for development.
- **Gradle wrapper** per mod (Gradle 9.7.1, matching Fabric's example mod), plugin
  `net.fabricmc.fabric-loom`, Loom `splitEnvironmentSourceSets()`. No mappings — 26.x is
  unobfuscated and uses Mojang's official names.
- **git** repo at the workspace root; `mods/drone/CLAUDE.md` documents drone-specific architecture
  (where physics and tuning live, how input reaches the entity).
- **No `templates/` yet** — extracted when the second mod shows what is actually shared.

## 10. Testing

1. **Unit tests (JUnit 5, test-first) on `FlightModel`:**
   - No input from rest → state unchanged (hover holds altitude, no drift).
   - Full forward input → pitch tilt approaches 25°; horizontal speed converges to 0.40 ± 0.01
     blocks/tick; direction matches yaw.
   - Release at top speed → pitch tilt goes negative (brake lean), speed reaches 0 within ~3 s
     (60 ticks) and tilt returns to 0.
   - Strafe input accelerates along the drone's right axis.
   - Vertical: climb converges to 0.25, descend to −0.20, release returns `vy` to 0.
   - Yaw: per-tick change ≤ 10°; 350° → 10° turns positively through 0°.
   - `afterCollision` zeroes exactly the blocked axes.
2. **`./gradlew build`** — compiles, unit tests pass.
3. **Fabric client gametest** (`./gradlew runClientGameTest`, gametest source set): creates a flat
   singleplayer world, places a drone via the item, mounts the player, holds forward for ~3 s,
   asserts the drone moved forward and had a positive pitch tilt, and saves screenshots (static and
   in flight, third-person) for visual review of the model.
4. **Manual play-test** by Frankie with `./gradlew runClient` (separate dev game; real install
   untouched), followed by tuning of §6.1 values.
5. **Optional install** into the real launcher (Fabric Loader + Fabric API + drone jar) for
   "Frankie's World" — only after asking, and after backing up the world.
