package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.job.PathWork;
import dev.andreymudri.villagercity.job.StreetWork;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * How the paver builds one cell ({@link PathWork}'s cell helpers), driven through the street runs that use them
 * ({@link StreetWork}), and what is left of the old house-to-bell path job: it drains its queue and closes a door an
 * older save recorded as opened. Every street starts at {@link StreetTests#ROOT} and grows east along z = 24.
 */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PathTests {
    /** Absolute positions a protection mod would guard; the listener below cancels destroys there. */
    private static final Set<BlockPos> PROTECTED_BREAK = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((LivingDestroyBlockEvent e) -> {
            if (PROTECTED_BREAK.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    private static VillageData streetVillage(GameTestHelper helper) {
        return StreetTests.streetVillage(helper, StreetTests.ROOT, 0);
    }

    private static Villager enrollPaver(GameTestHelper helper, VillageData village, Job job) {
        Villager villager = GameTestSupport.spawnVillager(helper, 4, 1, 20);
        CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), job);
        return villager;
    }

    private static Villager enrollPaver(GameTestHelper helper, VillageData village) {
        return enrollPaver(helper, village, new StreetWork());
    }

    /** True once the first run is laid, all {@link StreetWork#RUN_LENGTH} centre cells of it. */
    private static boolean firstRunLaid(VillageData village) {
        return StreetTests.withHops(village, 1).size() == StreetWork.RUN_LENGTH;
    }

    /** The relative centre cell of the first run at column {@code x}. */
    private static BlockPos centre(int x) {
        return new BlockPos(x, StreetTests.ROOT.getY(), StreetTests.ROOT.getZ());
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_flat", timeoutTicks = 4000)
    public static void surfacesAStreetOnFlatGroundWithDirtPath(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper);
        enrollPaver(helper, village);
        helper.succeedWhen(() -> {
            helper.assertTrue(firstRunLaid(village), "the first run is not laid yet");
            for (StreetCell cell : StreetTests.withHops(village, 1)) {
                BlockPos pos = StreetTests.relative(helper, cell.pos());
                for (BlockPos each : List.of(pos, pos.north(), pos.south())) {
                    helper.assertTrue(helper.getBlockState(each.below()).is(Blocks.DIRT_PATH), "no dirt path under " + each.toShortString());
                }
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_hill", timeoutTicks = 6000)
    public static void stepsUpTerraces(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // One-block terraces every two columns from x = 6, so both the centre row and its sides climb.
        StreetTests.terrain(helper, (x, z) -> x < 6 ? 0 : Math.min(3, (x - 4) / 2));
        VillageData village = streetVillage(helper);
        enrollPaver(helper, village);
        helper.succeedWhen(() -> {
            helper.assertTrue(firstRunLaid(village), "the first run is not laid yet");
            List<BlockPos> cells = village.pathCells();
            for (BlockPos a : cells) {
                for (BlockPos b : cells) {
                    int dx = Math.abs(a.getX() - b.getX());
                    int dz = Math.abs(a.getZ() - b.getZ());
                    if (dx + dz == 1) {
                        helper.assertTrue(Math.abs(a.getY() - b.getY()) <= 1,
                                "adjacent path cells " + a.toShortString() + " and " + b.toShortString() + " differ by more than one block");
                    }
                }
                BlockPos rel = StreetTests.relative(helper, a);
                helper.assertTrue(helper.getBlockState(rel).isAir() && helper.getBlockState(rel.above()).isAir(),
                        "no headroom at " + rel.toShortString());
            }
            BlockPos last = StreetTests.relative(helper, StreetTests.withHops(village, 1).get(StreetWork.RUN_LENGTH - 1).pos());
            helper.assertTrue(last.getY() == 4, "the run ends at " + last.toShortString() + ", not on the top terrace");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_bridge", timeoutTicks = 6000)
    public static void bridgesAWaterChannel(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 8; x <= 9; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                helper.setBlock(x, 0, z, Blocks.WATER);
            }
        }
        VillageData village = streetVillage(helper);
        enrollPaver(helper, village);
        helper.succeedWhen(() -> {
            helper.assertTrue(firstRunLaid(village), "the first run is not laid yet");
            for (int x = 8; x <= 9; x++) {
                for (int z = 23; z <= 25; z++) {
                    helper.assertTrue(helper.getBlockState(new BlockPos(x, 0, z)).is(Blocks.OAK_PLANKS),
                            "no oak planks bridge the channel at " + x + ", 0, " + z);
                }
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_chest_route", timeoutTicks = 6000)
    public static void neverBreaksAChestOnACachedRun(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper);
        BlockPos target = centre(8);
        enrollPaver(helper, village);
        AtomicBoolean chestPlaced = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!chestPlaced.get() && village.pathCells().size() > 1) {
                helper.setBlock(target, Blocks.CHEST);
                chestPlaced.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chestPlaced.get(), "chest was never placed on the cached run");
            helper.assertTrue(helper.getBlockState(target).is(Blocks.CHEST), "chest on the cached run was broken");
            // The run that met the chest stopped; a later run grew the street from where it stopped.
            helper.assertFalse(StreetTests.withHops(village, 2).isEmpty(), "the street never grew past the chest");
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
        VillageData village = streetVillage(helper);
        ServerLevel level = helper.getLevel();
        Villager villager = enrollPaver(helper, village);
        AtomicBoolean chestPlaced = new AtomicBoolean();
        AtomicReference<BlockPos> chestPos = new AtomicReference<>();
        AtomicInteger streetsWhenPlaced = new AtomicInteger();
        // Every cell is covered in short grass, so the paver's first tasks are digs walking to a BreakBlock on short
        // grass: this catches one still walking there, more than 3 blocks away, before BreakBlock ever runs.
        helper.onEachTick(() -> {
            if (chestPlaced.get()) {
                return;
            }
            Task current = villager.getData(CitizenAttachments.RUNTIME).currentTask();
            if (!(current instanceof TaskSequence seq) || !(seq.currentStep() instanceof MoveTo move)) {
                return;
            }
            BlockPos target = move.target();
            if (!level.getBlockState(target).is(Blocks.SHORT_GRASS) || villager.distanceToSqr(Vec3.atCenterOf(target)) <= 9.0) {
                return;
            }
            level.setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
            chestPos.set(target.immutable());
            streetsWhenPlaced.set(village.streets().size());
            chestPlaced.set(true);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chestPlaced.get(), "chest was never placed during the paver's approach");
            BlockPos target = chestPos.get();
            helper.assertTrue(level.getBlockState(target).is(Blocks.CHEST), "chest placed during the approach was broken");
            helper.assertFalse(village.pathCells().contains(target), "the obstructed cell was recorded as a laid path cell");
            helper.assertTrue(village.streets().size() >= streetsWhenPlaced.get() + StreetWork.RUN_LENGTH,
                    "the street never grew another run after the chest");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_makepath_approach", timeoutTicks = 6000)
    public static void crossesAtGroundLevelABlockPlacedDuringTheApproachWithoutBuryingIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper);
        ServerLevel level = helper.getLevel();
        BlockPos storehouseAbs = helper.absolutePos(StreetTests.STOREHOUSE);
        Villager villager = enrollPaver(helper, village);
        AtomicBoolean placed = new AtomicBoolean();
        AtomicReference<BlockPos> supportPos = new AtomicReference<>();
        AtomicReference<Task> approachTask = new AtomicReference<>();
        // Flat, obstacle-free grass: every cell is a MakePath cell. This catches the paver still walking to one, more
        // than 3 blocks away, before MakePath (or its RequireDirt re-check) runs. The storehouse also sits on grass,
        // so a walk to it is excluded explicitly.
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
            if (!level.getBlockState(support).is(Blocks.GRASS_BLOCK) || villager.distanceToSqr(Vec3.atCenterOf(target)) <= 9.0) {
                return;
            }
            level.setBlock(support, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            supportPos.set(support.immutable());
            approachTask.set(current);
            placed.set(true);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(placed.get(), "diamond block was never placed during the paver's approach");
            // Wait for the very task that was walking toward the cell to finish: checking any earlier would pass
            // trivially while the paver has not reached it yet.
            helper.assertTrue(villager.getData(CitizenAttachments.RUNTIME).currentTask() != approachTask.get(),
                    "the paver has not yet finished approaching the cell");
            BlockPos support = supportPos.get();
            // A diamond block is as solid and walkable as the grass it replaced: at ground level it is a fine
            // surface to cross, left alone rather than paved over or buried under new dirt.
            helper.assertTrue(level.getBlockState(support).is(Blocks.DIAMOND_BLOCK), "block placed during the approach was paved over");
            helper.assertTrue(village.pathCells().contains(support.above()), "the cell over the diamond block was never recorded as a laid path cell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_chest_not_recorded", timeoutTicks = 6000)
    public static void obstructedSideCellIsNeverRecordedAsPath(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper);
        BlockPos target = centre(8).south();
        BlockPos targetAbs = helper.absolutePos(target);
        enrollPaver(helper, village);
        AtomicBoolean chestPlaced = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!chestPlaced.get() && village.pathCells().size() > 1) {
                helper.setBlock(target, Blocks.CHEST);
                chestPlaced.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chestPlaced.get(), "chest was never placed on the cached run");
            helper.assertTrue(helper.getBlockState(target).is(Blocks.CHEST), "chest on the cached run was broken");
            helper.assertFalse(StreetTests.withHops(village, 2).isEmpty(), "the street never grew past the chest");
            helper.assertFalse(village.pathCells().contains(targetAbs), "the obstructed side cell was recorded as a laid path cell");
            helper.assertFalse(village.streets().stream().anyMatch(cell -> cell.pos().getX() == targetAbs.getX() && cell.hops() == 1),
                    "the run laid the centre cell beside the obstructed side cell");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_requeue", timeoutTicks = 8000)
    public static void neverBreaksAProtectedBlockAndGrowsElsewhereAfterFiveFailures(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper);
        // A stone block standing on the south side cell at x = 7, one above the street: diggable, so the run plans
        // to cut it, but a cancelled LivingDestroyBlockEvent (another mod's land protection) makes every attempt to
        // break it fail.
        BlockPos stuckRelative = centre(7).south();
        BlockPos stuck = helper.absolutePos(stuckRelative);
        helper.setBlock(stuckRelative, Blocks.STONE);
        PROTECTED_BREAK.add(stuck);
        enrollPaver(helper, village);
        // Protection stays for every retry; it is lifted only once every assertion has passed.
        helper.succeedWhen(() -> {
            helper.assertFalse(StreetTests.withHops(village, 2).isEmpty(), "the street never grew anywhere else: the protected block stalled it");
            helper.assertTrue(helper.getLevel().getBlockState(stuck).is(Blocks.STONE), "the protected block was broken");
            helper.assertFalse(village.pathCells().contains(stuck), "the protected cell was recorded as a path cell");
            PROTECTED_BREAK.remove(stuck);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_resume", timeoutTicks = 6000)
    public static void resumesAfterAFreshJobMidRun(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper);
        Villager villager = enrollPaver(helper, village);
        AtomicBoolean swapped = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!swapped.get() && village.streets().size() >= 4) {
                CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new StreetWork());
                swapped.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(swapped.get(), "job was never swapped mid-run");
            for (int x = 5; x <= 12; x++) {
                BlockPos cell = helper.absolutePos(centre(x));
                helper.assertTrue(village.streets().stream().anyMatch(street -> street.pos().equals(cell)),
                        "the street has no cell at x " + x + " after the job was replaced");
            }
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
        VillageData village = streetVillage(helper);
        enrollPaver(helper, village);
        helper.succeedWhen(() -> {
            helper.assertTrue(firstRunLaid(village), "the first run is not laid yet");
            for (StreetCell cell : StreetTests.withHops(village, 1)) {
                BlockPos pos = StreetTests.relative(helper, cell.pos());
                for (BlockPos each : List.of(pos, pos.north(), pos.south())) {
                    helper.assertTrue(helper.getBlockState(each).isAir(), "short grass left on " + each.toShortString());
                    helper.assertTrue(helper.getBlockState(each.below()).is(Blocks.DIRT_PATH), "no dirt path under " + each.toShortString());
                }
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_stone_band", timeoutTicks = 6000)
    public static void crossesAStoneBandAtGroundLevelInsteadOfBuryingItInDirt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A band of cobblestone across the whole area, flush with the grass: not diggable, not replaceable, but as
        // solid and walkable as the grass it replaces, so the street crosses it as it is, at ground level.
        int bandX = 8;
        for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
            helper.setBlock(bandX, 0, z, Blocks.COBBLESTONE);
        }
        VillageData village = streetVillage(helper);
        int bandXAbs = helper.absolutePos(new BlockPos(bandX, 0, 0)).getX();
        int groundSurfaceYAbs = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        enrollPaver(helper, village);
        helper.succeedWhen(() -> {
            helper.assertTrue(firstRunLaid(village), "the first run is not laid yet");
            List<BlockPos> crossing = village.pathCells().stream().filter(pos -> pos.getX() == bandXAbs).toList();
            helper.assertTrue(crossing.size() == 3, "the street crosses the stone band in " + crossing.size() + " cells, not 3");
            for (BlockPos pos : crossing) {
                helper.assertTrue(pos.getY() == groundSurfaceYAbs, "the stone band was crossed above ground level at " + pos.toShortString());
                helper.assertTrue(helper.getLevel().getBlockState(pos.below()).is(Blocks.COBBLESTONE), "the stone band was covered at " + pos.toShortString());
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_fence_post", timeoutTicks = 4000)
    public static void aRunNeverStandsOnAFencePost(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A fence line replacing the ground itself across the whole area: groundAt sees the same height as the grass
        // either way, and standing on top of it would be the cheapest way across.
        int fenceX = 8;
        for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
            helper.setBlock(fenceX, 0, z, Blocks.OAK_FENCE);
        }
        VillageData village = streetVillage(helper);
        ServerLevel level = helper.getLevel();
        StreetWork job = new StreetWork();
        enrollPaver(helper, village, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(StreetTests.ROOM_TO_GROW.equals(job.waitingFor()), "the paver is still growing: " + job.waitingFor());
            helper.assertTrue(StreetTests.withHops(village, 1).size() == 3, "the run has "
                    + StreetTests.withHops(village, 1).size() + " cells instead of stopping at the fence");
            for (BlockPos cell : village.pathCells()) {
                helper.assertFalse(level.getBlockState(cell.below()).is(Blocks.OAK_FENCE), "path cell " + cell.toShortString() + " stands on a fence post");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_fence_line_capped", timeoutTicks = 4000)
    public static void neverCapsAFenceLineWithAPlacedPath(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // The same fence line: nothing may be built right above it either, which a RAISED cell one block up (its
        // support being the open air over the post) would do.
        int fenceX = 8;
        for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
            helper.setBlock(fenceX, 0, z, Blocks.OAK_FENCE);
        }
        VillageData village = streetVillage(helper);
        int fenceXAbs = helper.absolutePos(new BlockPos(fenceX, 0, 0)).getX();
        StreetWork job = new StreetWork();
        enrollPaver(helper, village, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(StreetTests.ROOM_TO_GROW.equals(job.waitingFor()), "the paver is still growing: " + job.waitingFor());
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                BlockPos above = new BlockPos(fenceX, 1, z);
                helper.assertFalse(helper.getBlockState(above).is(Blocks.DIRT_PATH) || helper.getBlockState(above).is(Blocks.DIRT),
                        "the fence post at z " + z + " was capped with a placed block");
            }
            helper.assertFalse(village.pathCells().stream().anyMatch(pos -> pos.getX() == fenceXAbs), "a path cell was recorded above the fence line");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_queue_drain", timeoutTicks = 1000)
    public static void drainsTheOldPathQueueWithoutLayingAPathOrOpeningADoor(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos bellRelative = new BlockPos(24, 1, 24);
        VillageData village = VillageTestSupport.freshVillage(helper, bellRelative, 30, false);
        StreetTests.stockedStorehouse(helper, village, new BlockPos(4, 1, 24));
        BlockPos origin = new BlockPos(8, 1, 8);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        for (BlueprintPlacement placement : blueprint.placements()) {
            helper.setBlock(origin.offset(placement.offset()), placement.state());
        }
        BuildingRecord house = new BuildingRecord(blueprint.id().toString(), helper.absolutePos(origin), blueprint.size());
        village.addHouse(house);
        // A save from before streets may still hold its queue: queued directly as well, whatever addHouse does.
        village.queuePath(house.origin());
        BlockPos door = origin.offset(2, 1, 0);
        enrollPaver(helper, village, new PathWork());
        AtomicBoolean opened = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockState state = helper.getBlockState(door);
            if (state.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(state)) {
                opened.set(true);
            }
        });
        helper.runAfterDelay(600, () -> {
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(village.pathQueue().isEmpty(), "the old path queue was not drained: " + village.pathQueue());
            helper.assertTrue(village.pathCells().isEmpty(), "a path to the bell was laid: " + village.pathCells());
            helper.assertFalse(opened.get(), "the paver opened the house's door");
            helper.succeed();
        });
    }

    /** An open oak door at (10, 1, 10), recorded on the village as opened by the paver before a restart. */
    private static BlockPos recordedOpenDoor(GameTestHelper helper, VillageData village) {
        BlockPos lower = new BlockPos(10, 1, 10);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        helper.setBlock(lower, door);
        helper.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        village.recordOpenedDoor(helper.absolutePos(lower));
        return lower;
    }

    private static void closesARecordedDoor(GameTestHelper helper, Job job) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper);
        BlockPos door = recordedOpenDoor(helper, village);
        enrollPaver(helper, village, job);
        helper.succeedWhen(() -> {
            BlockState state = helper.getBlockState(door);
            helper.assertTrue(state.getBlock() instanceof DoorBlock, "the door is gone");
            helper.assertFalse(((DoorBlock) state.getBlock()).isOpen(state), "the recorded door is still open");
            helper.assertTrue(village.openedDoors().isEmpty(), "the closed door is still recorded: " + village.openedDoors());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_recorded_door", timeoutTicks = 400)
    public static void closesADoorRecordedAsOpenedAndForgetsIt(GameTestHelper helper) {
        closesARecordedDoor(helper, new PathWork());
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_path_recorded_door_street", timeoutTicks = 400)
    public static void aStreetPaverClosesADoorRecordedAsOpened(GameTestHelper helper) {
        closesARecordedDoor(helper, new StreetWork());
    }
}
