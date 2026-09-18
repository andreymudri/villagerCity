package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.job.LumberjackJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.HashSet;
import java.util.Set;
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
public final class LumberjackTests {
    private static final BlockPos BELL = new BlockPos(40, 1, 40);
    private static final BlockPos STORE = new BlockPos(22, 1, 24);
    /** A bell near the middle of the area, so the whole area lies inside the village's tree reach. */
    private static final BlockPos WIDE_BELL = new BlockPos(26, 1, 26);
    /** A storehouse in the corner, where a lumberjack that has just deposited stands parked. */
    private static final BlockPos WIDE_STORE = new BlockPos(4, 1, 6);
    /** A tree 32 blocks from the parked lumberjack: outside a local search, well inside the village. */
    private static final BlockPos WIDE_TREE = new BlockPos(8, 1, 36);
    /** Far more logs than any threshold, so a well-stocked storehouse cannot be what stops the felling. */
    private static final int BANKED_LOGS = 448;
    /** More relocations than the village has sectors; past this the job is looping instead of sweeping. */
    private static final int MAX_RELOCATIONS = 64;
    /** Far enough from the tested area that no chunk of that village is loaded. */
    private static final int UNLOADED_DISTANCE = 20000;

    /** Five-log oak with a 3x3 canopy on its top three logs and a cap. */
    static void plantTree(GameTestHelper helper, BlockPos base) {
        for (int y = 0; y < 5; y++) {
            helper.setBlock(base.above(y), Blocks.OAK_LOG);
        }
        for (int y = 2; y <= 5; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos leaf = base.offset(dx, y, dz);
                    if (!(dx == 0 && dz == 0 && y < 5)) {
                        helper.setBlock(leaf, Blocks.OAK_LEAVES);
                    }
                }
            }
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_chop", timeoutTicks = 1500)
    public static void chopsTreeStoresLogsAndReplants(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        BlockPos base = new BlockPos(26, 1, 26);
        plantTree(helper, base);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        villager.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            StorehouseBlockEntity storehouse = helper.getBlockEntity(STORE);
            helper.assertTrue(storehouse.count(Items.OAK_LOG) == 5, "stored logs " + storehouse.count(Items.OAK_LOG));
            helper.assertBlockPresent(Blocks.OAK_SAPLING, base);
            helper.assertTrue(data.tool().getDamageValue() == 5, "axe damage " + data.tool().getDamageValue());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_posts", timeoutTicks = 300)
    public static void ignoresLogsWithoutLeaves(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(26, y, 26), Blocks.OAK_LOG);
        }
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.runAfterDelay(200, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(26, 1, 26));
            // Chopping is always a sequence; a bare move is the search relocating, which is not work on the post.
            Task task = villager.getData(CitizenAttachments.RUNTIME).currentTask();
            helper.assertTrue(task == null || task instanceof MoveTo, "lumberjack is working on a post");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_relocate", timeoutTicks = 2400)
    public static void relocatesToFellATreeBeyondTheLocalSearch(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, WIDE_BELL, 6, false);
        helper.setBlock(WIDE_STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(WIDE_STORE));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(WIDE_STORE);
        storehouse.insert(new ItemStack(Items.OAK_LOG), BANKED_LOGS);
        plantTree(helper, WIDE_TREE);
        Villager villager = GameTestSupport.spawnVillager(helper, 4, 1, 4);
        villager.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, WIDE_TREE);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_swept", timeoutTicks = 200)
    public static void reportsWaitingOnceTheWholeVillageIsSearched(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, WIDE_BELL, 6, false);
        helper.setBlock(WIDE_STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(WIDE_STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 4, 1, 4);
        LumberjackJob job = new LumberjackJob();
        TaskContext ctx = planningContext(helper, villager, village);
        Set<BlockPos> searched = new HashSet<>();
        for (Task task = job.plan(ctx); task != null; task = job.plan(ctx)) {
            if (!(task instanceof MoveTo move)) {
                helper.fail("planned " + task.describe(ctx) + " with no tree in the village");
            } else if (searched.size() >= MAX_RELOCATIONS) {
                helper.fail("still relocating after " + searched.size() + " moves, last to " + move.target().toShortString());
            } else {
                helper.assertTrue(searched.add(move.target()), "relocated to " + move.target().toShortString() + " twice");
                // Every relocation fails, as one to an unreachable sector would; none may be planned again.
                job.onTaskFinished(ctx, task, Task.Status.FAILED);
            }
        }
        helper.assertTrue(job.waitingFor() != null, "idle with no reason: waitingFor() is null");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_unstored", timeoutTicks = 200)
    public static void reportsWaitingForAStorehouseWhenLoaded(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, WIDE_BELL, 6, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 4, 1, 4);
        villager.getInventory().addItem(new ItemStack(Items.OAK_LOG, LumberjackJob.DEPOSIT_THRESHOLD));
        LumberjackJob job = new LumberjackJob();
        TaskContext ctx = planningContext(helper, villager, village);
        Task task = job.plan(ctx);
        helper.assertTrue(task == null, "planned " + (task == null ? "nothing" : task.describe(ctx)) + " with no storehouse");
        helper.assertTrue("a storehouse".equals(job.waitingFor()), "waiting for " + job.waitingFor());
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_unloaded", timeoutTicks = 200)
    public static void neverWalksToAnUnloadedSector(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageRegistry.get(helper.getLevel()).register(helper.absolutePos(WIDE_BELL).offset(UNLOADED_DISTANCE, 0, 0));
        village.setRadius(6);
        village.setManaged(false);
        Villager villager = GameTestSupport.spawnVillager(helper, 4, 1, 4);
        LumberjackJob job = new LumberjackJob();
        TaskContext ctx = planningContext(helper, villager, village);
        Task task = job.plan(ctx);
        helper.assertTrue(task == null, "planned " + (task == null ? "nothing" : task.describe(ctx)) + " into unloaded chunks");
        helper.assertTrue(job.waitingFor() != null, "idle with no reason: waitingFor() is null");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    /** A context for planning by hand. The villager is no citizen, so the scheduler leaves it alone. */
    private static TaskContext planningContext(GameTestHelper helper, Villager villager, VillageData village) {
        CitizenData data = new CitizenData(village.id(), JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE));
        return new TaskContext(helper.getLevel(), villager, village, data);
    }
}
