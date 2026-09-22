# Terrain, Crafting and Lighting: Implementation Plan

Spec: `docs/specs/2026-09-17-terrain-and-lighting-design.md` (approved by the user on 2026-09-17).

## Global Constraints

- Minecraft 1.21.1, NeoForge 21.1.250, ModDevGradle plugin `net.neoforged.moddev` 2.0.147
- Java 21: `export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`
- `/tmp` is a quota-limited tmpfs: `export TMPDIR=$HOME/.cache/tmp-villagercity` (create it with `mkdir -p`) before any Gradle command
- Gradle commands always pass `--no-daemon -Dorg.gradle.workers.max=4`
- Every task must leave these green: `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build`, `scripts/gametest.sh` (reads `build/gametest.log`), `python3 tools/nbt_structures.py --check`
- Mod id `villagercity`, base package `dev.andreymudri.villagercity`
- No mixins, no access transformers, no new dependencies
- GameTests live in `src/main/java/dev/andreymudri/villagercity/gametest/`, annotated `@GameTestHolder(VillagerCity.MODID)` + `@PrefixGameTestTemplate(false)`, template `GameTestSupport.TEST_AREA` (48x48 grass floor at y0, barrier ceiling about 12 above it)
- Every GameTest that registers a village, changes a game rule or changes day time has its own unique `batch` name starting with `vc_`, and restores game rules and day time on every exit path
- Tests remove the villages they create (`VillageTestSupport.remove`) before succeeding
- Use `helper.absolutePos` / `pos.subtract(helper.absolutePos(BlockPos.ZERO))`, never `helper.relativePos`
- Every block a citizen or the village breaks or places goes through `WorldPermissions` (`mayBreak`, `mayGrief`, `snapshot` + `placementCancelled`), as `BreakBlock`, `PlaceBlock` and `StorehouseService` do
- Reuse the existing tasks: `MoveTo` (use `MoveTo.digOut` for storehouse, plot and workshop walks), `BreakBlock`, `PlaceBlock`, `PickUpItems`, `Withdraw`, `Deposit`, `TaskSequence`. Every new `Task` overrides `describe(TaskContext)`
- Jobs keep no saved state; anything that must survive a reload lives in `VillageData` and is saved through `VillageCodecs`
- Match the surrounding code: Javadoc on public types and non-obvious methods, no FQNs in code, imports sorted like the neighbouring files
- Decompiled 1.21.1 and NeoForge sources for checking signatures: `~/.cache/villagercity-research/src/` (use `/usr/bin/grep -a`)
- Every new regression test must fail for the stated reason when the behaviour it guards is removed
- Commit messages: single-line conventional commits in English (`feat:`, `fix:`, `test:`, `docs:`). The author is Andrey Mudri only: never add `Co-Authored-By`, session links or any tool attribution

## Destination

A village on a hill whose storehouse holds only raw materials (oak logs, cobblestone, sand, wool, dye) keeps growing on its own:

- the artisan crafts what the builder and the lamplighter need; it does not smelt (Task 7, Step 8), so glass and
  charcoal are supplied by a player;
- the paver levels uneven plots and lays a walkable path, with steps and bridges, from each new house to the bell;
- the lamplighter lights the village until no ground in it is dark enough for monsters to spawn;
- new houses have a torch inside.

## Out of Scope

- Streets linking houses to each other — the user chose "plot + path to the bell" for this round
- Stairs blocks, slabs and retaining walls on paths — one-block steps are walkable, and shaping comes with more blueprints in sub-project 3
- Lighting caves and building interiors other than the starter house torch — the goal is no monster spawns on village ground
- Crafting items no job demands, and smithing, stonecutting or loom recipes — the artisan works to orders only
- Recipes that leave remainder items (buckets, bottles) — the artisan has no way to handle the remainders

---

