# SDD ledger — plan: docs/superpowers/plans/2026-10-19-drone-mod-plan.md
Spec: docs/superpowers/specs/2026-10-19-drone-mod-design.md
Setup: Ruling: work on branch drone-v1 in the main checkout, no git worktree — single-user brand-new repo, no parallel work, keeps one Gradle/Minecraft cache — cost if wrong: none, branch can be moved to a worktree any time.
Pre-flight:
| Producer → Consumer | Produces | Consumes | Finding |
|---|---|---|---|
| T1 → T5 | DroneMod.MOD_ID, DroneMod.id(String) | ModEntities/ModItems keys, TEXTURE, LAYER | consistent |
| T2 → T3 | FlightTuning/FlightState/DroneInput/FlightModel.step | T3 modifies same types, adds afterCollision/fromKeys | consistent |
| T2+T3 → T5 | step, afterCollision (instance), FlightState.hovering/withYaw, DroneInput.hover | DroneEntity.tick | consistent |
| T4 → T5 | UV table, textures/entity/drone.png | DroneModel.createLayer, DroneRenderer.TEXTURE | consistent |
| T3 → T6 | DroneInput.fromKeys(6 booleans, yaw) | DroneClient END_CLIENT_TICK | consistent |
| T5 → T6 | setInput, getPitchTilt, GameTestSupport.flatArena/check/serverDronePos | DroneFlightClientGameTest | consistent |
Task 1: Ruling: Bash tool shells predate the ~/.zshrc edit, so every Gradle command I run sets JAVA_HOME inline — no effect on the build itself — cost if wrong: none
Task 1: Ruling: branch drone-v1 was created during setup, not in Step 7 — same result — cost if wrong: none
Task 1: complete (commits 60ad579..eefed67, tests: bash -c 'cd mods/drone && ./gradlew build --console=plain -q && echo BUILD_OK' → BUILD_OK)
Task 2: complete (commits eefed67..71c5bd5, tests: bash -c 'cd mods/drone && ./gradlew test --console=plain -q && grep -ho "tests=\"[0-9]*\" skipped=\"[0-9]*\" failures=\"[0-9]*\" errors=\"[0-9]*\"" build/test-results/test/*.xml' → tests="10" skipped="0" failures="0" errors="0")
Task 3: Ruling: implemented afterCollision/fromKeys before vertical so the vertical tests could be watched failing at runtime, not just at compile — same end state — cost if wrong: none
Task 3: complete (commits 71c5bd5..dc1258c, tests: bash -c 'cd mods/drone && ./gradlew test --console=plain -q && grep -ho "tests=\"[0-9]*\" skipped=\"[0-9]*\" failures=\"[0-9]*\" errors=\"[0-9]*\"" build/test-results/test/*.xml' → tests="15" skipped="0" failures="0" errors="0")
Task 4: complete (commits dc1258c..8ec9854, tests: bash -c 'cd mods/drone && ./gradlew build --console=plain -q && echo BUILD_OK' → BUILD_OK)
Task 5: Ruling: body shrunk from 12x4x12 to 8x4x8 px (spec said ~3/4 block) — at 12 px the body hid the X arms the spec also requires; showcase screenshot showed a slab — cost if wrong: one-line revert in DroneModel + gen_textures
Task 5: Ruling: added a showcase scenario (3 unridden drones close-up) and HUD-hidden/side-view screenshots to DronePlacementClientGameTest — the plan's single rider screenshot was too small to verify the model — cost if wrong: ~5 s extra test time
Task 5: complete (commits 8ec9854..7803d34, tests: bash -c 'cd mods/drone && ./gradlew build --console=plain -q && ./gradlew runClientGameTest --console=plain > build/gt.log 2>&1 && ! grep -q AssertionError build/gt.log && grep -o "BUILD SUCCESSFUL" build/gt.log && echo "screenshots=$(grep -c Screenshot: build/gt.log)"' → screenshots=4)
Task 6: Ruling: climbAndHold measures y2/y3 at +30/+50 ticks after release, not +20/+40 — simulation shows vy only snaps to 0 after ~30 ticks, so +20..+40 still drifts ~0.05 and would flake on the 0.05 threshold — cost if wrong: none, still proves altitude hold
Task 6: Ruling: wallStopsDrone also holds D for 20 ticks against the wall and asserts dx < -1 — Review Focus 2 says it must still accept sideways input but the plan's sketch didn't test it — cost if wrong: ~1 s test time
Task 6: Ruling: flyForward adds a one-frame side-view screenshot (drone-flying-side) — tilt direction is easier to judge from the side — cost if wrong: none
Task 6: Ruling: dismount test holds Shift 5 ticks instead of a one-tick pressKey — dismount is decided server-side from the input packet's shift state (Player.rideTick → wantsToStopRiding → isShiftKeyDown), a one-tick tap never reached the server — cost if wrong: none, real players hold the key
Task 6: Ruling: also added mods/drone/README.md (player-facing) with two gametest screenshots in mods/drone/docs/ — user asked for good READMEs before handing off to another machine — cost if wrong: none
Task 6: complete (commits 7803d34..a091759, tests: bash -c 'cd mods/drone && ./gradlew build --console=plain -q && ./gradlew runClientGameTest --console=plain > build/gt.log 2>&1 && ! grep -q AssertionError build/gt.log && grep -o "BUILD SUCCESSFUL" build/gt.log && echo "unit=$(grep -ho "tests=\"[0-9]*\" skipped=\"0\" failures=\"0\" errors=\"0\"" build/test-results/test/*.xml | tr "\n" " ") screenshots=$(grep -c Screenshot: build/gt.log)"' → unit=tests="4" skipped="0" failures="0" errors="0" tests="15" skipped="0" failures="0" errors="0"  screenshots=6)
Handoff: paused after Task 6 (2026-10-19) at Frankie's request to resume on another machine. Next: Final Review (range 60ad579..drone-v1) → Task 7 play-test with Frankie → finishing-a-development-branch. Ledger copied to docs/superpowers/plans/2026-10-19-drone-mod-progress.md; restore it to <sdd-workspace>/progress.md before resuming.
