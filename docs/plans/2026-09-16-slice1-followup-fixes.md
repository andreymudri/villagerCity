# Slice 1 Follow-up Fixes: Implementation Plan

Spec: `docs/specs/2026-09-16-villager-city-design.md` §5. Source of the findings: the slice 1 fleet reviews
(phases 5-9). Every production fix here was reproduced by a reviewer before it was planned.

## Global Constraints

- Minecraft 1.21.1, NeoForge 21.1.250, ModDevGradle plugin `net.neoforged.moddev` 2.0.147, Gradle wrapper 9.2.1
- Java 21 (`mise.toml` pins `java = "temurin-21"`; export `JAVA_HOME=$(mise where java@temurin-21)` when mise is not activated)
- Mod id `villagercity`; base package `dev.andreymudri.villagercity`
- No mixins. No new access transformer lines
- Zero runtime dependencies beyond NeoForge; test-only JUnit Jupiter 5.10.2
- `/tmp` is a quota-limited tmpfs: export `TMPDIR=$HOME/.cache/tmp-villagercity` (mkdir -p it) before any Gradle command
- Gradle commands always pass `--no-daemon -Dorg.gradle.workers.max=4`
- Every task must leave these green: `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh`
- GameTests live in `src/main/java/dev/andreymudri/villagercity/gametest/`, annotated `@GameTestHolder(VillagerCity.MODID)` + `@PrefixGameTestTemplate(false)`, template `GameTestSupport.TEST_AREA`
- Every GameTest that registers a village, changes a game rule, or changes day time uses its own unique `batch` name, and restores any game rule or day time it changed on every exit path
- The registry is per level and shared: tests must remove the villages they create before succeeding
- Use `pos.subtract(helper.absolutePos(BlockPos.ZERO))` for test-relative positions, never `helper.relativePos` (it mirrors x and z in 1.21.1)
- Every new regression test must be shown FAILING on the pre-fix code for the stated reason before the fix is written; record the failing output in the task summary
- Decompiled 1.21.1 + NeoForge sources for checking a signature: `~/.cache/villagercity-research/src/` (use `/usr/bin/grep -a`, not `grep`)
- Commit messages: single-line conventional commits in English (`fix:`, `test:`); author Andrey Mudri only — never add `Co-Authored-By`, session links, or any tool attribution

## Destination

Before the first real-world play-test: villagers respect `doMobGriefing` and protection events, never chop
player-built log structures, never get stuck on an unreachable tree, never hire duplicate workers, stop
tasks cleanly when released, cannot be farmed for free storehouses or forged contribution credit, and the
tests that guard these behaviours can actually fail.

## Out of Scope

- PlotPlanner `MAX_CANDIDATES` capping the search below `radius + 16` — raising it multiplies column sampling on every failed claim; it belongs with the growth/performance work in sub-project 3
- PlotPlanner buried-column and leaves regression tests — low value until the planner changes again in sub-project 3
- Crediting only "useful" deposits (junk items still earn credit) — contribution rules are designed with mayorship in sub-project 5
- Storehouse debt handed between players (A withdraws, B deposits for credit) — per-player debt cannot see a handoff; village-level accounting comes with mayorship in sub-project 5

---

### Task 1: world permissions for citizen block changes

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/WorldPermissions.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/task/BreakBlock.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/task/PlaceBlock.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseService.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotPlanner.java`
- Modify: `src/main/resources/data/villagercity/loot_table/blocks/storehouse.json`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/ProtectionTests.java`

**Model:** capable

Findings: `BreakBlock` and `PlaceBlock` ignore `doMobGriefing`, `LivingDestroyBlockEvent` and `EntityPlaceEvent`
(an 18-log wall was broken with mobGriefing off and cancelling listeners registered). `StorehouseService`
overwrites non-solid player blocks (a powered rail was deleted with no drop) and re-places a storehouse whose
block drops itself, so breaking it every 5 seconds farms unlimited storehouses.

