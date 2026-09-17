package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.command.VillageCommand;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.UUID;
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
        helper.assertTrue(text.contains("paths: 0 cells laid, 0 queued"), "missing paths:\n" + text);
        helper.assertTrue(text.contains("workshop: table none, furnace none"), "missing workshop:\n" + text);
        helper.assertTrue(text.contains("dark spots: 0"), "missing dark spots:\n" + text);
        helper.assertTrue(!text.contains("artisan orders:"), "listed artisan orders with none placed:\n" + text);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_works")
    public static void describeListsPlotPreparationPathsWorkshopAndLighting(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 4, false);
        BlockPos preparedOrigin = helper.absolutePos(new BlockPos(30, 1, 30));
        BlockPos unpreparedOrigin = helper.absolutePos(new BlockPos(4, 1, 30));
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", preparedOrigin, new Vec3i(5, 5, 5), null, 0L, 0));
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", unpreparedOrigin, new Vec3i(5, 5, 5), null, 0L, 0, false));
        village.addPathCell(helper.absolutePos(new BlockPos(22, 2, 24)));
        village.addPathCell(helper.absolutePos(new BlockPos(21, 2, 24)));
        village.queuePath(helper.absolutePos(new BlockPos(4, 1, 4)));
        BlockPos table = helper.absolutePos(new BlockPos(18, 1, 22));
        BlockPos furnace = helper.absolutePos(new BlockPos(18, 1, 26));
        village.setCraftingTablePos(table);
        village.setFurnacePos(furnace);
        village.setDarkSpotCount(7);
        village.setArtisanOrders(List.of("torch x4", "oak_planks x12"));

        String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(text.lines().anyMatch(line -> line.startsWith("plot " + preparedOrigin.toShortString() + " ") && line.endsWith(", prepared")),
                "missing prepared plot:\n" + text);
        helper.assertTrue(text.lines().anyMatch(line -> line.startsWith("plot " + unpreparedOrigin.toShortString() + " ") && line.endsWith(", unprepared")),
                "missing unprepared plot:\n" + text);
        helper.assertTrue(text.contains("paths: 2 cells laid, 1 queued"), "missing paths:\n" + text);
        helper.assertTrue(text.contains("workshop: table " + table.toShortString() + ", furnace " + furnace.toShortString()), "missing workshop:\n" + text);
        helper.assertTrue(text.contains("dark spots: 7"), "missing dark spots:\n" + text);
        helper.assertTrue(text.contains("artisan orders: torch x4, oak_planks x12"), "missing artisan orders:\n" + text);
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

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_step", timeoutTicks = 200)
    public static void describeShowsTheCurrentStepAndThePlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        Villager builder = GameTestSupport.spawnVillager(helper, 4, 1, 4);
        BlockPos origin = helper.absolutePos(new BlockPos(30, 1, 30));
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), origin, blueprint.size(), null, 0L, 1));
        BlockPos target = helper.absolutePos(new BlockPos(40, 1, 40));
        CitizenTestSupport.enroll(builder, village, JobType.BUILDER, ItemStack.EMPTY,
                new ScriptedJob(TaskSequence.of(new MoveTo(target, 1.0))));
        helper.runAtTickTime(5, () -> {
            String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
            helper.assertTrue(text.contains("task=walking to " + target.toShortString() + " ("), "missing current step:\n" + text);
            long left = blueprint.placements().stream().filter(p -> !BuilderJob.isDone(helper.getLevel(), village.plots().get(0), p)).count();
            helper.assertTrue(left > 0 && text.contains("plot " + origin.toShortString() + " " + blueprint.id() + ": " + left + "/" + blueprint.placements().size()
                    + " blocks left, released, abandoned 1/3"), "missing plot:\n" + text);
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }
}
