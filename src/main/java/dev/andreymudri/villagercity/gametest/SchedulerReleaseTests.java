package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class SchedulerReleaseTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);

    /** Never finishes on its own; counts how many times stop() was called. */
    private static final class StopProbe implements Task {
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public Status tick(TaskContext ctx) {
            return Status.RUNNING;
        }

        @Override
        public void stop(TaskContext ctx) {
            stops.incrementAndGet();
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_release_job")
    public static void stopsTaskWhenJobIsCleared(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        StopProbe probe = new StopProbe();
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(probe));
        helper.runAfterDelay(10, data::clear);
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(probe.stops.get() == 1, "stop called " + probe.stops.get() + " times");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_release_village")
    public static void stopsTaskWhenVillageIsRemoved(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        StopProbe probe = new StopProbe();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(probe));
        helper.runAfterDelay(10, () -> VillageTestSupport.remove(helper, village));
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(probe.stops.get() == 1, "stop called " + probe.stops.get() + " times");
            helper.succeed();
        });
    }
}