- [ ] **Step 1:** Write `ProtectionTests.java` with these tests, each in its own batch, and run them RED:
  - `breakBlockRespectsMobGriefing`: set `GameRules.RULE_MOBGRIEFING` false, `ScriptedJob(new BreakBlock(log))` next to the villager; `succeedWhen` results == `[FAILED]` and the log is still present; restore the rule in a `finally`-style path (restore before every `succeed`/assert that can throw — use `helper.onEachTick` or restore at the top of the `succeedWhen` lambda only once results are non-empty).
  - `breakBlockRespectsCancelledDestroyEvent`: mobGriefing true; a static listener registered once in a `static {}` block via `NeoForge.EVENT_BUS.addListener((LivingDestroyBlockEvent e) -> { if (PROTECTED.contains(e.getPos())) e.setCanceled(true); })` with `static final Set<BlockPos> PROTECTED = ConcurrentHashMap.newKeySet()`; add the log's absolute pos, expect `[FAILED]` and the log present; remove the pos before succeeding.
  - `placeBlockRespectsCancelledPlaceEvent`: same pattern with `BlockEvent.EntityPlaceEvent` cancelled for the target pos; villager holds one cobblestone; expect `[FAILED]`, target still air, cobblestone still in inventory.
  - `storehouseIsNotPlacedOverPlayerBlocks`: grass floor, bell at (24,1,24) radius 8 unmanaged; `POWERED_RAIL` on every (x,1,z) with x,z in 20..28 except the bell; `VillageTicker.tickVillage`; assert every rail is still present and, if a storehouse was placed, its position is not one of the rail cells.
  - `storehouseDropsNothingWhenBroken`: tick to place the storehouse, `level.destroyBlock(pos, true)`, then `runAfterDelay(5)` assert no `ItemEntity` whose item is `StorehouseContent.ITEM` within 3 blocks.
  - `storehouseRespectsMobGriefing`: mobGriefing false, tick a fresh village, assert `storehousePos() == null`; restore the rule.

- [ ] **Step 2:** Create `WorldPermissions.java`:

```java
package dev.andreymudri.villagercity.citizen;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/** The permission checks vanilla/NeoForge mob griefing honours, applied to citizen and village block changes. */
public final class WorldPermissions {
    private WorldPermissions() {
    }

    /** doMobGriefing (via EntityMobGriefingEvent), Block#canEntityDestroy, and LivingDestroyBlockEvent. */
    public static boolean mayBreak(ServerLevel level, LivingEntity actor, BlockPos pos) {
        return CommonHooks.canEntityDestroy(level, pos, actor);
    }

    /** doMobGriefing (or EntityMobGriefingEvent when an actor is known) before placing anything. */
    public static boolean mayGrief(ServerLevel level, @Nullable Entity actor) {
        return EventHooks.canEntityGrief(level, actor);
    }

    /** Take before setBlock; after placing, call {@link #placementCancelled} and restore the snapshot when it returns true. */
    public static BlockSnapshot snapshot(ServerLevel level, BlockPos pos) {
        return BlockSnapshot.create(level.dimension(), level, pos);
    }

    /** Fires EntityPlaceEvent the way vanilla item placement does: after the block is set. */
    public static boolean placementCancelled(@Nullable Entity actor, BlockSnapshot snapshot) {
        return EventHooks.onBlockPlace(actor, snapshot, Direction.UP);
    }
}
```

- [ ] **Step 3:** `BreakBlock.tick`: after the air check and state-change handling, `if (!WorldPermissions.mayBreak(level, villager, pos)) return Status.FAILED;` (before any progress is made). Update the class Javadoc.

- [ ] **Step 4:** `PlaceBlock.tick`: before removing the cost item, `if (!WorldPermissions.mayGrief(level, villager)) return Status.FAILED;`. Replace the bare `setBlock` with: take `snapshot`, `setBlock`, then `if (WorldPermissions.placementCancelled(villager, snapshot)) { snapshot.restore(Block.UPDATE_CLIENTS); villager.getInventory().addItem(new ItemStack(cost)); return Status.FAILED; }` (skip the refund when `cost == Items.AIR`). Check `BlockSnapshot.restore(int)` in the decompiled sources before using it.

- [ ] **Step 5:** `PlotPlanner`: add an overload `find(ServerLevel level, VillageData village, Vec3i size, Predicate<BlockPos> originAllowed)` that skips a buildable candidate whose origin (`new BlockPos(minX, buildY, minZ)`) fails the predicate; the existing three-argument `find` delegates with `pos -> true`.

- [ ] **Step 6:** `StorehouseService.ensureStorehouse`: return early when `!WorldPermissions.mayGrief(level, null)`; call the new `find` overload with `pos -> level.getBlockState(pos).isAir()`; place with the snapshot/`placementCancelled(null, snapshot)` pattern and do not record the position when cancelled.

- [ ] **Step 7:** Replace the storehouse loot table so the block drops nothing (its contents still drop from `StorehouseBlock.onRemove`):

```json
{
  "type": "minecraft:block",
  "pools": [],
  "random_sequence": "villagercity:blocks/storehouse"
}
```

- [ ] **Step 8:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass, including the pre-existing `dropsContentsWhenBroken`.

- [ ] **Step 9:** Commit: `fix: citizens and storehouse placement respect mob griefing and protection events`

