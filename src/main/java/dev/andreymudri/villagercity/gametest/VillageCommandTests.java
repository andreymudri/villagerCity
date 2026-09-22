package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.command.PlotDiagnostics;
import dev.andreymudri.villagercity.command.VillageCommand;
import dev.andreymudri.villagercity.command.VillageOutline;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.Earthwork;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VillageCommandTests {
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_why")
    public static void whyAgreesWithThePlotSearch(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 8, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BlockPos flat = helper.absolutePos(new BlockPos(18, 1, 18));
        // A two-block step in the middle of the spot, which is earthwork, and a village with no paver may do none.
        BlockPos bump = new BlockPos(30, 1, 30);
        helper.setBlock(bump, Blocks.DIRT);
        helper.setBlock(bump.above(), Blocks.DIRT);
        BlockPos uneven = helper.absolutePos(bump);

        String onFlat = String.join("\n", PlotDiagnostics.explain(helper.getLevel(), village, flat, blueprint.size()));
        String onBump = String.join("\n", PlotDiagnostics.explain(helper.getLevel(), village, uneven, blueprint.size()));

        helper.assertTrue(onFlat.startsWith("This spot is buildable."), "flat ground was rejected:\n" + onFlat);
        helper.assertFalse(onBump.startsWith("This spot is buildable."), "a step was accepted:\n" + onBump);
        helper.assertTrue(onBump.contains("NO  earthwork") && onBump.contains("the budget is 0 with no paver"), "no earthwork reason given:\n" + onBump);
        // The planner must agree: it takes the flat spot and never the one the diagnosis rejected.
        BlockPos planned = PlotPlanner.find(helper.getLevel(), village, blueprint.size()).orElseThrow();
        helper.assertTrue(Math.abs(planned.getX() - uneven.getX()) > 1 || Math.abs(planned.getZ() - uneven.getZ()) > 1,
                "the planner took the spot the diagnosis rejected: " + planned);
        String onPlanned = String.join("\n", PlotDiagnostics.explain(helper.getLevel(), village, planned.offset(blueprint.size().getX() / 2, 0, blueprint.size().getZ() / 2), blueprint.size()));
        helper.assertTrue(onPlanned.startsWith("This spot is buildable."),
                "the diagnosis rejected what the planner chose:\n" + onPlanned);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_why_budget")
    public static void whyNamesTheEarthBudgetASpotBroke(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 8, false);
        try {
            // A paver on the roster, so the spot is judged against the paver's budget rather than none at all.
            village.setCitizen(UUID.randomUUID(), JobType.PAVER);
            Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
            // The 7x7 spot centred on (34, 34): four rows at y5 and three at y1, levelled best at y5 for 21 * 4 = 84 blocks.
            BlockPos spot = new BlockPos(34, 1, 34);
            for (int x = 34; x <= 37; x++) {
                for (int z = 31; z <= 37; z++) {
                    for (int y = 1; y <= 4; y++) {
                        helper.setBlock(x, y, z, Blocks.DIRT);
                    }
                }
            }
            List<String> lines = PlotDiagnostics.explain(helper.getLevel(), village, helper.absolutePos(spot), blueprint.size());
            String why = String.join("\n", lines);
            helper.assertTrue(lines.get(0).equals("This spot is not buildable:"), "a spot needing 84 blocks of earth was accepted:\n" + why);
            helper.assertTrue(why.contains("NO  earthwork: levelling to y" + helper.absolutePos(new BlockPos(0, 5, 0)).getY() + " moves 84 blocks, the budget is "
                    + Earthwork.MAX_VOLUME + " for the paver"), "the budget it broke is not named:\n" + why);
            helper.assertTrue(why.contains("yes column step"), "the column step within its limit is not reported as met:\n" + why);
            helper.assertTrue(lines.stream().filter(line -> line.startsWith("NO  ")).count() == 1, "a rule other than the budget turned it down:\n" + why);
            // The planner agrees: it never takes that spot.
            BlockPos corner = helper.absolutePos(spot.offset(-blueprint.size().getX() / 2, 0, -blueprint.size().getZ() / 2));
            List<BlockPos> offered = new ArrayList<>();
            PlotPlanner.findSite(helper.getLevel(), village, blueprint.size(), true, pos -> !offered.add(pos));
            helper.assertTrue(offered.stream().noneMatch(pos -> pos.getX() == corner.getX() && pos.getZ() == corner.getZ()),
                    "the planner offered the spot the diagnosis refused for earthwork");
            helper.assertFalse(offered.isEmpty(), "the planner offered nothing at all, so the agreement proves nothing");
        } finally {
            VillageTestSupport.remove(helper, village);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_why_street")
    public static void whyReportsTheStreetASpotOpensOnto(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 8, false);
        try {
            Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
            for (int x = 10; x <= 38; x++) {
                village.addStreetCell(helper.absolutePos(new BlockPos(x, 1, 30)), 2);
            }
            // Centre (16, 26): the footprint is z 24..28 and its margin z 23..29, right beside the street at z 30.
            String beside = String.join("\n", PlotDiagnostics.explain(helper.getLevel(), village, helper.absolutePos(new BlockPos(16, 1, 26)), blueprint.size()));
            String away = String.join("\n", PlotDiagnostics.explain(helper.getLevel(), village, helper.absolutePos(new BlockPos(16, 1, 12)), blueprint.size()));
            helper.assertTrue(beside.contains("(the street's height, 2 hops out)"), "the street beside the spot is not reported:\n" + beside);
            helper.assertTrue(away.contains("NO  street: the spot touches no street; the nearest street cell is 18 blocks away, 2 hops out"),
                    "a spot away from the street was not turned down for it:\n" + away);
        } finally {
            VillageTestSupport.remove(helper, village);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_outline")
    public static void showDrawsTheVillageUntilItIsHidden(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 8, false);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            VillageOutline.show(player, village, 30);
            helper.assertTrue(VillageOutline.isWatching(player), "the player is not watching after show");
            helper.assertTrue(VillageOutline.hide(player), "hide did not report an outline to stop");
            helper.assertFalse(VillageOutline.isWatching(player), "the player still watches after hide");
            VillageOutline.show(player, village, 1);
            // The view ends on its own: the redraw that runs past its end forgets the player.
            helper.getLevel().setDayTime(helper.getLevel().getDayTime());
        } finally {
            VillageOutline.hide(player);
            VillageTestSupport.remove(helper, village);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_stock")
    public static void stockFillsTheStorehouseForTheHousesAsked(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 8, false);
        BlockPos store = new BlockPos(20, 1, 24);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(store));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        Map<Item, Integer> materials = blueprint.requiredMaterials();

        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
                .withPosition(Vec3.atCenterOf(helper.absolutePos(new BlockPos(24, 1, 24))))
                .withLevel(helper.getLevel());
        int result = VillageCommand.stock(source, 3);

        helper.assertTrue(result == 1, "stock did not succeed: " + result);
        for (Map.Entry<Item, Integer> material : materials.entrySet()) {
            long stored = storehouse.count(material.getKey());
            long wanted = (long) material.getValue() * 3;
            helper.assertTrue(stored == wanted,
                    "stored " + stored + " " + material.getKey() + ", wanted " + wanted);
        }
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

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
        helper.assertTrue(text.lines().anyMatch(line -> line.equals("workshop: table none")), "missing workshop:\n" + text);
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
        village.setCraftingTablePos(table);
        village.setDarkSpotCount(7);
        village.setArtisanOrders(List.of("torch x4", "oak_planks x12"));

        String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(text.lines().anyMatch(line -> line.startsWith("plot " + preparedOrigin.toShortString() + " ") && line.endsWith(", prepared")),
                "missing prepared plot:\n" + text);
        helper.assertTrue(text.lines().anyMatch(line -> line.startsWith("plot " + unpreparedOrigin.toShortString() + " ") && line.endsWith(", unprepared")),
                "missing unprepared plot:\n" + text);
        helper.assertTrue(text.contains("paths: 2 cells laid, 1 queued"), "missing paths:\n" + text);
        helper.assertTrue(text.lines().anyMatch(line -> line.equals("workshop: table " + table.toShortString())), "missing workshop:\n" + text);
        helper.assertTrue(!text.contains("furnace"), "the report still names a furnace the village no longer keeps:\n" + text);
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
