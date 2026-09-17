package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.command.VillageCommand;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
public final class VillageCommandTests {
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_describe")
    public static void describeListsVillageStorehouseAndCitizens(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 8, false);
        BlockPos store = new BlockPos(20, 1, 24);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(store));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 5));
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new ScriptedJob());

        String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
        helper.assertTrue(text.contains(village.id().toString()), "missing id:\n" + text);
        helper.assertTrue(text.contains("age: dark"), "missing age:\n" + text);
        helper.assertTrue(text.contains("houses: 0"), "missing house count:\n" + text);
        helper.assertTrue(text.contains("oak_log x5"), "missing storehouse contents:\n" + text);
        helper.assertTrue(text.contains("job=builder") && text.contains("task=idle"), "missing citizen:\n" + text);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_waiting", timeoutTicks = 200)
    public static void describeSaysWhatAnIdleBuilderWaitsFor(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 4, false);
        BlockPos store = new BlockPos(20, 1, 24);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(store));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        storehouse.insertFromCitizen(new ItemStack(Items.COBBLESTONE, 20));
        Villager builder = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(builder, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        Villager sleeper = GameTestSupport.spawnVillager(helper, 26, 1, 26);
        CitizenTestSupport.enroll(sleeper, village, JobType.BUILDER, ItemStack.EMPTY, new ScriptedJob());
        sleeper.startSleeping(helper.absolutePos(new BlockPos(26, 1, 26)));
        String asleep = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
        helper.assertTrue(asleep.contains("citizen " + sleeper.getUUID() + " job=builder task=sleeping"), "sleeping citizen not shown sleeping:\n" + asleep);
        sleeper.discard();
        helper.succeedWhen(() -> {
            String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
            helper.assertTrue(text.contains("task=idle (waiting for materials: ") && text.contains("cobblestone x5") && text.contains("oak_planks x57"),
                    "missing materials reason:\n" + text);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_no_plot", timeoutTicks = 200)
    public static void describeSaysWhenNoPlotIsBuildable(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if ((x + z) % 7 == 0) {
                    helper.setBlock(x, 1, z, Blocks.OAK_LOG);
                }
            }
        }
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 4, false);
        BlockPos store = new BlockPos(20, 1, 25);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(store));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow().requiredMaterials()
                .forEach((item, count) -> storehouse.insertFromCitizen(new ItemStack(item, count)));
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 23);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
            helper.assertTrue(text.contains("task=idle (waiting for a buildable plot near the bell)"), "missing plot reason:\n" + text);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_far_citizen")
    public static void describeListsRosterCitizensAnywhere(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 4, false);
        Villager far = GameTestSupport.spawnVillager(helper, 2, 1, 2);
        far.teleportTo(far.getX(), far.getY() + 40, far.getZ());
        CitizenTestSupport.enroll(far, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob());
        village.setCitizen(far.getUUID(), JobType.LUMBERJACK);
        UUID gone = UUID.randomUUID();
        village.setCitizen(gone, JobType.BUILDER);
        String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
        far.discard();
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(text.contains("citizen " + far.getUUID() + " job=lumberjack task="), "citizen far from the bell not listed:\n" + text);
        helper.assertTrue(text.contains("citizen " + gone + " job=builder not loaded"), "unloaded roster citizen not listed:\n" + text);
        helper.succeed();
    }
}