### Task 1: foundation: new jobs, hiring, saved village works, plot preparation flag

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/JobType.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/JobAssignment.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/VillagerCity.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/Plot.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageTicker.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/command/VillageCommand.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/task/DigStep.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/VillageWorks.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/PaverJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/SitePrep.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/PathWork.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/LamplighterJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/ArtisanJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/craft/WorkshopService.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/HiringTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageLifecycleTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageCommandTests.java`

This task fixes every shared interface the later tasks build on. Its stubs must compile and do nothing harmful.

- [ ] **Step 1: jobs.** Add these to `JobType`, in this order after `BUILDER`: `ARTISAN("artisan")`, `PAVER("paver")`, `LAMPLIGHTER("lamplighter")`.
- [ ] **Step 2: hiring.** In `JobAssignment`:
  - `SLICE_JOBS = List.of(JobType.LUMBERJACK, JobType.BUILDER, JobType.ARTISAN, JobType.PAVER, JobType.LAMPLIGHTER)`;
  - `employ` gives `LUMBERJACK` a stone axe, `PAVER` a stone pickaxe, and every other job `ItemStack.EMPTY`;
  - update the class Javadoc.
- [ ] **Step 3: plot preparation flag.** `Plot` gains a last record component `boolean prepared`:
  - the 7-argument constructor delegates with `prepared = true`;
  - the 5-argument constructor stays and is also prepared;
  - `VillageCodecs.PLOT` reads and writes `Codec.BOOL.optionalFieldOf("prepared", true)`;
  - `VillageData.releasePlot`, `releasePlotsBuiltBy` and `assignPlot` keep the flag;
  - add `public void markPlotPrepared(UUID plotId)` to `VillageData`.
- [ ] **Step 4: saved village works.** Create `village/VillageWorks.java`:
  ```java
  /** Saved village infrastructure: laid path cells, houses waiting for a path, and the artisan's workshop blocks. */
  public record VillageWorks(List<BlockPos> pathCells, List<BlockPos> pathQueue, Optional<BlockPos> craftingTable, Optional<BlockPos> furnace) {
      public static final VillageWorks EMPTY = new VillageWorks(List.of(), List.of(), Optional.empty(), Optional.empty());
      public static final Codec<VillageWorks> CODEC = RecordCodecBuilder.create(i -> i.group(
              BlockPos.CODEC.listOf().optionalFieldOf("path_cells", List.of()).forGetter(VillageWorks::pathCells),
              BlockPos.CODEC.listOf().optionalFieldOf("path_queue", List.of()).forGetter(VillageWorks::pathQueue),
              BlockPos.CODEC.optionalFieldOf("crafting_table").forGetter(VillageWorks::craftingTable),
              BlockPos.CODEC.optionalFieldOf("furnace").forGetter(VillageWorks::furnace)
      ).apply(i, VillageWorks::new));
  }
  ```
  - `VillageCodecs.VILLAGE` gains the field `VillageWorks.CODEC.optionalFieldOf("works", VillageWorks.EMPTY)`, before `managed`. It stays within the 16-field group limit.
  - The 13-argument `VillageData` constructor gains a `VillageWorks works` parameter before `managed`, and the 3-argument constructor passes `VillageWorks.EMPTY`.
- [ ] **Step 5: `VillageData` API.** The later tasks use exactly these methods:
  - Paths:
    - `List<BlockPos> pathCells()`;
    - `boolean isPathColumn(int x, int z)`, true when any path cell has that x and z;
    - `void addPathCell(BlockPos surface)`, which stores the cell a villager walks on (the air cell above the path block);
    - `List<BlockPos> pathQueue()`;
    - `void queuePath(BlockPos houseOrigin)`;
    - `boolean removeQueuedPath(BlockPos houseOrigin)`.
  - `addHouse(BuildingRecord house)` also calls `queuePath(house.origin())` when `house.blueprint()` starts with `villagercity:`. Vanilla homes (`minecraft:home`) queue no path.
  - Workshop:
    - `@Nullable BlockPos craftingTablePos()` and `void setCraftingTablePos(@Nullable BlockPos)`;
    - `@Nullable BlockPos furnacePos()` and `void setFurnacePos(@Nullable BlockPos)`.
  - `VillageWorks works()` builds the saved record from these fields.
  - Transient, not saved:
    - `int darkSpotCount()` and `void setDarkSpotCount(int)`;
    - `List<String> artisanOrders()` and `void setArtisanOrders(List<String>)`.
  - `occupiedFootprints()` also includes the workshop blocks when they are set.
- [ ] **Step 6: job skeletons.**
  - Create `job/SitePrep.java` and `job/PathWork.java` as `public final class ... implements Job`. `plan` returns null and `waitingFor` returns null. Tasks 3 and 4 replace their bodies.
  - Create `job/LamplighterJob.java` and `job/ArtisanJob.java` the same way, with `waitingFor` returning `"nothing yet"`.
  - Create `job/PaverJob.java` in its final form:
    ```java
    /** Prepares unprepared plots first ({@link SitePrep}), then lays queued paths to the bell ({@link PathWork}). */
    public final class PaverJob implements Job {
        private final SitePrep prep = new SitePrep();
        private final PathWork paths = new PathWork();
        private @Nullable Job last;
        private @Nullable String waitingFor;

        @Override
        public @Nullable Task plan(TaskContext ctx) {
            waitingFor = null;
            Task task = prep.plan(ctx);
            last = prep;
            if (task == null) {
                task = paths.plan(ctx);
                last = paths;
            }
            if (task == null) {
                waitingFor = prep.waitingFor() != null ? prep.waitingFor() : paths.waitingFor() != null ? paths.waitingFor() : "a plot to prepare or a path to lay";
            }
            return task;
        }

        @Override
        public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
            if (last != null) {
                last.onTaskFinished(ctx, task, status);
            }
        }

        @Override
        public @Nullable String waitingFor() {
            return waitingFor;
        }
    }
    ```
  - Register all three new jobs in `VillagerCity`: `Jobs.register(JobType.ARTISAN, ArtisanJob::new)`, and the same for `PAVER` and `LAMPLIGHTER`.
- [ ] **Step 7: workshop hook.** Create `craft/WorkshopService.java` with `public static void ensureWorkshop(ServerLevel level, VillageData village)`, left empty with a Javadoc saying Task 7 implements it. Call it in `VillageTicker.tickVillage` right after `StorehouseService.ensureStorehouse`.
- [ ] **Step 8: dig check.** Make `DigStep.isDiggable(ServerLevel, BlockPos)` public so paths and site preparation use the same natural-ground rule.
- [ ] **Step 9: command output.** In `VillageCommand.describe`:
  - `plotText` appends `, prepared` or `, unprepared`;
  - after the plot lines, add `paths: <pathCells size> cells laid, <pathQueue size> queued`;
  - add `workshop: table <pos|none>, furnace <pos|none>`;
  - add `dark spots: <darkSpotCount>`;
  - when `artisanOrders` is not empty, add `artisan orders: <joined with ", ">`.
- [ ] **Step 10: tests.**
  - `HiringTests.hiresEveryJobOnceInOrder`: 6 adult unemployed villagers and one tick. Exactly one of each job, the paver holds a stone pickaxe, and one villager stays unemployed.
  - `HiringTests.twoVillagersGetLumberjackAndBuilder`.
  - `VillageRegistryTests` round-trips through NBT:
    - a plot with `prepared = false`;
    - path cells, the path queue and both workshop positions;
    - a plot tag without `prepared`, which decodes as prepared.
  - `addHouse` queues a path for a `villagercity:blueprint/starter_house` record and not for `minecraft:home`.
  - `VillageCommandTests` checks the new lines.
  - Fix every existing test broken by the extra hires.
- [ ] **Step 11: verify and commit.** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build`, `scripts/gametest.sh` and `python3 tools/nbt_structures.py --check`. All must be green. Commit: `feat: artisan, paver and lamplighter jobs are hired, and villages save plot preparation, paths and workshop positions`.

