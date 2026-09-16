package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class TaskSchedulerTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);

    /** Never finishes; counts how often it was ticked. */
    private static final class CountingTask implements Task {
        final AtomicInteger ticks = new AtomicInteger();

        @Override
        public Status tick(TaskContext ctx) {
            ticks.incrementAndGet();
            return Status.RUNNING;
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_force")
    public static void forcesCityActivityWhileTaskRuns(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        BlockPos bait = helper.absolutePos(new BlockPos(26, 1, 24));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), bait.getX() + 0.5, bait.getY(), bait.getZ() + 0.5, new ItemStack(Items.BREAD)));
        CountingTask task = new CountingTask();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(task));
        // the nearby bread makes vanilla's GoToWantedItem core behaviour want to set WALK_TARGET. A per-tick latch
        // does not help it survive TaskScheduler's own erase: that runs in the same entity tick as the set, so a
        // check made after the entity's tick (as onEachTick's callback is) never observes it with the fix in place.
        // The latch matters because with the erase removed the target is never cleared by TaskScheduler at all, so
        // it stays set from whenever the sensor first notices the bread until the villager physically reaches it;
        // checking on every tick catches that window reliably, where a single check at a fixed tick can land after
        // the villager has already reached and consumed the bread (vanilla clears WALK_TARGET once reached).
        AtomicBoolean sawWalkTarget = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                sawWalkTarget.set(true);
            }
        });
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(villager.getBrain().isActive(CitizenAttachments.CITY_TASK.get()), "city_task not active");
            helper.assertTrue(task.ticks.get() >= 30, "task ticked " + task.ticks.get());
            helper.assertFalse(sawWalkTarget.get(), "vanilla walk target present");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_panic", timeoutTicks = 200)
    public static void pausesWhilePanicking(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CountingTask task = new CountingTask();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(task));
        AtomicLong panicTick = new AtomicLong(-1);
        AtomicInteger countAtPanic = new AtomicInteger();
        helper.runAfterDelay(5, () -> villager.hurt(helper.getLevel().damageSources().generic(), 0.5f));
        helper.onEachTick(() -> {
            if (panicTick.get() < 0 && villager.getBrain().isActive(Activity.PANIC)) {
                panicTick.set(helper.getTick());
                countAtPanic.set(task.ticks.get());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(panicTick.get() >= 0 && helper.getTick() >= panicTick.get() + 10, "waiting for panic");
            helper.assertTrue(villager.getBrain().isActive(Activity.PANIC), "panic ended too early");
            helper.assertTrue(task.ticks.get() == countAtPanic.get(), "task ticked during panic");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_night")
    public static void yieldsToRestAtNight(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.getLevel().setDayTime(13000);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CountingTask task = new CountingTask();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(task));
        helper.runAfterDelay(40, () -> {
            boolean ticked = task.ticks.get() > 0;
            boolean forced = villager.getBrain().isActive(CitizenAttachments.CITY_TASK.get());
            helper.getLevel().setDayTime(GameTestSupport.DAY_TIME);
            VillageTestSupport.remove(helper, village);
            helper.assertFalse(ticked, "task ran at night");
            helper.assertFalse(forced, "city_task active at night");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_tool")
    public static void reEquipsToolAfterMainHandIsCleared(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new ScriptedJob(new CountingTask()));
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(villager.getMainHandItem() == data.tool(), "tool not equipped");
            villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        });
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(villager.getMainHandItem() == data.tool(), "tool not re-equipped");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_release")
    public static void releasesCitizenWhenVillageIsRemoved(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(new CountingTask()));
        helper.runAfterDelay(10, () -> VillageTestSupport.remove(helper, village));
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(data.job() == JobType.NONE, "job kept after village removal");
            helper.assertFalse(villager.getBrain().isActive(CitizenAttachments.CITY_TASK.get()), "city_task still active");
            helper.succeed();
        });
    }
}
