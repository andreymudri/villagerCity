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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
    public static void retriesInsteadOfAbandoningWhenThereIsNoRouteYet(GameTestHelper helper) {
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
        BuildingRecord house = placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        helper.runAfterDelay(3000, () -> {
            // Never removeQueuedPath without requeuing it: a house whose route cannot be built right now (the bell
            // walled off entirely, here) keeps its place in the queue for a later retry instead of losing its path
            // to the bell forever, with nothing left anywhere that would ever queue it again.
            helper.assertTrue(village.pathQueue().contains(house.origin()), "house was dropped from the queue instead of retried");
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
    private static final BlockPos DOOR_LOWER = new BlockPos(10, 2, 8);
    private static final BlockPos DOOR_UPPER = new BlockPos(10, 3, 8);

    /** {@code oak}'s door properties (facing, half, hinge, open, powered) carried onto an iron door block state. */
    private static BlockState ironDoorLike(BlockState oak) {
        return Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, oak.getValue(DoorBlock.FACING))
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, oak.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF))
                .setValue(DoorBlock.HINGE, oak.getValue(DoorBlock.HINGE))
                .setValue(DoorBlock.OPEN, oak.getValue(DoorBlock.OPEN))
                .setValue(DoorBlock.POWERED, oak.getValue(DoorBlock.POWERED));
    }

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

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_makepath_approach", timeoutTicks = 6000)
    public static void crossesAtGroundLevelABlockPlacedDuringTheApproachWithoutBuryingIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        ServerLevel level = helper.getLevel();
        BlockPos storehouseAbs = helper.absolutePos(new BlockPos(4, 1, 24));
        Villager villager = enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean placed = new AtomicBoolean();
        AtomicReference<BlockPos> supportPos = new AtomicReference<>();
        AtomicReference<Task> approachTask = new AtomicReference<>();
        // The whole area is flat, obstacle-free grass, so every cell (past the raised door step) is a MakePath cell:
        // this catches the paver still walking to one, more than 3 blocks away, before MakePath (or its RequireDirt
        // re-check) ever runs, mirroring neverBreaksAChestPlacedDuringTheApproach for the placement side. The
        // storehouse also sits on grass, so its own withdrawal walk (also a MoveTo in a TaskSequence) is excluded
        // explicitly, or it is mistaken for a MakePath approach too.
        helper.onEachTick(() -> {
            if (placed.get()) {
                return;
            }
            Task current = villager.getData(CitizenAttachments.RUNTIME).currentTask();
            if (!(current instanceof TaskSequence seq) || !(seq.currentStep() instanceof MoveTo move)) {
                return;
            }
            BlockPos target = move.target();
            if (target.equals(storehouseAbs)) {
                return;
            }
            BlockPos support = target.below();
            if (!level.getBlockState(support).is(Blocks.GRASS_BLOCK)) {
                return;
            }
            if (villager.distanceToSqr(Vec3.atCenterOf(target)) <= 9.0) {
                return;
            }
            level.setBlock(support, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            supportPos.set(support.immutable());
            approachTask.set(current);
            placed.set(true);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(placed.get(), "diamond block was never placed during the paver's approach");
            // Wait for the very task instance that was walking toward the cell to finish (success or failure):
            // checking survival any earlier would trivially pass while the paver has not reached it yet.
            helper.assertTrue(villager.getData(CitizenAttachments.RUNTIME).currentTask() != approachTask.get(),
                    "the paver has not yet finished approaching the cell");
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            BlockPos support = supportPos.get();
            // A diamond block is exactly as solid and walkable as the grass_block it replaced: at ground level (the
            // only kind of cell this whole flat area ever produces), a foreign block like it is already a fine
            // surface to cross, left alone rather than buried under new dirt or detoured around (see supportBlocked
            // in PathRoute, and the guard in its neighbour loop that only applies it to RAISED/CUT/BRIDGE cells).
            helper.assertTrue(level.getBlockState(support).is(Blocks.DIAMOND_BLOCK), "block placed during the approach was paved over");
            helper.assertTrue(village.pathCells().contains(support.above()), "the cell over the diamond block was never recorded as a laid path cell");
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

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_iron_door", timeoutTicks = 4000)
    public static void neverOpensAnIronDoor(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        BuildingRecord house = placeStarterHouse(helper, village, HOUSE_ORIGIN);
        // Swap the starter house's own oak door for an iron one, same facing/half/hinge: no vanilla mob (and this
        // paver, driving navigation directly rather than through InteractWithDoor) can open it, so the house's path
        // must be skipped instead of the door being forced open.
        helper.setBlock(DOOR_LOWER, ironDoorLike(helper.getBlockState(DOOR_LOWER)));
        helper.setBlock(DOOR_UPPER, ironDoorLike(helper.getBlockState(DOOR_UPPER)));
        enrollPaver(helper, village, 12, 1, 20);
        helper.runAfterDelay(3000, () -> {
            // Retried, not abandoned: an iron door is exactly the "no route yet" case (see doorOutside), which must
            // keep the house queued for a later retry rather than lose its path to the bell forever.
            helper.assertTrue(village.pathQueue().contains(house.origin()), "house was dropped from the queue instead of retried");
            helper.assertTrue(village.pathCells().isEmpty(), "path cells laid despite the door being iron");
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            helper.assertTrue(lower.getBlock() instanceof DoorBlock doorBlock && !doorBlock.isOpen(lower), "iron door was opened");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_door_griefing", timeoutTicks = 1000)
    public static void neverOpensTheDoorWithMobGriefingOff(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        boolean previous = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, helper.getLevel().getServer());
        enrollPaver(helper, village, 12, 1, 20);
        helper.runAfterDelay(200, () -> {
            helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(previous, helper.getLevel().getServer());
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(lower.getBlock() instanceof DoorBlock doorBlock && !doorBlock.isOpen(lower),
                    "the door was opened with mobGriefing off");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_door_closes", timeoutTicks = 6000)
    public static void closesTheDoorOnceThePaverWalksClear(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean opened = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            if (lower.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(lower)) {
                opened.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            helper.assertTrue(opened.get(), "the door was never opened by the paver");
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            helper.assertTrue(lower.getBlock() instanceof DoorBlock doorBlock && !doorBlock.isOpen(lower),
                    "the door was not closed again once the paver walked clear of it");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_no_route_door", timeoutTicks = 3200)
    public static void neverOpensTheDoorForAHouseThatCannotBeRouted(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        // The bell walled off entirely, like retriesInsteadOfAbandoningWhenThereIsNoRouteYet, but this house's own
        // door is left ordinary and wooden - openable in principle. Opening the door before find() is even
        // attempted (the old doorOutside) would open it here regardless of the route ever succeeding, and every
        // retry that followed the sweep's next close would open it again, fighting the sweep forever. Deferred
        // until a route is actually found, the door here must never open at all, since no route to this bell ever
        // exists.
        for (int x = 21; x <= 27; x++) {
            for (int z = 21; z <= 27; z++) {
                for (int y = 0; y <= 4; y++) {
                    helper.setBlock(x, y, z, Blocks.BRICKS);
                }
            }
        }
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        BuildingRecord house = placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean everOpened = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            if (lower.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(lower)) {
                everOpened.set(true);
            }
        });
        helper.runAfterDelay(3000, () -> {
            helper.assertTrue(village.pathQueue().contains(house.origin()), "house was dropped from the queue instead of retried");
            helper.assertFalse(everOpened.get(), "the door was opened even though the house was never actually routed");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_door_swapped", timeoutTicks = 6000)
    public static void neverForceClosesADoorSwappedForAnIronOneAfterOpening(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean swapped = new AtomicBoolean();
        AtomicBoolean everClosedAfterSwap = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            if (!swapped.get()) {
                if (lower.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(lower)) {
                    // The exact repro: a player breaks the oak door this mod opened and puts their own iron one in
                    // its place, open, right after. Only the block-identity check in closeIfClear stops the sweep
                    // from later force-closing it - an iron door the paver itself could never have opened - which
                    // would otherwise contradict the very rule neverOpensAnIronDoor enforces for opening one.
                    helper.setBlock(DOOR_LOWER, ironDoorLike(lower));
                    helper.setBlock(DOOR_UPPER, ironDoorLike(helper.getBlockState(DOOR_UPPER)));
                    swapped.set(true);
                }
                return;
            }
            if (lower.getBlock() instanceof DoorBlock doorBlock && !doorBlock.isOpen(lower)) {
                everClosedAfterSwap.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(swapped.get(), "the door was never opened, so the swap never happened");
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            helper.assertFalse(everClosedAfterSwap.get(), "the iron door that replaced the oak one was force-closed by the sweep");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_door_griefing_after_open", timeoutTicks = 2000)
    public static void keepsTrackingTheDoorInsteadOfForceClosingWhenGriefingTurnsOffAfterOpening(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        Villager villager = enrollPaver(helper, village, 12, 1, 20);
        BlockPos farAway = helper.absolutePos(new BlockPos(2, 1, 2));
        AtomicBoolean opened = new AtomicBoolean();
        AtomicLong openedAtTick = new AtomicLong(-1);
        helper.onEachTick(() -> {
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            if (!opened.get()) {
                if (!(lower.getBlock() instanceof DoorBlock doorBlock) || !doorBlock.isOpen(lower)) {
                    return;
                }
                opened.set(true);
                openedAtTick.set(helper.getLevel().getGameTime());
                // Turned off right as the door opens, well before the paver could ever walk clear of it on its own:
                // the sweep must never force it shut while griefing is disallowed, the same rule that stops this
                // mod opening a door in the first place (see neverOpensTheDoorWithMobGriefingOff).
                helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, helper.getLevel().getServer());
            }
            // Kept far enough, every tick from the moment it opens, that it is never once seen within
            // DOOR_CLOSE_DISTANCE of it: only the DOOR_OPEN_TIMEOUT_TICKS safety net ever attempts to close this
            // door, so whether that attempt actually goes through is entirely down to the mayGrief re-check.
            villager.teleportTo(farAway.getX() + 0.5, farAway.getY(), farAway.getZ() + 0.5);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(opened.get(), "the door was never opened by the paver");
            helper.assertTrue(helper.getLevel().getGameTime() - openedAtTick.get() > PathWork.DOOR_OPEN_TIMEOUT_TICKS + 100,
                    "not enough ticks have passed since the door opened for the timeout safety net to have tried closing it");
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, helper.getLevel().getServer());
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(lower.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(lower),
                    "the door was force-closed while mobGriefing was off");
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_step_at_door", timeoutTicks = 6000)
    public static void routesOverAForeignStepUnderTheDoorstepInsteadOfLosingTheRoute(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        // The one cell a route can never detour around: doorOutside always returns exactly DOOR_OUTSIDE, so a
        // player's cobblestone step right under it (same groundAt height as the flat grass everywhere else) must
        // not throw the whole route away and leave the house with no path to the bell at all.
        BlockPos step = DOOR_OUTSIDE.below();
        helper.setBlock(step, Blocks.COBBLESTONE);
        enrollPaver(helper, village, 12, 1, 20);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertFalse(village.pathCells().isEmpty(), "no path cells laid");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            helper.assertTrue(helper.getBlockState(step).is(Blocks.COBBLESTONE), "the cobblestone step under the doorstep was broken");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_stone_band", timeoutTicks = 8000)
    public static void crossesAStoneBandAtGroundLevelInsteadOfBuryingItInDirt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A band of cobblestone the route has no way around (it spans the whole test area, replacing the grass
        // GameTestSupport itself lays down): not natural ground (not diggable, so not a CUT), not replaceable, but
        // exactly as solid and walkable as the grass it replaces (groundAt sees the same height either way), so the
        // route must cross it as GROUND - stepping onto it as-is, one block below the walking height it would have
        // used anyway - rather than as a RAISED cell that places its own dirt (later paved into a dirt path by
        // MakePath) in the air right above it, leaving the cobblestone itself untouched but no longer the surface.
        int bandZ = 16;
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            helper.setBlock(x, 0, bandZ, Blocks.COBBLESTONE);
        }
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        int bandZAbs = helper.absolutePos(new BlockPos(0, 0, bandZ)).getZ();
        // The natural ground-level walking height everywhere else in this flat area (one above the grass at
        // relative y=0): a cell crossing the band at this same height is GROUND; anything higher is RAISED.
        int groundSurfaceYAbs = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        enrollPaver(helper, village, 12, 1, 20);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            List<BlockPos> crossing = village.pathCells().stream().filter(pos -> pos.getZ() == bandZAbs).toList();
            helper.assertFalse(crossing.isEmpty(), "route never crosses the stone band");
            for (BlockPos pos : crossing) {
                helper.assertTrue(pos.getY() == groundSurfaceYAbs,
                        "the stone band was crossed as a RAISED cell at " + pos.toShortString() + " instead of at ground level");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_fence_post", timeoutTicks = 8000)
    public static void routeNeverStandsOnAFencePost(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A fence line replacing the ground itself (flush, like the cobblestone band in
        // crossesAStoneBandAtGroundLevelInsteadOfBuryingItInDirt, not sitting on top of it): blocksMotion() is true
        // for a fence just like it is for the grass it replaces, so groundAt sees exactly the same height either
        // way, and standing directly on top of it is the cheapest way across - the temptation a real stand-on-it
        // check has to resist. Left short of the whole area width (unlike the cobblestone band, a fence is neither
        // diggable nor replaceable, so a full-width line would leave no way across at all, fixed or not) so a route
        // can still detour around its end near the bell if crossing it is refused.
        int fenceZ = 16;
        for (int x = 0; x < 30; x++) {
            helper.setBlock(x, 0, fenceZ, Blocks.OAK_FENCE);
        }
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        ServerLevel level = helper.getLevel();
        enrollPaver(helper, village, 12, 1, 20);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().isEmpty(), "path still queued");
            helper.assertTrue(reachedBell(village, bell), "path never reaches within 2 of the bell");
            for (BlockPos cell : village.pathCells()) {
                helper.assertFalse(level.getBlockState(cell.below()).is(Blocks.OAK_FENCE),
                        "path cell " + cell.toShortString() + " stands directly on a fence post");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    /** The doorstep cell itself unusable (a chest on it, as in {@code neverBreaksAChestAtTheDoor}), and the first
     * neighbour {@code resolveStart} tries (north, per its own {@code Direction.Plane.HORIZONTAL} order) turned to
     * water at exactly the height that makes its {@code ground().y()} equal the doorstep's own height: the exact
     * repro the finding describes. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_start_fallback_water", timeoutTicks = 100)
    public static void startFallbackNeverStandsInOpenWater(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        helper.setBlock(DOOR_OUTSIDE, Blocks.CHEST);
        BlockPos waterSupport = new BlockPos(DOOR_OUTSIDE.getX(), DOOR_OUTSIDE.getY() - 1, DOOR_OUTSIDE.getZ() - 1);
        helper.setBlock(waterSupport, Blocks.WATER);
        ServerLevel level = helper.getLevel();
        List<PathRoute.Cell> route = PathRoute.find(level, village, helper.absolutePos(DOOR_OUTSIDE), village.center())
                .orElseThrow(() -> new IllegalStateException("no route found"));
        PathRoute.Cell startCell = route.get(0);
        BlockPos expectedStart = helper.absolutePos(new BlockPos(DOOR_OUTSIDE.getX(), DOOR_OUTSIDE.getY(), DOOR_OUTSIDE.getZ() - 1));
        // Without the fluid-to-BRIDGE promotion, this neighbour misclassifies as GROUND, gets rejected outright by
        // standableSupport for having a fluid (no-floor) support, and resolveStart moves on to a dry neighbour
        // instead - a different cell than the one asserted here, and never a BRIDGE.
        helper.assertTrue(startCell.surface().equals(expectedStart),
                "the start fallback skipped the water neighbour at " + expectedStart.toShortString() + " instead of bridging it");
        helper.assertTrue(startCell.kind() == PathRoute.Kind.BRIDGE,
                "the start fallback stood on the water neighbour as " + startCell.kind() + " instead of BRIDGE");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_no_route_starvation", timeoutTicks = 6000)
    public static void doesNotStarveOtherHousesBehindAnUnroutableOne(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        BlockPos bell = helper.absolutePos(bellRelative);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        BuildingRecord houseA = placeStarterHouse(helper, village, HOUSE_ORIGIN);
        // House A's door swapped for iron, exactly like neverOpensAnIronDoor: no vanilla mob (and this paver) can
        // ever open it, so house A can never be routed. The single paver below must not spend every tick retrying
        // house A forever while house B, queued right behind it, never gets a turn.
        helper.setBlock(DOOR_LOWER, ironDoorLike(helper.getBlockState(DOOR_LOWER)));
        helper.setBlock(DOOR_UPPER, ironDoorLike(helper.getBlockState(DOOR_UPPER)));
        BuildingRecord houseB = placeStarterHouse(helper, village, new BlockPos(30, 1, 8));
        enrollPaver(helper, village, 20, 1, 20);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.pathQueue().contains(houseA.origin()),
                    "house A was dropped from the queue instead of staying queued for a later retry");
            helper.assertFalse(village.pathQueue().contains(houseB.origin()),
                    "house B never got its path: house A at the head, permanently unroutable, starved it");
            helper.assertTrue(reachedBell(village, bell), "house B's path never reaches within 2 of the bell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_door_timeout", timeoutTicks = 1600)
    public static void closesTheDoorAfterATimeoutWhenTheOpenerNeverComesNear(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        Villager villager = enrollPaver(helper, village, 12, 1, 20);
        BlockPos farAway = helper.absolutePos(new BlockPos(2, 1, 2));
        AtomicBoolean opened = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            if (lower.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(lower)) {
                opened.set(true);
            }
            if (opened.get()) {
                // Kept far enough from the door, every single tick, that it is never once seen within
                // DOOR_CLOSE_DISTANCE of it: only the DOOR_OPEN_TIMEOUT_TICKS safety net - not the "opener walked
                // clear" leg, which needs everNear to have been set first - can close the door in this test, since
                // the opener (still alive throughout) is never near it to begin with.
                villager.teleportTo(farAway.getX() + 0.5, farAway.getY(), farAway.getZ() + 0.5);
            }
        });
        helper.runAfterDelay(1400, () -> {
            helper.assertTrue(opened.get(), "the door was never opened by the paver");
            helper.assertTrue(villager.isAlive(), "the opener died or was discarded instead of merely staying away");
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(lower.getBlock() instanceof DoorBlock doorBlock && !doorBlock.isOpen(lower),
                    "the door was not closed by the timeout when its opener never came near");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_door_discard", timeoutTicks = 1500)
    public static void closesADoorEvenAfterThePaverThatOpenedItIsDiscarded(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        placeStarterHouse(helper, village, HOUSE_ORIGIN);
        Villager villager = enrollPaver(helper, village, 12, 1, 20);
        AtomicBoolean discarded = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (discarded.get()) {
                return;
            }
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            if (lower.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(lower)) {
                // The exact repro: gone the very tick the door opens, so no further tick of this paver, its job, or
                // any task instance of its ever runs again - the only thing left that can still close the door has
                // to owe nothing to any of those three still existing.
                villager.discard();
                discarded.set(true);
            }
        });
        helper.runAfterDelay(1200, () -> {
            helper.assertTrue(discarded.get(), "the door was never opened before the paver was discarded");
            BlockState lower = helper.getBlockState(DOOR_LOWER);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(lower.getBlock() instanceof DoorBlock doorBlock && !doorBlock.isOpen(lower),
                    "the door was still open 1200 ticks after the paver that opened it was discarded");
            helper.succeed();
        });
    }
}