### Task 2: plots on uneven ground, and the builder waits for preparation

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotPlanner.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotRules.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/plot/Earthwork.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/BuilderJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/UnevenPlotTests.java`

**Depends:** T1

- [ ] **Step 1: `Earthwork`.** Create `village/plot/Earthwork.java` with pure math, no world access:
  ```java
  /** Cut and fill for levelling columns to one floor height. A column's groundY is its first free y (Column.groundY). */
  public final class Earthwork {
      public static final int MAX_VARIANCE = 6;
      public static final int MAX_VOLUME = 80;
      public record Level(int floorY, int volume) {}
      /** The floor y between the lowest and highest groundY with the least cut + fill; ties go to the higher floor. */
      public static Level best(int[] groundYs) { ... }
      /** Blocks to cut (groundY - floorY when positive) plus blocks to fill (floorY - groundY when positive), summed over columns. */
      public static int volume(int[] groundYs, int floorY) { ... }
  }
  ```
- [ ] **Step 2: planner.** In `PlotPlanner`:
  - Add `public record Site(BlockPos origin, int earthwork)`.
  - Add `public static Optional<Site> findSite(ServerLevel level, VillageData village, Vec3i size, boolean allowEarthwork, Predicate<BlockPos> originAllowed)`. It walks the same spiral and applies the same natural, no-fluid and present checks over the footprint plus `PlotRules.MARGIN`.
  - Without earthwork it keeps today's rule: variance at most `PlotRules.MAX_HEIGHT_VARIANCE`, origin y is the highest groundY, earthwork 0.
  - With earthwork, a spot passing today's rule still counts as earthwork 0. Any other spot is accepted when its variance is at most `Earthwork.MAX_VARIANCE` and `Earthwork.best(...).volume()` is at most `Earthwork.MAX_VOLUME`. Its origin y is `best.floorY`.
  - Skip any footprint whose inflated area contains a column where `village.isPathColumn(x, z)`.
  - The existing `find(...)` overloads delegate with `allowEarthwork = false` and return only the origin, so the storehouse search is unchanged.
  - Keep a failed search of a radius-160 village under 12 ms. The existing `PlotPlannerTests.aFailedSearchOfALargeVillageIsCheap` must stay green.
- [ ] **Step 3: builder.** In `BuilderJob`:
  - `claimPlot` calls `findSite(level, village, size, village.jobCount(JobType.PAVER) > 0, sameOriginPredicate)` and creates the plot with `prepared = site.earthwork() == 0`.
  - `plan` returns null, with `waitingFor = "the paver to prepare the plot"`, while the builder's own plot is not prepared. This check comes before materials are withdrawn.
- [ ] **Step 4: tests (`UnevenPlotTests`).**
  - `aSlopeIsOnlyPlannedWithAPaver`: build a terraced slope with no flat spot, varying by 3 inside the reach. `findSite` with `allowEarthwork = false` is empty, and with `true` it is present, with earthwork above 0.
  - `theFloorIsTheLevelWithTheLeastEarthwork`: hand-built columns with a known best level.
  - `tooMuchEarthworkIsRefused`: variance 6 with a volume above 80.
  - `pathColumnsAreNeverBuiltOn`.
  - `theBuilderWaitsForAnUnpreparedPlot`: an unprepared plot plus full materials leaves the builder idle with that waiting reason. After `markPlotPrepared`, it withdraws materials.
  - `aBuilderWithAPaverClaimsASlopedPlotUnprepared`.
- [ ] **Step 5: verify and commit.** Run the build and the full GameTest suite. Commit: `feat: builders claim sloped plots when the village has a paver, and wait until the plot is prepared`.

### Task 3: the paver levels a plot (site preparation)

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/job/SitePrep.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/FillMaterials.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/SitePrepTests.java`