### Task 2: village citizen roster for job assignment

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/JobAssignment.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/CitizenRoster.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageLifecycleTests.java`
- Modify: `src/test/java/dev/andreymudri/villagercity/village/VillageDataTest.java`

**Model:** capable

Finding: `JobAssignment.assign` counts filled jobs only among villagers inside the radius box, so a lumberjack
working 18 blocks from the bell let an idle villager become a second lumberjack; nobody is ever unassigned.

- [ ] **Step 1:** In `VillageLifecycleTests`, add (RED first):
  - `doesNotDuplicateJobWhenWorkerIsAwayFromBell` (batch `vc_life_away`): the `placesStorehouse...` setup, one tick, teleport the lumberjack with `moveTo` to relative (44,1,24), tick again, assert exactly one lumberjack and one builder across all three villagers.
  - `forgetsKilledCitizen` (batch `vc_life_killed`, timeoutTicks 200): two villagers, tick, `lumberjack.kill()`, spawn a replacement; `succeedWhen` the lumberjack `isRemoved()`, then tick and assert the replacement is the lumberjack.
  - `rosterSurvivesCodecRoundTrip` (no world state needed beyond `prepareArea`): a `VillageData` with two roster entries, encode with `VillageCodecs.VILLAGE.encodeStart(NbtOps.INSTANCE, v)` and decode, assert `citizens()` is equal.

- [ ] **Step 2:** `VillageData`: add `private final Map<UUID, JobType> citizens` (a `LinkedHashMap`), a constructor parameter `Map<UUID, JobType> citizens` inserted after `ledger` (update the delegating 3-arg constructor with `Map.of()`), and:

```java
    /** Employed citizens by villager UUID. Survives unloads; entries leave only when the villager is destroyed or dismissed. */
    public Map<UUID, JobType> citizens() {
        return Collections.unmodifiableMap(citizens);
    }

    public void setCitizen(UUID villager, JobType job) {
        if (job == JobType.NONE) {
            citizens.remove(villager);
        } else {
            citizens.put(villager, job);
        }
    }

    public boolean removeCitizen(UUID villager) {
        return citizens.remove(villager) != null;
    }

    public int jobCount(JobType job) {
        return (int) citizens.values().stream().filter(job::equals).count();
    }
```

  Add a JUnit test in `VillageDataTest` for `setCitizen` (NONE removes), `removeCitizen` and `jobCount`.

- [ ] **Step 3:** `VillageCodecs.VILLAGE`: add `Codec.unboundedMap(UUIDUtil.STRING_CODEC, JobType.CODEC).optionalFieldOf("citizens", Map.of()).forGetter(VillageData::citizens)` before `managed`, and pass it through the constructor. Old saves without the field load with an empty roster.

- [ ] **Step 4:** `JobAssignment.assign`: count filled jobs with `village.jobCount(job)`. While scanning villagers in the area, a villager whose `CitizenData` belongs to this village with a job other than NONE is written into the roster with `village.setCitizen(uuid, job)` (reconciles saves made before the roster existed). `employ` also calls `village.setCitizen(villager.getUUID(), job)`. Update the class Javadoc: filled jobs come from the roster, candidates from the area.

- [ ] **Step 5:** Create `CitizenRoster.java`:

```java
package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** Drops a citizen from its village roster when the villager is destroyed (killed, discarded, converted), not when it unloads. */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class CitizenRoster {
    private CitizenRoster() {
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity.RemovalReason reason = villager.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) {
            return;
        }
        villager.getExistingData(CitizenAttachments.CITIZEN.get()).ifPresent(data -> {
            if (data.villageId() == null) {
                return;
            }
            VillageRegistry registry = VillageRegistry.get(level);
            VillageData village = registry.get(data.villageId());
            if (village != null && village.removeCitizen(villager.getUUID())) {
                registry.setDirty();
            }
        });
    }
}
```

  Verify in the decompiled sources that the removal reason is set before `EntityLeaveLevelEvent` fires for both `discard()` and death; if it is not, say so and use the reason-carrying hook that is.

- [ ] **Step 6:** Build and `scripts/gametest.sh` — both pass, including the existing `refillsJobAfterCitizenDies`.

- [ ] **Step 7:** Commit: `fix: count village jobs from a saved citizen roster instead of nearby villagers`

### Task 3: scheduler stops the running task on release

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/TaskScheduler.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/Task.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/SchedulerReleaseTests.java`

**Model:** mid

Finding: `TaskScheduler.release()` drops `runtime.current` without calling `Task.stop`, so e.g. a `BreakBlock`
leaves its crack overlay behind when the citizen is dismissed or its village is removed.

