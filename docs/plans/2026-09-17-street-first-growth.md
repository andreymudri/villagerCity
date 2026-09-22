# Street-first village growth

Implements `docs/specs/2026-09-17-street-first-growth-design.md`.

## Global Constraints

- Minecraft 1.21.1, NeoForge 21.1.250, ModDevGradle plugin `net.neoforged.moddev` 2.0.147
- Java 21: `export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`
- `/tmp` is a quota-limited tmpfs: `export TMPDIR=$HOME/.cache/tmp-villagercity` before any Gradle command
- Gradle commands always pass `--no-daemon -Dorg.gradle.workers.max=4`
- Every task leaves these green: `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build`, `scripts/gametest.sh`,
  `python3 tools/nbt_structures.py --check`
- Mod id `villagercity`, base package `dev.andreymudri.villagercity`
- No mixins, no access transformers, no new dependencies
- GameTests live in `src/main/java/dev/andreymudri/villagercity/gametest/`, annotated `@GameTestHolder(VillagerCity.MODID)`
  + `@PrefixGameTestTemplate(false)`, template `GameTestSupport.TEST_AREA`
- Every GameTest that registers a village, changes a game rule or changes day time has its own unique `batch` name
  starting with `vc_`, and restores game rules and day time on every exit path
- No `succeedWhen` or `onEachTick` callback may throw a raw `AssertionError`, `NoSuchElementException` or
  `IndexOutOfBoundsException`: the GameTest server crashes instead of reporting. Use `helper.fail` /
  `GameTestAssertException`
- Tests remove the villages they create (`VillageTestSupport.remove`) before succeeding
- Use `helper.absolutePos` / `pos.subtract(helper.absolutePos(BlockPos.ZERO))`, never `helper.relativePos`
- Every block a citizen or the village breaks or places goes through `WorldPermissions`
- Jobs keep no saved state; anything that must survive a reload lives in `VillageData` and is saved through
  `VillageCodecs`
- Match the surrounding code: Javadoc on public types and non-obvious methods, no FQNs in code, sorted imports
- Decompiled 1.21.1 and NeoForge sources: `~/.cache/villagercity-research/src/` (use `/usr/bin/grep -a`)
- Every new regression test must fail for the stated reason when the behaviour it guards is removed
- Commit messages: single-line conventional commits in English. The author is Andrey Mudri only: never add
  `Co-Authored-By`, session links or any tool attribution

## Destination

A village on a mountain grows: the paver lays 3-block-wide streets that climb the slope, houses attach to those
streets at the street's own height with at least 5 blocks between neighbours, and `/villagercity village` shows the
street graph. No plot is ever refused for being uneven —
only for costing the paver more than it can move.

## Out of Scope

- Street junctions, squares and crossings — a street is a run of cells, and a road network is a separate design.
- Houses facing the street — the blueprint has no facing rule yet, so it would be invented here rather than implemented.
- Moving houses the old rule already placed — they stay and count as anchors, because relocating a built house means
  demolition, which nothing in the mod does.

## Not Yet Specified

- Should a street stop at a village's edge, or should reaching the edge grow the village radius?
- When two streets from different ends meet, should they join into one graph or stay separate runs?