**Depends:** T1

- [ ] **Step 1: pick a plot.** `SitePrep.plan` takes the first plot in `village.plots()` where `!prepared()`. Plots skipped by a transient `Map<UUID, Long> skipUntil` are passed over. When there is none, it returns null with `waitingFor = null`.
- [ ] **Step 2: work area.**
  - Area: `plot.footprint().inflate(PlotRules.MARGIN)`. Floor: `plot.origin().getY()`.
  - For each column, the ground top is the highest block at or above the floor that blocks motion and is not leaves, searching up to `floor + SitePrep.MAX_RISE`, where `public static final int MAX_RISE = 12`. The ground is the first free y below the floor, searching down at most `SitePrep.MAX_DROP`, where `public static final int MAX_DROP = 6`. Task 2's `Earthwork` class is not used, so this task does not depend on Task 2.
- [ ] **Step 3: cut pass, first.**
  - Pick the highest cell in the area with y ≥ floor whose block is `DigStep.isDiggable`, or is replaceable vegetation that is not air (`canBeReplaced()` and not a fluid).
  - Plan `TaskSequence.of(MoveTo.digOut(cell, BuilderJob.WORK_REACH), new BreakBlock(cell), new PickUpItems(cell, 2.0, FillMaterials::isFill))`.
  - A non-natural, non-replaceable block in the area above the floor (a player build) makes the plot fail preparation. Use the retry handling in step 6.
- [ ] **Step 4: fill pass, after the cut.** Pick the lowest open cell (no collision shape) with y < floor, down to the column's ground, inside the area.
  - The fill state comes from `FillMaterials.choose(inventory)`: dirt, then cobblestone, then stone. Place with `PlaceBlock(cell, state, item)`.
  - With nothing to fill in the inventory, `Withdraw` up to 32 of one fill item. The storehouse's dirt comes first, then cobblestone, then stone.
  - With nothing in the storehouse either, return null with `waitingFor = "dirt or cobblestone to fill the plot"`.
- [ ] **Step 5: carrying.** When the inventory has no empty slot, or preparation just finished, deposit all fill items with `Deposit(storehouse, FillMaterials::isFill)`.
  - `FillMaterials.isFill` accepts dirt, cobblestone, stone, cobbled deepslate, gravel and sand (what digging drops). Only dirt, cobblestone and stone are placed.
- [ ] **Step 6: done and failures.**
  - When no cut cell and no fill cell is left, call `village.markPlotPrepared(plot.id())` and `VillageRegistry.get(level).setDirty()`.
  - Count consecutive failed tasks. On the 5th, put the plot in `skipUntil` for `BuilderJob.RETRY_TICKS` and reset the count.
- [ ] **Step 7: tests (`SitePrepTests`).** Use a forced `PaverJob` via `CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new PaverJob())`.
  - `levelsASlopedPlot`: dirt terraces from floor-2 to floor+2 under and over a 5x5 plot with margin. Once prepared, every area cell below the floor is solid, every cell from the floor up to floor+4 is free, and the plot is marked prepared.
  - `reusesDugEarthBeforeTheStorehouse`: the storehouse holds cobblestone. Fill that the dug dirt covers is dirt.
  - `waitsForFillWhenNothingIsLeft`.
  - `neverBreaksAPlayerBuildOnThePlot`: a plank block on the plot is never broken.
  - `respectsMobGriefing`: with `doMobGriefing` false nothing is changed. Restore the rule on every exit.
- [ ] **Step 8: verify and commit.** Build and GameTests green. Commit: `feat: the paver cuts and fills uneven plots, reusing dug earth, and marks them prepared`.

