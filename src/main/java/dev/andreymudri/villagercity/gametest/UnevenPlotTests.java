package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.TaskScheduler;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.Earthwork;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Plots on uneven ground: earthwork is planned only with a paver, and builders wait for the plot to be prepared. */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class UnevenPlotTests {
    private static final Vec3i HOUSE = new Vec3i(5, 5, 5);
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    /** Where the tests put a plot the paver never manages to prepare. */
    private static final BlockPos STUCK = new BlockPos(8, 1, 8);
    /** Terrace heights by x modulo 8: every 7 columns in a row hold both a 0 and a 3, so no spot varies by less than 3. */
    private static final int[] TERRACE = {0, 1, 2, 3, 3, 2, 1, 0};

    private static BlockPos relative(GameTestHelper helper, BlockPos absolute) {
        return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    private static VillageData village(GameTestHelper helper) {
        return new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 4);
    }

    /** Raises dirt terraces over the whole area; column x's first free y is {@code 1 + TERRACE[x % 8]}. */
    private static void terraces(GameTestHelper helper) {
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                for (int y = 1; y <= TERRACE[x % TERRACE.length]; y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT);
                }
            }
        }
    }

    /**
     * Covers the area with logs (never buildable) except the plot area whose footprint starts at relative (10, 10), the
     * only spot the spiral can take; its columns' first free y is {@code 1 + heightAt(x)}, filled with dirt.
     */
    private static void onlySpotAt10(GameTestHelper helper, IntUnaryOperator heightAt) {
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                boolean inArea = x >= 9 && x <= 15 && z >= 9 && z <= 15;
                if (!inArea) {
                    helper.setBlock(x, 1, z, Blocks.OAK_LOG);
                    continue;
                }
                for (int y = 1; y <= heightAt.applyAsInt(x); y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT);
                }
            }
        }
    }

    /**
     * Covers the area with logs (never buildable) except a flat dirt plateau at x 30..44, z 30..44 whose first free y is
     * {@code 1 + height}, so the only buildable spot lies that far above the bell at relative y 1.
     */
    private static void plateauAbove(GameTestHelper helper, int height) {
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if (x < 30 || x > 44 || z < 30 || z > 44) {
                    helper.setBlock(x, 1, z, Blocks.OAK_LOG);
                    continue;
                }
                for (int y = 1; y <= height; y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT);
                }
            }
        }
    }

    /** Whether a test-relative origin lies on the plateau {@link #plateauAbove} raises, margin included. */
    private static boolean onTheShelf(BlockPos origin) {
        return origin.getX() >= 31 && origin.getX() <= 39 && origin.getZ() >= 31 && origin.getZ() <= 39;
    }

    /** Chebyshev distance between two plot corners, which is also the distance between their centres for one size. */
    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_anchored_search")
    public static void theSearchGrowsFromWhatTheVillageBuilt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 4);
        // Flat ground everywhere: every spot within reach is equally buildable, so only the order decides.
        BlockPos bellFirst = relative(helper, PlotPlanner.find(helper.getLevel(), village, HOUSE).orElseThrow(() -> new AssertionError("no plot found")));
        helper.assertTrue(bellFirst.equals(new BlockPos(18, 1, 18)), "a village with nothing built did not start at the bell: " + bellFirst);

        BlockPos house = new BlockPos(38, 1, 38);
        village.addHouse(new BuildingRecord("minecraft:home", helper.absolutePos(house), HOUSE));
        village.setRadius(4);
        BlockPos origin = relative(helper, PlotPlanner.findSite(helper.getLevel(), village, HOUSE, false, pos -> true).map(PlotPlanner.Site::origin).orElseThrow(() -> new AssertionError("no plot found beside the house")));
        int toHouse = chebyshev(origin, house);
        // As near the house as HOUSE_GAP lets a pad stand: the house's width plus the gap. The first spot a village with
        // nothing built takes lies twice as far from it.
        int beside = HOUSE.getX() + PlotRules.HOUSE_GAP;
        helper.assertTrue(toHouse == beside, "planned " + toHouse + " blocks from the house the village should grow beside, not " + beside + ", at " + origin);
        helper.assertTrue(chebyshev(bellFirst, house) > beside, "the bell's first spot is already beside the house: " + bellFirst);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_anchored_storehouse")
    public static void theSearchGrowsFromTheStorehouseBeforeAnyHouseIsBuilt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 4);
        // Flat ground everywhere and no house yet: the storehouse is the only thing the village has built.
        BlockPos storehouse = new BlockPos(40, 1, 40);
        village.setStorehousePos(helper.absolutePos(storehouse));
        BlockPos origin = relative(helper, PlotPlanner.find(helper.getLevel(), village, HOUSE)
                .orElseThrow(() -> new AssertionError("no plot found beside the storehouse")));
        int toStorehouse = chebyshev(origin, storehouse);
        int toBell = chebyshev(origin, BELL);
        helper.assertTrue(toStorehouse <= 8, "planned " + toStorehouse + " blocks from the storehouse the village should grow beside, at " + origin);
        helper.assertTrue(toBell > toStorehouse, "planned nearer the bell (" + toBell + ") than the storehouse (" + toStorehouse + "), at " + origin);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_vertical_reach", timeoutTicks = 400)
    public static void aShelfWellAboveTheBellIsOnlyReachedByAGrownVillage(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // The only buildable ground is a shelf 32 blocks above the bell.
        int height = 32;
        plateauAbove(helper, height);
        VillageData small = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 4);
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), small, HOUSE).isEmpty(),
                "a young village whose reach is " + PlotPlanner.verticalReach(4) + " blocks planned a plot " + height + " blocks up the mountain");
        VillageData grown = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), height);
        BlockPos origin = relative(helper, PlotPlanner.find(helper.getLevel(), grown, HOUSE)
                .orElseThrow(() -> new AssertionError("a village of radius " + height + " found nowhere to build at all")));
        helper.assertTrue(onTheShelf(origin) && origin.getY() == height + 1,
                "a village of radius " + height + " planned at " + origin + " instead of the shelf " + height + " blocks above its bell");

        // Nothing is built under an overhang, however far the village reaches: a roof above the reach hides the shelf.
        int roofY = 1 + PlotPlanner.verticalReach(height) + 5;
        for (int x = 30; x <= 44; x++) {
            for (int z = 30; z <= 44; z++) {
                helper.setBlock(x, roofY, z, Blocks.STONE);
            }
        }
        Optional<BlockPos> roofed = PlotPlanner.find(helper.getLevel(), grown, HOUSE).map(pos -> relative(helper, pos));
        for (int x = 30; x <= 44; x++) {
            for (int z = 30; z <= 44; z++) {
                helper.setBlock(x, roofY, z, Blocks.AIR);
            }
        }
        helper.assertFalse(roofed.filter(UnevenPlotTests::onTheShelf).isPresent(), "planned under the roof at " + roofed);

        // The reach is the village's radius, between the two bounds.
        helper.assertTrue(PlotPlanner.verticalReach(0) == PlotPlanner.MIN_VERTICAL, "reach of a new village " + PlotPlanner.verticalReach(0));
        helper.assertTrue(PlotPlanner.verticalReach(height) == height, "reach of a radius-" + height + " village " + PlotPlanner.verticalReach(height));
        helper.assertTrue(PlotPlanner.verticalReach(VillageData.DEFAULT_RADIUS * 4) == PlotPlanner.MAX_VERTICAL,
                "reach of a huge village " + PlotPlanner.verticalReach(VillageData.DEFAULT_RADIUS * 4));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_slope_paver")
    public static void aSlopeIsOnlyPlannedWithAPaver(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        terraces(helper);
        VillageData village = village(helper);
        helper.assertTrue(PlotPlanner.findSite(helper.getLevel(), village, HOUSE, false, pos -> true).isEmpty(), "a slope was planned without earthwork");
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), village, HOUSE).isEmpty(), "find planned a slope");
        PlotPlanner.Site site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true)
                .orElseThrow(() -> new AssertionError("no site on the slope with earthwork allowed"));
        helper.assertTrue(site.earthwork() > 0, "earthwork " + site.earthwork());
        BlockPos origin = relative(helper, site.origin());
        int[] groundYs = new int[(HOUSE.getX() + 2) * (HOUSE.getZ() + 2)];
        int i = 0;
        for (int x = origin.getX() - PlotRules.MARGIN; x <= origin.getX() + HOUSE.getX(); x++) {
            for (int z = origin.getZ() - PlotRules.MARGIN; z <= origin.getZ() + HOUSE.getZ(); z++) {
                groundYs[i++] = 1 + TERRACE[x % TERRACE.length];
            }
        }
        Earthwork.Level best = Earthwork.best(groundYs);
        helper.assertTrue(origin.getY() == best.floorY(), "origin " + origin + " is not at the best floor " + best.floorY());
        helper.assertTrue(site.earthwork() == best.volume(), "earthwork " + site.earthwork() + ", expected " + best.volume());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_best_floor")
    public static void theFloorIsTheLevelWithTheLeastEarthwork(GameTestHelper helper) {
        // Pure math: the least volume wins, and a tie goes to the higher floor.
        helper.assertTrue(Earthwork.best(new int[] {0, 0, 0, 4, 4}).equals(new Earthwork.Level(0, 8)), "best " + Earthwork.best(new int[] {0, 0, 0, 4, 4}));
        helper.assertTrue(Earthwork.best(new int[] {1, 3}).equals(new Earthwork.Level(3, 2)), "tie " + Earthwork.best(new int[] {1, 3}));
        helper.assertTrue(Earthwork.best(new int[] {2, 5, 5, 5, 9}).equals(new Earthwork.Level(5, 7)), "middle " + Earthwork.best(new int[] {2, 5, 5, 5, 9}));
        helper.assertTrue(Earthwork.volume(new int[] {2, 5, 9}, 4) == 2 + 1 + 5, "volume " + Earthwork.volume(new int[] {2, 5, 9}, 4));

        // In the world: one row of 7 columns at y1, five rows at y3, one row at y5. Floor 3 costs 28, floors 2 and 4 cost 63.
        GameTestSupport.prepareArea(helper);
        onlySpotAt10(helper, x -> x == 9 ? 0 : x == 15 ? 4 : 2);
        PlotPlanner.Site site = PlotPlanner.findSite(helper.getLevel(), village(helper), HOUSE, true, pos -> true)
                .orElseThrow(() -> new AssertionError("no site on the hand-built columns"));
        BlockPos origin = relative(helper, site.origin());
        helper.assertTrue(origin.equals(new BlockPos(10, 3, 10)), "origin " + origin);
        helper.assertTrue(site.earthwork() == 28, "earthwork " + site.earthwork());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_too_much")
    public static void tooMuchEarthworkIsRefused(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // Four rows at y1 and three rows at y7: a variance of exactly 6, whose best floor (y1) still costs 21 * 6 = 126.
        onlySpotAt10(helper, x -> x <= 12 ? 0 : 6);
        int[] groundYs = new int[49];
        for (int i = 0; i < groundYs.length; i++) {
            groundYs[i] = i < 28 ? 1 : 7;
        }
        helper.assertTrue(Earthwork.best(groundYs).volume() > Earthwork.MAX_VOLUME, "the test spot needs more than " + Earthwork.MAX_VOLUME + " blocks of earthwork");
        Optional<PlotPlanner.Site> site = PlotPlanner.findSite(helper.getLevel(), village(helper), HOUSE, true, pos -> true);
        helper.assertTrue(site.isEmpty(), "planned a spot needing too much earthwork: " + site.map(s -> relative(helper, s.origin()) + " earthwork " + s.earthwork()));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_max_variance")
    public static void theMostVariedGroundIsAcceptedWhenItsEarthworkIsSmall(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // One row of 7 columns at y1 and six rows at y7: a variance of exactly 6, levelled at y7 for 7 * 6 = 42 blocks.
        onlySpotAt10(helper, x -> x == 9 ? 0 : 6);
        PlotPlanner.Site site = PlotPlanner.findSite(helper.getLevel(), village(helper), HOUSE, true, pos -> true)
                .orElseThrow(() -> new AssertionError("a spot varying by " + Earthwork.MAX_COLUMN_STEP + " with little earthwork was refused"));
        BlockPos origin = relative(helper, site.origin());
        helper.assertTrue(origin.equals(new BlockPos(10, 7, 10)), "origin " + origin);
        helper.assertTrue(site.earthwork() == 42, "earthwork " + site.earthwork());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_path_columns")
    public static void pathColumnsAreNeverBuiltOn(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        BlockPos first = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, false, pos -> true).orElseThrow().origin();
        // A path through the first spot's margin column.
        BlockPos path = new BlockPos(first.getX() - PlotRules.MARGIN, first.getY(), first.getZ());
        village.addPathCell(path);
        for (boolean earthwork : new boolean[] {false, true}) {
            BlockPos origin = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, earthwork, pos -> true)
                    .orElseThrow(() -> new AssertionError("no site beside the path")).origin();
            Footprint area = Footprint.of(origin, HOUSE).inflate(PlotRules.MARGIN);
            helper.assertFalse(area.minX() <= path.getX() && path.getX() <= area.maxX() && area.minZ() <= path.getZ() && path.getZ() <= area.maxZ(),
                    "planned over the path at " + relative(helper, origin) + " (earthwork " + earthwork + ")");
        }
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), village, HOUSE).map(origin -> !origin.equals(first)).orElse(false), "find planned over the path");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_builder_waits", timeoutTicks = 1600)
    public static void theBuilderWaitsForAnUnpreparedPlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Map<Item, Long> stocked = storehouse.counts();
        Villager villager = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        BlockPos origin = new BlockPos(8, 1, 8);
        UUID plotId = UUID.randomUUID();
        village.addPlot(new Plot(plotId, blueprint.id().toString(), helper.absolutePos(origin), blueprint.size(), villager.getUUID(), 0L, 0, false));
        BuilderJob job = new BuilderJob();
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.runAfterDelay(3 * TaskScheduler.IDLE_RETRY_TICKS + 20, () -> {
            String waiting = job.waitingFor();
            int carried = Inventories.count(villager.getInventory(), stack -> stack.getItem() instanceof BlockItem);
            boolean untouched = storehouse.counts().equals(stocked);
            boolean nothingPlaced = helper.getLevel().getBlockState(helper.absolutePos(origin)).isAir();
            if (!"the paver to prepare the plot".equals(waiting) || carried > 0 || !untouched || !nothingPlaced) {
                VillageTestSupport.remove(helper, village);
            }
            helper.assertTrue("the paver to prepare the plot".equals(waiting), "waiting for " + waiting);
            helper.assertTrue(carried == 0 && untouched, "withdrew materials for an unprepared plot, carrying " + carried);
            helper.assertTrue(nothingPlaced, "built on an unprepared plot");
            village.markPlotPrepared(plotId);
            helper.succeedWhen(() -> {
                helper.assertFalse(storehouse.counts().equals(stocked), "no materials withdrawn after preparation; waiting for " + job.waitingFor());
                VillageTestSupport.remove(helper, village);
            });
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_builder_claims", timeoutTicks = 1200)
    public static void aBuilderWithAPaverClaimsASlopedPlotUnprepared(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        terraces(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        // A paver on the roster, with no villager behind it, so nothing prepares the plot during the test.
        village.setCitizen(UUID.randomUUID(), JobType.PAVER);
        // A builder in a village with a paver only builds beside a street: one street cell on the lowest terrace (x = 24).
        village.addStreetCell(helper.absolutePos(new BlockPos(24, 1, 30)), 1);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Map<Item, Long> stocked = storehouse.counts();
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 2, 20);
        BuilderJob job = new BuilderJob();
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().size() == 1, "no plot claimed; waiting for " + job.waitingFor());
            Plot plot = village.plots().get(0);
            boolean onStreet = !PlotPlanner.touchingStreets(village, plot.footprint().inflate(PlotRules.MARGIN)).isEmpty();
            if (plot.prepared() || !onStreet || !storehouse.counts().equals(stocked)) {
                VillageTestSupport.remove(helper, village);
            }
            helper.assertFalse(plot.prepared(), "a sloped plot at " + relative(helper, plot.origin()) + " was claimed prepared");
            helper.assertTrue(onStreet, "the plot at " + relative(helper, plot.origin()) + " does not open onto the street");
            helper.assertTrue(storehouse.counts().equals(stocked), "withdrew materials for an unprepared plot");
            helper.assertTrue("the paver to prepare the plot".equals(job.waitingFor()), "waiting for " + job.waitingFor());
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Gives the village a stocked storehouse and a builder holding an unprepared plot at {@link #STUCK}. */
    private static Villager builderOnAnUnpreparedPlot(GameTestHelper helper, VillageData village, UUID plotId, BuilderJob job) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Villager villager = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        village.addPlot(new Plot(plotId, blueprint.id().toString(), helper.absolutePos(STUCK), blueprint.size(), villager.getUUID(), 0L, 0, false));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        return villager;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_builder_gives_up", timeoutTicks = 18000)
    public static void aPlotNeverPreparedIsRetriedThenLeftBehind(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        UUID plotId = UUID.randomUUID();
        BuilderJob job = new BuilderJob();
        builderOnAnUnpreparedPlot(helper, village, plotId, job);
        boolean[] released = {false};
        helper.succeedWhen(() -> {
            helper.assertFalse(village.isFailedPlot(helper.absolutePos(STUCK)), "a plot the paver never prepared was blacklisted");
            Optional<Plot> stuck = village.plots().stream().filter(plot -> plot.id().equals(plotId)).findFirst();
            if (stuck.isPresent()) {
                helper.assertTrue(stuck.get().abandons() == 0, "a preparation timeout counted as an abandon: abandons " + stuck.get().abandons());
                released[0] |= stuck.get().released();
                throw new GameTestAssertException("still holding the unprepared plot; waiting for " + job.waitingFor());
            }
            helper.assertTrue(released[0], "the plot was dropped without ever being released for a retry");
            helper.assertTrue(village.plots().size() == 1, "no other plot claimed: " + village.plots());
            helper.assertTrue(village.plots().get(0).prepared(), "the new plot at " + relative(helper, village.plots().get(0).origin()) + " is unprepared");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_prep_timeout_retry", timeoutTicks = 12000)
    public static void aPreparationTimeoutOnlyDelaysThePlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        UUID plotId = UUID.randomUUID();
        BuilderJob job = new BuilderJob();
        // The paver is held up for want of fill materials, which is recoverable: the plot may wait, but must not be lost.
        Villager villager = builderOnAnUnpreparedPlot(helper, village, plotId, job);
        boolean[] prepared = {false};
        helper.succeedWhen(() -> {
            helper.assertFalse(village.isFailedPlot(helper.absolutePos(STUCK)), "a plot waiting for fill materials was blacklisted");
            if (!prepared[0]) {
                Plot held = village.plots().stream().filter(p -> p.id().equals(plotId)).findFirst().orElse(null);
                if (held == null) {
                    throw new GameTestAssertException("the plot was dropped instead of retried");
                }
                Plot plot = held;
                helper.assertTrue(plot.abandons() == 0, "a preparation timeout counted as an abandon: abandons " + plot.abandons());
                if (!plot.released()) {
                    throw new GameTestAssertException("the plot has not been released for a retry yet; waiting for " + job.waitingFor());
                }
                // The materials arrive and the paver finishes the plot it had to leave.
                village.assignPlot(plotId, villager.getUUID());
                village.markPlotPrepared(plotId);
                prepared[0] = true;
            }
            helper.assertTrue(village.houseCount() == 1, "the prepared plot was not built; waiting for " + job.waitingFor());
            helper.assertFalse(village.isFailedPlot(helper.absolutePos(STUCK)), "the built spot was blacklisted");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_prep_night", timeoutTicks = 14000)
    public static void aNightDoesNotSpendThePreparationWait(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        UUID plotId = UUID.randomUUID();
        BuilderJob job = new BuilderJob();
        builderOnAnUnpreparedPlot(helper, village, plotId, job);
        // Night falls 1000 ticks into the wait (the villagers' REST window is day time 12000 to 24000), and neither the
        // builder nor the paver runs through it, so those 12000 ticks must not count against the wait.
        helper.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, helper.getLevel().getServer());
        helper.getLevel().setDayTime(11000);
        helper.runAfterDelay(13400, () -> {
            long dayTime = helper.getLevel().getDayTime();
            Optional<Plot> plot = village.plots().stream().filter(p -> p.id().equals(plotId)).findFirst();
            boolean failed = village.isFailedPlot(helper.absolutePos(STUCK));
            String waiting = job.waitingFor();
            helper.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, helper.getLevel().getServer());
            helper.getLevel().setDayTime(GameTestSupport.DAY_TIME);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(dayTime > 24000, "the night never passed: day time " + dayTime);
            helper.assertFalse(failed, "the plot was blacklisted over a night");
            helper.assertTrue(plot.isPresent(), "the plot was dropped over a night");
            helper.assertFalse(plot.get().released(), "the plot was given up over a night, in which nobody worked");
            helper.assertTrue(plot.get().abandons() == 0, "abandons " + plot.get().abandons());
            helper.assertTrue("the paver to prepare the plot".equals(waiting), "waiting for " + waiting);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_builder_no_paver", timeoutTicks = 1400)
    public static void aBuilderWithoutAPaverClaimsNoSlopedPlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        terraces(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 2, 20);
        BuilderJob job = new BuilderJob();
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        long started = helper.getLevel().getGameTime();
        // Long enough for several plot searches, which retry every PLOT_SEARCH_RETRY_TICKS after a failed one.
        helper.runAfterDelay(5 * BuilderJob.PLOT_SEARCH_RETRY_TICKS + 100, () -> {
            String plots = village.plots().stream().map(plot -> relative(helper, plot.origin()).toString()).toList() + " after " + (helper.getLevel().getGameTime() - started) + " ticks";
            boolean none = village.plots().isEmpty();
            String waiting = job.waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(village.jobCount(JobType.PAVER) == 0, "the village has a paver");
            helper.assertTrue(none, "claimed a sloped plot with no paver to prepare it: " + plots);
            helper.assertTrue("a buildable plot near the bell".equals(waiting), "waiting for " + waiting);
            helper.succeed();
        });
    }

    /**
     * Covers the area with logs (never buildable) except one pad: footprint x {@code minX}..{@code minX + 4}, z 10..14,
     * plus its margin. Column (x, z) of it is dirt up to y {@code height}, so its first free y is {@code 1 + height}.
     */
    private static void onlySpot(GameTestHelper helper, int minX, IntBinaryOperator height) {
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                boolean inArea = x >= minX - 1 && x <= minX + HOUSE.getX() && z >= 9 && z <= 15;
                for (int y = 1; y <= 10; y++) {
                    boolean dirt = inArea && y <= height.applyAsInt(x, z);
                    helper.setBlock(x, y, z, !inArea && y == 1 ? Blocks.OAK_LOG : dirt ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    /**
     * An x for {@link #onlySpot} whose pad, with its floor at {@code floorY}, is not one of those the street search
     * skips. The skip hashes the absolute origin, which depends on where the test runs, so a fixed x would sometimes
     * test the skip instead of the rule it is about.
     */
    private static int unskippedX(GameTestHelper helper, int floorY) {
        for (int x = 10; x <= 24; x += PlotPlanner.STEP) {
            if (!PlotRules.skipped(helper.absolutePos(new BlockPos(x, floorY, 10)))) {
                return x;
            }
        }
        throw new GameTestAssertException("every candidate pad is skipped");
    }

    /** A village at {@link #BELL} with a street cell on the far z side of the {@link #onlySpot} pad at {@code minX}. */
    private static VillageData streetBesideSpot(GameTestHelper helper, int minX, int streetY) {
        VillageData village = village(helper);
        village.addStreetCell(helper.absolutePos(new BlockPos(minX + 2, streetY, 16)), 1);
        return village;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_street_floor")
    public static void aPadBesideTheShallowestStreetIsLevelledToItsHeight(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        // A street 3 hops out right beside the bell, on the ground, and one hop out farther away, a block above it.
        for (int x = 18; x <= 30; x++) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, 1, 20)), 3);
        }
        for (int x = 14; x <= 34; x++) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, 2, 38)), 1);
        }
        PlotPlanner.Site site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true)
                .orElseThrow(() -> new GameTestAssertException("no pad beside either street"));
        BlockPos origin = relative(helper, site.origin());
        Footprint reach = Footprint.of(origin, HOUSE).inflate(PlotRules.MARGIN + 1);
        helper.assertTrue(reach.minZ() <= 38 && 38 <= reach.maxZ() && reach.minX() <= 34 && 14 <= reach.maxX(),
                "the pad at " + origin + " does not touch the 1-hop street at z 38");
        helper.assertTrue(origin.getY() == 2, "the floor is y" + origin.getY() + ", not the street's y2");
        // The ground lies one block below that floor everywhere. The paver fills it all rather than leave the house's
        // floor over a one-block gap, so the pad is not taken as already prepared.
        helper.assertTrue(site.earthwork() == 49, "earthwork " + site.earthwork() + ", expected 49 blocks of fill up to the street");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_street_budget")
    public static void aStreetPadIsTakenWithinTheEarthBudgetAndRefusedPastIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        int minX = unskippedX(helper, 2);
        // The floor is the street's y2. Columns at y1 need one block of fill, columns at y4 two blocks cut: 49 + k blocks
        // for k columns at y4. The ground varies by 3, which the old one-block flatness rule refused.
        for (int[] pair : new int[][] {{41, 90}, {21, 70}}) {
            int high = pair[0];
            onlySpot(helper, minX, (x, z) -> (x - (minX - 1)) * 7 + (z - 9) < high ? 3 : 0);
            VillageData village = streetBesideSpot(helper, minX, 2);
            Optional<PlotPlanner.Site> site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true);
            if (pair[1] > Earthwork.MAX_VOLUME) {
                helper.assertTrue(site.isEmpty(), "a pad needing " + pair[1] + " blocks of earthwork was planned: " + site.map(s -> relative(helper, s.origin())));
            } else {
                BlockPos origin = site.map(s -> relative(helper, s.origin()))
                        .orElseThrow(() -> new GameTestAssertException("a pad varying by 3 and needing " + pair[1] + " blocks of earthwork was refused"));
                helper.assertTrue(origin.equals(new BlockPos(minX, 2, 10)), "origin " + origin);
                helper.assertTrue(site.get().earthwork() == pair[1], "earthwork " + site.get().earthwork() + ", expected " + pair[1]);
            }
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_street_column_step")
    public static void aStreetPadWithOneColumnSevenOffTheFloorIsRefused(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        int minX = unskippedX(helper, 1);
        int peakX = minX + 2;
        // Flat at the street's y1 except one column: 7 blocks up is refused, 6 blocks up is not, for about as little earth.
        for (int peak : new int[] {7, 6}) {
            onlySpot(helper, minX, (x, z) -> x == peakX && z == 12 ? peak : 0);
            VillageData village = streetBesideSpot(helper, minX, 1);
            Optional<PlotPlanner.Site> site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true);
            if (peak > Earthwork.MAX_COLUMN_STEP) {
                helper.assertTrue(site.isEmpty(), "a pad with a column " + peak + " off the floor was planned: " + site.map(s -> relative(helper, s.origin())));
            } else {
                helper.assertTrue(site.map(s -> relative(helper, s.origin())).filter(new BlockPos(minX, 1, 10)::equals).isPresent(),
                        "a pad with a column " + peak + " off the floor was refused: " + site.map(s -> relative(helper, s.origin())));
            }
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_no_streets")
    public static void aVillageWithNoStreetsStillPlansAtTheBell(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        // A laid path far from the bell, but no street cell: the village still plans as it did before streets.
        village.addPathCell(helper.absolutePos(new BlockPos(40, 1, 40)));
        for (boolean earthwork : new boolean[] {false, true}) {
            PlotPlanner.Site site = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, earthwork, pos -> true)
                    .orElseThrow(() -> new GameTestAssertException("no plot on flat ground with no streets (earthwork " + earthwork + ")"));
            BlockPos origin = relative(helper, site.origin());
            helper.assertTrue(origin.equals(new BlockPos(18, 1, 18)) && site.earthwork() == 0,
                    "planned " + origin + " with earthwork " + site.earthwork() + " instead of at the bell (earthwork " + earthwork + ")");
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_street_skip")
    public static void theSkippedPadsAreTheSameEverySearch(GameTestHelper helper) {
        // Pure: the skip is a hash of the origin, about one in eight.
        int skipped = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                BlockPos origin = new BlockPos(x * 3, 64, z * 5);
                helper.assertTrue(PlotRules.skipped(origin) == PlotRules.skipped(new BlockPos(origin.getX(), origin.getY(), origin.getZ())), "skip changed for " + origin);
                skipped += PlotRules.skipped(origin) ? 1 : 0;
            }
        }
        helper.assertTrue(skipped >= 64 && skipped <= 192, skipped + " of 1024 pads skipped, not about one in " + PlotRules.SKIP_ONE_IN);

        // In the world: a long street, and every pad beside it the search would offer, in the order it offers them.
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        for (int x = 6; x <= 42; x++) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, 1, 30)), 1);
        }
        List<BlockPos> first = new ArrayList<>();
        List<BlockPos> second = new ArrayList<>();
        PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> !first.add(pos));
        PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> !second.add(pos));
        helper.assertTrue(first.size() >= 16, "only " + first.size() + " pads beside the street; the test needs many");
        helper.assertTrue(first.equals(second), "the same search offered different pads:\n" + first + "\n" + second);
        for (BlockPos origin : first) {
            helper.assertFalse(PlotRules.skipped(origin), "offered a skipped pad at " + relative(helper, origin));
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_house_gap")
    public static void housesStandAtLeastFiveColumnsApart(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        int minX = unskippedX(helper, 1);
        onlySpot(helper, minX, (x, z) -> 0);
        for (boolean streets : new boolean[] {false, true}) {
            for (int gap : new int[] {PlotRules.HOUSE_GAP - 1, PlotRules.HOUSE_GAP}) {
                VillageData village = streets ? streetBesideSpot(helper, minX, 1) : village(helper);
                // A house to the east of the pad with exactly `gap` open columns between the two footprints.
                village.addHouse(new BuildingRecord("minecraft:home", helper.absolutePos(new BlockPos(minX + HOUSE.getX() + gap, 1, 10)), HOUSE));
                Optional<BlockPos> origin = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true).map(s -> relative(helper, s.origin()));
                boolean expected = gap >= PlotRules.HOUSE_GAP;
                helper.assertTrue(origin.filter(new BlockPos(minX, 1, 10)::equals).isPresent() == expected,
                        "with " + gap + " open columns to a house (streets " + streets + ") the search planned " + origin);
            }
        }
        helper.succeed();
    }

    /** Records a 3-wide street along z {@code row}, x {@code fromX}..{@code toX}: the centre in the graph, both sides as path cells. */
    private static void wideStreet(GameTestHelper helper, VillageData village, int fromX, int toX, int row, int y) {
        for (int x = fromX; x <= toX; x++) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, y, row)), 1);
            village.addPathCell(helper.absolutePos(new BlockPos(x, y, row - 1)));
            village.addPathCell(helper.absolutePos(new BlockPos(x, y, row + 1)));
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_street_even_row")
    public static void aStreetOnTheBellsOwnRowHasPadsAlongIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        // The first street starts at the bell, so its centre line lies on the bell's own row: an even offset of 0.
        wideStreet(helper, village, 26, 40, BELL.getZ(), 1);
        List<BlockPos> offered = new ArrayList<>();
        PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> !offered.add(pos));
        // Beside the street, not at its ends: the footprint plus margin stops right at a side cell, z 23 or z 25.
        long beside = offered.stream().map(pos -> relative(helper, pos))
                .filter(pos -> (pos.getZ() == 17 || pos.getZ() == 27) && pos.getX() >= 26 && pos.getX() + HOUSE.getX() - 1 <= 40)
                .count();
        helper.assertTrue(beside >= 8, "only " + beside + " of " + offered.size() + " pads lie beside a street on the bell's row");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_street_far")
    public static void aStreetBeyondTheSearchReachStillHasPads(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // Radius 4: the bell spiral reaches 20 blocks, to x 24; the street runs from x 28 to 40.
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(new BlockPos(4, 1, 24)), 4);
        wideStreet(helper, village, 28, 40, 24, 1);
        BlockPos origin = PlotPlanner.findSite(helper.getLevel(), village, HOUSE, true, pos -> true)
                .map(site -> relative(helper, site.origin()))
                .orElseThrow(() -> new GameTestAssertException("no pad beside a street beyond the search reach"));
        helper.assertTrue(origin.getX() + HOUSE.getX() - 1 > 4 + PlotPlanner.searchReach(village), "planned " + origin + ", within the old reach");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_storehouse_stranded")
    public static void aStorehouseStillFindsASpotInAPackedStreetVillage(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        wideStreet(helper, village, 26, 40, 24, 1);
        // Houses 5 apart along both sides of the street, and every other spot beside it within the gap of one.
        for (int x = 26; x <= 36; x += HOUSE.getX() + PlotRules.HOUSE_GAP) {
            village.addHouse(new BuildingRecord("minecraft:home", helper.absolutePos(new BlockPos(x, 1, 17)), HOUSE));
            village.addHouse(new BuildingRecord("minecraft:home", helper.absolutePos(new BlockPos(x, 1, 27)), HOUSE));
        }
        Vec3i storehouse = new Vec3i(1, 1, 1);
        helper.assertTrue(PlotPlanner.findSite(helper.getLevel(), village, storehouse, false, pos -> true).isEmpty(),
                "a house pad fits here, so the layout does not strand a search under the house rules");
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), village, storehouse).isPresent(), "the storehouse found nowhere to go");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_storehouse_no_street")
    public static void aStorehouseNeedsNoStreet(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        // A street far from the bell; the storehouse still goes beside the bell, as in a village with no streets.
        wideStreet(helper, village, 30, 40, 40, 1);
        BlockPos spot = PlotPlanner.find(helper.getLevel(), village, new Vec3i(1, 1, 1))
                .orElseThrow(() -> new GameTestAssertException("no storehouse spot on flat ground"));
        Footprint area = new Footprint(spot.getX(), spot.getZ(), spot.getX(), spot.getZ()).inflate(PlotRules.MARGIN);
        helper.assertTrue(PlotPlanner.touchingStreets(village, area).isEmpty() && chebyshev(relative(helper, spot), BELL) <= PlotPlanner.STEP,
                "the storehouse went beside the street at " + relative(helper, spot) + " instead of beside the bell");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_storehouse_gap")
    public static void aStorehouseMayStandThreeColumnsFromAHouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // Logs everywhere but the 3x3 around (10, 10), the only spot for a 1x1 storehouse.
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if (x < 9 || x > 11 || z < 9 || z > 11) {
                    helper.setBlock(x, 1, z, Blocks.OAK_LOG);
                }
            }
        }
        VillageData village = village(helper);
        // Columns 11, 12 and 13 lie open between the spot and the house.
        village.addHouse(new BuildingRecord("minecraft:home", helper.absolutePos(new BlockPos(14, 1, 8)), HOUSE));
        Optional<BlockPos> spot = PlotPlanner.find(helper.getLevel(), village, new Vec3i(1, 1, 1)).map(pos -> relative(helper, pos));
        helper.assertTrue(spot.filter(new BlockPos(10, 1, 10)::equals).isPresent(), "the storehouse spot 3 columns from a house was refused: " + spot);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_uneven_street_unprepared", timeoutTicks = 1200)
    public static void aPadBelowItsStreetIsClaimedUnprepared(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A street raised a block above the ground, so every pad beside it lies one block below its floor.
        for (int x = 14; x <= 34; x++) {
            helper.setBlock(x, 1, 34, Blocks.DIRT);
        }
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        for (int x = 14; x <= 34; x++) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, 2, 34)), 1);
        }
        // A paver on the roster, with no villager behind it, so nothing prepares the plot during the test.
        village.setCitizen(UUID.randomUUID(), JobType.PAVER);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 30);
        BuilderJob job = new BuilderJob();
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().size() == 1, "no plot claimed; waiting for " + job.waitingFor());
            Plot plot = village.plots().get(0);
            BlockPos origin = relative(helper, plot.origin());
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(origin.getY() == 2, "the plot at " + origin + " is not at the street's y2");
            helper.assertFalse(plot.prepared(), "the plot at " + origin + ", a block below its street, was claimed prepared");
        });
    }
}