### Task 1: the street graph on the village

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageWorks.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/command/VillageCommand.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/command/VillageOutline.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`

- [ ] **Step 1:** Add `record StreetCell(BlockPos pos, int hops)` to `VillageWorks`, with a codec, and a
      `List<StreetCell> streets` field beside the existing `pathCells`/`pathQueue`. `pathCells` keeps its meaning (every
      laid path column) and `streets` records the graph: one entry per laid cell with its hop count.
- [ ] **Step 2:** In `VillageData`, add `streets()`, `streetEnds()` (cells no other cell grew from, i.e. the frontier),
      `addStreetCell(BlockPos pos, int hops)` (which also calls the existing `addPathCell`), and
      `deepestHops()`. Keep `isPathColumn` as it is.
- [ ] **Step 3:** Add `openedDoors()` and `recordOpenedDoor(BlockPos)` / `clearOpenedDoor(BlockPos)` to `VillageData`,
      saved through the codec, so a door the paver opened is closed after a restart (spec, "Doors").
- [ ] **Step 4:** Extend `VillageCodecs` for both new fields, defaulting to empty so old saves load.
- [ ] **Step 5:** Add to `VillageCommand.describe`: `streets: N cells, M ends, deepest H hops`.
- [ ] **Step 6:** `VillageOutline` draws the street graph: one dust per street cell, in a colour distinct from the four
      it already uses (white village, orange search, blue plots, green houses), so `/villagercity show` makes the streets
      visible as they grow. Street ends get a second dust a block higher, so the frontier is readable at a glance.
- [ ] **Step 7:** Tests in `VillageRegistryTests`: a village with three street cells round-trips through the codec with
      hops intact; `streetEnds` returns only the frontier; an old save with no `streets` field loads with an empty graph;
      a recorded opened door survives a round trip.

### Task 2: the paver grows streets

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/job/StreetWork.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/PathWork.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/PathRoute.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/PathTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageWorksEndToEndTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/StreetTests.java`

**Depends:** T1

**Model:** capable

- [ ] **Step 1:** `StreetWork.nextRun(ServerLevel, VillageData)` picks the street end with the fewest hops, preferring
      ends whose ground is level with the cell they grew from, and returns the direction to grow: away from the bell,
      never back over an existing cell.
- [ ] **Step 2:** `StreetWork.plan(TaskContext)` lays up to `RUN_LENGTH = 8` cells from that end, each at most
      `MAX_STEP = 1` block above or below the previous, reusing `PathRoute`'s cell kinds and `PathWork`'s cell building
      (headroom clearing, support placement, `MakePath` surfacing) so both jobs build a cell the same way.
- [ ] **Step 3:** A run stops early at `REACH = 64` blocks from the bell (Chebyshev), at a fluid it cannot bridge, at a
      house, plot or storehouse footprint, or where the next cell would need more than one block of step. Each laid
      centre cell is recorded with `addStreetCell(pos, endHops + 1)`.
- [ ] **Step 3b: streets are 3 wide** (user decision, 2026-09-22; spec "Width"). `WIDTH = 3`. Each centre cell is laid
      with the two cells beside it across the run, at the centre cell's height, through the same cell building
      (cut, fill or plank bridge). A side cell whose ground is more than `SIDE_STEP = 2` blocks off the centre stops the
      run there, as does a side cell on a house, plot, storehouse or foreign path cell. All three cells go through
      `addPathCell`; only the centre goes into the graph.
- [ ] **Step 4:** Growth stops at `MAX_HOPS = 6`: an end at that depth is never extended.
- [ ] **Step 5:** Delete the house-to-bell path job from `PathWork`: `VillageData.addHouse` no longer queues a path, the
      queue drains to empty, and `PathWork` keeps only the cell-building helpers `StreetWork` uses. Remove the door
      opening entirely — no route starts at a house door any more — and with it `OPEN_DOORS`, `closeIfClear` and
      `doorOutside`. A door recorded on the village from an older save is closed once and cleared (T1 step 3).
      `PathTests` (30 GameTests) exercises the deleted house-to-bell job: queueing, routing from a door, door opening
      and the no-route backoff. Delete the tests of deleted behaviour. Keep, pointed at the helpers `StreetWork` now
      uses, every test of how a cell is built: headroom cutting, support fill, plank bridges, `MakePath` surfacing,
      and `WorldPermissions` refusals. Say in the commit which tests went and why.
- [ ] **Step 5b (amended 2026-09-22):** `VillageData.addHouse` stops queueing a path. Update the
      `VillageRegistryTests` assertion that pinned the queueing so it pins the opposite: a finished house queues
      nothing. In `VillageWorksEndToEndTests.aHillVillageGrowsOnItsOwn`, remove the "a path was laid from the house to
      the bell" assertion. No street is laid until T4 wires `StreetWork` into `PaverJob`, and T5 replaces the assertion
      with a street one. Keep every other assertion of that test.