### Task 4: the paver lays a path from each new house to the bell

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/job/PathWork.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/PathRoute.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/MakePath.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/PathTests.java`

**Depends:** T1

- [ ] **Step 1: `PathRoute`.** Signature: `static Optional<List<PathRoute.Cell>> find(ServerLevel level, VillageData village, BlockPos start, BlockPos goal)`, with `record Cell(BlockPos surface, Kind kind)` and `enum Kind { GROUND, RAISED, CUT, BRIDGE }`.
  - A* states are `(x, z, h)`, where `h` is the y a villager stands at.
  - Neighbours are the 4 horizontal cells with `|h' - h| ≤ 1`, and `h'` within ±2 of that column's ground.
  - The ground is the first free y above the topmost non-leaf block that blocks motion (as in `PlotPlanner.sample`).
  - Costs:
    - 1 per step;
    - +2 per block of `|h' - ground|`;
    - +3 when the column is fluid or its ground is more than 2 below `h'`, which makes it a bridge.
  - Refuse:
    - columns inside `village.occupiedFootprints()`, except the start column;
    - cells whose headroom `h'` and `h'+1` holds a block that is neither `DigStep.isDiggable` nor replaceable;
    - routes longer than 64 steps (cap expanded nodes at 4096).
  - The goal is reached at any cell within 2 horizontal blocks of `goal` (the bell), or on any `village.isPathColumn` cell.
  - Cell kinds: `GROUND` when `h' == ground`, `RAISED` when `h' > ground` without a bridge, `CUT` when `h' < ground`, `BRIDGE` as above.
- [ ] **Step 2: `PathWork.plan`.**
  - Take the first `village.pathQueue()` origin and find the `BuildingRecord` in `village.houses()` with that origin. If none, remove it from the queue.
  - Find the lower door half inside the footprint at `origin.y + 1` (`DoorBlock`, `DOUBLE_BLOCK_HALF` lower). The start is `door.relative(facing)`, the outside cell (the starter house door faces south, with z+1 outside).
  - Compute the route once and cache it in a transient field for that origin.
  - If there is no route, `VillagerCity.LOGGER.info` the reason, remove the origin from the queue, and return null.
- [ ] **Step 3: building each cell,** in route order, skipping cells already done.
  - Clear headroom: break the natural blocks at `surface` and `surface.above()` (`BreakBlock` plus `PickUpItems`).
  - Support:
    - `BRIDGE`: `PlaceBlock(surface.below(), OAK_PLANKS, OAK_PLANKS)` when open;
    - `RAISED`: `PlaceBlock(surface.below(), DIRT, DIRT)` when open;
    - `CUT`: break down to `surface.below()` being solid.
  - Surface: when `surface.below()` is grass or dirt, run `MakePath(surface.below())`. It is a new task that converts the block to `DIRT_PATH` like a shovel does:
    - it checks `WorldPermissions.mayGrief`;
    - it requires `surface` to be air;
    - it snapshots, sets the block, and restores it if `placementCancelled`.
  - When a cell is finished, call `village.addPathCell(surface)` and `setDirty`.
  - When the route is done, remove the origin from the queue.
- [ ] **Step 4: materials.** Withdraw the dirt or planks the next cells need, up to 32, and use dug dirt first. With neither available, return null with `waitingFor = "dirt or oak planks for the path"`.
- [ ] **Step 5: failures.** Five consecutive failed tasks move that origin to the end of the queue and clear the cached route.
- [ ] **Step 6: tests (`PathTests`).** Use a village with a finished starter house placed from the blueprint with `StructureTemplate` or `BlueprintTests` helpers, and `queuePath` on its origin.
  - `laysAPathToTheBell`: flat ground. Afterwards there are dirt path blocks from the door to within 2 of the bell, and `pathCells` is non-empty.
  - `stepsUpAHill`: the bell sits 3 blocks higher on terraces. Consecutive path cells differ in height by at most 1.
  - `bridgesAWaterChannel`: a 3-wide water channel crossed with oak planks.
  - `neverCrossesAHouse`: a second house between the door and the bell. No path cell lies inside its footprint.
  - `skipsWhenThereIsNoRoute`: the bell is walled off with bricks. The queue empties and no block changes.
- [ ] **Step 7: verify and commit.** Build and GameTests green. Commit: `feat: the paver lays a path with steps and bridges from each new house to the bell`.

### Task 5: lighting: the lamplighter, and a torch in the starter house

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/job/LamplighterJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/DarkSpots.java`
- Modify: `tools/nbt_structures.py`
- Modify: `src/main/resources/data/villagercity/structure/blueprint/starter_house.nbt`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/BlueprintTests.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/LightingTests.java`

**Depends:** T1

- [ ] **Step 1: blueprint torch.** In `tools/nbt_structures.py` `starter_house()`, add `b[(2, 2, 3)] = ("minecraft:wall_torch", {"facing": "north"})`, a torch on the inside of the north wall (z=4), above the bed. First read the file and check that `(2, 2, 4)` is a solid plank wall and `(2, 2, 3)` is air. If not, choose the interior air cell whose wall neighbour is solid and set `facing` to point away from it.
  - Regenerate with `python3 tools/nbt_structures.py`.
  - Update `BlueprintTests` for the new material (`Items.TORCH` x1) and the unchanged 125 placements.
