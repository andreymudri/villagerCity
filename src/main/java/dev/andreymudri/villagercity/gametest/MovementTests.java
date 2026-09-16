package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class MovementTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);

    /** Before the fix: accuracy floor(4.0)=4 treats the start as arrived, the villager never moves, FAILED after 200 ticks. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_move_reach_edge", timeoutTicks = 400)
    public static void moveToArrivesWhenTargetIsExactlyReachBlocksAway(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos target = helper.absolutePos(new BlockPos(14, 1, 10));
        ScriptedJob job = new ScriptedJob(new MoveTo(target, 4.0));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            helper.assertTrue(villager.distanceToSqr(Vec3.atCenterOf(target)) <= 4.0 * 4.0, "not within reach");
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Before the fix: moveTo(entity) uses accuracy 1, the neighbouring block counts as arrived, the item stays 1.9 away. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_pickup_edge", timeoutTicks = 300)
    public static void pickUpItemsReachesItemInNeighbouringBlock(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        Vec3 stand = helper.absoluteVec(new Vec3(10.5, 1, 10.05));
        villager.moveTo(stand.x, stand.y, stand.z);
        Vec3 itemAt = helper.absoluteVec(new Vec3(10.5, 1, 11.95));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), itemAt.x, itemAt.y, itemAt.z, new ItemStack(Items.OAK_LOG, 1), 0, 0, 0));
        ScriptedJob job = new ScriptedJob(new PickUpItems(BlockPos.containing(itemAt), 3.0, s -> s.is(ItemTags.LOGS)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 1, "log not picked up");
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Before the fix: the 300-tick REST pause exhausts the absolute 200-tick deadline and the task returns SUCCESS empty-handed. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_pickup_pause", timeoutTicks = 900)
    public static void pickUpItemsSurvivesNightPause(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos drop = helper.absolutePos(new BlockPos(30, 1, 10));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), drop.getX() + 0.5, drop.getY(), drop.getZ() + 0.5, new ItemStack(Items.OAK_LOG, 3)));
        helper.runAtTickTime(1, () -> helper.getLevel().setDayTime(13000));
        helper.runAtTickTime(320, () -> helper.getLevel().setDayTime(1000));
        ScriptedJob job = new ScriptedJob(new PickUpItems(drop, 3.0, s -> s.is(ItemTags.LOGS)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 320, "still night");
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 3, "logs carried " + Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)));
            VillageTestSupport.remove(helper, village);
        });
    }
}
