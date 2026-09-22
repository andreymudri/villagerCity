package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseService;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PlotPlannerTests {
    private static final Vec3i HOUSE = new Vec3i(5, 5, 5);

    /**
     * Test-relative position. Not {@code helper.relativePos}: in 1.21.1 it rotates by
     * {@code rotation.getRotated(CLOCKWISE_180)}, which for an unrotated test mirrors x and z around
     * the structure origin (a plot at relative 18,1,18 was reported as -18,1,-18).
     */
    private static BlockPos relative(GameTestHelper helper, BlockPos absolute) {
        return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    private static VillageData village(GameTestHelper helper) {
        return new VillageData(UUID.randomUUID(), helper.absolutePos(new BlockPos(24, 1, 24)), 4);
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void findsFlatGroundNearCenter(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        BlockPos origin = PlotPlanner.find(helper.getLevel(), village, HOUSE).orElseThrow(() -> new AssertionError("no plot found"));
        BlockPos relative = relative(helper, origin);
        helper.assertTrue(relative.getY() == 1, "plot should sit on the grass floor, got y=" + relative.getY());
        helper.assertTrue(relative.getX() >= 2 && relative.getX() <= 41 && relative.getZ() >= 2 && relative.getZ() <= 41,
                "plot outside the search reach: " + relative);
        helper.assertFalse(PlotRules.overlapsAny(Footprint.of(origin, HOUSE), village.occupiedFootprints()), "plot overlaps the bell");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void findsGroundWellBelowARaisedBell(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A bell on a hill 11 blocks above the village's flat ground, the hill filling the middle of the area.
        for (int x = 14; x <= 34; x++) {
            for (int z = 14; z <= 34; z++) {
                for (int y = 1; y <= 11; y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT_PATH);
                }
            }
        }
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(new BlockPos(24, 12, 24)), 4);
        BlockPos origin = PlotPlanner.find(helper.getLevel(), village, HOUSE).orElseThrow(() -> new AssertionError("no plot found below the raised bell"));
        helper.assertTrue(relative(helper, origin).getY() == 1, "plot should sit on the low ground, got " + relative(helper, origin));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void ignoresGroundTooFarBelowTheBell(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 14; x <= 34; x++) {
            for (int z = 14; z <= 34; z++) {
                for (int y = 1; y <= PlotPlanner.MAX_VERTICAL + 1; y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT_PATH);
                }
            }
        }
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(new BlockPos(24, PlotPlanner.MAX_VERTICAL + 2, 24)), 4);
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), village, HOUSE).isEmpty(), "plot found more than " + PlotPlanner.MAX_VERTICAL + " blocks below the bell");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void neverPlansUnderAnOverhang(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A stone shelf just above the vertical window over the whole reachable area, grass below it: a cave floor.
        int shelfY = 1 + PlotPlanner.MAX_VERTICAL + 1;
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                helper.setBlock(x, shelfY, z, Blocks.STONE);
            }
        }
        Optional<BlockPos> origin = PlotPlanner.find(helper.getLevel(), village(helper), HOUSE);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                helper.setBlock(x, shelfY, z, Blocks.AIR);
            }
        }
        helper.assertTrue(origin.isEmpty(), "plot planned under the stone shelf at " + origin.map(pos -> relative(helper, pos)));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void avoidsWater(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < 24; z++) {
                helper.setBlock(x, 0, z, Blocks.WATER);
            }
        }
        BlockPos origin = PlotPlanner.find(helper.getLevel(), village(helper), HOUSE).orElseThrow(() -> new AssertionError("no plot found"));
        int relZ = relative(helper, origin).getZ();
        helper.assertTrue(relZ >= 25, "plot margin touches water: z=" + relZ);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void avoidsExistingHouses(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        village.addHouse(new BuildingRecord("minecraft:home", helper.absolutePos(new BlockPos(22, 1, 22)), HOUSE));
        village.setRadius(4);
        BlockPos origin = PlotPlanner.find(helper.getLevel(), village, HOUSE).orElseThrow(() -> new AssertionError("no plot found"));
        helper.assertFalse(PlotRules.overlapsAny(Footprint.of(origin, HOUSE), village.occupiedFootprints()), "plot overlaps the house");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void rejectsTreesAndBuildings(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if ((x + z) % 7 == 0) {
                    helper.setBlock(x, 1, z, Blocks.OAK_LOG);
                }
            }
        }
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), village(helper), HOUSE).isEmpty(), "plot found through logs");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aFailedSearchOfALargeVillageIsCheap(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if ((x + z) % 7 == 0) {
                    helper.setBlock(x, 1, z, Blocks.OAK_LOG);
                }
            }
        }
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(new BlockPos(24, 1, 24)), 160);
        long best = Long.MAX_VALUE;
        boolean found = false;
        for (int run = 0; run < 5; run++) {
            long started = System.nanoTime();
            // Every buildable spot is refused, so the whole reach is searched, as when nothing is buildable.
            found |= PlotPlanner.find(helper.getLevel(), village, HOUSE, origin -> false).isPresent();
            best = Math.min(best, System.nanoTime() - started);
        }
        helper.assertTrue(!found, "a refused spot was returned");
        helper.assertTrue(best < 12_000_000L, "a failed search took " + best / 1000 + " us");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, timeoutTicks = 400)
    public static void aVillageWithoutRoomWaitsBeforeSearchingAgain(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                helper.setBlock(x, 1, z, Blocks.OAK_LOG);
            }
        }
        VillageData village = village(helper);
        ServerLevel level = helper.getLevel();
        StorehouseService.ensureStorehouse(level, village);
        helper.assertTrue(village.storehousePos() == null, "storehouse placed on logs");
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                helper.setBlock(x, 1, z, Blocks.AIR);
            }
        }
        long failedAt = level.getGameTime();
        StorehouseService.ensureStorehouse(level, village);
        helper.assertTrue(village.storehousePos() == null, "searched again right after a failed search");
        helper.succeedWhen(() -> {
            StorehouseService.ensureStorehouse(level, village);
            helper.assertTrue(village.storehousePos() != null, "no storehouse after the wait");
            helper.assertTrue(level.getGameTime() - failedAt >= StorehouseService.SEARCH_RETRY_TICKS, "searched again after only " + (level.getGameTime() - failedAt) + " ticks");
        });
    }

    /**
     * A street running north from the bell, (24, 1, 23) to its end at (24, 1, 16), with its side cells. The only natural
     * ground is the patch x 18 to 30, z 8 to 15 straight ahead of the end, cobblestone everywhere else, so every pad that
     * touches the street lies across the slice the next run would lay from the end, (23 to 25, 15). No pad is found:
     * a house there would cap the street for good.
     */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void noPadCapsTheSliceStraightAheadOfAStreetEnd(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if (x < 18 || x > 30 || z < 8 || z > 15) {
                    helper.setBlock(x, 0, z, Blocks.COBBLESTONE);
                }
            }
        }
        VillageData village = village(helper);
        for (int z = 23; z >= 16; z--) {
            village.addStreetCell(helper.absolutePos(new BlockPos(24, 1, z)), 1);
            village.addPathCell(helper.absolutePos(new BlockPos(23, 1, z)));
            village.addPathCell(helper.absolutePos(new BlockPos(25, 1, z)));
        }
        Optional<PlotPlanner.Site> site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true);
        helper.assertTrue(site.isEmpty(), "a pad at " + site.map(found -> relative(helper, found.origin()).toShortString()).orElse("")
                + " lies across the slice ahead of the street end");
        helper.succeed();
    }

    /** As {@link #noPadCapsTheSliceStraightAheadOfAStreetEnd}, but with natural ground beside the street too: a pad is found. */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aPadBesideTheStreetIsStillFound(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        for (int z = 23; z >= 16; z--) {
            village.addStreetCell(helper.absolutePos(new BlockPos(24, 1, z)), 1);
            village.addPathCell(helper.absolutePos(new BlockPos(23, 1, z)));
            village.addPathCell(helper.absolutePos(new BlockPos(25, 1, z)));
        }
        Optional<PlotPlanner.Site> site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true);
        helper.assertTrue(site.isPresent(), "no pad beside the street");
        Footprint area = Footprint.of(site.get().origin(), HOUSE).inflate(PlotRules.MARGIN);
        helper.assertFalse(area.intersects(new Footprint(helper.absolutePos(new BlockPos(23, 1, 15)).getX(),
                helper.absolutePos(new BlockPos(23, 1, 15)).getZ(), helper.absolutePos(new BlockPos(25, 1, 15)).getX(),
                helper.absolutePos(new BlockPos(25, 1, 15)).getZ())), "the pad at " + relative(helper, site.get().origin()).toShortString()
                + " lies across the slice ahead of the street end");
        helper.succeed();
    }

    /**
     * A street a block above flat ground, north from the bell, (24, 2, 23) to (24, 2, 16), with its side cells. The only
     * natural ground is the patch x 26 to 32, z 14 to 24 east of it, cobblestone everywhere else, so the pads that can
     * touch the street are the 5 by 5s at x 27, z 15 to 19, every column of which the paver has to fill one block up to
     * the street's height. There are five so that the one-in-eight skip ({@link PlotRules#skipped}), which varies with
     * where the test runs, leaves some. With {@code torch}, a torch stands at (29, 1, 20), where every one of their fills
     * would go.
     */
    private static Optional<PlotPlanner.Site> raisedStreetPad(GameTestHelper helper, boolean torch) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if (x < 26 || x > 32 || z < 14 || z > 24) {
                    helper.setBlock(x, 0, z, Blocks.COBBLESTONE);
                }
            }
        }
        if (torch) {
            helper.setBlock(29, 1, 20, Blocks.TORCH);
        }
        VillageData village = village(helper);
        for (int z = 23; z >= 16; z--) {
            village.addStreetCell(helper.absolutePos(new BlockPos(24, 2, z)), 1);
            village.addPathCell(helper.absolutePos(new BlockPos(23, 2, z)));
            village.addPathCell(helper.absolutePos(new BlockPos(25, 2, z)));
        }
        return PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true);
    }

    /** The pad of {@link #raisedStreetPad} is planned when nothing stands where its fill goes. */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aPadTheStreetRaisesIsPlannedWhenItsGroundIsClear(GameTestHelper helper) {
        Optional<PlotPlanner.Site> site = raisedStreetPad(helper, false);
        helper.assertTrue(site.map(found -> relative(helper, found.origin())).filter(origin -> origin.getX() == 27 && origin.getY() == 2
                && origin.getZ() >= 15 && origin.getZ() <= 19).isPresent(),
                "planned " + site.map(found -> relative(helper, found.origin()).toShortString()).orElse("nothing"));
        helper.succeed();
    }

    /**
     * A torch where the pad's fill would go is not ground the paver may clear, so preparing the pad would fail every time:
     * the pad is not planned. A torch lit before any plot was there is the case this guards.
     */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aPadWhoseFillATorchBlocksIsNotPlanned(GameTestHelper helper) {
        Optional<PlotPlanner.Site> site = raisedStreetPad(helper, true);
        helper.assertTrue(site.isEmpty(), "planned a pad at " + site.map(found -> relative(helper, found.origin()).toShortString()).orElse("")
                + " over a torch its preparation cannot clear");
        helper.succeed();
    }
}