- [ ] **Step 2: `DarkSpots`.** A per-village scanner held by the job, in transient fields.
  - `void scan(ServerLevel level, VillageData village, int columns)` walks the square `center ± radius` in a fixed row order, `columns` per call, and wraps around.
  - A column is dark when all of these hold, where `feet = heightmap(MOTION_BLOCKING_NO_LEAVES)`:
    - `level.getBlockState(feet.below()).isValidSpawn(level, feet.below(), EntityType.ZOMBIE)`;
    - `feet` and `feet.above()` have empty collision shapes and no fluid;
    - `level.getBrightness(LightLayer.BLOCK, feet) == 0`;
    - the column is not inside `village.occupiedFootprints()`;
    - the column is loaded.
  - It keeps a `LongSet` of dark feet positions: added when dark, removed when found lit. `boolean fullPassClean()` is true after a complete pass that found none.
  - `Optional<BlockPos> nearest(BlockPos from)`.
  - `void recheckAround(ServerLevel level, BlockPos pos, int radius)` re-tests known spots near a new torch.
- [ ] **Step 3: `LamplighterJob.plan`.**
  1. Call `scan(level, village, 256)` and `village.setDarkSpotCount(size)`.
  2. With no torch in the inventory: `Withdraw` up to `min(16, storehouse torches)`. With none in the storehouse and dark spots known, return null with `waitingFor = "torches"`.
  3. With a spot: the target is the spot itself, or when `village.isPathColumn(x, z)`, the first horizontal neighbour that is a valid, non-path spot position. Plan `TaskSequence.of(MoveTo.digOut(target, 2.5), new PlaceBlock(target, Blocks.TORCH.defaultBlockState(), Items.TORCH))`.
  4. After a successful place (`onTaskFinished`), call `recheckAround(level, target, 12)` on the next plan.
  5. When `fullPassClean()` holds and no spots remain, return null with `waitingFor = "the village is lit"`, and do not scan again for 1200 ticks (keep a transient `nextScanTick`).
- [ ] **Step 4: tests (`LightingTests`).**
  - `lightsADarkVillageUntilNothingCanSpawn`: at night time (restore day time) with torches in the storehouse and a village of radius 12, succeed when there is no valid spawn column with block light 0 inside the radius.
  - `neverPlacesATorchOnAPath`: path columns registered with `addPathCell` never get a torch.
  - `waitsForTorches`.
  - `theStarterHouseNeedsATorch`.
- [ ] **Step 5: verify and commit.** Build, GameTests and `tools/nbt_structures.py --check` green. Commit: `feat: the lamplighter lights village ground until no monster can spawn, and the starter house has a torch`.

### Task 6: `CraftPlanner`, recipe planning from storehouse stock

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/craft/CraftPlanner.java`
- Create: `src/main/java/dev/andreymudri/villagercity/craft/CraftStep.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/CraftPlannerTests.java`

**Depends:** T1

- [ ] **Step 1: types.**
  ```java
  /** One batch of work: craft {@code recipe} {@code times} times, or smelt {@code times} of {@code input}. */
  public record CraftStep(Kind kind, RecipeHolder<?> recipe, Map<Item, Integer> inputs, Item output, int outputCount, int times) {
      public enum Kind { CRAFT, SMELT }
  }
  ```
  `inputs` holds the total items consumed over all `times`, and `outputCount` the total produced.
- [ ] **Step 2: planner.**
  ```java
  public final class CraftPlanner {
      public static final int MAX_DEPTH = 4;
      public record Result(List<CraftStep> steps, Map<Item, Integer> unmet, Set<Item> missingBase) {}
      public static Result plan(RecipeManager recipes, HolderLookup.Provider registries, Map<Item, Long> stock,
                                List<Map.Entry<Item, Integer>> orders, Map<Item, Integer> reserved) { ... }
  }
  ```
  - Simulate stock: a mutable copy of `stock` minus `reserved`, floored at 0.
  - For each order in the given order, produce the missing count:
    - Candidate recipes: `RecipeType.CRAFTING` holders whose value is a `ShapedRecipe` or `ShapelessRecipe`, and `RecipeType.SMELTING`, with `getResultItem(registries).is(item)`.
    - Skip recipes whose ingredient items have a crafting remainder (`Item.hasCraftingRemainingItem()`).
    - Try candidates in a stable order: by recipe id.
  - For a crafting recipe, `times = ceil(missing / resultCount)`. For each non-empty ingredient slot, choose the item from `ingredient.getItems()` with the highest simulated stock. When none has enough, recursively plan that item (depth + 1 ≤ `MAX_DEPTH`) and choose the item that resolves.
  - Smelting consumes one input per output. Fuel is not planned here.
  - When a candidate succeeds, commit its steps (sub-steps first) and its stock changes. When it fails, roll back to the snapshot and try the next.
  - When no candidate succeeds, record `unmet[item] = missing`, and add to `missingBase` the base items (those with no recipe) whose shortfall blocked it.
- [ ] **Step 3: tests (`CraftPlannerTests`).** Use `helper.getLevel().getRecipeManager()` and `registryAccess()`.
  - `oakDoorFromLogs`: stock 10 oak logs, order oak door x1. Steps: planks then door, stock-consistent.
  - `torchesFromLogsViaCharcoal`: stock 10 oak logs, order torch x4. Steps include smelting a log to charcoal, crafting sticks and crafting torches.
  - `bedFromWhiteWoolAndDye`: stock 3 white wool, 3 red dye and 3 oak planks, order red bed x1.
  - `glassFromSand`.
  - `twoOrdersDoNotShareLogs`: stock 1 oak log, orders oak planks x4 and a stick. Only one is met.
  - `reservedItemsAreNotUsed`: 57 oak planks reserved.
  - `anUnmeetableOrderNamesItsBase`: order glass with no sand. `missingBase` contains sand.
- [ ] **Step 4: verify and commit.** Build and GameTests green. Commit: `feat: plan vanilla crafting and smelting steps from storehouse stock`.

### Task 7: the artisan crafts at a workshop by the storehouse

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/job/ArtisanJob.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/craft/WorkshopService.java`
- Create: `src/main/java/dev/andreymudri/villagercity/craft/CraftAtTable.java`
- Create: `src/main/java/dev/andreymudri/villagercity/craft/VillageDemand.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageWorks.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/ArtisanTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/command/VillageCommand.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageCommandTests.java`

