package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.job.PaverJob;
import dev.andreymudri.villagercity.job.StreetWork;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The whole street-first order of work: the paver prepares plots, else grows a street run ({@link StreetWork}), else
 * waits for room to grow; the builder of a village with a paver only builds beside a street.
 */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class StreetGrowthTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    /** The rise {@link #aSlopeIsCrossedInSeveralHopsAndNeverInOne} climbs. */
    private static final int RISE = 20;
    /** How far the plateau of {@link #aBellAtACliffEdgeStartsItsStreetLevelWithIt} stands above the ground beside it. */
    private static final int CLIFF = 6;

    /**
     * A paver running {@link PaverJob}, also put on the village's roster: enrolling alone does not add it to an unmanaged
     * village, and without it {@code jobCount(PAVER)} is 0, so a builder would take the no-paver path.
     */
    private static Villager enrollPaver(GameTestHelper helper, VillageData village, int x, int y, int z) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, y, z);
        CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new PaverJob());
        village.setCitizen(villager.getUUID(), JobType.PAVER);
        return villager;
    }

    /** A builder running {@link BuilderJob}, also put on the village's roster (see {@link #enrollPaver}). */
    private static Villager enrollBuilder(GameTestHelper helper, VillageData village, int x, int y, int z) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, y, z);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        village.setCitizen(villager.getUUID(), JobType.BUILDER);
        return villager;
    }

    /** Fails unless the house touches a street and its floor is at the height of a street it touches. */
    private static void assertOnAStreet(GameTestHelper helper, VillageData village, BuildingRecord house) {
        List<StreetCell> touching = PlotPlanner.touchingStreets(village, house.footprint().inflate(PlotRules.MARGIN));
        helper.assertFalse(touching.isEmpty(), "the house at " + StreetTests.relative(helper, house.origin()).toShortString()
                + " touches no street");
        helper.assertTrue(touching.stream().anyMatch(cell -> cell.pos().getY() == house.origin().getY()),
                "the house floor y " + house.origin().getY() + " is not the height of a street it touches: " + touching);
    }

    private static String waitingFor(Villager villager) {
        return villager.getData(CitizenAttachments.RUNTIME).activeJob().waitingFor();
    }

    /** The ground height of the pyramid: flat within two blocks of the bell, then one block up per block out, to {@link #RISE}. */
    private static int pyramid(int x, int z) {
        int distance = Math.max(Math.abs(x - BELL.getX()), Math.abs(z - BELL.getZ()));
        return Math.max(0, Math.min(RISE, distance - 2));
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_house", timeoutTicks = 12000)
    public static void aFreshVillageLaysAStreetAndThenBuildsAHouseOnIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        enrollPaver(helper, village, 28, 1, 28);
        enrollBuilder(helper, village, 22, 1, 28);
        helper.onEachTick(() -> {
            if (village.streets().isEmpty() && !village.plots().isEmpty()) {
                VillageTestSupport.remove(helper, village);
                helper.fail("the builder claimed a plot before the village had a street: " + village.plots());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount() + ", streets " + village.streets().size()
                    + ", plots " + village.plots().stream().map(Plot::origin).toList());
            assertOnAStreet(helper, village, village.houses().get(0));
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_pillar_bell", timeoutTicks = 12000)
    public static void aBellOnAPillarStillStartsAStreet(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // The bell stands on a 2-block stone pillar, 2 blocks above the ground beside it.
        helper.setBlock(BELL, Blocks.STONE);
        helper.setBlock(BELL.above(), Blocks.STONE);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL.above(2), 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        enrollPaver(helper, village, 28, 1, 28);
        enrollBuilder(helper, village, 22, 1, 28);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount() + ", streets " + village.streets().size()
                    + ", plots " + village.plots().size());
            helper.assertTrue(village.streets().stream().allMatch(cell -> StreetTests.relative(helper, cell.pos()).getY() == 1),
                    "a street cell is not on the ground: " + village.streets());
            assertOnAStreet(helper, village, village.houses().get(0));
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_walled_bell", timeoutTicks = 8000)
    public static void aBellNoStreetCanLeaveStillGetsAHouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A 3-block stone pillar on each corner beside the bell: every first slice has a side cell 3 blocks off its
        // centre, so no street can start at the bell.
        for (BlockPos corner : List.of(BELL.offset(-1, 0, -1), BELL.offset(1, 0, -1), BELL.offset(-1, 0, 1), BELL.offset(1, 0, 1))) {
            for (int y = 0; y < 3; y++) {
                helper.setBlock(corner.above(y), Blocks.STONE);
            }
        }
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Villager paver = enrollPaver(helper, village, 28, 1, 28);
        enrollBuilder(helper, village, 22, 1, 28);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount() + ", streets " + village.streets().size()
                    + ", plots " + village.plots().size());
            helper.assertTrue(village.streets().isEmpty(), "a street left the walled bell: " + village.streets());
            helper.assertTrue("room to grow".equals(waitingFor(paver)), "the paver is waiting for " + waitingFor(paver));
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * The bell stands at the northern edge of a plateau {@link #CLIFF} blocks high. North, the first direction a run from
     * the bell tries, leaves the plateau at the cliff foot; east and west have a side cell at the foot; south stays on the
     * plateau, level with the bell, and that is where the first street goes.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_cliff_bell", timeoutTicks = 4000)
    public static void aBellAtACliffEdgeStartsItsStreetLevelWithIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StreetTests.terrain(helper, (x, z) -> z >= BELL.getZ() ? CLIFF : 0);
        BlockPos bell = BELL.above(CLIFF);
        VillageData village = VillageTestSupport.freshVillage(helper, bell, 30, false);
        StreetTests.stockedStorehouse(helper, village, new BlockPos(40, CLIFF + 1, 40));
        enrollPaver(helper, village, 28, CLIFF + 1, 28);
        int bellY = helper.absolutePos(bell).getY();
        helper.succeedWhen(() -> {
            List<StreetCell> first = StreetTests.withHops(village, 1);
            helper.assertTrue(first.size() == StreetWork.RUN_LENGTH, "the first run has " + first.size() + " cells");
            helper.assertTrue(first.stream().allMatch(cell -> cell.pos().getY() == bellY), "the first street is not level with the bell: "
                    + first.stream().map(cell -> StreetTests.relative(helper, cell.pos()).toShortString()).toList());
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * An unprepared plot, with a dirt block on its floor to cut, and a street root with room to grow east: the paver
     * prepares the plot before it records a single street cell.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_paver_order", timeoutTicks = 4000)
    public static void thePaverPreparesAPlotBeforeItGrowsAStreet(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = StreetTests.streetVillage(helper, StreetTests.ROOT, 0);
        // Well clear of the run east from the root along z = 23 to 25, margin included.
        BlockPos origin = new BlockPos(10, 1, 32);
        helper.setBlock(origin.offset(1, 0, 1), Blocks.DIRT);
        Plot plot = new Plot(UUID.randomUUID(), Blueprints.STARTER_HOUSE.toString(), helper.absolutePos(origin), new Vec3i(3, 3, 3), null,
                0L, 0, false);
        village.addPlot(plot);
        enrollPaver(helper, village, 4, 1, 20);
        helper.onEachTick(() -> {
            boolean unprepared = village.plots().stream().anyMatch(p -> p.id().equals(plot.id()) && !p.prepared());
            if (unprepared && !village.streets().stream().allMatch(cell -> cell.hops() == 0)) {
                VillageTestSupport.remove(helper, village);
                helper.fail("the paver grew a street before it prepared the plot: " + village.streets().size() + " street cells");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().stream().anyMatch(p -> p.id().equals(plot.id()) && p.prepared()), "the plot is not prepared yet");
            helper.assertFalse(StreetTests.withHops(village, 1).isEmpty(), "the street never grew after the plot was prepared");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_builder_waits", timeoutTicks = 800)
    public static void aBuilderWithAPaverWaitsForAStreet(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        // A paver on the roster, with no villager behind it, so no street is ever laid.
        village.setCitizen(UUID.randomUUID(), JobType.PAVER);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Villager builder = enrollBuilder(helper, village, 22, 1, 28);
        helper.runAfterDelay(600, () -> {
            List<Plot> plots = village.plots();
            String waiting = waitingFor(builder);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(plots.isEmpty(), "the builder claimed a plot with no street to build on: " + plots);
            helper.assertTrue("a street to build on".equals(waiting), "the builder is waiting for " + waiting);
            helper.succeed();
        });
    }

    /** Runs with {@code skyAccess}: the rise climbs above the test area's 12-block height, into its barrier ceiling. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_slope", timeoutTicks = 24000, skyAccess = true)
    public static void aSlopeIsCrossedInSeveralHopsAndNeverInOne(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StreetTests.terrain(helper, StreetGrowthTests::pyramid);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        enrollPaver(helper, village, 26, 1, 26);
        int top = helper.absolutePos(BELL).getY() + RISE;
        int fewestHops = (RISE + StreetWork.RUN_LENGTH * StreetWork.MAX_STEP - 1) / (StreetWork.RUN_LENGTH * StreetWork.MAX_STEP);
        helper.succeedWhen(() -> {
            List<StreetCell> streets = village.streets();
            StreetCell summit = streets.stream().filter(cell -> cell.pos().getY() >= top).findFirst()
                    .orElseThrow(() -> new GameTestAssertException("no street has reached the top yet, cells at hops: " + streets.stream()
                            .map(cell -> StreetTests.relative(helper, cell.pos()).toShortString() + " @" + cell.hops()).toList()));
            helper.assertTrue(summit.hops() >= fewestHops, "the street reached the top in " + summit.hops() + " hops");
            for (int hops = 1; hops <= village.deepestHops(); hops++) {
                int h = hops;
                List<StreetCell> run = streets.stream().filter(cell -> cell.hops() == h).toList();
                int low = run.stream().mapToInt(cell -> cell.pos().getY()).min().orElse(0);
                int high = run.stream().mapToInt(cell -> cell.pos().getY()).max().orElse(0);
                helper.assertTrue(high - low < RISE, "the run at " + h + " hops climbs " + (high - low) + " blocks");
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_room", timeoutTicks = 600)
    public static void aVillageWhoseGraphCannotGrowReportsRoomToGrow(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        // The only end is already at the hop limit, so no run can start.
        village.addStreetCell(helper.absolutePos(BELL.east(2)), StreetWork.MAX_HOPS);
        Villager paver = enrollPaver(helper, village, 28, 1, 28);
        helper.succeedWhen(() -> {
            helper.assertTrue("room to grow".equals(waitingFor(paver)), "the paver is waiting for " + waitingFor(paver));
            helper.assertTrue(village.streets().size() == 1, "the graph grew to " + village.streets().size() + " cells");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_no_paver", timeoutTicks = 4000)
    public static void aVillageWithNoPaverStillBuildsOnFlatGround(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        enrollBuilder(helper, village, 22, 1, 22);
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount());
            helper.assertTrue(village.streets().isEmpty(), "a village with no paver has " + village.streets().size() + " street cells");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_late_plot", timeoutTicks = 4000)
    public static void aRunStopsAtAPlotClaimedAfterItStarted(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = StreetTests.streetVillage(helper, StreetTests.ROOT, 0);
        StreetWork job = new StreetWork();
        StreetTests.enrollPaver(helper, village, 1, job);
        // Once the run has laid its first slice, a plot is claimed across all three rows of the street from x = 9.
        Footprint[] claimed = new Footprint[1];
        AtomicBoolean placed = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!placed.get() && !StreetTests.withHops(village, 1).isEmpty()) {
                Plot plot = new Plot(UUID.randomUUID(), Blueprints.STARTER_HOUSE.toString(), helper.absolutePos(new BlockPos(9, 1, 20)),
                        new Vec3i(5, 3, 9), null, 0L, 0, true);
                village.addPlot(plot);
                claimed[0] = plot.footprint();
                placed.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(placed.get(), "the run never laid a slice");
            // The plot walls the end in on every side, so once the first run is over nothing can grow.
            helper.assertTrue(StreetTests.ROOM_TO_GROW.equals(job.waitingFor()), "the paver is still growing: " + job.waitingFor());
            for (BlockPos cell : village.pathCells()) {
                helper.assertFalse(claimed[0].contains(cell.getX(), cell.getZ()),
                        "path cell " + StreetTests.relative(helper, cell).toShortString() + " lies inside the plot claimed mid-run");
            }
            // The plot covers x = 9 to 13 and its margin x = 8, which SitePrep may cut or fill: the run ends at x = 7.
            helper.assertTrue(StreetTests.withHops(village, 1).size() == 3,
                    "the first run has " + StreetTests.withHops(village, 1).size() + " cells instead of stopping at the plot's margin");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_street_growth_late_unprepared", timeoutTicks = 6000)
    public static void aRunNeverLaysACellOnTheMarginOfAPlotClaimedAfterItStarted(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // Ground rising one block per column from x = 5 to x = 7, then flat at y = 3.
        StreetTests.terrain(helper, (x, z) -> Math.max(0, Math.min(3, x - 4)));
        VillageData village = StreetTests.streetVillage(helper, StreetTests.ROOT, 0);
        Villager paver = enrollPaver(helper, village, 4, 1, 20);
        // Once the run has laid its first slice, an unprepared plot is claimed from x = 7. Its margin, x = 6, is ground
        // the paver's SitePrep levels to the plot's floor before the run would resume.
        Footprint[] margin = new Footprint[1];
        UUID[] plotId = new UUID[1];
        helper.onEachTick(() -> {
            if (plotId[0] == null && !StreetTests.withHops(village, 1).isEmpty()) {
                Plot plot = new Plot(UUID.randomUUID(), Blueprints.STARTER_HOUSE.toString(), helper.absolutePos(new BlockPos(7, 2, 23)),
                        new Vec3i(3, 3, 3), null, 0L, 0, false);
                village.addPlot(plot);
                margin[0] = plot.footprint().inflate(PlotRules.MARGIN);
                plotId[0] = plot.id();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(plotId[0] != null, "the run never laid a slice");
            helper.assertTrue(village.plots().stream().anyMatch(plot -> plot.id().equals(plotId[0]) && plot.prepared()),
                    "the plot is not prepared yet");
            helper.assertTrue(StreetTests.ROOM_TO_GROW.equals(waitingFor(paver)), "the paver is waiting for " + waitingFor(paver));
            for (BlockPos cell : village.pathCells()) {
                BlockPos below = StreetTests.relative(helper, cell).below();
                helper.assertTrue(helper.getBlockState(below).isSolid(), "path cell " + below.above().toShortString() + " stands on "
                        + helper.getBlockState(below));
            }
            for (StreetCell cell : village.streets()) {
                helper.assertFalse(margin[0].contains(cell.pos().getX(), cell.pos().getZ()),
                        "street cell " + StreetTests.relative(helper, cell.pos()).toShortString() + " lies in the plot's margin");
            }
            VillageTestSupport.remove(helper, village);
        });
    }
}
