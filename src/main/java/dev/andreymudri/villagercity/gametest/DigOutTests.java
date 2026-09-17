package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class DigOutTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);
    /** A hill of stone from y1 to y6; the villager starts sealed in a two-block pocket at its foot. */
    private static final BlockPos POCKET = new BlockPos(10, 1, 20);
    /** Standing on top of the hill. */
    private static final BlockPos HILLTOP = new BlockPos(18, 7, 20);

    /** Absolute positions a protection mod guards; breaking them is cancelled. */
    private static final Set<BlockPos> PROTECTED = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((LivingDestroyBlockEvent e) -> {
            if (PROTECTED.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    private static void hill(GameTestHelper helper, Block block) {
        for (int x = 6; x <= 26; x++) {
            for (int z = 14; z <= 26; z++) {
                for (int y = 1; y <= 6; y++) {
                    helper.setBlock(x, y, z, block);
                }
            }
        }
        helper.setBlock(POCKET, Blocks.AIR);
        helper.setBlock(POCKET.above(), Blocks.AIR);
    }

    private static Villager trappedVillager(GameTestHelper helper) {
        Villager villager = GameTestSupport.spawnVillager(helper, POCKET.getX(), POCKET.getY(), POCKET.getZ());
        Vec3 feet = helper.absoluteVec(Vec3.atBottomCenterOf(POCKET));
        villager.moveTo(feet.x, feet.y, feet.z);
        return villager;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_dig_out", timeoutTicks = 2400)
    public static void aTrappedCitizenDigsAStaircaseOut(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        hill(helper, Blocks.STONE);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = trappedVillager(helper);
        BlockPos target = helper.absolutePos(HILLTOP);
        ScriptedJob job = new ScriptedJob(MoveTo.digOut(target, 2.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            helper.assertTrue(villager.distanceToSqr(Vec3.atCenterOf(target)) <= 2.5 * 2.5, "not on the hilltop");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_dig_out_plain", timeoutTicks = 600)
    public static void aPlainMoveNeverDigs(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        hill(helper, Blocks.STONE);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = trappedVillager(helper);
        ScriptedJob job = new ScriptedJob(new MoveTo(helper.absolutePos(HILLTOP), 2.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.FAILED)), "results " + job.results);
            assertHillIntact(helper, Blocks.STONE);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_dig_out_build", timeoutTicks = 600)
    public static void neverDigsThroughBuildBlocks(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        hill(helper, Blocks.STONE_BRICKS);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = trappedVillager(helper);
        ScriptedJob job = new ScriptedJob(MoveTo.digOut(helper.absolutePos(HILLTOP), 2.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.FAILED)), "results " + job.results);
            assertHillIntact(helper, Blocks.STONE_BRICKS);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_dig_out_griefing", timeoutTicks = 600)
    public static void neverDigsWithoutMobGriefing(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        hill(helper, Blocks.STONE);
        GameRules.BooleanValue griefing = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        griefing.set(false, helper.getLevel().getServer());
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = trappedVillager(helper);
        ScriptedJob job = new ScriptedJob(MoveTo.digOut(helper.absolutePos(HILLTOP), 2.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.FAILED)), "results " + job.results);
            griefing.set(true, helper.getLevel().getServer());
            assertHillIntact(helper, Blocks.STONE);
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Before the fix: every failed walk to the storehouse counted toward abandoning the plot, which was dropped for good. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_dig_out_plot_kept", timeoutTicks = 2400)
    public static void anUnreachableStorehouseNeverAbandonsThePlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        // Seal the storehouse (20, 1, 24) in bricks, which the builder never digs, too thick to reach it from outside.
        for (int x = 18; x <= 22; x++) {
            for (int z = 22; z <= 26; z++) {
                for (int y = 0; y <= 2; y++) {
                    if (x != 20 || z != 24 || y != 1) {
                        helper.setBlock(x, y, z, Blocks.STONE_BRICKS);
                    }
                }
            }
        }
        Villager villager = GameTestSupport.spawnVillager(helper, 30, 1, 30);
        UUID plotId = UUID.randomUUID();
        village.addPlot(new Plot(plotId, blueprint.id().toString(), helper.absolutePos(new BlockPos(34, 1, 34)), blueprint.size(), villager.getUUID()));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        // Six failed walks of at least 200 ticks each: more than MAX_CONSECUTIVE_FAILURES.
        helper.runAtTickTime(2200, () -> {
            Plot plot = village.plots().stream().filter(p -> p.id().equals(plotId)).findFirst().orElse(null);
            helper.assertTrue(plot != null, "plot dropped");
            helper.assertTrue(villager.getUUID().equals(plot.builder()) && plot.abandons() == 0, "plot abandoned: " + plot);
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    /** Before the fix a protected block straight ahead was chosen anyway, its break refused, and the whole move failed. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_dig_out_protected", timeoutTicks = 2400)
    public static void digsAroundAProtectedBlock(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        hill(helper, Blocks.STONE);
        List<BlockPos> guarded = List.of(POCKET.east(), POCKET.east().above(), POCKET.above(2), POCKET.east().above(2));
        guarded.forEach(pos -> PROTECTED.add(helper.absolutePos(pos)));
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = trappedVillager(helper);
        BlockPos target = helper.absolutePos(HILLTOP);
        ScriptedJob job = new ScriptedJob(MoveTo.digOut(target, 2.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            guarded.forEach(pos -> helper.assertBlockPresent(Blocks.STONE, pos));
            guarded.forEach(pos -> PROTECTED.remove(helper.absolutePos(pos)));
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Before the fix the builder's walk to the storehouse never dug, so a builder trapped in a cave never took materials. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_dig_out_builder", timeoutTicks = 3000)
    public static void aTrappedBuilderDigsOutToTakeMaterials(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        hill(helper, Blocks.STONE);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BlockPos store = HILLTOP;
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(store));
        StorehouseBlockEntity top = helper.getBlockEntity(store);
        blueprint.requiredMaterials().forEach((item, count) -> top.insertFromCitizen(new ItemStack(item, count)));
        Villager villager = trappedVillager(helper);
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), helper.absolutePos(new BlockPos(36, 1, 36)), blueprint.size(), villager.getUUID()));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(Inventories.count(villager.getInventory(), stack -> stack.is(Items.OAK_PLANKS)) == 57, "materials never taken");
            VillageTestSupport.remove(helper, village);
        });
    }

    private static void assertHillIntact(GameTestHelper helper, Block block) {
        for (int x = 6; x <= 26; x++) {
            for (int z = 14; z <= 26; z++) {
                for (int y = 1; y <= 6; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    boolean pocket = pos.equals(POCKET) || pos.equals(POCKET.above());
                    if (!pocket && !helper.getBlockState(pos).is(block)) {
                        helper.fail("dug " + pos);
                    }
                }
            }
        }
    }
}