- [ ] **Step 6:** Tests in `StreetTests`: a run climbs a 5-block slope one block per cell; a run stops at the reach
      limit; a run never crosses a house footprint; growth stops at 6 hops; a second run starts from the end the first
      one left; no door is ever opened; every laid centre cell has both side cells laid at its own height and recorded
      as path cells, while only the centre is in the graph; a run stops where a side cell would land on a house, and
      where a side cell's ground is 3 blocks off the centre.

### Task 3: houses attach to streets

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotPlanner.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotRules.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/Earthwork.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/command/PlotDiagnostics.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/UnevenPlotTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/VillageCommandTests.java`

**Depends:** T1

**Model:** capable

- [ ] **Step 1:** Delete `PlotRules.MAX_HEIGHT_VARIANCE` and every use of it. Flatness is no longer a rule.
      `command/PlotDiagnostics.java` uses it in four places and will not compile once it is gone, which is why that file
      and `VillageCommandTests` are in this task's file set: `/villagercity why` must keep explaining a rejected spot,
      against the new rules rather than the old one.
- [ ] **Step 2:** `PlotPlanner.findSite` only returns pads whose footprint plus `MARGIN` touches at least one street
      cell, and sets the pad's floor to that cell's Y. A village with no street cells falls back to today's behaviour so
      a village without a paver keeps working.
- [ ] **Step 3:** A pad is accepted when `Earthwork.volume` at that floor is at most `MAX_VOLUME` (80) and no column is
      more than `MAX_COLUMN_STEP` (6) off the floor; `Earthwork` gains `maxColumnStep`. Natural ground, no fluid, no
      overlap and the loose `±32` cave/overhang clamp stay as they are.
- [ ] **Step 4:** Order candidate pads by: fewest hops on the street cell they touch, then least earthwork, then nearest
      the bell.
- [ ] **Step 5:** Skip one pad in eight, decided by a hash of the pad's origin so the choice is stable across reloads.
- [ ] **Step 5b: houses stand 5 apart** (user decision, 2026-09-22; spec "Spacing"). Add `PlotRules.HOUSE_GAP = 5`. A
      pad is refused when its footprint comes within 5 columns of any house or plot footprint, along x or z. The gap
      applies with or without streets, and not to the storehouse, the workshop or path cells. `PlotDiagnostics.explain`
      names the gap when it is the rule that turned a spot down. Existing GameTests in this task's file set that place
      a second plot beside a first are updated to respect the gap. If one outside it breaks, report `blocked`, naming the
      test, rather than editing it.
- [ ] **Step 6:** `PlotDiagnostics.explain` reports the new rules in place of the deleted one: the hop count of the
      nearest street cell (or that there is none), the earthwork the pad would need against the 80-block budget, the
      worst column against the 6-block step, and which of the four acceptance rules turned the spot down (spec, "Command
      output"). `VillageCommandTests.whyAgreesWithThePlotSearch` pins it to `PlotPlanner`; keep that pin meaningful
      rather than relaxing the test — if the two can disagree, the command is lying to whoever asked.
- [ ] **Step 7:** Tests in `UnevenPlotTests`: a pad touching a street is chosen and its floor equals that street cell's
      Y; a pad needing 90 blocks of earth is refused while one needing 70 is taken; a pad with one column 7 off the floor
      is refused; an uneven pad the old flatness rule would have refused is now accepted; a village with no streets still
      plans as before; the skip is stable when the same search runs twice; a pad 4 columns from a house is refused and
      one 5 columns away is accepted, both with and without streets. In `VillageCommandTests`, `why` explains a
      spot refused for earthwork and names the budget it broke.

### Task 4: the village grows streets when it has nowhere to build

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/job/PaverJob.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/StreetWork.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/BuilderJob.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageTicker.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/UnevenPlotTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/StreetGrowthTests.java`

