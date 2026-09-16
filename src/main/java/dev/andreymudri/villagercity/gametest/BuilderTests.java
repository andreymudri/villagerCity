package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(STORE);
        for (Map.Entry<Item, Integer> entry : blueprint.requiredMaterials().entrySet()) {
            int left = entry.getValue() * copies;
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
        // second placement in build order is the wall plank at offset (1,1,0)
        helper.setBlock(origin.offset(1, 1, 0), Blocks.BEDROCK);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().stream().noneMatch(p -> p.id().equals(plotId)), "plot not abandoned");
            helper.assertBlockPresent(Blocks.COBBLESTONE, origin);
            helper.assertBlockPresent(Blocks.BEDROCK, origin.offset(1, 1, 0));
            VillageTestSupport.remove(helper, village);
        });
    }
}