- [ ] **Step 1:** `SchedulerReleaseTests.java` (RED first) with a private `StopProbe implements Task` that returns RUNNING forever and counts `stop` calls:
  - `stopsTaskWhenJobIsCleared` (batch `vc_release_job`): enroll with `ScriptedJob(probe)`, after 10 ticks `data.clear()`, after 15 ticks assert `probe.stops == 1`.
  - `stopsTaskWhenVillageIsRemoved` (batch `vc_release_village`): same, removing the village instead; assert `probe.stops == 1`.

- [ ] **Step 2:** `TaskScheduler`: change `release` to `release(ServerLevel level, Villager villager, CitizenData citizen, @Nullable VillageData village, CitizenRuntime runtime)`. When `runtime.current != null`, set it to null first, then call `stop(new TaskContext(level, villager, village, citizen))` on it. Both call sites pass what they have (the job-NONE path looks the village up only when `citizen.villageId()` is non-null; the removed-village path passes null). Call `release` before `citizen.clear()` on the removed-village path so the context still names the citizen's job.

- [ ] **Step 3:** `Task.stop` Javadoc: "`ctx.village()` is null when the task is stopped because the citizen's village no longer exists." Confirm with a grep that no existing `stop` implementation dereferences `ctx.village()`.

- [ ] **Step 4:** Build and `scripts/gametest.sh` — both pass.

- [ ] **Step 5:** Commit: `fix: stop the running task when a citizen is released`

### Task 4: make weak slice 1 tests able to fail

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/ScriptedJob.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/PrimitiveTaskTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/TaskSchedulerTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/BuilderTests.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`

**Model:** mid

Findings: four assertions cannot fail or check the wrong thing. For each change, prove it bites by applying the
named mutation in a scratch copy (never committed), running the test RED, then reverting.

- [ ] **Step 1:** `ScriptedJob`: add `public final List<Long> finishTimes = new CopyOnWriteArrayList<>();` and record `ctx.gameTime()` in `onTaskFinished`.

- [ ] **Step 2:** `PrimitiveTaskTests.breakBlockUsesToolTimeAndDrops`: capture `long start = helper.getLevel().getGameTime()` right before `enroll`, and replace the elapsed-tick assertion with `job.finishTimes.get(0) - start >= breakTicks(...)`. Mutation: `required = 1` in `BreakBlock.start` and in its state-change branch — the test must fail with "broke too fast".

- [ ] **Step 3:** `TaskSchedulerTests.forcesCityActivityWhileTaskRuns`: spawn a `new ItemEntity(level, x, y, z, new ItemStack(Items.BREAD))` two blocks from the villager at tick 0 so vanilla wants to walk to it. Mutation: delete `brain.eraseMemory(MemoryModuleType.WALK_TARGET)` from `TaskScheduler.tickCitizen` — the test must fail with "vanilla walk target present". If bread does not produce a walk target, find a stimulus that does (check `Villager`/`VillagerGoalPackages` in the decompiled sources) and say which.

- [ ] **Step 4:** `BuilderTests.abandonsPlotAfterRepeatedFailures`: fix the comment (the bedrock plank at (1,1,0) is placement 26; placements 0-24 are the floor, 25 is the log at (0,1,0)) and add `helper.assertBlockPresent(Blocks.COBBLESTONE, origin.offset(4, 0, 4))` and `helper.assertBlockPresent(Blocks.OAK_LOG, origin.offset(0, 1, 0))`. Mutation: remove the `standAside` branch from `BuilderJob.plan` — the test must fail.

- [ ] **Step 5:** `VillageRegistryTests.detectorIgnoresBellWithoutVillagers`: before placing the bell, discard every `Villager` within 32 blocks of the absolute bell position, so villagers leaked by an earlier failed test cannot make it fail. Mutation: spawn a villager 10 blocks from the bell before the discard — the test must still pass.

- [ ] **Step 6:** Build and `scripts/gametest.sh` — both pass.

- [ ] **Step 7:** Commit: `test: make break timing, walk target, abandon and detector assertions able to fail`

### Task 5: lumberjack targets only natural, reachable trees

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/job/TreeFinder.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/LumberjackJob.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/Replant.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/TreeTargetingTests.java`

**Depends:** T1

**Model:** capable

Findings: `TreeFinder.trunk` accepts any same-kind log structure touching one natural leaf (a player's 18-log
wall beside a canopy was chopped down), and `LumberjackJob` always re-targets the nearest tree, so one
unreachable tree (on a 3-block pillar) blocks the lumberjack forever. `Replant` places the sapling with a bare `setBlock`, bypassing doMobGriefing and `EntityPlaceEvent` (fixes1 phase 1 security review, reproduced).

