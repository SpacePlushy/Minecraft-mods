# Rocket Mod — Design Spec

- **Date:** 2026-10-19
- **Status:** Written autonomously while Frankie is AFK (Frankie's instruction: "work autonomously until it
  is ready for me to test"). Every decision Frankie didn't make is marked *Claude* in §2, so Frankie can
  overrule it at the play-test.
- **Mod:** `mods/rocket/` · mod id `rocket` · package `io.palmisano.rocket`
- **Target:** Minecraft Java 26.3 · Fabric Loader 0.19.5 · Fabric API `0.164.0+26.3` · Java 25

## 1. Intent

A **rideable rocket ship** the player climbs into and launches, **complete with vapor billowing out from
the bottom at launch**. Where the drone hovers and glides, the rocket should feel like a real launch:
a few seconds of ignition with white clouds rolling out across the ground, a slow liftoff that builds
into a fast climb on a column of flame and smoke, and gravity pulling it back down when the engine is cut.

- **For:** Frankie, single-player, in a creative world (same audience as the drone).
- **Success:** Take the Rocket from the creative inventory, place it, climb in, hold Space, watch the
  launch vapor and liftoff, steer it around the sky, and bring it back down without anything breaking.

## 2. Decisions (with reasons)

| Decision | Choice | Who | Why |
|---|---|---|---|
| What it is | Rideable rocket ship with launch vapor | Frankie | Frankie's request |
| Process | superpowers: spec → plan → TDD execution → final review | Frankie | Frankie's request |
| Game mode | Creative only: no recipe, fuel or damage | Claude | Same as the drone, which Frankie liked; keeps scope to the flight and the launch |
| Crashes | Bump and stop: collisions and landings never break it or hurt the rider | Claude | Same as the drone; landing a rocket by hand is hard, so failure must be harmless |
| Flight feel | Physics rocket: thrust along the nose, gravity, momentum, steerable tilt | Claude | Approach A in §3: the only one that feels like a rocket rather than a second drone |
| Launch | Hold Space on the ground → 2 s ignition with vapor → liftoff. Let go during ignition to abort | Claude | Makes the vapor the star of every launch; "hold to launch" is forgiving |
| Steering | W/S/A/D tilt the nose (max 20°), mouse sets the heading, nose self-rights when released | Claude | Matches the drone's controls, so Frankie already knows them |
| Throttle | Hold Space = full throttle, release = engine idles (no thrust) and the rocket falls | Claude | Simplest keyboard rocket; landing softly by feathering Space is the skill to learn |
| Effects | Vanilla particles and vanilla sounds only | Claude | No custom textures or sound assets to make; looks and sounds can be swapped later |
| Rider | Sits inside an enclosed cabin; the model tilts around the cabin | Claude | 26.3 always draws riders seated, so the cabin must enclose a seated player; pivoting at the cabin keeps the rider inside when it tilts |
| Sync model | Client-authoritative while ridden, like boats and the drone | Claude | Zero input lag, no custom networking; proven in the drone |
| Code reuse | Copy the drone's patterns into the rocket, no shared library | Claude | Each mod ships as its own jar; a shared library is a separate decision for Frankie (noted in root `CLAUDE.md`) |

## 3. Approaches considered

- **A. Physics rocket (chosen).** A pure flight model with an engine state machine (off → ignition →
  burning), thrust along the rocket's nose, gravity and drag. The rider steers by tilting. Vapor comes
  from a pure "exhaust planner" that decides what to emit each tick. Most fun, fully unit-testable.
- **B. Scripted launch toy.** Press a key and the rocket follows a fixed trajectory up and back down.
  Spectacular once, nothing to do on the second flight. Rejected.
- **C. Re-skinned drone.** Reuse `FlightModel` with a rocket model. Fastest, but it would hover and
  brake like a drone: no launch, no gravity. Rejected.

## 4. Non-goals (v1)

Fuel, recipes, damage or durability, space or other dimensions, staging, a launch-pad block, HUD
(altitude/speed), custom particle textures or sound files, other players seeing the flame and tilt
(they see it move), a landing autopilot, config screens. Each is a reasonable follow-up.

## 5. Controls

| Input | Effect |
|---|---|
| Space (Jump key), held | On the ground with the engine off: 2 s ignition (vapor, rumble), then liftoff. Engine running: full throttle |
| Space released | Throttle falls to zero: the rocket coasts and then falls. During ignition: aborts the launch |
| W / S | Tilt the nose forward / back (thrust then pushes it that way) |
| A / D | Tilt the nose left / right |
| Mouse look | Heading eases toward where the rider looks (max 100°/s) |
| Sneak (Shift) | Dismount (vanilla). Mid-air, the engine cuts and the rocket falls and lands on its own |
| Right-click rocket | Climb in |
| Attack rocket | Remove it (creative behavior, like boats and the drone) |

Keys are read through the player's key mappings, so rebinding in Controls just works.

## 6. Architecture

Same split as the drone. Shared (`main`) code never imports client classes; the `flight` package
imports nothing from Minecraft or Fabric.

| Unit | Source set | Responsibility |
|---|---|---|
| `RocketTuning` | main | Record of every tunable flight constant (§7.1) with a `DEFAULT` |
| `RocketInput` | main | Record: `forward`, `strafe` ∈ [−1, 1], `throttleUp` (Space held), `targetYaw`. `fromKeys(...)`, `idle(yaw)` |
| `Engine` | main | Enum `OFF`, `IGNITION`, `BURNING` |
| `RocketState` | main | Record: velocity `vx, vy, vz` (blocks/tick), `yaw`, `pitchTilt`, `rollTilt` (deg), `throttle` ∈ [0, 1], `engine`, `engineTicks` (ticks in the current engine phase) |
| `RocketPhysics` | main | **Pure.** `step(state, input, onGround) → state` (one tick), `afterCollision(state, intended, actual)` (bump and stop), `observe(state, seen movement, yaw)` (the side that isn't simulating; same role as the drone's) |
| `ExhaustPlanner` | main | **Pure.** `plan(state, nozzleHeightAboveGround, random) → List<Puff>`: what to emit this tick (kind, offset from the nozzle, velocity). Kinds: `VAPOR`, `BILLOW`, `FLAME`, `TRAIL` |
| `EngineEvents` | main | **Pure.** Compares two states: `IGNITION_STARTED` (off → ignition), `LIFTOFF` (→ burning), `ABORTED` (ignition → off), `SHUTDOWN` (burning → off). Drives the sounds |
| `RocketEntity` | main | Extends `VehicleEntity`, one passenger. Authority branch like the drone; on the client, turns `Puff`s into vanilla particles and `EngineEvents` into vanilla sounds |
| `RocketItem`, `ModEntities`, `ModItems`, `RocketMod` | main | Placement and registration (Tools & Utilities tab) |
| `RocketClient` | client | Renderer and model layer; END_CLIENT_TICK reads the rider's keys into `RocketInput` |
| `RocketRenderState`, `RocketModel`, `RocketRenderer` | client | Blocky rocket model; tilt about the cabin |

### 6.1 Data flow (one tick, rider present)

```
keys/mouse ──► RocketClient (client tick) ──► rocket.setInput(RocketInput)
                                                   │
                RocketEntity.tick() on the rider's client (authoritative)
                                                   │
   RocketPhysics.step(state, input, onGround) ──► move(SELF) ──► afterCollision
                                                   │
          ExhaustPlanner.plan(...) ──► particles · EngineEvents ──► sounds
                                                   │
                vanilla vehicle-move packets ──► server (accepts, doesn't simulate)
```

- **Ridden:** the rider's client simulates; the server tracks the movement it sees (`observe`).
- **Unridden:** the server simulates with `RocketInput.idle(yaw)`: the engine is off, so an airborne
  rocket falls under gravity and lands; a landed one stays put. Clients interpolate (3 steps, like boats).
- **Hand-over:** the side that takes over starts from the observed velocity, so a rocket you jump out
  of keeps its momentum and arcs down, instead of stopping in mid-air.

## 7. Flight model

20 ticks/s. Velocity in blocks/tick (world frame). Yaw 0 faces +Z; forward = (−sin yaw, 0, cos yaw),
right = (−cos yaw, 0, −sin yaw). `pitchTilt > 0` = nose tipped forward, `rollTilt > 0` = nose tipped right.

Per tick, in order:

1. **Yaw.** `yaw += clamp(wrapDegrees(targetYaw − yaw) · yawResponse, ±maxYawRate)`.
2. **Tilt.** Target = `forward · maxTilt` and `strafe · maxTilt`; on the ground the target is 0 (it can't
   lean on the pad). `tilt += (target − tilt) · tiltResponse`. No input → the nose self-rights.
3. **Engine.** State machine:
   - `OFF` + Space held: on the ground → `IGNITION`; in the air → `BURNING` at once (no pad, no vapor).
   - `IGNITION`: Space released → `OFF` (abort). After `ignitionTicks` → `BURNING`.
   - `BURNING`: on the ground with throttle below 0.01 for `shutdownTicks` → `OFF`.
4. **Throttle.** `BURNING`: eases toward 1 (Space held) or 0 (released) by `throttleResponse`.
   Otherwise 0.
5. **Thrust.** Nose direction in the rocket frame: forward `sin(pitch)`, right `sin(roll)`,
   up `√(1 − sin²pitch − sin²roll)`; rotated to world by yaw. `v += nose · throttle · maxThrust`.
6. **Gravity.** `vy −= gravity`.
7. **Drag.** `v *= (1 − drag)` on all three axes.
8. **Ground.** On the ground: horizontal velocity `*= groundFriction`; a downward `vy` is zeroed by the
   collision step. Below `settleSpeed`, horizontal velocity snaps to exactly 0.

**Collision (bump and stop).** As the drone: after `move()`, zero each axis where the actual movement
differs from the intended one by more than `1e-7`. Landing, walls and ceilings stop that component.

### 7.1 Starting tuning values (`RocketTuning.DEFAULT`)

Starting points, simulated with these exact numbers; tune by feel after the play-test.

| Constant | Value | Effect |
|---|---|---|
| `gravity` | 0.04 blocks/tick² | Engine off: falls, terminal ≈ 1.96 blocks/tick (39 blocks/s) |
| `maxThrust` | 0.065 blocks/tick² | Thrust-to-weight 1.6; hovers at 62% throttle |
| `drag` | 0.02 /tick | Climb tops out at ≈ 1.23 blocks/tick (24.5 blocks/s) |
| `throttleResponse` | 0.10 /tick | Engine spools up in about a second; liftoff 12 ticks after ignition ends |
| `ignitionTicks` | 40 (2 s) | Vapor-only countdown on the pad |
| `shutdownTicks` | 20 (1 s) | Landed at zero throttle this long → engine off (next launch ignites again) |
| `maxTilt` | 20° | Full tilt still climbs (vertical thrust 0.061 > gravity); sideways top speed ≈ 1.09 blocks/tick |
| `tiltResponse` | 0.08 /tick | A heavy, deliberate lean (~90% in 1.4 s) |
| `yawResponse` | 0.20 /tick | Heading easing toward the look direction |
| `maxYawRate` | 5 °/tick (100°/s) | Turn-rate cap |
| `groundFriction` | 0.5 /tick | Stops sliding on landing |
| `settleSpeed` | 0.003 blocks/tick | Snap-to-zero threshold on the ground |
| `vaporHeight` | 8 blocks | Exhaust makes ground vapor while the nozzle is this close to the ground |

Simulated launch (Space held from the pad): ignition 2 s, liftoff 0.6 s later, then 18 blocks up after
3 s of burn, 52 blocks after 5 s, 164 blocks after 10 s.

## 8. Exhaust and sound (the launch vapor)

`ExhaustPlanner.plan(state, h, random)` returns this tick's puffs; `h` is the nozzle's height above the
ground below it (∞ if none within 16 blocks). Offsets are relative to the nozzle, velocities in the world
frame. The entity spawns them as vanilla particles on the client that simulates the rocket.

| Situation | Emits per tick |
|---|---|
| `OFF` | nothing |
| `IGNITION` | 6 `VAPOR` at ground level, flying radially outward (0.25–0.5 blocks/tick) and slightly up; 2 `BILLOW` (slow, lingering) |
| `BURNING`, any height | `round(1 + 5·throttle)` `FLAME` out of the nozzle along −nose (0.3–0.5 blocks/tick); `round(3·throttle)` `TRAIL` smoke |
| `BURNING`, `h < vaporHeight`, throttle > 0.3 | plus `round(8·(1 − h/vaporHeight))` `VAPOR` at ground level radially, and 1 `BILLOW` |

Particle mapping: `VAPOR` → `CLOUD` (white, puffy), `BILLOW` → `CAMPFIRE_COSY_SMOKE` (big, lingers and
rises), `FLAME` → `FLAME`, `TRAIL` → `LARGE_SMOKE`. If a screenshot shows a better vanilla particle for
a role, swapping it is a one-line change in the entity.

Sounds (vanilla, played locally by the simulating client):

| Event | Sound |
|---|---|
| `IGNITION_STARTED`, then every 10 ticks of ignition | `FIRE_EXTINGUISH` (steam hiss), pitch 0.6 |
| `LIFTOFF` (engine enters `BURNING`: after ignition, or a mid-air relight) | `FIREWORK_ROCKET_LAUNCH`, volume 3, pitch 0.5 (low roar) |
| `BURNING`, every 8 ticks | `FIRE_AMBIENT`, volume 0.4 + throttle, pitch 0.5 |
| `ABORTED`, `SHUTDOWN` | `FIRE_EXTINGUISH`, pitch 1.2 |

## 9. Entity, item and rendering

**Entity (`rocket:rocket`).** Hitbox 1.375 wide × 4.625 tall. One passenger. A player's attack removes it
in creative (like boats); no other damage handling, same as the drone. `causeFallDamage` returns false,
so hard landings never hurt the rider. Never applies block effects (like the drone: lava, fire, cactus and cobwebs do
nothing). Saves position and yaw; reloads at rest with the engine off (an airborne one then falls).
Dismount spot: beside the rocket when there's room (vanilla `DismountHelper`), else vanilla's default
(on top of the rocket).

**Item (`rocket:rocket`, "Rocket").** Tools & Utilities tab. Use on a block → rocket stands on the
clicked face, facing the way the player looks (like the drone), so after climbing in the rider
looks out of the window. No room (blocks or entities in the hitbox) → nothing spawns.

**Model (16 px = 1 block, about 4.6 blocks tall).** From the bottom: a dark engine bell; a 22 px square
lower body; four fins at the base (total span ≈ 2.4 blocks); a 22 px square cabin with a big window on
the front; a stepped nose cone. Livery: white body, red nose and fins, gray engine, blue window. The
cabin floor is 2 blocks up; the rider sits there, inside the cabin.

**Renderer.** Rotates by interpolated yaw, then applies `pitchTilt`/`rollTilt` about the cabin center
(2.75 blocks up), so the seated rider stays inside a tilted rocket. Shadow radius 0.8.

**Assets.** `textures/entity/rocket.png` (128×128), `textures/item/rocket.png` (16×16), item model and
definition JSON, `lang/en_us.json` ("Rocket"), mod icon. Generated by `tools/gen_textures.py`.

## 10. Edge cases

| Case | Behavior |
|---|---|
| Place with no room (e.g. under a 3-block ceiling, or a 1-wide corridor) | Nothing spawns |
| Launch under a ceiling | Stops against it (vertical component zeroed); engine keeps pushing until released |
| Engine cut at altitude | Coasts up, then falls; lands with a bump; no damage to anything |
| Dismount mid-air | Rider drops (creative flight available); rocket keeps its momentum, engine off, falls and lands |
| Space released during ignition | Launch aborts; the next press starts a full 2 s ignition |
| Rocket removed while ridden / rider logs out | Vanilla: passenger ejected / dismounted |
| World reload | Persists; reloads at rest, engine off |
| Water / lava | Flies through like air (no block effects, no buoyancy) |
| Above the build height | Keeps flying; no ceiling, no space dimension |

## 11. Testing

1. **Unit tests (JUnit, test-first)** on `RocketPhysics`, `RocketInput`, `ExhaustPlanner`, `EngineEvents`:
   at rest with no input nothing changes; ignition takes exactly `ignitionTicks` and aborts on release;
   liftoff happens only after ignition; climb converges to the §7.1 terminal speed; releasing at speed
   coasts up then falls; free fall converges to the gravity terminal; tilt follows W/S/A/D and self-rights;
   tilt is impossible on the ground; yaw takes the short way round (unbounded player yaw, e.g. 725°);
   shutdown after `shutdownTicks` landed; mid-air relight is instant; `afterCollision` zeroes exactly the
   blocked axes; `observe` tracks seen movement and ignores teleports; opposing keys cancel; the exhaust
   plan matches §8 (nothing when off, vapor only near the ground, flame count follows throttle, vapor
   flies outward).
2. **`./gradlew build`** green.
3. **Client gametests** (`./gradlew runClientGameTest`), reading server-side positions:
   placement + mount + showcase screenshots; no-room placement; launch (stays on the pad through
   ignition, then climbs; screenshot of the vapor during ignition and of the flame in flight);
   abort (release during ignition → never lifts off); steering (W moves it forward); engine cut → falls
   and lands; dismount mid-air → rocket falls and lands; hard landing in survival → rider unhurt.
   **Look at every screenshot.**
4. **Play-test** by Frankie with `./gradlew runClient`, then tune §7.1.

## 12. Workspace

`mods/rocket/` is a new self-contained Fabric project, created by copying `mods/drone`'s Gradle files
and wrapper (root `CLAUDE.md` "Adding a new mod"), with its own `CLAUDE.md` and `README.md`, a row in the
root `README.md`, and branch `rocket-v1` merged to `main` after the play-test.
