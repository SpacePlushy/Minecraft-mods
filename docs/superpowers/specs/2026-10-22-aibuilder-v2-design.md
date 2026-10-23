# AI Builder v2: builds that move and builds that work — Design Spec

- **Date:** 2026-10-22
- **Status:** Frankie skipped the design questions ("make the design calls yourself, tell me what you decided, and
  start"), so every decision here is *Claude*'s and is reported to Frankie as made.
- **Builds on:** the v1 spec, `2026-10-21-aibuilder-design.md`, which still holds everywhere this one is silent
  (the frame and directions, the tools' rules, protection, the lava rule, undo, refinement, the caps, who may
  build, Claude Code on Frankie's own login, no API key, no paid extra usage, build tools only).
- **Frankie's request, word for word:** "i want it to be able to build things like a working calculator and
  things like that. I want to say things like, make a dragon with flapping wings. and i want it to do that. can
  you find out what this would require and build it into this mod?"

## 1. Intent

v1 builds things that stand still. v2 adds two kinds of build:

- **Builds that move.** "A dragon with flapping wings" gives a dragon whose wings beat, its tail sways and its head
  bobs. They keep moving when Frankie leaves and comes back, and after the game restarts. Windmills turn, water
  wheels roll, a dragon circles a tower, a pendulum swings.
- **Builds that work.** "A working calculator" gives a redstone machine that really computes. Frankie sets two
  numbers on levers and the sum lights up in digits. Doors that open on a button, clocks, counters, lamp
  displays and the like work too, because Claude tests its machine before it says it's done.

Success: those two prompts, typed as they are, produce a dragon whose wings visibly flap, and a calculator that
gets every sum right, with no client mod and no stutter.

Anti-goals (failure even if it technically works): a world that needs a client mod to look right; motion that
makes the game stutter; a machine that works only after the player pokes it; a "working" machine Claude never
tested; anything that loosens v1's safety rules (owner only, build tools only, no API key, never paid extra
usage, never damaging what the player built).

## 2. What it takes (the findings behind the design)

- **Motion.** Blocks can't move. Swapping blocks frame by frame looks choppy and puts solid blocks in the
  player's way. Vanilla's **block display** entities can move: each draws one block, with a position, a rotation
  and a scale. The game client glides it from one pose to the next by itself (`interpolation_duration`), which is
  how datapack makers animate models. They have no collision, and a vanilla client draws them, so the world
  needs no client mod. In 26.3 their setters are private (`Display.setTransformation` and the rest), so the mod
  reaches them through an accessor mixin. Setting the interpolation delay forces a fresh glide even when the
  value repeats (`SynchedEntityData.set(…, true)`), so a pose a few ticks apart glides smoothly.
- **Redstone.** The mod places blocks the way `/fill … strict` does (`UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS`):
  no neighbour updates and no `onPlace`. That keeps water still and sand in place while a build goes up. It also
  means redstone the mod builds stays dead: a lever placed on and a lamp beside it stay dark until something
  nudges them. That's true in v1 today.
- **Designing circuits.** Claude knows redstone, but a big circuit designed blind is almost never right the first
  time: signal strength runs out after 15 blocks, repeaters face one way, torches invert, timing matters. A
  machine is "working" only if it's tested. So Claude needs a way to try its machine (flip inputs, let the world
  run, read the outputs), and parts that are known to work, so a calculator is put together from tested adders
  and digit displays instead of hundreds of hand-placed gates.

## 3. Decisions