- [ ] **Step 1:** `TreeTargetingTests.java` (RED first; use `LumberjackTests.plantTree`, which is package-private in the same package):
  - `rejectsLogWallTouchingCanopy` (batch `vc_tree_wall`): OAK_LOG wall x=26..31, y=1..3, z=26 on grass, plus a default-state OAK_LEAVES cluster of 4 at (32,3,26),(32,4,26),(32,3,27),(32,3,25); assert `TreeFinder.trunk(level, abs(26,1,26))` is empty.
  - `rejectsPostWithFewLeaves` (batch `vc_tree_post`): 3-high OAK_LOG post with two natural leaves beside its top; assert empty.
  - `acceptsPlantedTree` (batch `vc_tree_ok`): `plantTree` at (26,1,26); assert present with 5 logs.
  - `skipsUnreachableTree` (batch `vc_tree_unreachable`, timeoutTicks 2000): the reviewer's setup — dirt pillar (26,1..3,26) with `plantTree` at (26,4,26), a reachable `plantTree` at (22,1,34), village bell (40,1,40) radius 6 with storehouse at (22,1,24), lumberjack at (22,1,22) with a stone axe and `new LumberjackJob()`; `succeedWhen` the log at (22,1,34) is gone.
  - `skipsProtectedTree` (batch `vc_tree_protected`, timeoutTicks 2000): two planted trees; a static `LivingDestroyBlockEvent` listener (same `PROTECTED` set pattern as Task 1's tests) protects every log of the nearer tree; `succeedWhen` the farther tree's base log is gone and the protected tree's logs are all present; clear the set before succeeding.
  - `replantRespectsGriefing` (batch `vc_tree_replant`): with `doMobGriefing` false, run a `Replant` for a villager holding an oak sapling next to an air cell on dirt; assert the cell stays air and the sapling stays in the inventory; restore the gamerule before succeeding.
  - `replantRespectsPlaceEvent` (batch `vc_tree_replant_event`): a static `EntityPlaceEvent` listener cancels placements at a protected position (same `PROTECTED` set pattern as Task 1's tests); assert the cell stays air after `Replant` ticks and the sapling is back in the inventory; clear the set before succeeding.

- [ ] **Step 2:** `TreeFinder`: add `MAX_SPREAD = 5` and `MIN_LEAVES = 4`. In `trunk`, count distinct natural leaf positions (a `Set<BlockPos>`) instead of a boolean; if any connected same-kind log lies more than `MAX_SPREAD` blocks horizontally (Chebyshev) from the base, return empty; return empty when fewer than `MIN_LEAVES` natural leaves touch the logs or when the base has no log of its kind directly above it. Update the Javadoc to state the three rules. Add `findNearest(ServerLevel, BlockPos, VillageData, Predicate<BlockPos> skipBase)`; the existing overload delegates with `base -> false`.

- [ ] **Step 3:** `LumberjackJob`: add `AVOID_TICKS = 2400`, a `Map<BlockPos, Long> avoidUntil` and a `@Nullable BlockPos target`. In `plan`, drop expired entries, pass `base -> avoidUntil.containsKey(base)` to `findNearest`, and set `target = found.base()` when returning the tree sequence (null for a deposit). Override `onTaskFinished`: when `target != null`, avoid it until `gameTime + AVOID_TICKS` if the status is FAILED or `TreeFinder.trunk(level, target)` is still present (logs left standing, e.g. protected); then clear `target`. The avoid list is in-memory only, which matches `Job`'s contract that jobs keep no state that must survive a save.

- [ ] **Step 3b:** `Replant`: return SUCCESS without placing when `WorldPermissions.mayGrief(level, villager)` is false. Otherwise take `WorldPermissions.snapshot(level, pos)` before `setBlock`; after placing, when `WorldPermissions.placementCancelled(villager, snapshot)` is true, `snapshot.restore(Block.UPDATE_CLIENTS)` and return the sapling to the villager's inventory. The sapling is consumed only by a placement that stands. Update the class Javadoc.

- [ ] **Step 4:** Build and `scripts/gametest.sh` — both pass, including `LumberjackTests` and `EndToEndTests`.

- [ ] **Step 5:** Commit: `fix: lumberjack ignores log structures and gives up on unreachable or protected trees`

### Task 6: storehouse credit follows player clicks

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseMenu.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseBlockEntity.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/StorehouseTests.java`

**Depends:** T2

**Model:** capable

Findings: credit is the per-session net increase between `startOpen` and `stopOpen`, so a player can withdraw
the lumberjack's logs and redeposit them for credit, every open player is credited for other players' deposits,
and hopper inserts are credited to whoever has the menu open. Separately, `extractForCitizen` merges stacks
from several slots into the first slot's components, losing names and enchantments.

- [ ] **Step 1:** In `StorehouseTests`, replace `playerDepositsAreCreditedCitizenOnesAreNot` and add (RED first where the old code allows; the menu class does not exist yet, so write them against `StorehouseMenu` and record the compile failure as the RED for those):
  - `quickMoveIntoStorehouseCreditsTheClicker`: mock player with 10 oak logs in inventory; `StorehouseMenu menu = new StorehouseMenu(1, player.getInventory(), storehouse)`; `menu.clicked(<player slot index holding the logs>, 0, ClickType.QUICK_MOVE, player)`; assert DEPOSIT credit 10.
  - `withdrawThenRedepositEarnsNothing`: storehouse holds 20 logs from `insertFromCitizen`; quick-move them to the player, then back; assert DEPOSIT credit 0.
  - `otherOpenersAndHoppersAreNotCredited`: two mock players each with their own menu; player A quick-moves 5 logs; `storehouse.setItem(26, new ItemStack(Items.COBBLESTONE, 7))` directly (hopper path); assert A has 5 and B has 0.
  - `extractKeepsComponentsApart`: slot 0 holds 1 plain oak log, slot 1 holds 1 oak log with a custom name (`DataComponents.CUSTOM_NAME`); `extractForCitizen(Items.OAK_LOG, 2)` returns a stack whose components match exactly one of the two, and the other stays in the storehouse.

- [ ] **Step 2:** `VillageData`: add `private final Map<UUID, Long> storehouseDebt` (constructor parameter after `citizens`, `Map.of()` in the 3-arg constructor) with `long debt(UUID)` and `void setDebt(UUID, long)` (zero removes). `VillageCodecs`: `Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).optionalFieldOf("storehouse_debt", Map.of())`.

- [ ] **Step 3:** Create `StorehouseMenu extends ChestMenu` using `super(MenuType.GENERIC_9x3, containerId, playerInventory, storehouse, 3)`. Override `clicked(int slotId, int button, ClickType clickType, Player player)`: total the item count of container slots 0..26 before and after `super.clicked(...)`; when the level is a `ServerLevel` and the delta is non-zero, find the village whose `storehousePos()` equals the block entity's position and apply:
  - delta < 0 (withdrew n): `setDebt(player, debt + n)`.
  - delta > 0 (deposited n): `repaid = min(debt, n)`, `setDebt(player, debt - repaid)`, and record `n - repaid` as `ContributionCategory.DEPOSIT` when positive.
  Mark the registry dirty when anything changed.

- [ ] **Step 4:** `StorehouseBlockEntity`: `createMenu` returns `new StorehouseMenu(containerId, inventory, this)`. Remove `openSnapshots`, `adjustSnapshots`, and the `startOpen`/`stopOpen` crediting overrides. In `extractForCitizen`, take the first matching slot's stack as the template and only pull from slots where `ItemStack.isSameItemSameComponents(slot, template)`.

- [ ] **Step 5:** Build and `scripts/gametest.sh` — both pass, including `citizenInsertAndExtract` and `EndToEndTests`.

- [ ] **Step 6:** Commit: `fix: credit storehouse deposits per player click and keep withdrawn stacks apart`

### Task 7: storehouse exits charge or refuse

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseBlockEntity.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseBlock.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseContent.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseMenu.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/StorehouseTests.java`

**Depends:** T6

**Model:** capable

Design: `docs/specs/2026-09-16-fixes1-phase3-design.md` §1. Finding (phase 2 security review, reproduced): only
click withdrawals create debt, so citizen items pulled out by a hopper under the storehouse, or dropped by
breaking it, earn full DEPOSIT credit when clicked back in. NeoForge registers no `IItemHandler` capability
for modded block entity types (`CapabilityHooks` lists vanilla types only), so hoppers reach the storehouse
through the vanilla `Container` path, which checks `Container.canTakeItem`.

- [ ] **Step 1:** In `StorehouseTests`, add (RED first, each shown failing on the current code):
  - `hoppersCannotPullFromStorehouse` (batch `vc_storehouse_hopper_out`): storehouse block at (10,2,10) with a `Blocks.HOPPER` at (10,1,10) below it; `insertFromCitizen(new ItemStack(Items.OAK_LOG, 5))`; after 60 ticks (`helper.runAfterDelay`) assert the storehouse still holds 5 oak logs and the hopper is empty, then succeed.
  - `hoppersCanStillInsertWithoutCredit` (batch `vc_storehouse_hopper_in`): village whose `storehousePos` is the storehouse at (10,1,10); a `Blocks.HOPPER` facing down at (10,2,10) holding 3 cobblestone; `succeedWhen` the storehouse holds 3 cobblestone and `village.ledger()` has no DEPOSIT credit for anyone; remove the village before succeeding.
  - `breakingChargesTheBreakerDebt` (batch `vc_storehouse_break`): village with storehouse at (10,1,10) holding 12 oak logs via `insertFromCitizen`; `ServerPlayer player = helper.makeMockServerPlayerInLevel()`, `player.setGameMode(GameType.SURVIVAL)`, `player.gameMode.destroyBlock(abs)`; assert `village.debt(player.getUUID()) == 12`. Then put a fresh storehouse block back at the same position, give the player 12 oak logs in hotbar slot 0, open `new StorehouseMenu(1, player.getInventory(), storehouse)` and quick-move them in; assert DEPOSIT credit 0 and debt 0. Discard item entities and the mock player, remove the village, succeed.
  - `explosionsDoNotOpenStorehouse` (batch `vc_storehouse_explosion`): storehouse at (10,1,10); `helper.getLevel().explode(null, x + 2.5, y + 0.5, z + 0.5, 4.0f, Level.ExplosionInteraction.TNT)` using the absolute storehouse position; assert the storehouse block is still present.

- [ ] **Step 2:** `StorehouseMenu`: extract the village lookup from `settle` into `public static @Nullable VillageData owner(ServerLevel level, BlockPos storehousePos)` (the village whose `storehousePos()` equals the position) and use it in `settle`. Add `public static void chargeDebt(ServerLevel level, BlockPos storehousePos, UUID player, long amount)`: when `amount > 0` and an owner exists, `setDebt(player, debt + amount)` and mark the registry dirty.

- [ ] **Step 3:** `StorehouseBlockEntity`: override `canTakeItem(Container target, int slot, ItemStack stack)` to return `false`, with a Javadoc line saying hoppers may insert but never extract, because extraction outside a menu click would bypass debt. `extractForCitizen` does not use `canTakeItem` and keeps working.

- [ ] **Step 4:** `StorehouseBlock`: override `playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player)`. On a `ServerLevel`, when the block entity is a `StorehouseBlockEntity`, sum the counts of its 27 slots and call `StorehouseMenu.chargeDebt(serverLevel, pos, player.getUUID(), total)`; then return `super.playerWillDestroy(...)`. Contents still drop in `onRemove` as before.

- [ ] **Step 5:** `StorehouseContent`: build the block properties as `BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL).explosionResistance(1200.0f)`. Check the method name in the decompiled `BlockBehaviour.Properties` before using it.

- [ ] **Step 6:** Build and `scripts/gametest.sh` — both pass, including `dropsContentsWhenBroken`, `citizenInsertAndExtract` and `EndToEndTests`.

- [ ] **Step 7:** Commit: `fix: storehouse refuses hopper extraction, charges breakers debt and resists explosions`

### Task 8: citizens leaving free their job and plots, and plots resume

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/village/Plot.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/village/CitizenRoster.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/BuilderJob.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/gametest/BuilderTests.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/PlotHandoverTests.java`

**Depends:** T2, T6

**Model:** capable

Design: `docs/specs/2026-09-16-fixes1-phase3-design.md` §2 and §3. Findings: (a, phase 1 correctness review,
reproduced) `CitizenRoster` ignores `CHANGED_DIMENSION`, so a citizen that leaves the dimension keeps its
roster entry and the village never re-hires; (b, e2e debugger) after an abandon the builder needs a full
blueprint's materials again, which rarely happens; (c) a builder that dies or leaves keeps its plot, and
`claimPlot` refuses while any plot exists, so no builder ever builds again.

- [ ] **Step 1:** Create `PlotHandoverTests.java` (RED first; record each failure):
  - `dimensionChangeFreesTheJob` (batch `vc_handover_dim`, timeoutTicks 400): `freshVillage(helper, (24,1,24), 8, false)`; spawn villagers at (20,1,20) and (28,1,20); `VillageTicker.tickVillage` hires a lumberjack and a builder; force-load nether chunk (0,0) (`netherLevel.setChunkForced(0, 0, true)`), teleport the lumberjack with `teleportTo(netherLevel, 8.5, 70, 8.5, Set.of(), 0, 0)`; spawn a replacement at (20,1,28); after 100 ticks tick the village again; `succeedWhen` the replacement's `CitizenData.job()` is LUMBERJACK and the roster holds exactly one LUMBERJACK. Discard the teleported villager, un-force the chunk and remove the village before succeeding.
  - `leavingBuilderPlotIsTakenOver` (batch `vc_handover_builder`, timeoutTicks 4000): village as in `BuilderTests`; builder A enrolled with `new BuilderJob()` and a plot at origin (8,1,8) assigned to A; pre-place the 25 floor cobblestone blocks of the plot; stock the storehouse with exactly the materials for the remaining placements (assert `!storehouse.hasAll(blueprint.requiredMaterials())`); `A.discard()`; assert the plot now has a null builder; enroll builder B at (12,1,14); `succeedWhen` `village.houseCount() == 1`.
  - `releasedPlotWaitsForRetry` (batch `vc_handover_retry`, timeoutTicks 600): stocked village; add a plot with a null builder, `retryAt = gameTime + 200`, `abandons = 1`; enroll a builder; at +100 ticks assert `village.plotBuiltBy(builder)` is empty; `succeedWhen` (after +200) it is present.
  - `plotFieldsSurviveCodecRoundTrip` (no batch): a village with one released plot (builder null, retryAt 1234, abandons 2) and one assigned plot; encode with `VillageCodecs.VILLAGE` to `NbtOps.INSTANCE` and parse back; assert both plots equal the originals. Then remove `retry_at` and `abandons` from the assigned plot's tag, parse again, and assert retryAt 0, abandons 0 and the builder unchanged.

- [ ] **Step 2:** `BuilderTests.abandonsPlotAfterRepeatedFailures`: the first abandon now releases the plot. Replace `plot not abandoned` with assertions that the plot with `plotId` still exists with a null builder, `abandons == 1` and `retryAt > 0`; keep the block assertions. Add `dropsPlotOnThirdAbandon` (batch `vc_builder_abandon_drop`, timeoutTicks 3000): identical setup but the plot starts with `abandons = 2`; `succeedWhen` the plot is gone and the same four block assertions hold.

- [ ] **Step 3:** `Plot`:
```java
/** A building in progress. A released plot has no builder and may be taken over from game time {@code retryAt}. */
public record Plot(UUID id, String blueprint, BlockPos origin, Vec3i size, @Nullable UUID builder, long retryAt, int abandons) {
    public Plot {
        origin = origin.immutable();
    }

    public Plot(UUID id, String blueprint, BlockPos origin, Vec3i size, UUID builder) {
        this(id, blueprint, origin, size, builder, 0L, 0);
    }

    public boolean released() {
        return builder == null;
    }

    public Footprint footprint() {
        return Footprint.of(origin, size);
    }
}
```

- [ ] **Step 4:** `VillageCodecs.PLOT`: `UUIDUtil.CODEC.optionalFieldOf("builder").forGetter(p -> Optional.ofNullable(p.builder()))`, `Codec.LONG.optionalFieldOf("retry_at", 0L).forGetter(Plot::retryAt)`, `Codec.INT.optionalFieldOf("abandons", 0).forGetter(Plot::abandons)`; construct with `builder.orElse(null)`.

- [ ] **Step 5:** `VillageData`: `plotBuiltBy` skips released plots (`builder != null && builder.equals(...)`). Add, each replacing the matching list entry in place:
  - `public void releasePlot(UUID plotId, long retryAt, boolean abandoned)` — builder null, the given `retryAt`, `abandons + 1` when `abandoned`.
  - `public boolean releasePlotsBuiltBy(UUID builder)` — releases every plot held by that builder with `retryAt` 0 and `abandons` unchanged; returns whether any changed.
  - `public void assignPlot(UUID plotId, UUID builder)` — sets the builder, keeps `retryAt` and `abandons`.

- [ ] **Step 6:** `CitizenRoster.onLeave`: continue when `reason.shouldDestroy() || reason == Entity.RemovalReason.CHANGED_DIMENSION`; keep returning early for every other reason. When the village exists, call both `removeCitizen` and `releasePlotsBuiltBy` for the villager and mark the registry dirty if either changed. Update the class Javadoc.

- [ ] **Step 7:** `BuilderJob`: add `MAX_ABANDONS = 3` and `RETRY_TICKS = 2400`. Where `consecutiveFailures >= MAX_CONSECUTIVE_FAILURES`: if `plot.abandons() + 1 >= MAX_ABANDONS` remove the plot, otherwise `village.releasePlot(plot.id(), level.getGameTime() + RETRY_TICKS, true)`; mark dirty, reset `consecutiveFailures`, return null. In `claimPlot`, before the `!village.plots().isEmpty()` refusal: find the first released plot with `retryAt <= level.getGameTime()`; if present, `assignPlot` it to this villager, mark dirty and return it with its builder set (no material check). Update the class Javadoc.

- [ ] **Step 8:** Build and `scripts/gametest.sh` — both pass, including `BuilderTests`, `VillageLifecycleTests`, `VillageRegistryTests` and `EndToEndTests`.

- [ ] **Step 9:** Commit: `fix: leaving citizens free their job and plots, and abandoned plots resume after a cooldown`