**Depends:** T1, T6

- [ ] **Step 1: `WorkshopService.ensureWorkshop`.**
  - Skip when there is no storehouse, it is not loaded, or `WorldPermissions.mayGrief(level, null)` is false.
  - For the crafting table: when the position is null or its block is gone, clear it and search the cells within 3 horizontal blocks of the storehouse, at storehouse y ±1. The village places NO furnace (Step 8). The first spot, in a fixed order, must be air or replaceable, with a sturdy floor and a free cell above, and outside `occupiedFootprints()` and path columns.
  - Place like `StorehouseService`: snapshot, `setBlock`, restore if `placementCancelled`. Then save the position.
- [ ] **Step 2: `VillageDemand`.** `static Demand of(ServerLevel level, VillageData village, StorehouseBlockEntity storehouse)`, with `record Demand(List<Map.Entry<Item, Integer>> orders, Map<Item, Integer> reserved)`.
  - Builder needs: for the first plot, the blueprint's unfinished placements' materials (`BuilderJob.isDone`). With no plot and `jobCount(BUILDER) > 0`, the starter house's `requiredMaterials()`.
  - `reserved` holds the builder needs, capped at what the storehouse holds.
  - Orders are the builder needs minus storehouse counts, plus torches (`16 - count(TORCH)`) when `jobCount(LAMPLIGHTER) > 0`. Keep positive entries only, sorted by amount ascending, then item id.
- [ ] **Step 3: `ArtisanJob.plan`.**
  1. Without a storehouse or workshop blocks, return null with `waitingFor = "a workshop by the storehouse"`.
  2. Compute the demand and run `CraftPlanner.plan`. Set `village.setArtisanOrders(...)` to strings like `"oak_door x1"`. With nothing to do, return null with `waitingFor = "no orders"`. With only unmet orders, use `waitingFor = "materials: " + missingBase ids`.
  3. Take the first step:
     - **CRAFT:** `times` is capped so the outputs fit one stack. Plan `TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Withdraw(storehouse, inputs), MoveTo.digOut(table, 2.5), new CraftAtTable(step), MoveTo.digOut(storehouse, 2.5), new Deposit(storehouse, any item))`.
     - **SMELT:** the village does not smelt (Step 8). A plan containing a smelting step is not buildable: skip it, and report the output of the skipped smelting step through `waitingFor = "materials: " + ...` so a player knows what to bring (Step 8).
- [ ] **Step 4: `CraftAtTable(CraftStep)`.** It must be within 3 blocks of the table.
  - Every 10 ticks it removes one batch of inputs from the villager's inventory, adds the result (`recipe.getResultItem(registries)` copy), and swings the arm.
  - It fails when inputs are missing or the result does not fit.
  - `describe`: `"crafting <item> at <pos>"`.