| Decision | Choice | Why |
|---|---|---|
| How things move | Block displays, driven by a server-side engine in the mod | The only vanilla way to get smooth motion of block-looking shapes; no client mod |
| Reaching the displays | An accessor mixin for the private setters | They're private in 26.3; an accessor changes no game behaviour |
| Motion vocabulary | `swing` (back and forth through an angle), `spin` (round and round), `bob` (back and forth along a direction), combinable, with parts nested in parts | Covers wings, tails, heads, windmills, wheels, pendulums, doors, and a dragon circling a tower (a spin about a pivot outside the part), with a small set Claude can reason about and the mod can check. Keyframe timelines can come later |
| Time | A part's pose is a pure function of the world's game time since its build began | Stateless: it resumes exactly after a restart, nested parts stay in step, and it's unit-testable |
| Update rate | A new pose every `ANIMATION_STEP_TICKS` (4), gliding over the same 4 ticks | Smooth to the eye, a quarter of the network traffic of every tick |
| Budget | `max_moving_blocks` per build (default 2,000, up to 10,000); `moving_parts` (default `true`; `false` freezes every moving part) | Each moving block is an entity, drawn and sent to the client |
| Checking motion | A `check_motion` tool: each part's box at the ends of its motion, and the build's own solid blocks it passes through | Claude can't see; this tells it a wing flaps up, not into the body |
| Redstone that works | A **power-up pass** once a build's blocks are in: each redstone component is told it was placed and its neighbours are told, once, on the tick budget | Machines start as if built by hand, while water, sand and leaves still get no updates |
| Testing machines | A `test_redstone` tool: flip the build's levers, press its buttons, wait, read lamps, power levels and pistons, then put the inputs back | A machine counts as working only once tested; Claude is told to test before `finish` |
| What a machine may touch | Strict: a machine moves and powers only the build's own blocks. A piston's reach holds only the build's blocks or air inside the box, and never slime or honey (no flying machines); no build redstone beside redstone-reacting blocks that aren't the build's; dispensers hold only items they'd simply drop; pistons wait while a player is in their way. Clocks and other never-ending machines are allowed | v1 never lets a build harm what the player made, and §5.5 never moves the player. A machine that worked would now act on the world after the build, where v1's write checks don't reach (builder check, 2026-10-22) |
| Live runs | Each milestone's live build must pass its own check (§5.6). At most three live attempts a milestone, each failure fixed in the tools, the parts or the prompt, never by loosening the check. If M9's third calculator still fails, a ready-made `calculator` part is added, it's run once more, and the report says which way it got there | One live run is one sample of a model whose output varies; this caps the usage spent on Frankie's plan (about nine builds in all, each within the caps) |
| Ready-made parts | A library of tested circuit parts (`place_gadget`) with named ports, and a `wire` tool that runs redstone between ports | Big machines are put together from parts that work, not built gate by gate |
| The calculator | Adds two numbers from 0 to 15, set on two rows of four levers, and shows the sum (0 to 30) on two seven-segment digits | Clearly a working calculator, and buildable from the library. A keypad with multi-step entry is later |
| Order | M7 builds that move; M8 redstone that works, and testing; M9 the parts library, wiring and the calculator | Motion is lower risk and stands alone; M9 needs M8 |
| Process | As v1: a plan per milestone, TDD, a fresh Opus 5.5 review, fixes, a live build on Frankie's login, screenshots sent to Frankie. No stops between milestones unless Frankie asks | Frankie asked me to start and make the calls; v1's last four milestones ran the same way |
| Branch | `aibuilder-v2`, from `aibuilder-minors` (pull request #2, not yet merged) | v2 builds on those fixes; once #2 merges, v2's pull request shows only v2 |

## 4. Builds that move (M7)

### 4.1 Building a moving part

- Every write tool (`fill`, `wall`, `line`, `cylinder`, `sphere`, `cone`, `arch`, `roof`, `staircase`,
  `set_blocks`) takes an optional `part` name. A shape with a part goes in as block displays, one per block, at
  the same grid positions and with the same block states (directions relative, as always), instead of as world
  blocks. Everything else in the build stays ordinary blocks, so a dragon can have a solid body Frankie can stand
  on and wings that move.
- A display changes no world block. The tools still check it the same way as a block: inside the allowed box,
  not over a protected (player-made) block, not a banned block. The lava rule doesn't apply: a display can't
  burn or light anything.
- Part names are short words (`left_wing`, `tail`). A part may be built over several calls, and the same name
  goes on adding to it.
- Displays are placed with the build's other blocks, in order, through the placement job, journaled for undo
  (as v1 journals item frames), and tagged as the mod's.
- `max_moving_blocks` caps a build's displays; past it, the shape is refused `too_many`, saying how many are left.

### 4.2 Giving a part its motion

`animate(part, pivot, motions, parent?)`:

- `pivot`: a local position, half-blocks allowed (`[2.5, 6, 0.5]`), that the part turns about.
- `motions`: one or more of
  - `{"swing": {"axis": "forward", "from": -30, "to": 40, "period": 40, "phase": 0}}`: turns back and forth
    between two angles in degrees, smoothly (a sine), once each `period` ticks;
  - `{"spin": {"axis": "up", "period": 200, "phase": 0}}`: a full turn each `period` ticks (negative: the other
    way);
  - `{"bob": {"direction": "up", "distance": 0.5, "period": 60, "phase": 0}}`: moves back and forth along a
    direction by up to `distance` blocks.
- Axes and directions are the relative words (forward, back, left, right, up, down). An angle's sign follows
  one plain rule, given in the tool's text with examples: a positive angle turns clockwise as you look along the
  axis (about `forward`, the part's top moves to the right). The rule is about what's seen, because the local
  frame is a mirror image of the world's axes: the maths makes it so in every facing.
- `phase` (0 to 1) shifts a motion in its cycle, so a tail can lag the wings.
- `parent`: another part this one rides with. A wing whose parent is `body` follows the body as it spins round a
  tower and flaps as well. No cycles; at most 4 levels.
- Limits: `period` from 10 to 2,400 ticks, angles from −180 to 180, `distance` up to 16 blocks, at most 8 motions a
  part and 32 parts a build. Calling `animate` again on a part replaces its motion.
- A part with no motion stands still, as a plain display (useful for shapes between whole blocks).

### 4.3 The engine

- On the server tick, every `ANIMATION_STEP_TICKS` ticks, for each moving build in a loaded chunk, the engine
  works out each part's pose at the world's game time and gives each of the part's displays its new transformation,
  with an interpolation of the same number of ticks. It runs within the mod's tick time budget, spreading a big
  build's displays over ticks if it must.
- The pose maths (motions summed in order, parents composed, a display's transformation about the pivot) is pure
  Java in `core`, with its own small vector and quaternion types, unit-tested. The world side only converts to
  vanilla's types and sets the entities.
- Displays in unloaded chunks aren't touched; when their chunk loads, the next step poses them.
- `moving_parts: false` in the config stops the engine: every part stays where it is, and starts again when it's
  turned back on.

### 4.4 Saved with the world, undo, refinement

- A world's moving builds are saved data (`AnimationStore`): per build, its frame, its parts (name, pivot,
  motions, parent, displays), and the game time it began. Builds whose data can't be read are skipped with a
  warning, as `UndoStore` does.
- `/aibuild undo` removes the build's displays (they're in its journal) and its moving parts with them.
- A refinement ("make the wings flap faster") picks up the build's parts: `get_context` lists them with their
  motions, `animate` changes a part's motion, and a write with an existing part name adds to it. Undoing the
  refinement puts the motions back as they were.
- A world opened without the mod keeps the displays where they last stood.

### 4.5 `check_motion`

For each part: the box it fills at rest, the boxes at each end of its motion (or, for a spin, at a quarter-turn
apart), and how many of the build's own solid blocks a part passes through in a full cycle, with the first few
positions. Read-only, like `check_build`; the system prompt tells Claude to call it after animating.

### 4.6 What Claude is told

`tools.json` describes `part`, `animate` and `check_motion` with examples (a flapping wing about its shoulder, a
windmill, a dragon circling a tower through a parent). The system prompt gains a short section: moving parts are
for things that move; keep the solid body as blocks; mind the budget; always check the motion.

### 4.7 Tests

- Unit: poses at given times (swing ends and middle, spin quarter turns, bob, phase, nesting, a pivot outside the
  part); the angle-sign rule in each facing; limits refused with the right reason.
- Server gametests: a scripted build with a part spawns displays where its blocks would be, and no world blocks
  there; the displays' transformations change between two times and match the pure maths; the budget refuses past
  it; the saved data survives a save and reload and the motion carries on; undo removes the displays and the
  part.
- Client gametest: a scripted model (a body block and a hinged wing) pictured at two moments, the wing up in one
  and down in the other (look at the screenshots).
- Live (Frankie's login, `AIBUILDER_LIVE=1`): "a dragon with flapping wings", pictured at several moments; the
  screenshots go to Frankie.

## 5. Builds that work (M8, M9)

### 5.1 Redstone that works when the build ends (M8)

Once a build's blocks and displays are in (after the shape pass), a power-up pass goes over every position the
build changed that holds a redstone component (dust, torches, repeaters, comparators, observers, levers,
buttons, pressure plates, pistons, lamps, doors, trapdoors, fence gates, note blocks, dispensers, droppers,
hoppers, rails, daylight detectors, targets, sculk sensors and the like) and runs, once, what placing it by hand
would have: its `onPlace`, then a neighbour update around it. It runs within the tick time budget, in the build's
placement order. Nothing else in the build gets updates. A build that refines a machine gets the pass for what it
changed.

The pass also runs over the build so far before each `test_redstone`, so a test sees a live machine.

A machine changes things as it runs (a lamp lights, a piston pushes a block somewhere the build never placed
one), and v1's undo takes back only the positions the build wrote. So undo of a build with levers first puts the
build's own levers back as they were placed and lets the machine settle (bounded ticks: a door someone opened
closes), then takes the build back as v1 does. A block one of the build's own clocks moved stays, as anything
changed after a build does in v1; what it can move is only the build's own (§5.5).

### 5.5 What a machine may touch (M8)

A machine that works acts on the world after the build, where v1's write checks don't reach. So it may move and
power only the build's own blocks:

- **Pistons.** A piston's reach (the blocks it would push, up to 12 and its head; for a sticky piston, the block
  it pulls) must hold only the build's own blocks or air, inside the allowed box, and never a slime or honey block
  (so no flying machines, which would leave the box).
