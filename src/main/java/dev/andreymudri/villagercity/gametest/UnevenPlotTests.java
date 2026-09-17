package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.TaskScheduler;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.Earthwork;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Plots on uneven ground: earthwork is planned only with a paver, and builders wait for the plot to be prepared. */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class UnevenPlotTests {
    private static final Vec3i HOUSE = new Vec3i(5, 5, 5);
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
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
                .orElseThrow(() -> new AssertionError("a spot varying by " + Earthwork.MAX_VARIANCE + " with little earthwork was refused"));
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
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Map<Item, Long> stocked = storehouse.counts();
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 2, 20);
        BuilderJob job = new BuilderJob();
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().size() == 1, "no plot claimed; waiting for " + job.waitingFor());
            Plot plot = village.plots().get(0);
            if (plot.prepared() || !storehouse.counts().equals(stocked)) {
                VillageTestSupport.remove(helper, village);
            }
            helper.assertFalse(plot.prepared(), "a sloped plot at " + relative(helper, plot.origin()) + " was claimed prepared");
            helper.assertTrue(storehouse.counts().equals(stocked), "withdrew materials for an unprepared plot");
            helper.assertTrue("the paver to prepare the plot".equals(job.waitingFor()), "waiting for " + job.waitingFor());
            VillageTestSupport.remove(helper, village);
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
}