**Depends:** T2, T3

**Model:** capable

- [ ] **Step 1:** `PaverJob` plans in this order: an unprepared plot to prepare, else a street run from `StreetWork`,
      else `waitingFor = "room to grow"`.
- [ ] **Step 2:** `BuilderJob` claims only street-attached pads (T3 does the choosing; the builder just stops treating
      "no plot" as a failure when the village has no streets yet) and reports `waitingFor = "a street to build on"`.
- [ ] **Step 3:** The first street run of a village starts at the bell: `StreetWork` treats the bell's own cell as the
      root end at hop 0 when the graph is empty.
- [ ] **Step 3b (amended 2026-09-22):** `UnevenPlotTests.aBuilderWithAPaverClaimsASlopedPlotUnprepared` expects a
      builder with a paver on the roster to claim a sloped plot in a village with no streets, which Step 2 now forbids.
      Give that test's village a street cell beside the terraces (`addStreetCell`), so the builder claims a
      street-attached pad that still needs preparing. The test keeps checking what it was written for: the plot is
      claimed unprepared.
- [ ] **Step 4:** Tests in `StreetGrowthTests`: a fresh village with a paver and a builder lays a street and then builds
      a house on it; on a slope the village crosses a 20-block rise in several hops and never in one; a village whose
      graph cannot grow reports "room to grow"; a village with no paver still builds on flat ground with no streets.

### Task 5: documentation and the whole village end to end

**Files:**
- Modify: `README.md`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/StreetWork.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/PaverJob.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/VillageWorksEndToEndTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/StreetGrowthTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/task/MoveTo.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotPlanner.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/MovementTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/PlotPlannerTests.java`

**Depends:** T4

**Model:** capable

- [ ] **Step 1:** README: describe street-first growth, the numbers (streets 3 wide, run of 8, 6 hops, reach 64, earth
      budget 80, column step 6, houses at least 5 apart, one pad in eight skipped) and what `/villagercity village` now
      prints.
- [ ] **Step 2:** An end-to-end GameTest on a slope: a village with a storehouse, a paver, a builder and a lamplighter
      lays 3-wide streets (restore a street assertion in `aHillVillageGrowsOnItsOwn`, which T2 had to drop), builds two
      houses on them at the streets' own heights and at least 5 blocks apart, and lights
      the ground, with no wrong blocks and no village state left behind. It runs with `skyAccess = true`: under the
      barrier ceiling every column reads as lit.
- [ ] **Step 3:** Two follow-ups from the phase-3 review. (a) A bell on a cliff top: T4 measures the first run's first
      centre against that column's own ground, so a bell at a plateau's edge starts its first street, and its first
      house, at the cliff foot, even when a street level with the bell could leave in another direction. Prefer a
      bell-root direction whose first centre is within `MAX_STEP` of the ground under the bell, and fall back to the
      free first step only when there is none. The bell-on-a-pillar test must still pass. Add a GameTest with a 6-block
      plateau edge beside the bell that ends with the first street level with the bell. (b) Pin the paver's order in a
      test: an unprepared plot and a street that can still grow exist together, and the paver prepares the plot first.
      Swapping `prep.plan` and `streets.plan` in `PaverJob.plan` must fail it.
- [ ] **Step 4:** Two defects the slope test exposed. (a) A builder that finishes a house from the inside cannot
      leave through the closed door and stays "stuck". Likely cause, still to be confirmed with a failing test first:
      MoveTo drives the navigation directly and never sets the brain's PATH memory, so vanilla's InteractWithDoor never
      opens a door on the path. MoveTo opens a closed wooden door on its path when the citizen reaches it, and closes it
      once through. (b) A pad claimed straight ahead of the only street end caps the graph for good. PlotPlanner keeps
      a pad's footprint inflated by the margin off the first slice straight ahead of every street end.
