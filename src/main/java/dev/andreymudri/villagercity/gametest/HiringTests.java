package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.village.JobAssignment;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageTicker;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Which jobs a village hires, in which order, and with which tools. */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class HiringTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    private static JobType jobOf(Villager villager) {
        return villager.getExistingData(CitizenAttachments.CITIZEN.get()).map(CitizenData::job).orElse(JobType.NONE);
    }

    private static long countJob(List<Villager> villagers, JobType job) {
        return villagers.stream().map(HiringTests::jobOf).filter(job::equals).count();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_hiring_every_job")
    public static void hiresEveryJobOnceInOrder(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        List<Villager> villagers = List.of(
                GameTestSupport.spawnVillager(helper, 20, 1, 20),
                GameTestSupport.spawnVillager(helper, 28, 1, 20),
                GameTestSupport.spawnVillager(helper, 20, 1, 28),
                GameTestSupport.spawnVillager(helper, 28, 1, 28),
                GameTestSupport.spawnVillager(helper, 22, 1, 20),
                GameTestSupport.spawnVillager(helper, 26, 1, 28));
        VillageTicker.tickVillage(helper.getLevel(), village);
        List<Villager> byUuid = villagers.stream().sorted(Comparator.comparing(Entity::getUUID)).toList();
        List<JobType> hired = byUuid.stream().map(HiringTests::jobOf).toList();
        VillageTestSupport.remove(helper, village);
        for (JobType job : JobAssignment.SLICE_JOBS) {
            helper.assertTrue(countJob(villagers, job) == 1, job.getSerializedName() + " hired " + countJob(villagers, job) + " times: " + hired);
        }
        helper.assertTrue(JobAssignment.SLICE_JOBS.equals(List.of(JobType.LUMBERJACK, JobType.BUILDER, JobType.ARTISAN, JobType.PAVER, JobType.LAMPLIGHTER)),
                "hiring order " + JobAssignment.SLICE_JOBS);
        helper.assertTrue(hired.subList(0, JobAssignment.SLICE_JOBS.size()).equals(JobAssignment.SLICE_JOBS), "jobs not given in order: " + hired);
        helper.assertTrue(countJob(villagers, JobType.NONE) == 1, "unemployed " + countJob(villagers, JobType.NONE) + ": " + hired);
        Villager paver = villagers.stream().filter(v -> jobOf(v) == JobType.PAVER).findFirst().orElseThrow();
        helper.assertTrue(paver.getData(CitizenAttachments.CITIZEN).tool().is(Items.STONE_PICKAXE), "paver tool " + paver.getData(CitizenAttachments.CITIZEN).tool());
        Villager lumberjack = villagers.stream().filter(v -> jobOf(v) == JobType.LUMBERJACK).findFirst().orElseThrow();
        helper.assertTrue(lumberjack.getData(CitizenAttachments.CITIZEN).tool().is(Items.STONE_AXE), "lumberjack tool " + lumberjack.getData(CitizenAttachments.CITIZEN).tool());
        for (JobType job : List.of(JobType.BUILDER, JobType.ARTISAN, JobType.LAMPLIGHTER)) {
            Villager worker = villagers.stream().filter(v -> jobOf(v) == job).findFirst().orElseThrow();
            helper.assertTrue(worker.getData(CitizenAttachments.CITIZEN).tool().isEmpty(), job.getSerializedName() + " tool " + worker.getData(CitizenAttachments.CITIZEN).tool());
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_hiring_two")
    public static void twoVillagersGetLumberjackAndBuilder(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        List<Villager> villagers = List.of(
                GameTestSupport.spawnVillager(helper, 20, 1, 20),
                GameTestSupport.spawnVillager(helper, 28, 1, 20));
        VillageTicker.tickVillage(helper.getLevel(), village);
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(countJob(villagers, JobType.LUMBERJACK) == 1, "lumberjacks " + countJob(villagers, JobType.LUMBERJACK));
        helper.assertTrue(countJob(villagers, JobType.BUILDER) == 1, "builders " + countJob(villagers, JobType.BUILDER));
        helper.succeed();
    }
}
