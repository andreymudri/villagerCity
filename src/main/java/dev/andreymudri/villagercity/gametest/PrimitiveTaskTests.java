package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PrimitiveTaskTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);

    private static void assertResults(GameTestHelper helper, ScriptedJob job, Task.Status... expected) {
        helper.assertTrue(job.results.equals(List.of(expected)), "results " + job.results);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_move", timeoutTicks = 400)
    public static void moveToReachesTarget(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos target = helper.absolutePos(new BlockPos(30, 1, 30));
        ScriptedJob job = new ScriptedJob(new MoveTo(target, 1.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS);
            helper.assertTrue(villager.distanceToSqr(Vec3.atCenterOf(target)) <= 1.5 * 1.5, "not at target");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_stuck", timeoutTicks = 700)
    public static void moveToFailsWhenTargetIsWalledIn(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        for (int x = 28; x <= 32; x++) {
            for (int z = 28; z <= 32; z++) {
                for (int y = 1; y <= 4; y++) {
                    boolean shell = x == 28 || x == 32 || z == 28 || z == 32 || y == 4;
                    if (shell) {
                        helper.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        ScriptedJob job = new ScriptedJob(new MoveTo(helper.absolutePos(new BlockPos(30, 1, 30)), 1.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.FAILED);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_break")
    public static void breakBlockUsesToolTimeAndDrops(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        BlockPos log = new BlockPos(12, 1, 10);
        helper.setBlock(log, Blocks.OAK_LOG);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        ItemStack axe = new ItemStack(Items.STONE_AXE);
        ScriptedJob job = new ScriptedJob(new BreakBlock(helper.absolutePos(log)));
        long start = helper.getLevel().getGameTime();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, axe, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS);
            helper.assertTrue(job.finishTimes.get(0) - start >= BreakBlock.breakTicks(helper.getLevel(), helper.absolutePos(log), Blocks.OAK_LOG.defaultBlockState(), axe.copy()),
                    "broke too fast");
            helper.assertBlockPresent(Blocks.AIR, log);
            helper.assertEntityPresent(EntityType.ITEM, log, 2.0);
            helper.assertTrue(axe.getDamageValue() == 1, "axe damage " + axe.getDamageValue());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_break_bedrock")
    public static void breakBlockFailsOnUnbreakable(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        helper.setBlock(new BlockPos(12, 1, 10), Blocks.BEDROCK);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        ScriptedJob job = new ScriptedJob(new BreakBlock(helper.absolutePos(new BlockPos(12, 1, 10))));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.FAILED);
            helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(12, 1, 10));
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_place")
    public static void placeBlockConsumesItemAndFailsWithout(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
        BlockPos first = helper.absolutePos(new BlockPos(12, 1, 10));
        BlockPos second = helper.absolutePos(new BlockPos(12, 1, 12));
        ScriptedJob job = new ScriptedJob(
                new PlaceBlock(first, Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS),
                new PlaceBlock(second, Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS, Task.Status.FAILED);
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(12, 1, 10));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(12, 1, 12));
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_PLANKS)) == 0, "plank not consumed");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_pickup", timeoutTicks = 300)
    public static void pickUpItemsCollectsMatchingDrops(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos drop = helper.absolutePos(new BlockPos(14, 1, 10));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), drop.getX() + 0.5, drop.getY(), drop.getZ() + 0.5, new ItemStack(Items.OAK_LOG, 3)));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), drop.getX() + 0.5, drop.getY(), drop.getZ() + 1.5, new ItemStack(Items.DIRT, 2)));
        ScriptedJob job = new ScriptedJob(new PickUpItems(drop, 3.0, s -> s.is(ItemTags.LOGS)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS);
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 3, "logs not picked up");
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.DIRT)) == 0, "picked up non-matching item");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_storage")
    public static void depositThenWithdraw(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        BlockPos store = new BlockPos(11, 1, 10);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.OAK_LOG, 10));
        BlockPos storeAbs = helper.absolutePos(store);
        ScriptedJob job = new ScriptedJob(
                new Deposit(storeAbs, s -> true),
                new Withdraw(storeAbs, Map.of(Items.OAK_LOG, 4)),
                new Withdraw(storeAbs, Map.of(Items.OAK_LOG, 50)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS, Task.Status.SUCCESS, Task.Status.FAILED);
            StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
            helper.assertTrue(storehouse.count(Items.OAK_LOG) == 6, "stored " + storehouse.count(Items.OAK_LOG));
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 4, "carried");
            VillageTestSupport.remove(helper, village);
        });
    }
}