- [ ] **Step 6: tests (`ArtisanTests`).**
  - `placesAWorkshopByTheStorehouse`, and replaces a broken one.
  - `craftsADoorFromLogsIntoTheStorehouse`: a builder on the roster and an unfinished plot needing a door, with only logs in stock. Eventually the storehouse holds an oak door.
  - `neverUsesTheBuildersPlanks`: reserved planks stay.
  - `aVillageWithLogsGlassWoolAndDyeBuildsAHouse`: the end-to-end test. Stock:
    - 60 oak logs;
    - 25 cobblestone;
    - 2 glass (the village cannot make it: Step 8);
    - 3 white wool;
    - 3 red dye;
    - 1 coal (for torches, which are crafted, not smelted).

    With a builder and an artisan, succeed when `houseCount() == 1` (timeout 24000).
  - `respectsMobGriefingForTheWorkshop`.
- [ ] **Step 8: the village does not smelt.**

  Five review rounds found measured item theft in the artisan's use of a furnace, every one of them traced to the
  same fact: a furnace is shared with players, a furnace slot carries no ownership information, and the village's own
  finished batch is byte-identical to a player's. Each fix closed the probe that found it and left the class open —
  the last one was defeated by a plain vanilla hopper with no player action at all. The user's decision is to cut the
  feature rather than keep paying for it, and this step records that decision so nothing reintroduces it by accident.

  - **`SmeltInFurnace` is deleted**, along with every smelting path in `ArtisanJob` and every test that exercised one.
  - **The workshop is a crafting table and nothing else.** `WorkshopService` no longer places, records, replaces or
    relocates a furnace, and `VillageWorks` keeps no furnace position. A furnace an older version already placed is
    **left standing** — the village simply stops maintaining it. Breaking a block a player may be using to fix our own
    bookkeeping is exactly the kind of thing this step exists to prevent. An old save that recorded a furnace position
    must still load, with the unknown field ignored.
  - **The `furnace` field is deleted, not just left unset** (user decision, 2026-09-22). Remove it from the
    `VillageWorks` record and its codec, `furnacePos`/`setFurnacePos` and the `occupiedFootprints` entry from
    `VillageData`, the furnace from `VillageCommand`'s workshop line, and the furnace round-trip assertions from
    `VillageRegistryTests`, and the furnace from the workshop line `VillageCommandTests` expects.
  - **The artisan crafts, and only crafts.** A plan whose steps include smelting is not buildable: the artisan skips
    it and reports what a player must bring.
  - **The report names the item the village actually needs, not its raw material.** The village cannot turn sand into
    glass any more, so sand is of no use to it and `materials: sand` would send a player to the wrong place. An
    unbuildable order reports the output of the smelting step it cannot do: the item a player's own furnace would
    make, never that step's input. For glass that is the order itself (`materials: glass`). For a torch with no coal
    it is `materials: charcoal`, not `torch`: charcoal is what the player must actually bring, since the village
    still crafts the torch (user decision, 2026-09-22). This is the whole user-facing surface of this cut.
  - **Smelted goods become player-supplied.** Glass and charcoal come from the player's own furnace into the
    storehouse. Torches are unaffected: they are crafted, from coal or charcoal plus a stick.
- [ ] **Step 9: tests for a village that does not smelt.** In `ArtisanTests`, each in its own `vc_` batch:
  - no furnace is ever placed, and the workshop is complete with a crafting table alone;
  - a village whose storehouse holds sand but no glass, with a plot wanting glass, reports `materials: glass` — not
    sand — and goes on filling the orders it can;
  - a furnace standing next to the storehouse, holding a player's items, is never touched, never emptied and never
    recorded, however long the village runs;
  - an old save that recorded a furnace position loads, and the village neither uses nor maintains that furnace;
  - the village still crafts everything its blueprint needs that is not smelted: doors, planks and torches.
- [ ] **Step 7: verify and commit.** Build and GameTests green. Commit: `feat: the artisan crafts what the village is short of at a workshop by the storehouse`.

### Task 8: README, handoff notes and a village-wide end-to-end check

**Files:**
- Modify: `README.md`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/VillageWorksEndToEndTests.java`

**Depends:** T2, T3, T4, T5, T7

- [ ] **Step 1: README.**
  - Describe the artisan, paver and lamplighter under "What works today", with the hiring order.
  - Update play-testing step 5: logs, cobblestone, sand, wool, dye and coal are enough, and the house needs a torch.
  - Add the new known issues that the spec's Out of Scope list implies.
- [ ] **Step 2: `VillageWorksEndToEndTests.aHillVillageGrowsOnItsOwn`.** A managed village on a 3-terrace slope with 5 unemployed villagers and a storehouse stocked with raw materials.
  - Succeed when there is one house, its plot was prepared by the paver, a path cell exists, and no dark spawnable column remains within radius 12.
  - Timeout 36000. Use its own batch.
- [ ] **Step 3: verify and commit.** Build, GameTests and structures green. Commit: `docs: README covers the artisan, paver and lamplighter; test a hill village growing on its own`.
