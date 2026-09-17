package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.PathWork;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PathTests {
    private static final BlockPos HOUSE_ORIGIN = new BlockPos(8, 1, 8);

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
}
