# SDD ledger — plan: docs/superpowers/plans/2026-10-22-aibuilder-m5-plan.md

Executor: superpowers:executing-plans (Native), as M1–M4. Spec: docs/superpowers/specs/2026-10-21-aibuilder-design.md
(read; §8 check_build and place_block_entity, §9 lava rule and feel, §10 entities, §5.3 HUD, §13).
Frankie (2026-10-22): autonomous to the end of M6; real Claude runs OK for live checks and the showcase; never in the suites.

## Pre-flight (shared interfaces)

| Producer → consumer | Produced | Consumed | Found |
|---|---|---|---|
| T1 → T5 | `lava_near_flammable` rejections | the system prompt tells Claude the rule | consistent |
| T2 → T5 | `check_build` | the system prompt's pipeline calls it before finish | consistent |
| T3 → T5 | `place_block_entity` | the system prompt's detail guidance | consistent |
| T4 | the HUD payload | BuildManager only | no shared interfaces with T1–T3 |

Pre-flight: Ruling: M5's 26.3 API facts were read with javap before the plan was executed (scratchpad javap_m5*.sh):
`BlockState.ignitedByLava()`, `canSurvive`, `SignText(messages, filtered, color, glowing)`, `LecternBlockEntity.setBook`,
`BannerPatternLayers(List<Layer(Holder<BannerPattern>, DyeColor)>)` set through `BlockEntity.applyComponents`,
`ItemContainerContents.fromItems`, `ItemFrame(Level, BlockPos, Direction)`, Fabric's `HudElementRegistry` with
`HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`, `PayloadTypeRegistry.clientboundPlay()` — cost if
wrong: a compile error at the task
Task 1: complete (commits fce9469..f74a963, tests: bash -c 'cd mods/aibuilder && export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && ./gradlew build --console=plain -q && grep -h "required tests" build/run/gameTest/logs/latest.log | sed "s/.*(Minecraft) //"' → All 40 required tests passed :))
Task 1: Ruling: the world's fire mask covers the allowed box grown 3 sideways and 4 up and down (`FireMask.around`), read in the site survey's protection scan: lava just outside the box sets the build's wood alight all the same — cost if wrong: a few more block reads per surveyed column.
Task 1: Ruling: the lava rule is a second pass after the pending guard, so a call the guard refuses remembers no lava or wood; the guard now counts the entries that passed validation, bounds and protection (an upper bound of the batch) — cost if wrong: a call with hundreds of thousands of lava rejections could be refused when its batch would have fit.
Task 1: Ruling: a block's own spot never counts against it (lava may replace a log, planks may replace lava); a replace-mode fill may leave the old block, so the old and the new both count there — cost if wrong: a few lava placements refused next to replace fills that did replace the wood.
Task 1: Ruling: the reach is the plan's 3 sideways, from 1 below to 4 above (the spec says "within 3 blocks sideways or 4 below a flammable block"; the plan adds one level below, conservative) — cost if wrong: lava refused one level above wood it couldn't reach.
Task 1: Ruling: builder_system.md states the exact rule now rather than in Task 5, next to the fluids paragraph it belongs to — cost if wrong: none.
Task 2: complete (commits f74a963..14609ea, tests: bash -c 'cd mods/aibuilder && export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && ./gradlew build --console=plain -q && grep -h "required tests" build/run/gameTest/logs/latest.log | sed "s/.*(Minecraft) //" && ./gradlew runClientGameTest --console=plain -q' → [05:33:52] [Render thread/INFO] (Minecraft) Stopping!)
Task 2: Ruling: a door is reported when either side is solid at feet or head height (the brief's "both sides of a closed door blocked" read as: both sides are checked, and one blocked side already makes a door you can't walk through) — cost if wrong: a door set against a wall on purpose (a fake door) is listed.
Task 2: Ruling: dark looks at each changed position and the spots on and beside it, not a flood fill of each room; a room over natural ground is found along its walls, a built floor all over — cost if wrong: the middle of a big unfloored room isn't listed, its edges are.
Task 2: Ruling: walkable is 2 blocks with no collision and no fluid over a full top face (`isFaceSturdy(UP)`: not a bottom slab, where nothing spawns); roofed is the no-leaves heightmap above the spot, so a tree canopy isn't a roof — cost if wrong: dark spots under a leaf roof aren't listed.
Task 2: Ruling: low is a walkable spot one block high under a solid block of the build, stairs and leaves aside as ceilings (the space under a staircase, a canopy) — cost if wrong: a crawl space under a stair ceiling isn't listed.
Task 2: Ruling: before reading light, the check waits (at most 5 s) for the light engine's pending tasks on the build's chunks (`waitForPendingTasks`), since light is worked out on its own thread — cost if wrong: none; a lit room read too early would be listed as dark.
Task 2: Ruling: when placement is still behind after terraform's 40 s wait, check_build answers "N blocks are still going in" (not an error) instead of checking half a build; the check itself has 60 s, inside the MCP timeout with the wait — cost if wrong: Claude calls it again.
Task 2: Ruling: `ScriptedBackend.replies()` records every call's answer, and `ArenaBuild.reply(tool)` reads one, so gametests can read check_build's text — cost if wrong: none (test seam).
Task 2: Ruling: the check_build gametests run with `skyAccess = true`: 26.x roofs a gametest structure with barriers otherwise — cost if wrong: none.
Task 2: Ruling: builder_system.md's "Check and fix" step calls check_build now (the pre-flight's T2 → T5 row), rather than waiting for Task 5 — cost if wrong: none.
Task 3: complete (commits 14609ea..bde967b, tests: bash -c 'cd mods/aibuilder && export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && ./gradlew build --console=plain -q && grep -h "required tests" build/run/gameTest/logs/latest.log | sed "s/.*(Minecraft) //" && ./gradlew runClientGameTest --console=plain -q && echo client gametests passed' → client gametests passed)
Task 3: Ruling: the item and banner-pattern catalog reaches the tools through `WorldReader.items()` (banner patterns are the world's data-driven registry), not a seventh ToolExecutor argument — cost if wrong: none.
Task 3: Ruling: overlong text is cut quietly (sign lines at 384 characters, what the game takes from a client; pages at 800; titles and authors at 32), but too many parts are refused as bad_argument (a 5th sign line, a 28th stack, a 51st page, a 7th banner layer), so Claude learns — cost if wrong: one retry.
Task 3: Ruling: a count over the item's stack size is cut to it (99 diamonds become 64); one color and glow cover both sides of a sign; a book without a title is "Untitled", without an author "Claude" — cost if wrong: less than asked.
Task 3: Ruling: container data fills any block entity that is a Container with slots (chests, barrels, shulker boxes, hoppers, pots…) from slot 0; stacks past its size are left out — cost if wrong: none.
Task 3: Ruling: place_block_entity at a spot already holding the same block (the world's own) journals the spot before writing its data, so undo gives the old data back — cost if wrong: none.
Task 3: Ruling: an item frame needs no block; pos is the empty spot in front of a solid block; one that can't hang when its turn comes (nothing solid behind it) is skipped and logged, since the tool call has already answered — cost if wrong: Claude isn't told; it would see the bare wall with inspect_region.
Task 3: Ruling: undo takes down, without drops, the frames that still hold the build's item or nothing; a frame holding another item holds the player's, so it stays and counts as kept — cost if wrong: that frame drops its item when its wall is restored (dropped, not destroyed).
Task 3: Ruling: undo waits up to 10 s (`UNDO_ENTITY_WAIT_SECONDS`) for the frames' chunks' entities, which load after the blocks — cost if wrong: a frame still unloaded after that drops as an item when its wall goes.
Task 3: Ruling: builder_system.md's interiors line asks for place_block_entity dressing now (the pre-flight's T3 → T5 row) — cost if wrong: none.
Task 4: complete (commits bde967b..eae1547, tests: bash -c 'cd mods/aibuilder && export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && ./gradlew build --console=plain -q && grep -h "required tests" build/run/gameTest/logs/latest.log | sed "s/.*(Minecraft) //" && ./gradlew runClientGameTest --console=plain -q && echo client gametests passed' → client gametests passed)
Task 4: Ruling: the panel's state is a pure `BuildStatus` record (title, phases, the phase under way, placed, calls, note), bounded to 48 characters a string and 12 phases, since it's Claude's text; the payload carries it with wire limits of its own — cost if wrong: none.
Task 4: Ruling: before the brief the panel's title is "AI Builder" with the backend's note ("Starting Claude…") under it — cost if wrong: none.
Task 4: Ruling: the server sends the panel only while the owner is alive and in the build's world, and clears it once when they aren't; the client also drops it after 5 s without a status and in any other world than the one it came from. Death and dimension changes are covered by those code paths, not by a gametest (the demo test covers the end and a cancel) — cost if wrong: the panel lingers at most 5 s.
Task 4: Ruling: the panel is attached before vanilla's chat layer, top left with a 4 px margin, at most 170 px of text wide — cost if wrong: it overlaps something a pack draws there.
Task 5: complete (commits eae1547..c76fef2, tests: bash -c 'cd mods/aibuilder && export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && ./gradlew build --console=plain -q && grep -h "required tests" build/run/gameTest/logs/latest.log | sed "s/.*(Minecraft) //" && ./gradlew runClientGameTest --console=plain -q && echo client gametests passed' → client gametests passed)
Task 5: Live check (2026-10-22, Frankie's login): "a medieval stone castle with a gatehouse", Medium, superflat test world → "Done: 22,504 blocks placed." ("Stonehold Castle with Gatehouse"): 107 tool calls, 108,065 output tokens (Claude Code's list-price estimate $3.77, nothing extra on the subscription), the test 1,012 s; five-hour window 11% → 13%, seven-day 54%. place_block_entity ×11 (5 banners, a sign, a lectern with a book, a chest, 3 barrels); check_build ×4 (192 → 120 → 10 → 10 problems; Claude fixed a door's missing half, two azaleas on stone, lighting and an open attic); 2 checkpoints for 9 phases; one rejection (bedrock under the superflat floor), which Claude worked around.
Task 5: Ruling: dark means sky light 13 or less where the world has a sky (the heightmap only where it hasn't): the castle's first check listed 184 outdoor spots in rings under its towers' battlements, which still get sky light 14 — cost if wrong: an indoor spot right by a window (sky light 14) isn't listed.
Task 5: Ruling: a checkpoint moves the panel past the last phase it names (the whole name, a part of it 4 or more letters long, or the phase's first word that means something: "gatehouse and keep") and never back; naming none ends the phase in progress — cost if wrong: a word shared with a later phase skips the panel ahead.
Task 5: Ruling: the placement feel is the first two blocks written each tick, at half a player's place volume, with 4 of the block's own particles; none for air or the finalize pass — cost if wrong: taste, for the play-test.
Task 5: Ruling: the live test's end viewpoints are taken as a spectator after the chat fades (the castle's player fell from the 32-up overview and died); the castle's overview wasn't re-shot, since its world went with the test and the showcase runs use the fixed camera — cost if wrong: none.
Task 5: Ruling: the five low-ceiling spots left in the castle's last reports (under battlement and roof stairs) stay as check_build reports them; Claude judged them fine — cost if wrong: a little noise in a castle's report.

## Final review (a fresh Opus 5.5 reviewer, fce9469..c76fef2)

Final: verdict "with fixes": no Critical; Important I1 (text limits in code points, the game's in UTF-16 units), I2 (undo deletes a frame holding the player's same-id item); eleven minors. Re-graded by effect: M1 (container data in furnaces, hoppers, bookshelves, jukeboxes: water beside a filled bookshelf flows, a stocked furnace runs and undo leaves it), M2 (a build that only hung frames leaves the undo history: M6's refinements will make those) and M8 (banned and creative-only items handed over in chests and frames) raised to Important.
Final: fixed I1, text limits count UTF-16 units without splitting an emoji — limitsCountWhatTheGameCounts and anEmojiTitleStillSaves RED→GREEN (the gametest's save error was "is too long: 36"), suite: unit green, 58/58 server gametests, client gametests green.
Final: fixed I2, undo takes down only a frame holding nothing or exactly the build's item (ItemStack.isSameItemSameComponents) — undoKeepsAFrameHoldingThePlayersItem RED→GREEN, suite as above.
Final: fixed M1 (re-graded Important), container data only for passive storage (chests and trapped chests, barrels, shulker boxes, dispensers and droppers, decorated pots) — onlyPassiveContainersTakeItems RED→GREEN, suite as above.
Final: fixed M2 (re-graded Important), `UndoJournal.isEmpty()` counts spawned entities and BuildManager keeps a frames-only build in the history — aJournalWithOnlyFramesIsNotEmpty RED→GREEN, suite as above.
Final: fixed M8 (re-graded Important), `ItemCatalog.banned`: a banned or unbreakable block's item, spawn eggs and creative-only items are refused `banned_block` — bannedItemsAreRefused and theGamesBannedItemsAreRefused RED→GREEN, suite as above.
Final: minor (deferred): the door check counts any collision at feet height as blocked (a carpet, a bottom slab, a closed trapdoor) — count only collision taller than the 0.6 step height.
Final: minor (deferred): in the Nether the heightmap "roof" is the bedrock ceiling, so every outdoor spot beside a Nether build is dark; glass and leaf floors are listed though nothing spawns on them — in ceiling dimensions scan up a bounded distance; use isValidSpawn for the floor.
Final: minor (deferred): a check_build that times out keeps running and each retry adds another; the journal copy at its start is one tick's work at the Epic cap — one check in flight per build, cancelled on timeout or at the end.
Final: minor (deferred): the lava rule judges Claude's entries, not what placement makes of them (two entries for one spot where the lava sorts last; a bed's head beside lava) — keep the last entry per spot before sorting; admit two-block footprints whole.
Final: minor (deferred): a book page shows about 250–300 characters; the tool's 800 isn't readable in full — say about 250 in the tool description, or split long pages.
Final: minor (deferred): the HUD's death and dimension branches are covered by reading only — extract the decision into a pure function and unit-test it.
Final: minor (deferred): the mod's CLAUDE.md says "as of M4", "all 19 tools" and the old darkness rule — M6's docs pass.
Final: minor (deferred): the HUD keeps § codes in Claude's title and phase names; its limits (TEXT_MAX, PHASES_MAX, STALE_NANOS) live outside Tunables.
Final: Ruling: valuable items (diamonds, netherite) in chests and frames stay allowed: the owner's own build in the owner's own world, and creative-only and unbreakable items are now refused — cost if wrong: a survival world's balance, the owner's call.
Final: Ruling: one color and glow for both sides of a sign stands (Task 3) — cost if wrong: no two-colored signs.
Final: Ruling: the HUD's look and its ✔/▶ glyphs stand; the demo's and the live castle's screenshots were looked at — cost if wrong: taste.
Final: Ruling: PhaseProgress's word heuristics stand (Task 5) — cost if wrong: a shared word skips the panel ahead.
Final: Ruling: the darkness thresholds (sky light 13 or less, no flood fill) stand (Tasks 2 and 5); the Nether case is the deferred minor above — cost if wrong: a spot by a window isn't listed.
Final: Ruling: the feel's volume and particle count stand for Frankie's play-test — cost if wrong: taste.
Final: Ruling: the HUD's bar counts tool calls only: the cap builds reach first (the castle used 107 of 150 calls and 108k of 500k tokens) — cost if wrong: a token-heavy build's bar understates it.
Final: Ruling: fires lava didn't light (campfires, lightning) and the world changing after the survey stay outside §9's rule, which reads the world when the build starts — cost if wrong: a fire the rule couldn't have stopped.
Final: Ruling: the live test's camera moves are test-only and live-only; the M6 drafts in the working tree were outside the range and are M6's — cost if wrong: none.
