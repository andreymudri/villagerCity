package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.job.PathRoute;
import dev.andreymudri.villagercity.job.PathWork;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PathTests {
    private static final BlockPos HOUSE_ORIGIN = new BlockPos(8, 1, 8);
    /** Absolute positions a protection mod would guard; the listener below cancels destroys there. */
    private static final Set<BlockPos> PROTECTED_BREAK = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((LivingDestroyBlockEvent e) -> {
            if (PROTECTED_BREAK.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    /** Places a finished starter house (registered as a house, which queues its path) at the given relative origin. */
    private static BuildingRecord placeStarterHouse(GameTestHelper helper, VillageData village, BlockPos relativeOrigin) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        for (BlueprintPlacement placement : blueprint.placements()) {
            helper.setBlock(relativeOrigin.offset(placement.offset()), placement.state());
        }
        BuildingRecord house = new BuildingRecord(blueprint.id().toString(), helper.absolutePos(relativeOrigin), blueprint.size());
        village.addHouse(house);
        return house;
    }

    /** A storehouse stocked with plenty of dirt and oak planks, the two materials paths use. */
    private static void stockedStorehouse(GameTestHelper helper, VillageData village, BlockPos relative) {
        helper.setBlock(relative, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(relative));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(relative);
        storehouse.insertFromCitizen(new ItemStack(Items.DIRT, 64));
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_PLANKS, 64));
    }

    private static Villager enrollPaver(GameTestHelper helper, VillageData village, int x, int y, int z) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, y, z);
        CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new PathWork());
        return villager;
    }

    private static boolean reachedBell(VillageData village, BlockPos bell) {
        return village.pathCells().stream().anyMatch(pos ->
                Math.max(Math.abs(pos.getX() - bell.getX()), Math.abs(pos.getZ() - bell.getZ())) <= 2);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_flat", timeoutTicks = 6000)
    public static void laysAPathToTheBell(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertFalse(village.pathCells().isEmpty(), "no path cells laid");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_hill", timeoutTicks = 8000)
    public static void stepsUpAHill(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
            int floorY = terraceHeight(z);
            if (floorY == 0) {
                continue;
            }
            for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
                for (int y = 0; y <= floorY; y++) {
                    helper.setBlock(x, y, z, y == floorY ? Blocks.GRASS_BLOCK : Blocks.DIRT);
                }
            }
        }
        BlockPos bellRelative = new BlockPos(10, 4, 30);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 20, 1, 4);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertFalse(village.pathCells().isEmpty(), "no path cells laid");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            for (BlockPos a : village.pathCells()) {
                for (BlockPos b : village.pathCells()) {
                    int dx = Math.abs(a.getX() - b.getX());
                    int dz = Math.abs(a.getZ() - b.getZ());
                    if (Math.max(dx, dz) == 1 && dx <= 1 && dz <= 1) {
                        helper.assertTrue(Math.abs(a.getY() - b.getY()) <= 1,
                                "adjacent path cells " + a.toShortString() + " and " + b.toShortString() + " differ by more than one block");
                    }
                }
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    private static int terraceHeight(int z) {
        if (z < 13) {
            return 0;
        }
        if (z < 19) {
            return 1;
        }
        if (z < 25) {
            return 2;
        }
        return 3;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_bridge", timeoutTicks = 8000)
    public static void bridgesAWaterChannel(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 18; z <= 20; z++) {
                helper.setBlock(x, 0, z, Blocks.WATER);
            }
        }
        BlockPos bellRelative = new BlockPos(10, 1, 30);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 20, 1, 4);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            boolean bridged = false;
            for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
                for (int z = 18; z <= 20; z++) {
                    if (helper.getBlockState(new BlockPos(x, 0, z)).is(Blocks.OAK_PLANKS)) {
                        bridged = true;
                    }
                }
            }
            helper.assertTrue(bridged, "no oak planks bridge the water channel");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_avoid_house", timeoutTicks = 8000)
    public static void neverCrossesAHouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 30);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        BlockPos blockerRelative = new BlockPos(12, 1, 16);
        BuildingRecord blocker = new BuildingRecord(BuildingRecord.VANILLA_HOME, helper.absolutePos(blockerRelative), new Vec3i(10, 3, 10));
        village.addHouse(blocker);
        Footprint blockerFootprint = blocker.footprint();
        enrollPaver(helper, village, 20, 1, 12);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            for (BlockPos cell : village.pathCells()) {
                helper.assertFalse(blockerFootprint.contains(cell.getX(), cell.getZ()),
                        "path cell " + cell.toShortString() + " lies inside the second house");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_no_route", timeoutTicks = 4000)
    public static void skipsWhenThereIsNoRoute(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        for (int x = 21; x <= 27; x++) {
            for (int z = 21; z <= 27; z++) {
                for (int y = 0; y <= 4; y++) {
                    helper.setBlock(x, y, z, Blocks.BRICKS);
                }
            }
        }
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        helper.runAfterDelay(3000, () -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path never abandoned");
            helper.assertTrue(village.pathCells().isEmpty(), "path cells laid despite no route");
            boolean anyPath = false;
            for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
                for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                    if (helper.getBlockState(new BlockPos(x, 0, z)).is(Blocks.DIRT_PATH)) {
                        anyPath = true;
                    }
                }
            }
            helper.assertFalse(anyPath, "a path was laid despite the bell being walled off");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    /** The cell just outside the starter house's door, per {@code doorOutside}: (10, 2, 8) is the door's lower half,
     * facing south (into the house), so (10, 2, 7) is the cell outside it. */
    private static final BlockPos DOOR_OUTSIDE = new BlockPos(10, 2, 7);

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_chest_door", timeoutTicks = 4000)
    public static void neverBreaksAChestAtTheDoor(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        BlockPos chest = helper.absolutePos(DOOR_OUTSIDE);
        helper.setBlock(DOOR_OUTSIDE, Blocks.CHEST);
        enrollPaver(helper, village, 12, 1, 20);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getBlockState(chest).is(Blocks.CHEST), "chest at the door was broken");
            helper.assertTrue(village.pathQueue().isEmpty(), "path never abandoned");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_chest_route", timeoutTicks = 6000)
    public static void neverBreaksAChestOnACachedRoute(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        ServerLevel level = helper.getLevel();
        // The same route PathWork itself will compute and cache on its first plan(), from the same clear terrain:
        // a pure, side-effect-free query, so predicting it this way does not disturb what PathWork later finds.
        List<PathRoute.Cell> predicted = PathRoute.find(level, village, helper.absolutePos(DOOR_OUTSIDE), village.center())
                .orElseThrow(() -> new IllegalStateException("no route to predict"));
        BlockPos target = predicted.get(Math.min(6, predicted.size() - 1)).surface();
        enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean chestPlaced = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!chestPlaced.get() && !village.pathCells().isEmpty()) {
                level.setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
                chestPlaced.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chestPlaced.get(), "chest was never placed on the cached route");
            helper.assertTrue(level.getBlockState(target).is(Blocks.CHEST), "chest on the cached route was broken");
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_chest_approach", timeoutTicks = 6000)
    public static void neverBreaksAChestPlacedDuringTheApproach(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                helper.setBlock(x, 1, z, Blocks.SHORT_GRASS);
            }
        }
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        ServerLevel level = helper.getLevel();
        Villager villager = enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean chestPlaced = new AtomicBoolean();
        AtomicReference<BlockPos> chestPos = new AtomicReference<>();
        // Every cell is covered in short grass, so sooner or later the paver's current task is a dig() walking to a
        // BreakBlock on short grass (the door cell itself is raised and placed, not dug, so this is not necessarily
        // the very first cell): this catches it still walking there, more than 3 blocks away, before BreakBlock ever
        // runs, matching the finding's repro exactly rather than the pre-check neverBreaksAChestOnACachedRoute covers.
        helper.onEachTick(() -> {
            if (chestPlaced.get()) {
                return;
            }
            Task current = villager.getData(CitizenAttachments.RUNTIME).currentTask();
            if (!(current instanceof TaskSequence seq) || !(seq.currentStep() instanceof MoveTo move)) {
                return;
            }
            BlockPos target = move.target();
            if (!level.getBlockState(target).is(Blocks.SHORT_GRASS)) {
                return;
            }
            if (villager.distanceToSqr(Vec3.atCenterOf(target)) <= 9.0) {
                return;
            }
            level.setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
            chestPos.set(target.immutable());
            chestPlaced.set(true);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chestPlaced.get(), "chest was never placed during the paver's approach");
            BlockPos target = chestPos.get();
            helper.assertTrue(level.getBlockState(target).is(Blocks.CHEST), "chest placed during the approach was broken");
            helper.assertFalse(village.pathCells().contains(target), "the obstructed cell was recorded as a laid path cell");
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_chest_not_recorded", timeoutTicks = 6000)
    public static void obstructedCellIsNeverRecordedAsPath(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        ServerLevel level = helper.getLevel();
        // The same route PathWork itself will compute and cache on its first plan(), from the same clear terrain:
        // a pure, side-effect-free query, so predicting it this way does not disturb what PathWork later finds.
        List<PathRoute.Cell> predicted = PathRoute.find(level, village, helper.absolutePos(DOOR_OUTSIDE), village.center())
                .orElseThrow(() -> new IllegalStateException("no route to predict"));
        BlockPos target = predicted.get(Math.min(6, predicted.size() - 1)).surface();
        enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean chestPlaced = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!chestPlaced.get() && !village.pathCells().isEmpty()) {
                level.setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
                chestPlaced.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chestPlaced.get(), "chest was never placed on the cached route");
            helper.assertTrue(level.getBlockState(target).is(Blocks.CHEST), "chest on the cached route was broken");
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            // Not isPathColumn: the recomputed route may legitimately cross the chest's column again at a different
            // height (isPathColumn does not distinguish), so only the exact obstructed cell is asserted here.
            helper.assertFalse(village.pathCells().contains(target), "the obstructed cell was recorded as a laid path cell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_requeue", timeoutTicks = 12000)
    public static void requeuesAHouseAfterFiveConsecutiveFailures(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        BuildingRecord houseA = placeStarterHouse(helper, village, HOUSE_ORIGIN);
        // A stone block right at house A's door threshold: diggable (so the route to it is valid and dig() starts),
        // but a cancelled LivingDestroyBlockEvent (simulating another mod's land protection) makes every attempt to
        // break it fail, forcing MAX_CONSECUTIVE_FAILURES (5) failures without ever laying a single cell for house A.
        BlockPos stuck = helper.absolutePos(DOOR_OUTSIDE);
        helper.setBlock(DOOR_OUTSIDE, Blocks.STONE);
        PROTECTED_BREAK.add(stuck);
        BuildingRecord houseB = placeStarterHouse(helper, village, new BlockPos(30, 1, 8));
        enrollPaver(helper, village, 20, 1, 20);
        // Protection must stay in place for every retry the loop below performs; it is only lifted once, after every
        // assertion below has already passed (as ProtectionTests does), never in a finally that would also run - and
        // so lift it - on each of the many earlier ticks where an assertion still throws and retries.
        helper.succeedWhen(() -> {
            helper.assertFalse(village.pathQueue().contains(houseB.origin()),
                    "house B was never taken off the queue: house A at the head, stuck forever, starved it");
            helper.assertTrue(reachedBell(village, bell), "house B's path never reaches within 2 of the bell");
            helper.assertTrue(village.pathQueue().contains(houseA.origin()),
                    "house A was dropped from the queue instead of requeued at the back");
            helper.assertTrue(helper.getLevel().getBlockState(stuck).is(Blocks.STONE), "the protected block at the door was broken");
            PROTECTED_BREAK.remove(stuck);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_resume", timeoutTicks = 8000)
    public static void resumesAfterAFreshJobMidPath(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        Villager villager = enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean swapped = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!swapped.get() && village.pathCells().size() >= 3) {
                CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new PathWork());
                swapped.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(swapped.get(), "job was never swapped mid-path");
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_grass", timeoutTicks = 6000)
    public static void clearsShortGrassBeforePaving(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                helper.setBlock(x, 1, z, Blocks.SHORT_GRASS);
            }
        }
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertFalse(village.pathCells().isEmpty(), "no path cells laid");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            VillageTestSupport.remove(helper, village);
        });
    }
}
