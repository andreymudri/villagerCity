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

    private static Villager enrollPaver(GameTestHelper helper, VillageData village, int x, int y, int z) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, y, z);
        CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new PaverJob());
        return villager;
    }

    private static Villager enrollBuilder(GameTestHelper helper, VillageData village, int x, int y, int z) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, y, z);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        return villager;
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
            BuildingRecord house = village.houses().get(0);
            List<StreetCell> touching = PlotPlanner.touchingStreets(village, house.footprint().inflate(PlotRules.MARGIN));
            helper.assertFalse(touching.isEmpty(), "the house at " + house.origin().subtract(helper.absolutePos(BlockPos.ZERO)).toShortString()
                    + " touches no street");
            helper.assertTrue(touching.stream().anyMatch(cell -> cell.pos().getY() == house.origin().getY()),
                    "the house floor y " + house.origin().getY() + " is not the height of a street it touches: " + touching);
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
            helper.assertTrue(StreetTests.withHops(village, 1).size() == 4,
                    "the first run has " + StreetTests.withHops(village, 1).size() + " cells instead of stopping at the plot");
            VillageTestSupport.remove(helper, village);
        });
    }
}
