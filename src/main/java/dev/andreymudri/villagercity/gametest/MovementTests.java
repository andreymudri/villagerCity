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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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

    /** The door of {@link #shutIn}'s box, in its north wall. */
    private static final BlockPos DOOR = new BlockPos(10, 1, 8);

    /**
     * A roofed 5 by 5 cobblestone box from (8, 1, 8) to (12, 3, 12), shut with a closed {@code door} in the middle of
     * its north wall at {@link #DOOR}, and a villager standing inside it.
     */
    private static Villager shutIn(GameTestHelper helper, Block door) {
        for (int x = 8; x <= 12; x++) {
            for (int z = 8; z <= 12; z++) {
                helper.setBlock(x, 4, z, Blocks.COBBLESTONE);
                if (x == 8 || x == 12 || z == 8 || z == 12) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(x, y, z, Blocks.COBBLESTONE);
                    }
                }
            }
        }
        BlockState closed = door.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.OPEN, false);
        helper.setBlock(DOOR, closed.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(DOOR.above(), closed.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return GameTestSupport.spawnVillager(helper, 10, 1, 10);
    }

    private static boolean doorOpen(GameTestHelper helper) {
        BlockState state = helper.getBlockState(DOOR);
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN);
    }

    /**
     * A citizen shut in a house walks out through its closed wooden door, and the door is closed again behind it. The
     * builder of a finished house used to stand at such a door for good: nothing opened it.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_move_wooden_door", timeoutTicks = 600)
    public static void moveToLeavesThroughAClosedWoodenDoorAndClosesIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = shutIn(helper, Blocks.OAK_DOOR);
        BlockPos target = helper.absolutePos(new BlockPos(10, 1, 3));
        ScriptedJob job = new ScriptedJob(new MoveTo(target, 1.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results + ", villager at "
                    + StreetTests.relative(helper, villager.blockPosition()).toShortString());
            helper.assertFalse(doorOpen(helper), "the door was left open");
            VillageTestSupport.remove(helper, village);
        });
    }

    /** An iron door is never opened: a citizen shut in behind one stays in, and its move fails. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_move_iron_door", timeoutTicks = 600)
    public static void moveToNeverOpensAnIronDoor(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = shutIn(helper, Blocks.IRON_DOOR);
        BlockPos target = helper.absolutePos(new BlockPos(10, 1, 3));
        ScriptedJob job = new ScriptedJob(new MoveTo(target, 1.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.onEachTick(() -> {
            if (doorOpen(helper)) {
                VillageTestSupport.remove(helper, village);
                helper.fail("the iron door was opened");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.FAILED)), "results " + job.results);
            BlockPos at = StreetTests.relative(helper, villager.blockPosition());
            helper.assertTrue(at.getZ() > DOOR.getZ(), "the villager got out to " + at.toShortString());
            VillageTestSupport.remove(helper, village);
        });
    }
}