- **Power.** None of the build's redstone components may be within 2 blocks of a redstone-reacting block that isn't
  the build's (a door, trapdoor, fence gate, piston, lamp, rail, note block, dispenser, dropper, hopper, TNT…).
- **Players.** The power-up pass and `test_redstone` leave a piston alone while a player stands in its reach, and
  say so (bounded waiting, as placement waits); a test that would push the player waits too.
- **Dispensers.** `place_block_entity` puts in a dispenser only items it would simply drop: nothing with a dispense
  behaviour of its own in vanilla's registry (fluids in buckets, fire charges, flint and steel, explosives,
  arrows and other projectiles, potions, bone meal, minecarts, boats, armour, shulker boxes and the like). A
  dropper may hold anything else the build may place.
- **Checked twice.** `check_build` gains two problems, `piston` (a reach that breaks the rule above) and
  `powers_outside`, so Claude fixes them before `finish`; and the power-up pass and every test leave out a piston or
  component that breaks them, so nothing outside the build is ever pushed or powered by it.

Clocks and other machines that never stop are allowed: they drive only the build's own parts.

### 5.6 What the live runs must show

- **M7:** "a dragon with flapping wings": screenshots half a flap apart differ at the wings, and `check_motion` was
  called.
- **M8:** "a lever that opens a piston door": the test flips the lever and the door opens, flips it back and the
  door closes.
