package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class BuilderTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    private static final BlockPos STORE = new BlockPos(20, 1, 24);

    /** Places a storehouse holding `copies` full sets of the starter house materials. */
    static StorehouseBlockEntity stockedStorehouse(GameTestHelper helper, VillageData village, Blueprint blueprint, int copies) {
        Map<Item, Integer> materials = new LinkedHashMap<>();
        blueprint.requiredMaterials().forEach((item, count) -> materials.put(item, count * copies));
        return stockedStorehouse(helper, village, materials);
    }

    /** Places a storehouse holding exactly the given materials. */
    static StorehouseBlockEntity stockedStorehouse(GameTestHelper helper, VillageData village, Map<Item, Integer> materials) {
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(STORE);
        for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                int stack = Math.min(left, new ItemStack(entry.getKey()).getMaxStackSize());
                storehouse.insertFromCitizen(new ItemStack(entry.getKey(), stack));
                left -= stack;
            }
        }
        return storehouse;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_house", timeoutTicks = 4000)
    public static void buildsStarterHouseFromStoredMaterials(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 1);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount());
            helper.assertTrue(village.plots().isEmpty(), "plot still open");
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.getItem() instanceof BlockItem) == 0, "leftovers not deposited");
            BuildingRecord house = village.houses().get(0);
            for (BlueprintPlacement placement : blueprint.placements()) {
                BlockPos pos = house.origin().offset(placement.offset());
                helper.assertTrue(helper.getLevel().getBlockState(pos).is(placement.state().getBlock()),
                        "wrong block at " + helper.relativePos(pos) + ": " + helper.getLevel().getBlockState(pos));
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_obstruction", timeoutTicks = 4000)
    public static void clearsObstructionsInsideThePlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 1);
        Villager villager = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        BlockPos origin = new BlockPos(8, 1, 8);
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), helper.absolutePos(origin), blueprint.size(), villager.getUUID()));
        BlockPos obstruction = origin.offset(3, 2, 3);
        helper.setBlock(obstruction, Blocks.STONE);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount());
            helper.assertBlockPresent(Blocks.AIR, obstruction);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_abandon", timeoutTicks = 3000)
    public static void abandonsPlotAfterRepeatedFailures(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 2);
        Villager villager = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        BlockPos origin = new BlockPos(8, 1, 8);
        UUID plotId = UUID.randomUUID();
        village.addPlot(new Plot(plotId, blueprint.id().toString(), helper.absolutePos(origin), blueprint.size(), villager.getUUID()));
        // the bedrock plank at (1,1,0) is placement 26; placements 0-24 are the floor, 25 is the log at (0,1,0)
        helper.setBlock(origin.offset(1, 1, 0), Blocks.BEDROCK);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            Plot plot = village.plots().stream().filter(p -> p.id().equals(plotId)).findFirst().orElse(null);
            helper.assertTrue(plot != null, "plot dropped on the first abandon");
            helper.assertTrue(plot.released(), "plot not released, builder " + plot.builder());
            helper.assertTrue(plot.abandons() == 1, "abandons " + plot.abandons());
            helper.assertTrue(plot.retryAt() > 0, "retryAt " + plot.retryAt());
            assertAbandonedWalls(helper, origin);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_abandon_drop", timeoutTicks = 3000)
    public static void dropsPlotOnThirdAbandon(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 2);
        Villager villager = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        BlockPos origin = new BlockPos(8, 1, 8);
        UUID plotId = UUID.randomUUID();
        village.addPlot(new Plot(plotId, blueprint.id().toString(), helper.absolutePos(origin), blueprint.size(), villager.getUUID(), 0L, 2));
        helper.setBlock(origin.offset(1, 1, 0), Blocks.BEDROCK);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().stream().noneMatch(p -> p.id().equals(plotId)), "plot not dropped on the third abandon");
            helper.assertTrue(village.isFailedPlot(helper.absolutePos(origin)), "dropped plot not remembered as failed");
            assertAbandonedWalls(helper, origin);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_failed_plot", timeoutTicks = 600)
    public static void neverClaimsAFailedPlotAgain(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 1);
        BlockPos first = PlotPlanner.find(helper.getLevel(), village, blueprint.size()).orElseThrow();
        village.markPlotFailed(first);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 20);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().size() == 1, "no plot claimed");
            helper.assertTrue(!village.plots().get(0).origin().equals(first), "failed plot claimed again at " + first);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_unreachable_plot", timeoutTicks = 800)
    public static void neverClaimsAPlotItCannotWalkTo(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        // A sheer 8-high plateau of paths (never buildable) in the middle; the only flat ground is the ring below it.
        for (int x = 10; x <= 38; x++) {
            for (int z = 10; z <= 38; z++) {
                for (int y = 1; y <= 8; y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT_PATH);
                }
            }
        }
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 9, 24), 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        Map<Item, Integer> materials = blueprint.requiredMaterials();
        helper.setBlock(new BlockPos(20, 9, 24), StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(new BlockPos(20, 9, 24)));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(new BlockPos(20, 9, 24));
        materials.forEach((item, count) -> {
            for (int left = count; left > 0; left -= new ItemStack(item).getMaxStackSize()) {
                storehouse.insertFromCitizen(new ItemStack(item, Math.min(left, new ItemStack(item).getMaxStackSize())));
            }
        });
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), village, blueprint.size()).isPresent(), "no buildable ground below the plateau; the test needs some");
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 9, 20);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.runAfterDelay(600, () -> {
            List<Plot> plots = village.plots();
            String waiting = villager.getData(CitizenAttachments.RUNTIME).activeJob().waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(plots.isEmpty(), "claimed a plot below the cliff: " + plots);
            helper.assertTrue("a buildable plot near the bell".equals(waiting), "waiting for " + waiting);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_search_backoff", timeoutTicks = 600)
    public static void buildersShareTheWaitAfterAFailedPlotSearch(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 1);
        ServerLevel level = helper.getLevel();
        // A search just failed for this village: no builder may search again before the wait is over.
        long until = level.getGameTime() + BuilderJob.PLOT_SEARCH_RETRY_TICKS;
        village.setNextPlotSearch(until);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 20);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().size() == 1, "no plot claimed");
            helper.assertTrue(level.getGameTime() >= until, "plot claimed during the village's wait");
            VillageTestSupport.remove(helper, village);
        });
    }

    private static void assertAbandonedWalls(GameTestHelper helper, BlockPos origin) {
        helper.assertBlockPresent(Blocks.COBBLESTONE, origin);
        helper.assertBlockPresent(Blocks.COBBLESTONE, origin.offset(4, 0, 4));
        helper.assertBlockPresent(Blocks.OAK_LOG, origin.offset(0, 1, 0));
        helper.assertBlockPresent(Blocks.BEDROCK, origin.offset(1, 1, 0));
    }
}
