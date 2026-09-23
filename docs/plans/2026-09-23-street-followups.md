# Street-first follow-ups

Closes the follow-ups the street-first run left open (see `docs/plans/2026-09-17-street-first-growth.md`), plus the
older test gaps from runs `works` and `street`.

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
- A test must not depend on where the GameTest framework places its structure: a rule that hashes absolute
  positions (such as `PlotRules.skipped`) gives different answers on every run
- Every block a citizen or the village breaks or places goes through `WorldPermissions`
- Jobs keep no saved state; anything that must survive a reload lives in `VillageData` and is saved through
  `VillageCodecs`
- Match the surrounding code: Javadoc on public types and non-obvious methods, no FQNs in code, sorted imports
- Decompiled 1.21.1 and NeoForge sources: `~/.cache/villagercity-research/src/` (use `/usr/bin/grep -a`)
- Every new regression test must fail for the stated reason when the behaviour it guards is removed; prove it with
  a mutant and report the mutant
- A defect named below is reproduced with a failing test before it is fixed. If it cannot be reproduced, report
  that with the evidence instead of changing the code
- Commit messages: single-line conventional commits in English. The author is Andrey Mudri only: never add
  `Co-Authored-By`, session links or any tool attribution

## Destination

Every street-first follow-up is either fixed with a guarding test or reported as not reproducible. The README's known
issues describe what is left.

## Out of Scope

- Street junctions, squares and crossings — a road network is a separate design, as the street-first plan says.
- Houses facing the street — the blueprint has no facing rule yet.
- Rewriting the published commit `cb5c227` — the user decided to leave history as it is.

## Not Yet Specified

- Should a street stop at a village's edge, or should reaching the edge grow the village radius?

### Task 1: doors are closed in every case, and the tests prove it

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/task/MoveTo.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/MovementTests.java`

**Depends:** none

**Model:** mid

- [ ] **Step 1:** Test that a door is closed once the citizen is through it and more than `DOOR_REACH` past it,
      while the move is still running (a far target). Deleting `closePassedDoors` from `MoveTo.tick` must fail it.
- [ ] **Step 2:** Test that a door is closed when the citizen's chunk unloads: remove the villager with
      `RemovalReason.UNLOADED_TO_CHUNK` on the tick the door opens. Limiting `CitizenRoster.onLeave` to removal
      reasons where `shouldDestroy()` is true must fail it. If the door's own chunk cannot be written during that
      event, report it and close the door by another means inside `MoveTo`.
- [ ] **Step 3:** Pull the "which doors may a citizen open" rule into one small public static predicate on `MoveTo`
      (a closed `DoorBlock` in `BlockTags.WOODEN_DOORS`), and test it directly with oak, iron and copper doors. The
      pathfinder never routes through an iron door, so only a direct test can guard the wooden-only check.
- [ ] **Step 4:** `OPENERS` is a `WeakHashMap` keyed on the villager, but each `MoveTo` in its value holds the
      villager strongly (`opener`), so no entry can ever be collected. Make the map really weak, for example by not
      holding the villager strongly in the move, and fix the comment. Keep every existing door test green.

### Task 2: a skipped lot is a real gap, a one-cell street keeps its way clear, and the timing test stops flaking

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotPlanner.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotRules.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/PlotPlannerTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/UnevenPlotTests.java`

**Depends:** none

**Model:** capable

- [ ] **Step 1:** One pad in eight is skipped (`PlotRules.skipped`, a hash of the pad origin), but pads are tried at
      step 1, so a neighbouring pad a block away takes the same lot and the gap rarely appears. Change the rule so a
      skipped lot stays empty: no house footprint overlaps it, and roughly one lot in eight along a street stays open.
      A village must still fill its streets otherwise. Test the rule in a way that does not depend on where the
      GameTest structure is placed.
- [ ] **Step 2:** A street end whose street is a single cell has no parent cell, so no slice ahead of it is kept
      clear, and a pad beside it can cap it. Take the direction from the bell to that cell (its dominant horizontal
      axis), and keep that slice clear like any other end. Four `UnevenPlotTests` seed a single street cell and broke
      when this was tried before: keep what each of them tests, and move only their pad expectations where the new
      rule moves the pad.
- [ ] **Step 3:** `aFailedSearchOfALargeVillageIsCheap` measures wall-clock time and fails when the machine is
      loaded. Make it robust under load (for example by bounding the work the search does rather than its time),
      while it still fails for a search made about ten times costlier. Prove both: run it with other Gradle builds
      running at the same time, and with that mutant.

### Task 3: the builder sees the columns the paver gave up on

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/job/StreetWork.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/BuilderJob.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/PaverJob.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/StreetGrowthTests.java`

**Depends:** none

**Model:** capable

- [ ] **Step 1:** `BuilderJob` decides whether the village is street-bound by calling `StreetWork.nextRun(level,
      village)`, which ignores the paver's in-memory `refused` columns. When the graph is empty and the paver has
      given up on the first slice of every direction from the bell, the paver waits for "room to grow" while the
      builder waits for "a street to build on", and nothing happens until a reload. Reproduce it with a GameTest (a
      paver and a builder on the roster, every first slice a cell `WorldPermissions` refuses or the paver cannot
      reach), then make the builder see the same refusals, so that it falls back to the old search. Refusals stay
      transient: they are not saved.

### Task 4: the street graph and the artisan tests close their gaps

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/craft/VillageDemand.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/ArtisanTests.java`

**Depends:** none

**Model:** mid

- [ ] **Step 1:** `VillageData.streetEnds()` counts a cell as grown on only when a later cell with hops no lower lies
      within one block. Test the hop clause: a later cell with lower hops beside it must leave it an end. Also test
      that `addStreetCell` for a cell already in the graph keeps its first hop count and does not add it twice.
- [ ] **Step 2:** `ArtisanJob.pay` chooses one item per ingredient slot from spare stock. Test the case where two
      slots of one recipe accept the same item and stock holds only enough for one of them.
- [ ] **Step 3:** The README says the artisan can order again materials the builder is already carrying. Reproduce
      it with a test (a builder carrying a house's planks while the artisan plans), then have `VillageDemand` count
      what the village's builders carry. If it does not reproduce, report the evidence.

### Task 5: the README says what is left

**Files:**
- Modify: `README.md`
- Modify: `src/main/java/dev/andreymudri/villagercity/command/PlotDiagnostics.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/VillageCommandTests.java`
- Test: `src/main/java/dev/andreymudri/villagercity/gametest/ArtisanTests.java`

**Depends:** T1, T2, T3, T4

**Model:** mid

- [ ] **Step 1:** Update the README's known issues and the street-first section to match what T1-T4 actually
      changed. Remove the lines about the single-cell street and about the artisan re-ordering what the builder
      carries, but only where the tasks fixed them. Describe the new skip rule.
- [ ] **Step 2:** `command/PlotDiagnostics.java` still calls `PlotRules.skipped(padOrigin)` and says "one pad in 8".
      T2 moved the skip to whole lots (`PlotRules.coversSkippedLot`, `LOT_SIZE`). Report a pad as skipped when its
      footprint covers a skipped lot, word it as lots, and keep `VillageCommandTests` green. A test that depends on
      where the structure is placed is not allowed.
- [ ] **Step 3:** `VillageDemand`'s no-plot branch (it sums what every builder carries) has no test. Add one to
      `ArtisanTests`: a roster builder carrying the starter house's planks, no plot and an empty storehouse must
      produce no plank order. Dropping that subtraction must fail it.