- **M9:** "a working calculator": the test itself (not only Claude) reads the right digits for 0 + 0, 6 + 3, 9 + 7,
  12 + 5 and 15 + 15.

Each within the caps (M9's live run raises them to 400 tool calls and 90 minutes, as the showcase raised its own).
At most three attempts a milestone, as §3 says.

### 5.2 `test_redstone` (M8)

`test_redstone(steps)` runs a short experiment on the build's own machine, in the world, over ticks:

- steps: `{"set": [x, y, z], "on": true}` (a lever the build placed), `{"press": [x, y, z]}` (a button the build
  placed), `{"wait": ticks}`, `{"read": [[x, y, z], …]}`;
- a read says, for each position, what's there and its state: a lamp lit or not, a dust's power level, a
  repeater or comparator on or off, a piston extended, a door open, a torch lit;
- afterwards every lever is put back as it was, and the world gets the time to settle;
- limits: 64 steps, 400 ticks waited in all, 32 positions a read; only the build's own levers and buttons, only
  inside the allowed box; it waits for queued placement first, as `check_build` does.

The system prompt tells Claude a machine isn't done until it's been tested for every case that matters (each
input combination for a small one; the edge cases for a big one), and to say in its summary what it tested.

### 5.3 Ready-made parts and wiring (M9)

- `place_gadget(kind, at, facing, options)` stamps a part from the library at a local position, facing a relative
  direction, and answers with its size and its ports: named positions with what they are (input or output, and
  how to drive or read them). Kinds:
  - `lever_row` (n levers, labelled on signs) and `lamp_row` (n lamps);
  - `adder` (1 to 8 bits, ripple carry: A + B → sum and carry);
  - `to_decimal` (up to 8 bits → two or three decimal digits, as 4-bit codes);
  - `digit` (a seven-segment display of lamps, with its decoder, driven by a 4-bit code);
  - `clock` (a pulse every n ticks), `pulse` (a short pulse when its input turns on), `toggle` (each pulse flips
    it), `latch` (n bits, set and held), `counter` (n bits);
  - `piston_door` (2×2 or 3×3, open while powered).
- Every gadget is generated by pure Java (its blocks in a local frame), and verified by server gametests in all
  four facings: each input combination for the small ones, sampled cases for the big ones.
- `wire(from, to, via)` runs redstone from an output port to an input port (or a row of ports to a row of the same
  size) along the waypoints Claude gives: dust on a support block, a repeater at least every 15 blocks pointing
  the signal's way, climbing a level by a step of blocks. It refuses to cross or touch another wire on the same
  level, and says where, so Claude routes over it.
- Gadgets and wires are ordinary blocks under every v1 rule (box, protection, banned blocks, undo), and the
  power-up pass and `test_redstone` work on them as on anything else.

### 5.4 Tests (M8, M9)

- M8 server gametests: a lever placed on, with a lamp beside it, lights once the build ends; a torch NOT gate
  reads right; `test_redstone` flips a lever, reads a lamp, and puts the lever back; water and sand in the same
  build stay put; undo of a piston door left open puts its lever back, lets it close, and leaves nothing behind;
  a piston whose reach holds a player-made block is a `piston` problem and stays unpowered; a piston that would push
  slime is a `piston` problem; a lever beside the world's own TNT is `powers_outside` and the TNT stays unpowered; a
  dispenser refuses a lava bucket and takes a stack of cobblestone; the power-up waits while a player stands in a
  piston's reach.
- M9: each gadget's truth table in four facings; `wire` runs, turns, climbs and refuses a crossing; a scripted
  calculator built only from gadgets and wires shows 9 + 7 = 16 and 15 + 15 = 30.
- Live: M8, "a lever that opens a piston door"; M9, "a working calculator", tested by Claude itself, then by the
  test reading every digit for a sample of sums. Screenshots go to Frankie.

## 6. Left to each milestone's plan

Exact tool schemas and wording, the gadgets' layouts, the wire's step and crossing rules, how displays are tagged
and journaled, the engine's spread over ticks, and tunable values not stated here.
