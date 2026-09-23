package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseService;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
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
    /** No lot is left empty: these tests are about other rules, and {@link PlotRules#skippedLot} varies with where they run. */
    private static final PlotRules.LotSkip NO_LOT_SKIPPED = (lotX, lotZ) -> false;
    /** Most passes over its columns a failed search may cost ({@link #aFailedSearchOfALargeVillageIsCheap}). */
    private static final long MAX_FAILED_SEARCH_PASSES = 8;
    /** A radius whose search reach, 22, keeps every pad and its margin inside the 48-wide test area around (24, 24). */
    private static final int INSIDE_RADIUS = 6;
    /**
     * Most block states a failed search of an {@link #INSIDE_RADIUS} village may read. It read 11871 when this was
     * written; the bound leaves room for about a third more.
     */
    private static final long MAX_FAILED_SEARCH_READS = 16_000;

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

    /**
     * A failed search is cheap, checked twice. First its work is counted: the block states {@link PlotPlanner#sample}
     * reads in a failed search of a village whose reach and margin stay inside the test area, so every column read is
     * one this test laid and the count is the same wherever the area stands; it is bounded by
     * {@link #MAX_FAILED_SEARCH_READS}, which reading a column more than once, or reading more of each, exceeds. Then
     * a failed search of a large village, whose reach runs past the area, is timed against one pass sampling every
     * column within its reach, measured in the same runs in the server thread's CPU time, which time spent waiting
     * for a busy machine does not add to; that catches work outside {@link PlotPlanner#sample}.
     */
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
        BlockPos center = helper.absolutePos(new BlockPos(24, 1, 24));
        VillageData inside = new VillageData(UUID.randomUUID(), center, INSIDE_RADIUS);
        long before = PlotPlanner.blockReads();
        // Every buildable spot is refused, so the whole reach is searched, as when nothing is buildable.
        boolean foundInside = PlotPlanner.find(helper.getLevel(), inside, HOUSE, origin -> false).isPresent();
        long reads = PlotPlanner.blockReads() - before;
        helper.assertTrue(!foundInside, "a refused spot was returned inside the area");
        helper.assertTrue(reads <= MAX_FAILED_SEARCH_READS, "a failed search read " + reads + " block states, more than " + MAX_FAILED_SEARCH_READS);

        VillageData village = new VillageData(UUID.randomUUID(), center, 160);
        int reach = PlotPlanner.searchReach(village);
        int window = PlotPlanner.verticalReach(village.radius());
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        LongSupplier clock = threads.isCurrentThreadCpuTimeSupported() ? threads::getCurrentThreadCpuTime : System::nanoTime;
        long search = Long.MAX_VALUE;
        long pass = Long.MAX_VALUE;
        long ground = 0;
        boolean found = false;
        for (int run = 0; run < 5; run++) {
            long started = clock.getAsLong();
            found |= PlotPlanner.find(helper.getLevel(), village, HOUSE, origin -> false).isPresent();
            search = Math.min(search, clock.getAsLong() - started);
            started = clock.getAsLong();
            for (int x = center.getX() - reach; x <= center.getX() + reach; x++) {
                for (int z = center.getZ() - reach; z <= center.getZ() + reach; z++) {
                    ground += PlotPlanner.sample(helper.getLevel(), x, z, center.getY(), window).groundY();
                }
            }
            pass = Math.min(pass, clock.getAsLong() - started);
        }
        helper.assertTrue(!found, "a refused spot was returned");
        helper.assertTrue(search < MAX_FAILED_SEARCH_PASSES * pass, "a failed search took " + search / 1000 + " us, "
                + String.format("%.1f", (double) search / pass) + " times one pass over its columns (" + pass / 1000 + " us, ground sum " + ground + ")");
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
        Optional<PlotPlanner.Site> site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true, NO_LOT_SKIPPED);
        helper.assertTrue(site.isEmpty(), "a pad at " + site.map(found -> relative(helper, found.origin()).toShortString()).orElse("")
                + " lies across the slice ahead of the street end");
        helper.succeed();
    }

    /**
     * A village at the bell (24, 1, 24) whose street is the single cell {@code cell}, on natural ground only in the patch
     * x {@code fromX}..{@code toX}, z {@code fromZ}..{@code toZ}, cobblestone everywhere else; the pad the search finds.
     */
    private static Optional<PlotPlanner.Site> oneCellStreetPad(GameTestHelper helper, BlockPos cell, int fromX, int toX, int fromZ, int toZ) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if (x < fromX || x > toX || z < fromZ || z > toZ) {
                    helper.setBlock(x, 0, z, Blocks.COBBLESTONE);
                }
            }
        }
        VillageData village = village(helper);
        village.addStreetCell(helper.absolutePos(cell), 1);
        return PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true, NO_LOT_SKIPPED);
    }

    /**
     * A street of a single cell grew from no cell, so the slice kept clear ahead of it lies on from the bell through it,
     * along the axis on which it lies farther from the bell, or along both when it lies as far along each. In every
     * layout the only natural ground is a patch where each pad touching the cell covers that slice: (24, 16), eight
     * blocks north of the bell, keeps (23 to 25, 15) clear; (32, 22), eight east and two north, keeps (33, 21 to 23)
     * clear; and (32, 16), eight each way, keeps both (31 to 33, 15) and (33, 15 to 17) clear, the patch north of it
     * covering only the first and the patch east of it only the second.
     */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void noPadCapsAOneCellStreet(GameTestHelper helper) {
        record Layout(String name, BlockPos cell, int fromX, int toX, int fromZ, int toZ) {
        }
        List<Layout> layouts = List.of(
                new Layout("north", new BlockPos(24, 1, 16), 18, 30, 8, 15),
                new Layout("east", new BlockPos(32, 1, 22), 33, 40, 17, 27),
                new Layout("level, north patch", new BlockPos(32, 1, 16), 26, 38, 8, 15),
                new Layout("level, east patch", new BlockPos(32, 1, 16), 33, 40, 16, 22));
        for (Layout layout : layouts) {
            Optional<PlotPlanner.Site> site = oneCellStreetPad(helper, layout.cell(), layout.fromX(), layout.toX(), layout.fromZ(), layout.toZ());
            helper.assertTrue(site.isEmpty(), "a pad at " + site.map(found -> relative(helper, found.origin()).toShortString()).orElse("")
                    + " caps the one-cell street at " + layout.cell().toShortString() + " (" + layout.name() + ")");
        }
        helper.succeed();
    }

    /**
     * As the north layout of {@link #noPadCapsAOneCellStreet}, but with the only natural ground east of the cell, x 25 to
     * 31, z 16 to 22: the one pad there, (26, 1, 17), keeps off the slice and is found.
     */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aPadBesideAOneCellStreetIsStillFound(GameTestHelper helper) {
        Optional<BlockPos> origin = oneCellStreetPad(helper, new BlockPos(24, 1, 16), 25, 31, 16, 22).map(found -> relative(helper, found.origin()));
        helper.assertTrue(origin.filter(new BlockPos(26, 1, 17)::equals).isPresent(), "planned " + origin + " instead of 26, 1, 17");
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
        Optional<PlotPlanner.Site> site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true, NO_LOT_SKIPPED);
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
     * the street's height. No lot is left empty ({@link #NO_LOT_SKIPPED}). With {@code torch}, a torch stands at
     * (29, 1, 20), where every one of their fills would go.
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
        return PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true, NO_LOT_SKIPPED);
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
