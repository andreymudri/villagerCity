package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.job.PaverJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class SitePrepTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    /** A flat dirt platform, top at {@code floor - 1}, wide enough that approaching the plot never needs a climb or a dig. */
    private static void platform(GameTestHelper helper, int floor, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = 1; y <= floor - 1; y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT);
                }
            }
        }
    }

    private static Villager pavingVillager(GameTestHelper helper, int x, int y, int z, VillageData village) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, y, z);
        CitizenTestSupport.enroll(villager, village, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new PaverJob());
        return villager;
    }

    private static StorehouseBlockEntity storehouseWith(GameTestHelper helper, VillageData village, BlockPos pos, Item item, int count) {
        helper.setBlock(pos, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(pos));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(pos);
        storehouse.insertFromCitizen(new ItemStack(item, count));
        return storehouse;
    }

    private static void setMobGriefing(GameTestHelper helper, boolean value) {
        helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(value, helper.getLevel().getServer());
    }

    private static boolean mobGriefing(GameTestHelper helper) {
        return helper.getLevel().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_slope", timeoutTicks = 16000, skyAccess = true)
    public static void levelsASlopedPlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(5, 1, 5), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        // The center row (z=12) terraces from floor-2 to floor+2; the rest of the plot and its margin already sit at floor-1.
        int[] rowTops = {3, 3, 4, 5, 6, 7, 7};
        for (int x = 9; x <= 15; x++) {
            int top = rowTops[x - 9];
            for (int y = 1; y <= top; y++) {
                helper.setBlock(x, y, 12, Blocks.DIRT);
            }
            for (int y = top + 1; y <= floor - 1; y++) {
                helper.setBlock(x, y, 12, Blocks.AIR);
            }
        }
        Villager villager = pavingVillager(helper, 20, floor, 20, village);
        helper.succeedWhen(() -> {
            Plot plot = village.plots().get(0);
            helper.assertTrue(plot.prepared(), "not prepared yet");
            for (int x = 9; x <= 15; x++) {
                for (int z = 9; z <= 15; z++) {
                    BlockPos below = new BlockPos(x, floor - 1, z);
                    helper.assertTrue(helper.getBlockState(below).blocksMotion(), "gap not filled at " + below);
                    for (int y = floor; y <= floor + 4; y++) {
                        BlockPos cell = new BlockPos(x, y, z);
                        helper.assertTrue(helper.getBlockState(cell).isAir(), "cell not cleared at " + cell);
                    }
                }
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_reuse", timeoutTicks = 6000, skyAccess = true)
    public static void reusesDugEarthBeforeTheStorehouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        // A gap under the plot, three cells deep, and three separate one-block dirt bumps (each an ordinary auto-step,
        // never tall enough to make the villager dig a staircase) with just enough dug earth to fill it.
        helper.setBlock(11, 1, 11, Blocks.STONE);
        for (int y = 2; y <= floor - 1; y++) {
            helper.setBlock(11, y, 11, Blocks.AIR);
        }
        helper.setBlock(9, floor, 9, Blocks.DIRT);
        helper.setBlock(10, floor, 9, Blocks.DIRT);
        helper.setBlock(9, floor, 10, Blocks.DIRT);
        storehouseWith(helper, village, new BlockPos(20, floor, 20), Items.COBBLESTONE, 64);
        pavingVillager(helper, 16, floor, 16, village);
        helper.succeedWhen(() -> {
            Plot plot = village.plots().get(0);
            helper.assertTrue(plot.prepared(), "not prepared yet");
            for (int y = 2; y <= floor - 1; y++) {
                helper.assertBlockPresent(Blocks.DIRT, new BlockPos(11, y, 11));
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_waits", timeoutTicks = 1200, skyAccess = true)
    public static void waitsForFillWhenNothingIsLeft(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        helper.setBlock(11, 1, 11, Blocks.STONE);
        for (int y = 2; y <= floor - 1; y++) {
            helper.setBlock(11, y, 11, Blocks.AIR);
        }
        Villager villager = pavingVillager(helper, 16, floor, 16, village);
        helper.runAfterDelay(800, () -> {
            String waiting = villager.getData(CitizenAttachments.RUNTIME).activeJob().waitingFor();
            boolean prepared = village.plots().get(0).prepared();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue("dirt or cobblestone to fill the plot".equals(waiting), "waiting for " + waiting);
            helper.assertTrue(!prepared, "plot prepared without any fill material");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_player_build", timeoutTicks = 1200, skyAccess = true)
    public static void neverBreaksAPlayerBuildOnThePlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        BlockPos plank = new BlockPos(11, floor + 1, 11);
        helper.setBlock(plank, Blocks.OAK_PLANKS);
        pavingVillager(helper, 16, floor, 16, village);
        helper.runAfterDelay(800, () -> {
            boolean prepared = village.plots().get(0).prepared();
            VillageTestSupport.remove(helper, village);
            helper.assertBlockPresent(Blocks.OAK_PLANKS, plank);
            helper.assertTrue(!prepared, "plot prepared despite the player build");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_griefing", timeoutTicks = 1200, skyAccess = true)
    public static void respectsMobGriefing(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        BlockPos mound = new BlockPos(11, floor, 11);
        helper.setBlock(mound, Blocks.DIRT);
        boolean previous = mobGriefing(helper);
        setMobGriefing(helper, false);
        pavingVillager(helper, 16, floor, 16, village);
        helper.runAfterDelay(800, () -> {
            var state = helper.getBlockState(mound);
            boolean prepared;
            try {
                prepared = village.plots().get(0).prepared();
            } finally {
                setMobGriefing(helper, previous);
                VillageTestSupport.remove(helper, village);
            }
            helper.assertTrue(state.is(Blocks.DIRT), "mound changed with mobGriefing off: " + state);
            helper.assertTrue(!prepared, "plot prepared with mobGriefing off");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_flower_gap", timeoutTicks = 3000, skyAccess = true)
    public static void levelsAGapCoveredByAFlower(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        // A one-block gap under the plot, covered by a poppy: natural vegetation the paver must break before filling,
        // not a solid cell PlaceBlock could ever succeed on directly.
        BlockPos gap = new BlockPos(11, floor - 1, 11);
        helper.setBlock(11, floor - 2, 11, Blocks.STONE);
        helper.setBlock(gap, Blocks.POPPY);
        storehouseWith(helper, village, new BlockPos(20, floor, 20), Items.DIRT, 64);
        pavingVillager(helper, 16, floor, 16, village);
        helper.succeedWhen(() -> {
            Plot plot = village.plots().get(0);
            helper.assertTrue(plot.prepared(), "not prepared yet");
            helper.assertBlockPresent(Blocks.DIRT, gap);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_torch_gap", timeoutTicks = 1200, skyAccess = true)
    public static void neverBreaksATorchInTheFillGap(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        // A player's torch sits in the same kind of gap; it has no collision either, but it is not natural vegetation
        // and must be left alone, counted as an obstruction instead of a fill target.
        BlockPos torch = new BlockPos(11, floor - 1, 11);
        helper.setBlock(11, floor - 2, 11, Blocks.STONE);
        helper.setBlock(torch, Blocks.TORCH);
        storehouseWith(helper, village, new BlockPos(20, floor, 20), Items.DIRT, 64);
        pavingVillager(helper, 16, floor, 16, village);
        helper.runAfterDelay(800, () -> {
            boolean prepared = village.plots().get(0).prepared();
            VillageTestSupport.remove(helper, village);
            helper.assertBlockPresent(Blocks.TORCH, torch);
            helper.assertTrue(!prepared, "plot prepared despite the torch in the fill gap");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_mushroom_gap", timeoutTicks = 3000, skyAccess = true)
    public static void levelsAGapCoveredByAMushroom(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        // A brown mushroom on podzol: natural vegetation not covered by minecraft:flowers or minecraft:saplings, so
        // this only passes when the clear check is general rather than a hand-picked tag list.
        BlockPos gap = new BlockPos(11, floor - 1, 11);
        helper.setBlock(11, floor - 2, 11, Blocks.PODZOL);
        helper.setBlock(gap, Blocks.BROWN_MUSHROOM);
        storehouseWith(helper, village, new BlockPos(20, floor, 20), Items.DIRT, 64);
        pavingVillager(helper, 16, floor, 16, village);
        helper.succeedWhen(() -> {
            Plot plot = village.plots().get(0);
            helper.assertTrue(plot.prepared(), "not prepared yet");
            helper.assertBlockPresent(Blocks.DIRT, gap);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_berry_gap", timeoutTicks = 3000, skyAccess = true)
    public static void levelsAGapCoveredByASweetBerryBush(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 25, 25);
        // A sweet berry bush: also natural vegetation outside minecraft:flowers and minecraft:saplings.
        BlockPos gap = new BlockPos(11, floor - 1, 11);
        helper.setBlock(11, floor - 2, 11, Blocks.DIRT);
        helper.setBlock(gap, Blocks.SWEET_BERRY_BUSH);
        storehouseWith(helper, village, new BlockPos(20, floor, 20), Items.DIRT, 64);
        pavingVillager(helper, 16, floor, 16, village);
        helper.succeedWhen(() -> {
            Plot plot = village.plots().get(0);
            helper.assertTrue(plot.prepared(), "not prepared yet");
            helper.assertBlockPresent(Blocks.DIRT, gap);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_chest_midflight", timeoutTicks = 1500, skyAccess = true)
    public static void neverBreaksABlockPlacedDuringTheApproach(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        BlockPos origin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(origin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        platform(helper, floor, 5, 5, 40, 40);
        // An ordinary dirt bump the paver plans to cut, spawned far enough away that a player's chest can land in the
        // same cell mid-approach, well after planning picked it but well before the villager arrives to break it.
        BlockPos cutTarget = new BlockPos(11, floor + 1, 11);
        helper.setBlock(cutTarget, Blocks.DIRT);
        pavingVillager(helper, 30, floor, 30, village);
        helper.runAfterDelay(10, () -> helper.setBlock(cutTarget, Blocks.CHEST));
        helper.runAfterDelay(1400, () -> {
            boolean prepared = village.plots().get(0).prepared();
            VillageTestSupport.remove(helper, village);
            helper.assertBlockPresent(Blocks.CHEST, cutTarget);
            helper.assertTrue(!prepared, "plot prepared despite the chest placed during the approach");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_siteprep_skip_release", timeoutTicks = 1500, skyAccess = true)
    public static void releasesAnImpossiblePlotAfterFiveFailures(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        int floor = 5;
        platform(helper, floor, 5, 5, 25, 15);
        UUID stuckId = UUID.randomUUID();
        BlockPos stuckOrigin = new BlockPos(10, floor, 10);
        village.addPlot(new Plot(stuckId, "villagercity:blueprint/starter_house", helper.absolutePos(stuckOrigin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        // An unbreakable torch in the first plot's fill gap; the paver must give up on it after five failures instead
        // of starving the second, perfectly flat, plot of any attention at all.
        helper.setBlock(11, floor - 2, 11, Blocks.STONE);
        helper.setBlock(11, floor - 1, 11, Blocks.TORCH);
        UUID freeId = UUID.randomUUID();
        BlockPos freeOrigin = new BlockPos(18, floor, 10);
        village.addPlot(new Plot(freeId, "villagercity:blueprint/starter_house", helper.absolutePos(freeOrigin), new Vec3i(3, 1, 3), null, 0L, 0, false));
        pavingVillager(helper, 14, floor, 12, village);
        helper.succeedWhen(() -> {
            Plot stuck = village.plots().stream().filter(p -> p.id().equals(stuckId)).findFirst().orElseThrow();
            Plot free = village.plots().stream().filter(p -> p.id().equals(freeId)).findFirst().orElseThrow();
            helper.assertTrue(free.prepared(), "the free plot was never reached; the stuck plot was never skipped");
            helper.assertTrue(!stuck.prepared(), "the torch plot should never be prepared");
            VillageTestSupport.remove(helper, village);
        });
    }
}
