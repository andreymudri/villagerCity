package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.StreetWork;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The paver growing the street graph ({@link StreetWork}). Every test seeds one street cell, the root, two blocks
 * east of the bell, so the first run grows east along the row z = 24 with its side cells on z = 23 and z = 25.
 */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class StreetTests {
    static final BlockPos BELL = new BlockPos(2, 1, 24);
    static final BlockPos ROOT = new BlockPos(4, 1, 24);
    static final BlockPos STOREHOUSE = new BlockPos(2, 1, 40);
    static final String ROOM_TO_GROW = "room to grow";

    /** A bell at {@link #BELL}, a stocked storehouse, and the root street cell at {@code root} with {@code hops}. */
    static VillageData streetVillage(GameTestHelper helper, BlockPos root, int hops) {
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(BELL.getX(), root.getY(), BELL.getZ()), 30, false);
        stockedStorehouse(helper, village, new BlockPos(STOREHOUSE.getX(), root.getY(), STOREHOUSE.getZ()));
        village.addStreetCell(helper.absolutePos(root), hops);
        return village;
    }

    /** A storehouse stocked with plenty of dirt and oak planks, the two materials streets use. */
    static void stockedStorehouse(GameTestHelper helper, VillageData village, BlockPos relative) {
        helper.setBlock(relative, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(relative));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(relative);
        storehouse.insertFromCitizen(new ItemStack(Items.DIRT, 64));
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_PLANKS, 64));
    }

    /** Enrols a paver running {@code job} alone, standing north of the street row. */
    static Villager enrollPaver(GameTestHelper helper, VillageData village, int y, StreetWork job) {
        Villager villager = GameTestSupport.spawnVillager(helper, 4, y, 20);
        CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), job);
        return villager;
    }

    /** The street cells recorded with exactly {@code hops}, in the order they were laid. */
    static List<StreetCell> withHops(VillageData village, int hops) {
        return village.streets().stream().filter(cell -> cell.hops() == hops).toList();
    }

    static BlockPos relative(GameTestHelper helper, BlockPos absolute) {
        return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    /** Fills every column of the area from y = 0 up to {@code top(x, z)}: dirt below, grass on top. */
    static void terrain(GameTestHelper helper, Height top) {
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                int height = top.at(x, z);
                for (int y = 0; y <= height; y++) {
                    helper.setBlock(x, y, z, y == height ? Blocks.GRASS_BLOCK : Blocks.DIRT);
                }
            }
        }
    }

    /** The relative y of a column's topmost ground block. */
    @FunctionalInterface
    interface Height {
        int at(int x, int z);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_slope", timeoutTicks = 6000)
    public static void aRunClimbsAFiveBlockSlopeOneBlockPerCell(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // Flat up to x = 5, then one block higher per column until x = 10, five blocks up, then flat again.
        terrain(helper, (x, z) -> Math.max(0, Math.min(5, x - 5)));
        VillageData village = streetVillage(helper, ROOT, 0);
        enrollPaver(helper, village, 1, new StreetWork());
        helper.succeedWhen(() -> {
            List<StreetCell> run = withHops(village, 1);
            helper.assertTrue(run.size() == StreetWork.RUN_LENGTH, "the first run has " + run.size() + " cells, not " + StreetWork.RUN_LENGTH);
            int previous = ROOT.getY();
            for (StreetCell cell : run) {
                BlockPos pos = relative(helper, cell.pos());
                int ground = Math.max(0, Math.min(5, pos.getX() - 5)) + 1;
                helper.assertTrue(pos.getY() == ground, "street cell " + pos.toShortString() + " is not on the ground at y " + ground);
                helper.assertTrue(Math.abs(pos.getY() - previous) <= StreetWork.MAX_STEP,
                        "street cell " + pos.toShortString() + " steps more than one block from y " + previous);
                helper.assertTrue(helper.getBlockState(pos.below()).is(Blocks.DIRT_PATH), "no dirt path under " + pos.toShortString());
                previous = pos.getY();
            }
            helper.assertTrue(previous - ROOT.getY() == 5, "the run climbed " + (previous - ROOT.getY()) + " blocks, not 5");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_step", timeoutTicks = 4000)
    public static void aRunStopsWhereTheGroundStepsTwoBlocks(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        terrain(helper, (x, z) -> x >= 8 ? 2 : 0);
        VillageData village = streetVillage(helper, ROOT, 0);
        enrollPaver(helper, village, 1, new StreetWork());
        helper.succeedWhen(() -> {
            List<StreetCell> run = withHops(village, 1);
            helper.assertTrue(run.size() == 3, "the first run has " + run.size() + " cells instead of stopping before the 2-block step");
            // A second run started: the first one is over.
            helper.assertFalse(withHops(village, 2).isEmpty(), "no second run yet");
            helper.assertTrue(relative(helper, run.get(2).pos()).getX() == 7, "the first run ends at " + relative(helper, run.get(2).pos()).toShortString());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_reach", timeoutTicks = 4000)
    public static void aRunStopsAtTheReachLimit(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // The bell 60 blocks west of the root, outside the test area: no bell block, only the village's centre.
        BlockPos root = new BlockPos(20, 1, 24);
        BlockPos bellRelative = root.west(60);
        VillageTestSupport.removeVillagesNear(helper, bellRelative);
        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        VillageData village = registry.register(helper.absolutePos(bellRelative));
        village.setManaged(false);
        village.addStreetCell(helper.absolutePos(root), 0);
        registry.setDirty();
        BlockPos bell = village.center();
        enrollPaver(helper, village, 1, new StreetWork());
        helper.succeedWhen(() -> {
            List<StreetCell> run = withHops(village, 1);
            helper.assertTrue(run.size() == 4, "the first run has " + run.size() + " cells instead of stopping at the reach limit");
            // A second run started: the first one is over.
            helper.assertFalse(withHops(village, 2).isEmpty(), "no second run yet");
            for (StreetCell cell : village.streets()) {
                int reach = Math.max(Math.abs(cell.pos().getX() - bell.getX()), Math.abs(cell.pos().getZ() - bell.getZ()));
                helper.assertTrue(reach <= StreetWork.REACH, "street cell " + relative(helper, cell.pos()).toShortString() + " is " + reach + " from the bell");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_house", timeoutTicks = 4000)
    public static void aRunNeverCrossesAHouseFootprint(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper, ROOT, 0);
        // A recorded house across all three rows of the street, from x = 9.
        BuildingRecord house = new BuildingRecord(BuildingRecord.VANILLA_HOME, helper.absolutePos(new BlockPos(9, 1, 20)), new Vec3i(5, 3, 9));
        village.addHouse(house);
        Footprint footprint = house.footprint();
        StreetWork job = new StreetWork();
        enrollPaver(helper, village, 1, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(ROOM_TO_GROW.equals(job.waitingFor()), "the paver is still growing: " + job.waitingFor());
            for (BlockPos cell : village.pathCells()) {
                helper.assertFalse(footprint.contains(cell.getX(), cell.getZ()), "path cell " + relative(helper, cell).toShortString() + " lies inside the house");
            }
            List<StreetCell> run = withHops(village, 1);
            helper.assertTrue(run.size() == 4, "the first run has " + run.size() + " cells instead of stopping at the house");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_side_house", timeoutTicks = 4000)
    public static void aRunStopsWhereASideCellWouldLandOnAHouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper, ROOT, 0);
        // Covers the south side row (z = 25) from x = 9, never the centre row.
        BuildingRecord house = new BuildingRecord(BuildingRecord.VANILLA_HOME, helper.absolutePos(new BlockPos(9, 1, 25)), new Vec3i(5, 3, 5));
        village.addHouse(house);
        Footprint footprint = house.footprint();
        enrollPaver(helper, village, 1, new StreetWork());
        helper.succeedWhen(() -> {
            List<StreetCell> run = withHops(village, 1);
            helper.assertTrue(run.size() == 4, "the first run has " + run.size() + " cells instead of stopping where its side meets the house");
            // A second run started: the first one is over.
            helper.assertFalse(withHops(village, 2).isEmpty(), "no second run yet");
            for (BlockPos cell : village.pathCells()) {
                helper.assertFalse(footprint.contains(cell.getX(), cell.getZ()), "path cell " + relative(helper, cell).toShortString() + " lies inside the house");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_side_step", timeoutTicks = 4000)
    public static void aRunStopsWhereASideCellsGroundIsThreeBlocksOff(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // Ground at y = 3 everywhere, except one column of the south side row dug down to y = 0: three blocks below
        // the street, one more than a side cell may be filled.
        terrain(helper, (x, z) -> x == 8 && z == 25 ? 0 : 3);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(8, y, 25, Blocks.AIR);
        }
        BlockPos root = ROOT.above(3);
        VillageData village = streetVillage(helper, root, 0);
        enrollPaver(helper, village, 4, new StreetWork());
        helper.succeedWhen(() -> {
            List<StreetCell> run = withHops(village, 1);
            helper.assertTrue(run.size() == 3, "the first run has " + run.size() + " cells instead of stopping before the pit");
            // A second run started: the first one is over.
            helper.assertFalse(withHops(village, 2).isEmpty(), "no second run yet");
            helper.assertTrue(helper.getBlockState(new BlockPos(8, 3, 25)).isAir(), "the pit beside the street was filled");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_width", timeoutTicks = 8000)
    public static void everyCentreCellHasBothSideCellsLaidAtItsOwnHeight(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // Ground at y = 1, the north side row one lower and the south side row one higher: one side is filled, the
        // other cut, to the centre's height.
        terrain(helper, (x, z) -> z == 23 ? 0 : z == 25 ? 2 : 1);
        BlockPos root = ROOT.above();
        VillageData village = streetVillage(helper, root, 0);
        enrollPaver(helper, village, 2, new StreetWork());
        helper.succeedWhen(() -> {
            List<StreetCell> run = withHops(village, 1);
            helper.assertTrue(run.size() == StreetWork.RUN_LENGTH, "the first run has " + run.size() + " cells");
            List<BlockPos> pathCells = village.pathCells();
            for (StreetCell cell : run) {
                for (BlockPos pos : List.of(cell.pos(), cell.pos().north(), cell.pos().south())) {
                    BlockPos rel = relative(helper, pos);
                    helper.assertTrue(pathCells.contains(pos), "cell " + rel.toShortString() + " is not recorded as a path cell");
                    helper.assertTrue(helper.getBlockState(rel.below()).is(Blocks.DIRT_PATH), "no dirt path under " + rel.toShortString());
                    helper.assertTrue(helper.getBlockState(rel).isAir(), "cell " + rel.toShortString() + " is not clear");
                }
            }
            for (StreetCell cell : village.streets()) {
                helper.assertTrue(relative(helper, cell.pos()).getZ() == ROOT.getZ(),
                        "a side cell " + relative(helper, cell.pos()).toShortString() + " is in the street graph");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_hops", timeoutTicks = 4000)
    public static void growthStopsAtSixHops(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper, ROOT, StreetWork.MAX_HOPS - 1);
        StreetWork job = new StreetWork();
        enrollPaver(helper, village, 1, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(ROOM_TO_GROW.equals(job.waitingFor()), "the paver is still growing: " + job.waitingFor());
            helper.assertTrue(village.deepestHops() == StreetWork.MAX_HOPS, "deepest street is " + village.deepestHops() + " hops");
            helper.assertTrue(withHops(village, StreetWork.MAX_HOPS).size() == StreetWork.RUN_LENGTH,
                    "the run at " + StreetWork.MAX_HOPS + " hops has " + withHops(village, StreetWork.MAX_HOPS).size() + " cells");
            helper.assertTrue(village.streets().size() == 1 + StreetWork.RUN_LENGTH, "the graph has " + village.streets().size() + " cells");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_second_run", timeoutTicks = 6000)
    public static void aSecondRunStartsFromTheEndTheFirstOneLeft(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper, ROOT, 0);
        enrollPaver(helper, village, 1, new StreetWork());
        helper.succeedWhen(() -> {
            List<StreetCell> first = withHops(village, 1);
            List<StreetCell> second = withHops(village, 2);
            helper.assertTrue(second.size() == StreetWork.RUN_LENGTH, "the second run has " + second.size() + " cells");
            BlockPos end = first.get(first.size() - 1).pos();
            BlockPos start = second.get(0).pos();
            helper.assertTrue(start.getY() == end.getY() && Math.abs(start.getX() - end.getX()) + Math.abs(start.getZ() - end.getZ()) == 1,
                    "the second run starts at " + relative(helper, start).toShortString() + ", not beside the first run's end "
                            + relative(helper, end).toShortString());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_no_door", timeoutTicks = 4000)
    public static void noDoorIsEverOpened(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = streetVillage(helper, ROOT, 0);
        // A starter house right beside the south side row, its door (in its z = 0 wall) facing the street.
        BlockPos origin = new BlockPos(6, 1, 26);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        for (BlueprintPlacement placement : blueprint.placements()) {
            helper.setBlock(origin.offset(placement.offset()), placement.state());
        }
        village.addHouse(new BuildingRecord(blueprint.id().toString(), helper.absolutePos(origin), blueprint.size()));
        BlockPos door = origin.offset(2, 1, 0);
        enrollPaver(helper, village, 1, new StreetWork());
        AtomicBoolean opened = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockState state = helper.getBlockState(door);
            if (state.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(state)) {
                opened.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(door).getBlock() instanceof DoorBlock, "the house has no door at " + door.toShortString());
            helper.assertFalse(withHops(village, 2).isEmpty(), "no second run yet");
            helper.assertFalse(opened.get(), "the paver opened the house's door");
            VillageTestSupport.remove(helper, village);
        });
    }
}
